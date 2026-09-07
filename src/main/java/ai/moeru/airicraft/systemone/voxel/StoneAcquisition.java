package ai.moeru.airicraft.systemone.voxel;

import ai.moeru.airicraft.systemone.TaskKernel;
import static ai.moeru.airicraft.systemone.voxel.VoxelCommand.*;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;

/** First Minecraft method: local, incremental stone excavation through observed surfaces. */
public final class StoneAcquisition implements TaskKernel.Domain<StoneAcquisition.Task, StoneAcquisition.World, VoxelCommand> {
	private final Set<String> additionalClearance;
	public StoneAcquisition() { this(Set.of()); }
	public StoneAcquisition(Set<String> additionalClearance) { this.additionalClearance = Set.copyOf(additionalClearance); }
	public record Task(int count, Pos origin, int scans, int failures, Set<Pos> rejected, Optional<VoxelCommand> last, List<Pos> route, Optional<Pos> descent) {
		public Task { rejected = Set.copyOf(rejected); route = List.copyOf(route); }
		public Task(int count, Pos origin, int scans, int failures, Set<Pos> rejected, Optional<VoxelCommand> last) {
			this(count, origin, scans, failures, rejected, last, List.of(origin), Optional.empty());
		}
		public static Task begin(int count, Pos origin) { return new Task(count, origin, 0, 0, Set.of(), Optional.empty()); }
	}
	public record World(Pose eye, Pos feet, Map<String, Integer> inventory, Map<Pos, Seen> known, Set<Pos> footholds, SurvivalPolicy.Vitals vitals, List<ItemObservation.Drop> drops) {
		public World {
			inventory = Map.copyOf(inventory);
			// Map.copyOf uses linear probing: clustered voxel hashes make large terrain snapshots quadratic.
			known = java.util.Collections.unmodifiableMap(new java.util.HashMap<>(known));
			footholds = Set.copyOf(footholds); java.util.Objects.requireNonNull(vitals); drops = List.copyOf(drops);
		}
		public World(Pose eye, Pos feet, Map<String, Integer> inventory, Map<Pos, Seen> known, Set<Pos> footholds, SurvivalPolicy.Vitals vitals) { this(eye,feet,inventory,known,footholds,vitals,List.of()); }
		public World(Pose eye, Pos feet, Map<String, Integer> inventory, Map<Pos, Seen> known, Set<Pos> footholds) { this(eye, feet, inventory, known, footholds, SurvivalPolicy.Vitals.healthy()); }
		public World(Pose eye, Pos feet, Map<String, Integer> inventory, Map<Pos, Seen> known) { this(eye, feet, inventory, known, Set.of()); }
	}

