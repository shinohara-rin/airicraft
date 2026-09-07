package ai.moeru.airicraft.systemone.voxel;

import java.util.*;
import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelCommand.*;
import ai.moeru.airicraft.systemone.voxel.StoneAcquisition.World;
import ai.moeru.airicraft.systemone.voxel.ProductionKnowledge.SearchPrior;

/** Search opens observed surfaces. A geological prior never supplies an ore coordinate. */
public final class UndergroundSearch {
	public record Task(SearchPrior prior, List<String> targets, Pos origin, Pos position, int direction,
		int steps, Set<Pos> rejected, Set<Pos> ignoredTargets, Optional<Pos> destination, Optional<Pos> lookedAt, VoxelCommand last, List<Pos> route) {
		public Task { route = List.copyOf(route); targets = List.copyOf(targets); rejected = Set.copyOf(rejected); ignoredTargets = Set.copyOf(ignoredTargets); }
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
		if (steps >= task.prior().maxSteps() || rejected.size() >= 16) return new Complete<>(Outcome.failure("underground_search_budget_exhausted"));
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
		// A look may reveal an existing floor after a downward step was proposed. Reconsider excavation then.
		candidates.sort(Comparator.comparingInt(p -> StoneAcquisition.standable(world.known(), p) ? 0
			: p.y() == world.feet().y() && foothold(task.prior(), world, p, reserved).isPresent() ? 1
			: retained.filter(p::equals).isPresent() ? 2 : 3));
		for (Pos next : candidates) {
			if (!eligible.test(next) || !eligible.test(next.offset(0, -1, 0)) || entryColumn(world, next).stream().anyMatch(pos -> !eligible.test(pos))) continue;
			if (rejected.contains(next) || squared(next, task.origin()) > task.prior().radius() * task.prior().radius()) continue;
			int[] heading = DIRECTIONS[task.direction()];
			if ((next.x() - world.feet().x()) * heading[0] + (next.z() - world.feet().z()) * heading[1] < 0) continue;
			if (world.footholds().contains(next.offset(0, -1, 0)) && retained.filter(next::equals).isEmpty()) continue;
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
			if (looked.filter(inspect::equals).isEmpty()) {
				double dx = inspect.x() + .5 - world.eye().x(), dy = inspect.y() + .5 - world.eye().y(), dz = inspect.z() + .5 - world.eye().z();
				return action(task, world, steps, rejected, next, inspect, new Look((float) Math.toDegrees(Math.atan2(-dx, dz)), (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)))));
			}
			rejected.add(next);
		}
		return new Complete<>(Outcome.failure("no_observed_underground_step"));
	}
	private static List<Pos> entryColumn(World world, Pos next) {
		return List.of(next.offset(0, Math.max(1, world.feet().y() + 1 - next.y()), 0), next.offset(0, 1, 0), next);
	}
	private static Optional<Place> foothold(SearchPrior prior, World world, Pos next, Map<String, Integer> reserved) {
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
		}
		return Optional.empty();
	}
	private static Execute<Task, VoxelCommand> action(Task task, World world, int steps, Set<Pos> rejected, Pos destination, Pos looked, VoxelCommand command) {
		// A sidestep explores local space; it does not replace the search's chosen heading.
		return new Execute<>(new Task(task.prior(), task.targets(), task.origin(), world.feet(), task.direction(), steps, rejected, task.ignoredTargets(), Optional.of(destination), Optional.ofNullable(looked), command, RouteMemory.append(task.route(), world.feet())), command);
	}
	public static double squared(Pos a, Pos b) { return Math.pow(a.x() - b.x(), 2) + Math.pow(a.z() - b.z(), 2); }
}
