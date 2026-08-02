package ai.moeru.airicraft.debug;

import ai.moeru.airicraft.BridgeUnavailableException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientTickWorldQueryServiceTest {
	@Test
	void regionCursorUsesStableXThenYThenZOrder() {
		var bounds = new ClientTickWorldQueryService.RegionBounds(10, 20, 30, 11, 21, 31);

		assertEquals(8L, bounds.totalCellCount());
		assertEquals(10, bounds.positionAt(0).getX());
		assertEquals(20, bounds.positionAt(0).getY());
		assertEquals(30, bounds.positionAt(0).getZ());
		assertEquals(11, bounds.positionAt(1).getX());
		assertEquals(21, bounds.positionAt(3).getY());
		assertEquals(31, bounds.positionAt(4).getZ());
	}

	@Test
	void regionPagesRemainBoundedWithoutChangingTheRequestedCoordinates() {
		var bounds = new ClientTickWorldQueryService.RegionBounds(-100, -64, 200, 100, 319, 400);

		var first = bounds.page(0L, 50);
		var second = bounds.page(first.endCursorExclusive(), 50);

		assertEquals(50, first.count());
		assertEquals(50L, second.startCursor());
		assertFalse(first.complete());
		assertTrue(bounds.totalCellCount() > ClientTickWorldQueryService.MAX_PAGE_LIMIT);
	}

	@Test
	void finalPageMarksTheArbitraryRegionComplete() {
		var bounds = new ClientTickWorldQueryService.RegionBounds(0, 0, 0, 2, 0, 0);

		var page = bounds.page(2L, 100);

		assertEquals(1, page.count());
		assertTrue(page.complete());
	}

	@Test
	void regionPageRejectsAnUnboundedLimit() {
		var bounds = new ClientTickWorldQueryService.RegionBounds(0, 0, 0, 10, 10, 10);

		var exception = assertThrows(
			BridgeUnavailableException.class,
			() -> bounds.page(0L, ClientTickWorldQueryService.MAX_PAGE_LIMIT + 1)
		);

		assertEquals("invalid_request", exception.code());
	}
}
