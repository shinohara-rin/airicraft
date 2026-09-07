package ai.moeru.airicraft.systemone.voxel;

import java.util.*;
import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelCommand.*;
import ai.moeru.airicraft.systemone.voxel.StoneAcquisition.World;
import ai.moeru.airicraft.systemone.voxel.ProductionKnowledge.SearchPrior;

/** Search opens observed surfaces. A geological prior never supplies an ore coordinate. */
public final class UndergroundSearch {
	public enum Preparation { OBSERVING, EXCAVATING }
	public record Task(SearchPrior prior, List<String> targets, Pos origin, Pos position, int direction,
		int steps, Set<Pos> rejected, Set<Pos> ignoredTargets, Optional<Pos> destination, Optional<Pos> lookedAt, VoxelCommand last, List<Pos> route, Preparation preparation, List<Pos> areas) {
		public Task { route = List.copyOf(route); targets = List.copyOf(targets); rejected = Set.copyOf(rejected); ignoredTargets = Set.copyOf(ignoredTargets); areas = List.copyOf(areas); }
		public Task(SearchPrior prior, List<String> targets, Pos origin, Pos position, int direction, int steps, Set<Pos> rejected, Set<Pos> ignoredTargets, Optional<Pos> destination, Optional<Pos> lookedAt, VoxelCommand last, List<Pos> route, Preparation preparation) {
			this(prior, targets, origin, position, direction, steps, rejected, ignoredTargets, destination, lookedAt, last, route, preparation, List.of(origin));
		}
		public Task(SearchPrior prior, List<String> targets, Pos origin, Pos position, int direction, int steps, Set<Pos> rejected, Set<Pos> ignoredTargets, Optional<Pos> destination, Optional<Pos> lookedAt, VoxelCommand last, List<Pos> route) {
			this(prior, targets, origin, position, direction, steps, rejected, ignoredTargets, destination, lookedAt, last, route, Preparation.OBSERVING);
		}
		public Task(SearchPrior prior, List<String> targets, Pos origin, Pos position, int direction, int steps, Set<Pos> rejected, Set<Pos> ignoredTargets, Optional<Pos> destination, Optional<Pos> lookedAt, VoxelCommand last) {
			this(prior, targets, origin, position, direction, steps, rejected, ignoredTargets, destination, lookedAt, last, List.of(origin));
		}

		public static Task begin(SearchPrior prior, List<String> targets, World world, Set<Pos> ignored) {
			return new Task(prior, targets, world.feet(), world.feet(), Math.floorMod(Math.round((float) world.eye().yaw() / 90), 4), 0, Set.of(), ignored, Optional.empty(), Optional.empty(), null);
		}
	}
	private static final int[][] DIRECTIONS = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}};
	public Decision<Task, VoxelCommand> decide(View<Task> view, World world) {
		return decide(view, world, Map.of());
	}
	public Decision<Task, VoxelCommand> decide(View<Task> view, World world, Map<String, Integer> reserved) {
		return decide(view, world, reserved, pos -> true);
	}
	public Decision<Task, VoxelCommand> decide(View<Task> view, World world, Map<String, Integer> reserved, java.util.function.Predicate<Pos> eligible) {
		Task task = view.task();
		if (world.known().entrySet().stream().anyMatch(e -> e.getValue().identified() && task.targets().contains(e.getValue().blockId()) && !task.ignoredTargets().contains(e.getKey()))) {
			return new Complete<>(Outcome.success("resource_surface_observed:" + task.prior().item()));
		}
		if (view.acting()) return new Keep<>();
		var rejected = new HashSet<>(task.rejected());
		if (view.commandResult().filter(o -> o.kind() != ResultKind.SUCCEEDED).isPresent()) task.destination().ifPresent(rejected::add);
		int steps = task.steps() + (world.feet().equals(task.position()) ? 0 : 1);
		Optional<Pos> destination = task.destination(), looked = task.lookedAt();
		if (destination.filter(world.feet()::equals).isPresent()) {
			destination = Optional.empty(); looked = Optional.empty();
			// Reachability changed with our position. Past local failures must not exhaust a progressing search.
			rejected.clear();
		}
		if (steps >= task.prior().maxSteps()) {
			// A completed local search is one method attempt, not proof that the resource
			// cannot be found. Continue only from real progress into a distinct observed area.
			boolean arrived = task.last() instanceof Navigate && task.destination().filter(world.feet()::equals).isPresent()
				&& view.commandResult().filter(o -> o.kind() == ResultKind.SUCCEEDED).isPresent();
			if (arrived && task.areas().size() < task.prior().maxAreas() && StoneAcquisition.standable(world.known(), world.feet())
				&& eligible.test(world.feet()) && eligible.test(world.feet().offset(0,-1,0))
				&& task.areas().stream().allMatch(center -> squared(center, world.feet()) >= Math.pow(task.prior().radius() / 2.0, 2))) {
				var areas = new ArrayList<>(task.areas()); areas.add(world.feet());
				return new Keep<>(new Task(task.prior(), task.targets(), world.feet(), world.feet(), task.direction(), 0,
					Set.of(), task.ignoredTargets(), Optional.empty(), Optional.empty(), null,
					RouteMemory.append(task.route(), world.feet()), Preparation.OBSERVING, areas));
			}
			return new Complete<>(Outcome.failure("underground_search_budget_exhausted"));
		}
		if (rejected.size() >= 16) return new Complete<>(Outcome.failure("underground_search_budget_exhausted"));
		if (destination.filter(rejected::contains).isPresent()) { destination = Optional.empty(); looked = Optional.empty(); }
		// Retain a selected step across ceiling preparation and lighting interruptions.
		List<Pos> candidates = new ArrayList<>();
		destination.ifPresent(candidates::add);
		for (int turn : new int[]{0, 1, 3, 2}) {
			int[] d = DIRECTIONS[(task.direction() + turn) % 4];
			// An observed cave floor competes with cutting another step.
			for (int dy : world.feet().y() > task.prior().preferredY() ? new int[]{-1, 0} : new int[]{0}) {
				Pos candidate = world.feet().offset(d[0], dy, d[1]);
				if (!candidates.contains(candidate)) candidates.add(candidate);
			}
		}
		Optional<Pos> retained = destination;
		int[] heading = DIRECTIONS[task.direction()];
		// Finish an opened step: its newly cleared headroom is not a competing cave discovery.
		// Before excavation starts, a look can still replace the proposal with an existing floor.
		candidates.sort(Comparator.<Pos>comparingInt(p -> task.preparation() == Preparation.EXCAVATING && retained.filter(p::equals).isPresent() ? 0
			: (p.x() - world.feet().x()) * heading[0] + (p.z() - world.feet().z()) * heading[1] < 0 ? 1 : 0)
			.thenComparingInt(p -> task.preparation() == Preparation.EXCAVATING && retained.filter(p::equals).isPresent() ? 0
			: StoneAcquisition.standable(world.known(), p) ? 1
			: p.y() == world.feet().y() && foothold(task.prior(), world, p, reserved).isPresent() ? 2
			: retained.filter(p::equals).isPresent() ? 3 : 4));
		for (Pos next : candidates) {
			if (!eligible.test(next) || !eligible.test(next.offset(0, -1, 0)) || entryColumn(world, next).stream().anyMatch(pos -> !eligible.test(pos))) continue;
			if (rejected.contains(next) || squared(next, task.origin()) > task.prior().radius() * task.prior().radius()) continue;
			if (task.route().contains(next) && retained.filter(next::equals).isEmpty()) continue;
			var column = entryColumn(world, next);
			boolean obstructed = false;
			for (Pos cell : column) {
				Seen seen = world.known().get(cell);
				if (seen != null && seen.traversable()) continue;
				if (seen != null && seen.identified() && (!task.prior().excavatable().contains(seen.blockId()) || world.footholds().contains(cell))) { obstructed = true; break; }
			}
			if (obstructed) { rejected.add(next); continue; }
			for (Pos cell : column) {
				Seen seen = world.known().get(cell);
				if (seen != null && seen.identified() && !seen.traversable() && ObservedReach.visible(world.known(), world.eye(), cell, 4.3)) {
					return action(task, world, steps, rejected, next, null, new Break(cell, seen.blockId()));
				}
			}
			if (StoneAcquisition.standable(world.known(), next) && column.stream().allMatch(p -> world.known().get(p) != null && world.known().get(p).traversable())) {
				return action(task, world, steps, rejected, next, null, new Navigate(next, 12, 200));
			}
			var footing = foothold(task.prior(), world, next, reserved);
			if (footing.isPresent()) return action(task, world, steps, rejected, next, null, footing.get());
			Pos inspect = column.stream().filter(p -> world.known().get(p) == null || !world.known().get(p).identified()).findFirst().orElse(next.offset(0, -1, 0));
			VoxelCommand observation = TerrainAccess.inspect(world, inspect, Set.copyOf(task.prior().excavatable()), eligible);
			if (observation instanceof Break || looked.filter(inspect::equals).isEmpty()) {
				return action(task, world, steps, rejected, next, observation instanceof Look ? inspect : null, observation);
			}
			rejected.add(next);
		}
		return new Complete<>(Outcome.failure("no_observed_underground_step"));
	}
	private static List<Pos> entryColumn(World world, Pos next) {
		return List.of(next.offset(0, Math.max(1, world.feet().y() + 1 - next.y()), 0), next.offset(0, 1, 0), next);
	}
	private static Optional<VoxelCommand> foothold(SearchPrior prior, World world, Pos next, Map<String, Integer> reserved) {
		Pos footing = next.offset(0, -1, 0);
		Seen gap = world.known().get(footing);
		if (gap == null || !gap.identified() || !gap.empty() || !entryColumn(world, next).stream().allMatch(p -> world.known().get(p) != null && world.known().get(p).traversable())) return Optional.empty();
		for (var material : prior.supports()) {
			if (world.inventory().getOrDefault(material.item(), 0) <= reserved.getOrDefault(material.item(), 0)) continue;
			for (Face face : Face.values()) {
				Pos support = footing.offset(-face.x, -face.y, -face.z);
				Seen surface = world.known().get(support);
				if (StoneAcquisition.supportsStanding(surface) && ObservedReach.visibleFace(world.known(), world.eye(), support, face, 4.3)) {
					return Optional.of(new Place(material.item(), support, surface.blockId(), face, material.block()));
				}
			}
			Pos standingSupport = world.feet().offset(0, -1, 0);
			Seen standing = world.known().get(standingSupport);
			if (world.footholds().contains(standingSupport) && StoneAcquisition.supportsStanding(standing)) {
				for (Face face : Face.values()) if (face.y == 0 && face.adjacent(standingSupport).equals(footing)) {
					return Optional.of(new EdgePlace(new Place(material.item(), standingSupport, standing.blockId(), face, material.block())));
				}
			}
		}
		return Optional.empty();
	}
	private static Execute<Task, VoxelCommand> action(Task task, World world, int steps, Set<Pos> rejected, Pos destination, Pos looked, VoxelCommand command) {
		// A sidestep explores local space; it does not replace the search's chosen heading.
		Preparation preparation = command instanceof Break ? Preparation.EXCAVATING
			: task.destination().filter(destination::equals).isPresent() ? task.preparation() : Preparation.OBSERVING;
		return new Execute<>(new Task(task.prior(), task.targets(), task.origin(), world.feet(), task.direction(), steps, rejected, task.ignoredTargets(), Optional.of(destination), Optional.ofNullable(looked), command, RouteMemory.append(task.route(), world.feet()), preparation, task.areas()), command);
	}
	public static double squared(Pos a, Pos b) { return Math.pow(a.x() - b.x(), 2) + Math.pow(a.z() - b.z(), 2); }
}
