package ai.moeru.airicraft.systemone.voxel;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;

class RouteMemoryTest {
	@Test void aDistantSuspendedRouteKeepsObservedSupportWithoutCreatingUnknownCells() {
		var route = java.util.stream.IntStream.rangeClosed(0, 60).mapToObj(z -> new Pos(0, 65 - z, z)).toList();
		var retained = new HashSet<Pos>(); RouteMemory.retain(retained, route);
		var current = new Pos(0, 65, -1);
		var support = route.getLast().offset(0, -1, 0);
		var unrelated = support.offset(20, 0, 0);
		var memory = new HashMap<>(Map.of(support, new Seen("stone", false, true, true, 15, 1), unrelated, new Seen("stone", false, true, true, 15, 1)));
		memory.keySet().removeIf(p -> !RouteMemory.keep(p, current, retained));
		assertEquals(Set.of(support), memory.keySet());
		assertFalse(memory.containsKey(route.getLast()), "pinning is retention, not observation");
		memory.put(support, new Seen("air", true, true, false, 15, 2));
		assertFalse(StoneAcquisition.supportsStanding(memory.get(support)), "new observations invalidate pinned support");
		memory.keySet().removeIf(p -> !RouteMemory.keep(p, current, Set.of()));
		assertTrue(memory.isEmpty(), "expired continuation releases distant memory");
		assertTrue(retained.size() <= 150 * route.size());
	}
	@Test void routeProgressOnlyAppendsChangedObservedPositions() {
		var first = new Pos(0, 5, 0); var second = first.offset(0, -1, 1);
		var route = RouteMemory.append(List.of(first), first);
		assertEquals(List.of(first), route);
		assertEquals(List.of(first, second), RouteMemory.append(route, second));
		assertEquals(List.of(first), route);
	}
}
