package ai.moeru.airicraft.systemone.voxel;

import java.util.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import static ai.moeru.airicraft.systemone.TaskKernel.*;
import ai.moeru.airicraft.systemone.voxel.StoneAcquisition.World;
import ai.moeru.airicraft.systemone.voxel.VoxelCommand.Navigate;

/** Rejoins an observed route, then follows bounded legs toward its final waypoint. */
public final class ReturnNavigation {
	private ReturnNavigation() {}
	public record State(List<Pos> route, int progress, int target, Set<Pos> rejected, Optional<Pos> last) {
		public State { route = List.copyOf(route); rejected = Set.copyOf(rejected); if (route.isEmpty()) throw new IllegalArgumentException("Return route required"); }
		public static State begin(List<Pos> route) { return new State(route, -1, -1, Set.of(), Optional.empty()); }
	}
	public sealed interface Decision permits Move, Arrived, Unavailable {}
	public record Move(State state, Navigate command) implements Decision {}
	public record Arrived() implements Decision {}
	public record Unavailable(String reason) implements Decision {}
	public static Decision advance(State state, World world, Optional<Outcome> result) {
		var route = state.route();
		if (world.feet().equals(route.getLast()) || (state.target() == route.size() - 1 && state.last().isPresent()
			&& result.filter(o -> o.kind() == ResultKind.SUCCEEDED).isPresent() && near(world.feet(), route.getLast()))) return new Arrived();
		int progress = state.progress();
		var rejected = new HashSet<>(state.rejected());
		if (state.last().isPresent() && result.isPresent()) {
			if (result.get().kind() == ResultKind.SUCCEEDED && near(world.feet(), route.get(state.target()))) {
				progress = state.target(); rejected.clear();
			}
			else rejected.add(state.last().get());
		}
		if (rejected.size() >= 4) return new Unavailable("leg_attempts_exhausted");
		int target;
		if (progress < 0) {
			target = 0;
			for (int i = 1; i < route.size(); i++) if (distance(world.feet(), route.get(i)) < distance(world.feet(), route.get(target))) target = i;
			if (target < route.size() - 1 && near(world.feet(), route.get(target))) progress = target;
		}
		else target = Math.min(progress + 1, route.size() - 1);
		if (progress >= 0) {
			target = progress;
			double length = 0;
			while (target + 1 < route.size() && length + distance(route.get(target), route.get(target + 1)) <= 16) {
				length += distance(route.get(target), route.get(target + 1)); target++;
			}
			if (target == progress && target + 1 < route.size()) target++;
		}
		for (int index = target; index > progress; index--) {
			Pos waypoint = route.get(index);
			var stance = world.known().keySet().stream()
				.filter(p -> !rejected.contains(p) && near(p, waypoint) && StoneAcquisition.standable(world.known(), p))
				.sorted(Comparator.<Pos>comparingDouble(p -> distance(p, waypoint)).thenComparingInt(Pos::x).thenComparingInt(Pos::y).thenComparingInt(Pos::z)).findFirst();
			if (stance.isPresent()) return new Move(new State(route, progress, index, rejected, stance), new Navigate(stance.get(), 48, 400));
		}
		return new Unavailable("no_observed_return_stance");
	}
	private static boolean near(Pos a, Pos b) { return UndergroundSearch.squared(a, b) <= 4 && Math.abs(a.y() - b.y()) <= 1; }
	private static double distance(Pos a, Pos b) { return Math.sqrt(UndergroundSearch.squared(a, b) + Math.pow(a.y() - b.y(), 2)); }
}