	@Override public Decision<Task, VoxelCommand> decide(View<Task> view, World world) {
		Task task = view.task();
		if (world.inventory().getOrDefault("minecraft:cobblestone", 0) >= task.count()) {
			return new Complete<>(Outcome.success("cobblestone_inventory_observed"));
		}
		if (view.acting()) return new Keep<>();
		if (view.commandResult().filter(result -> result.kind() == ResultKind.SUCCEEDED).isPresent() && task.last().orElse(null) instanceof Navigate move) {
			var route = new java.util.ArrayList<>(task.route());
			if (!route.contains(move.stance())) route.add(move.stance());
			if (!route.contains(world.feet())) route.add(world.feet());
			task = new Task(task.count(), task.origin(), task.scans(), task.failures(), task.rejected(), task.last(), route, Optional.empty());
		}
		if (task.failures() >= 12) return new Complete<>(Outcome.failure("local_acquisition_alternatives_exhausted"));
		if (world.inventory().keySet().stream().noneMatch(id -> id.endsWith("_pickaxe"))) {
			return new Complete<>(Outcome.failure("pickaxe_required"));
		}
		if (view.commandResult().filter(result -> result.kind() != ResultKind.SUCCEEDED).isPresent()) {
			Set<Pos> rejected = new HashSet<>(task.rejected());
			task.last().ifPresent(command -> {
				if (command instanceof Break broken) rejected.add(broken.target());
				if (command instanceof Navigate move) rejected.add(move.stance());
			});
			task.descent().ifPresent(rejected::add);
			task = new Task(task.count(), task.origin(), task.scans(), task.failures() + 1, rejected, Optional.empty(), task.route(), Optional.empty());
		}
		else if (task.descent().isEmpty() && task.last().orElse(null) instanceof Break broken && broken.target().y() >= world.feet().y() - 1 && standable(world.known(), broken.target())
			&& (broken.target().y() < world.feet().y() || stone(broken.expectedBlock()))) {
			task = new Task(task.count(), task.origin(), 0, task.failures(), task.rejected(), task.last(), task.route(), Optional.of(broken.target()));
		}
		if (task.descent().isPresent()) {
			Pos destination = task.descent().get();
			// Entering a lower step crosses the column at the departure height. Standing room alone is insufficient.
			Pos clearance = new Pos(destination.x(), Math.max(destination.y() + 1, world.feet().y() + 1), destination.z());
			Seen ceiling = world.known().get(clearance);
			if (ceiling != null && ceiling.empty()) return execute(task, new Navigate(destination, 24, 200), 0);
			if (ceiling != null && ceiling.identified() && (stone(ceiling.blockId()) || soil(ceiling.blockId()) || additionalClearance.contains(ceiling.blockId()))
				&& !world.footholds().contains(clearance) && ObservedReach.visible(world.known(), world.eye(), clearance, 4.3)) {
				return execute(task, new Break(clearance, ceiling.blockId()), task.scans());
			}
			if (task.scans() == 0) {
				double dx = clearance.x() + .5 - world.eye().x(), dy = clearance.y() + .5 - world.eye().y(), dz = clearance.z() + .5 - world.eye().z();
				return execute(task, new Look((float) Math.toDegrees(Math.atan2(-dx, dz)), (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)))), 1);
			}
			var rejected = new HashSet<>(task.rejected()); rejected.add(destination);
			task = new Task(task.count(), task.origin(), task.scans(), task.failures() + 1, rejected, Optional.empty(), task.route(), Optional.empty());
		}
		Task current = task;
		List<Map.Entry<Pos, Seen>> targets = world.known().entrySet().stream()
			.filter(entry -> entry.getValue().identified() && !entry.getValue().empty())
			.filter(entry -> !current.rejected().contains(entry.getKey()))
			.filter(entry -> horizontalSquared(entry.getKey(), current.origin()) <= 12 * 12)
			.filter(entry -> !entry.getKey().equals(world.feet().offset(0, -1, 0)))
			.filter(entry -> !world.footholds().contains(entry.getKey()))
			.filter(entry -> current.route().stream().noneMatch(stance -> entry.getKey().equals(stance.offset(0, -1, 0))))
			.filter(entry -> stone(entry.getValue().blockId()) || entry.getKey().y() >= world.feet().y() - 1)
			.filter(entry -> stone(entry.getValue().blockId()) || soil(entry.getValue().blockId()))
			.sorted(Comparator.<Map.Entry<Pos, Seen>>comparingInt(entry -> stone(entry.getValue().blockId()) ? 0 : 1)
				.thenComparingInt(entry -> entry.getKey().y())
				.thenComparingDouble(entry -> distanceSquared(world.eye(), entry.getKey()))
				.thenComparingInt(entry -> entry.getKey().x()).thenComparingInt(entry -> entry.getKey().y()).thenComparingInt(entry -> entry.getKey().z()))
			.toList();
		// A short approach to known stone competes with excavation; Baritone does not acquire the target.
		for (var entry : targets) {
			if (!stone(entry.getValue().blockId())) continue;
			if (entry.getKey().y() >= world.feet().y() - 1 && ObservedReach.visible(world.known(), world.eye(), entry.getKey(), 4.3)) return execute(task, new Break(entry.getKey(), entry.getValue().blockId()), task.scans());
			Optional<Pos> approach = world.known().keySet().stream()
				.filter(pos -> !pos.equals(world.feet()) && !current.rejected().contains(pos) && standable(world.known(), pos))
				.filter(pos -> horizontalSquared(pos, current.origin()) <= 12 * 12)
				.filter(pos -> entry.getKey().y() >= pos.y() - 1)
				.filter(pos -> ObservedReach.visible(world.known(), new Pose(pos.x() + .5, pos.y() + 1.62, pos.z() + .5, 0, 0), entry.getKey(), 4.3))
				.sorted(Comparator.<Pos>comparingDouble(pos -> horizontalSquared(pos, world.feet()) + Math.pow(pos.y() - world.feet().y(), 2))
					.thenComparingInt(Pos::x).thenComparingInt(Pos::y).thenComparingInt(Pos::z)).findFirst();
			if (approach.isPresent()) return execute(task, new Navigate(approach.get(), 24, 200), 0);
		}
		for (var entry : targets) {
			if (entry.getKey().y() >= world.feet().y() - 1 && ObservedReach.visible(world.known(), world.eye(), entry.getKey(), 4.3)) {
				return execute(task, new Break(entry.getKey(), entry.getValue().blockId()), task.scans());
			}
		}
		// Looking is information gathering, not a ritual after every excavation step.
		if (task.scans() < 4) {
			return execute(task, new Look((float) ((world.eye().yaw() + 90) % 360), 55), task.scans() + 1);
		}
		// Known standing positions are exploration frontiers; no buried resource coordinates are used.
		Optional<Pos> frontier = world.known().keySet().stream()
			.filter(pos -> !pos.equals(world.feet()) && standable(world.known(), pos))
			.filter(pos -> !current.rejected().contains(pos) && !current.route().contains(pos) && horizontalSquared(pos, current.origin()) <= 12 * 12)
			.filter(pos -> horizontalSquared(pos, world.feet()) >= 4)
			// Observed soil/stone is a useful excavation surface; a nearer canopy is not.
			.sorted(Comparator.<Pos>comparingInt(pos -> {
				String support = world.known().get(pos.offset(0,-1,0)).blockId();
				return soil(support) || stone(support) ? 0 : 1;
			}).thenComparingDouble(pos -> horizontalSquared(pos, world.feet()))
				.thenComparingInt(Pos::x).thenComparingInt(Pos::y).thenComparingInt(Pos::z))
			.findFirst();
		return frontier.<Decision<Task, VoxelCommand>>map(pos -> execute(current, new Navigate(pos, 24, 200), 0))
			.orElseGet(() -> new Complete<>(Outcome.failure("no_observed_local_method")));
	}

	private Execute<Task, VoxelCommand> execute(Task task, VoxelCommand command, int scans) {
		return new Execute<>(new Task(task.count(), task.origin(), scans, task.failures(), task.rejected(), Optional.of(command), task.route(), task.descent()), command);
	}
	public static boolean standable(Map<Pos, Seen> known, Pos pos) {
		Seen feet = known.get(pos), head = known.get(pos.offset(0, 1, 0)), floor = known.get(pos.offset(0, -1, 0));
		return feet != null && feet.traversable() && head != null && head.traversable() && supportsStanding(floor);
	}
	public static boolean supportsStanding(Seen floor) { return floor != null && floor.identified() && !floor.empty() && floor.fullSupport(); }
	private static boolean stone(String id) { return id.equals("minecraft:stone") || id.equals("minecraft:cobblestone"); }
	private static boolean soil(String id) { return id.equals("minecraft:dirt") || id.equals("minecraft:grass_block"); }
	private static double horizontalSquared(Pos a, Pos b) { return Math.pow(a.x() - b.x(), 2) + Math.pow(a.z() - b.z(), 2); }
	private static double distanceSquared(Pose eye, Pos pos) {
		return Math.pow(eye.x() - pos.x() - 0.5, 2) + Math.pow(eye.y() - pos.y() - 0.5, 2) + Math.pow(eye.z() - pos.z() - 0.5, 2);
	}
}
