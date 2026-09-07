package ai.moeru.airicraft.systemone.voxel;

import ai.moeru.airicraft.systemone.TaskKernel;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;

/** First Minecraft method: local, incremental stone excavation through observed surfaces. */
public final class StoneAcquisition implements TaskKernel.Domain<StoneAcquisition.Task, StoneAcquisition.World, StoneAcquisition.Command> {
	public sealed interface Command permits Look, Break, Navigate {}
	public record Look(float yaw, float pitch) implements Command {}
	public record Break(Pos target, String expectedBlock) implements Command {}
	public record Navigate(Pos stance, int maxTravel, int maxTicks) implements Command {}
	public record Task(int count, Pos origin, int scans, int failures, Set<Pos> rejected, Optional<Command> last) {
		public Task { rejected = Set.copyOf(rejected); }
		public static Task begin(int count, Pos origin) { return new Task(count, origin, 0, 0, Set.of(), Optional.empty()); }
	}
	public record World(Pose eye, Pos feet, Map<String, Integer> inventory, Map<Pos, Seen> known) {
		public World { inventory = Map.copyOf(inventory); known = Map.copyOf(known); }
	}

	@Override public Decision<Task, Command> decide(View<Task> view, World world) {
		Task task = view.task();
		if (world.inventory().getOrDefault("minecraft:cobblestone", 0) >= task.count()) {
			return new Complete<>(Outcome.success("cobblestone_inventory_observed"));
		}
		if (view.acting()) return new Keep<>();
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
			task = new Task(task.count(), task.origin(), task.scans(), task.failures() + 1, rejected, Optional.empty());
		}
		else if (task.last().orElse(null) instanceof Break broken && standable(world.known(), broken.target())) {
			return execute(task, new Navigate(broken.target(), 24, 200), 0);
		}
		Task current = task;
		List<Map.Entry<Pos, Seen>> targets = world.known().entrySet().stream()
			.filter(entry -> entry.getValue().identified() && !entry.getValue().empty())
			.filter(entry -> !current.rejected().contains(entry.getKey()))
			.filter(entry -> horizontalSquared(entry.getKey(), current.origin()) <= 12 * 12)
			.filter(entry -> !entry.getKey().equals(world.feet().offset(0, -1, 0)))
			.filter(entry -> entry.getKey().y() >= world.feet().y() - 1)
			.filter(entry -> stone(entry.getValue().blockId()) || soil(entry.getValue().blockId()))
			.sorted(Comparator.<Map.Entry<Pos, Seen>>comparingInt(entry -> stone(entry.getValue().blockId()) ? 0 : 1)
				.thenComparingInt(entry -> entry.getKey().y())
				.thenComparingDouble(entry -> distanceSquared(world.eye(), entry.getKey()))
				.thenComparingInt(entry -> entry.getKey().x()).thenComparingInt(entry -> entry.getKey().y()).thenComparingInt(entry -> entry.getKey().z()))
			.toList();
		for (var entry : targets) {
			if (distanceSquared(world.eye(), entry.getKey()) <= 4.3 * 4.3) {
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
			.filter(pos -> !current.rejected().contains(pos) && horizontalSquared(pos, current.origin()) <= 12 * 12)
			.filter(pos -> horizontalSquared(pos, world.feet()) >= 4)
			.sorted(Comparator.<Pos>comparingDouble(pos -> horizontalSquared(pos, world.feet()))
				.thenComparingInt(Pos::x).thenComparingInt(Pos::y).thenComparingInt(Pos::z))
			.findFirst();
		return frontier.<Decision<Task, Command>>map(pos -> execute(current, new Navigate(pos, 24, 200), 0))
			.orElseGet(() -> new Complete<>(Outcome.failure("no_observed_local_method")));
	}

	private Execute<Task, Command> execute(Task task, Command command, int scans) {
		return new Execute<>(new Task(task.count(), task.origin(), scans, task.failures(), task.rejected(), Optional.of(command)), command);
	}
	public static boolean standable(Map<Pos, Seen> known, Pos pos) {
		Seen feet = known.get(pos), head = known.get(pos.offset(0, 1, 0)), floor = known.get(pos.offset(0, -1, 0));
		return feet != null && feet.empty() && head != null && head.empty() && floor != null && floor.identified()
			&& (stone(floor.blockId()) || soil(floor.blockId()));
	}
	private static boolean stone(String id) { return id.equals("minecraft:stone") || id.equals("minecraft:cobblestone"); }
	private static boolean soil(String id) { return id.equals("minecraft:dirt") || id.equals("minecraft:grass_block"); }
	private static double horizontalSquared(Pos a, Pos b) { return Math.pow(a.x() - b.x(), 2) + Math.pow(a.z() - b.z(), 2); }
	private static double distanceSquared(Pose eye, Pos pos) {
		return Math.pow(eye.x() - pos.x() - 0.5, 2) + Math.pow(eye.y() - pos.y() - 0.5, 2) + Math.pow(eye.z() - pos.z() - 0.5, 2);
	}
}
