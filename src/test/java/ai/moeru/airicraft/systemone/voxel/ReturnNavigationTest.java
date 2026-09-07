package ai.moeru.airicraft.systemone.voxel;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import static ai.moeru.airicraft.systemone.TaskKernel.*;

class ReturnNavigationTest {
	private static final List<Pos> ROUTE = java.util.stream.IntStream.rangeClosed(0, 80).mapToObj(z -> new Pos(0, 64, z)).toList();
	@Test void aLongReturnUsesBoundedLegsAndReachesItsOriginalAnchor() {
		var state = ReturnNavigation.State.begin(ROUTE);
		Pos feet = ROUTE.getFirst();
		Optional<Outcome> feedback = Optional.empty();
		int moves = 0;
		while (moves <= 6) {
			var decision = ReturnNavigation.advance(state, world(feet), feedback);
			if (decision instanceof ReturnNavigation.Arrived) break;
			var move = assertInstanceOf(ReturnNavigation.Move.class, decision);
			assertTrue(Math.abs(move.command().stance().z() - feet.z()) <= 16);
			assertEquals(48, move.command().maxTravel());
			assertEquals(400, move.command().maxTicks());
			state = move.state(); feet = move.command().stance(); moves++;
			feedback = Optional.of(Outcome.success("arrived"));
		}
		assertEquals(5, moves);
		assertEquals(ROUTE.getLast(), feet);
		assertEquals(ROUTE, state.route());
	}
	@Test void aSupplyDetourRejoinsTheNearestWaypointBeforeAdvancing() {
		var move = assertInstanceOf(ReturnNavigation.Move.class, ReturnNavigation.advance(ReturnNavigation.State.begin(ROUTE), world(new Pos(-10, 64, 5)), Optional.empty()));
		assertEquals(ROUTE.get(5), move.command().stance());
		var next = assertInstanceOf(ReturnNavigation.Move.class, ReturnNavigation.advance(move.state(), world(move.command().stance()), Optional.of(Outcome.success("joined"))));
		assertEquals(ROUTE.get(21), next.command().stance());
	}
	@Test void changedOrUnknownRouteSupportDoesNotGrantUnboundedRetries() {
		var state = ReturnNavigation.State.begin(ROUTE);
		var world = world(ROUTE.getFirst());
		Optional<Outcome> feedback = Optional.empty();
		var attempted = new HashSet<Pos>();
		for (int i = 0; i < 4; i++) {
			var move = assertInstanceOf(ReturnNavigation.Move.class, ReturnNavigation.advance(state, world, feedback));
			assertTrue(attempted.add(move.command().stance()));
			state = move.state(); feedback = Optional.of(Outcome.failure("blocked"));
		}
		assertInstanceOf(ReturnNavigation.Unavailable.class, ReturnNavigation.advance(state, world, feedback));
		var unknown = new StoneAcquisition.World(world.eye(), world.feet(), Map.of(), Map.of());
		assertInstanceOf(ReturnNavigation.Unavailable.class, ReturnNavigation.advance(ReturnNavigation.State.begin(ROUTE), unknown, Optional.empty()));
	}
	private static StoneAcquisition.World world(Pos feet) {
		var known = new HashMap<Pos, Seen>();
		for (var p : ROUTE) for (int y = -1; y <= 1; y++) known.put(p.offset(0, y, 0), new Seen(y < 0 ? "floor" : "air", y >= 0, true, y < 0, 15, 1));
		return new StoneAcquisition.World(new Pose(feet.x() + .5, feet.y() + 1.62, feet.z() + .5, 0, 0), feet, Map.of(), known);
	}
}
