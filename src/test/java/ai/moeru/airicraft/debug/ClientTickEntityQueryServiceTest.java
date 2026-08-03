package ai.moeru.airicraft.debug;

import ai.moeru.airicraft.BridgeUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientTickEntityQueryServiceTest {
	private static final ClientTickPlayerSnapshot.PositionSnapshot PLAYER_POSITION =
		new ClientTickPlayerSnapshot.PositionSnapshot(0.0D, 64.0D, 0.0D, 0, 64, 0);

	@Test
	void queryCombinesRadiusIdentityNameTypeAndStateFilters() {
		var observations = List.of(
			observation(1, "self", "Player", "minecraft:player", 0.0D, 64.0D, 0.0D, true, true, true),
			observation(2, "sheep-2", "Sheep", "minecraft:sheep", 3.0D, 64.0D, 0.0D, true, true, false),
			observation(3, "sheep-3", "Sheep", "minecraft:sheep", 30.0D, 64.0D, 0.0D, true, true, false),
			observation(4, "cow-4", "Cow", "minecraft:cow", 2.0D, 64.0D, 0.0D, true, true, false)
		);
		var query = new ClientTickEntityQueryService.EntityQuery(
			null,
			new ClientTickEntityQueryService.RadiusBounds(0.0D, 64.0D, 0.0D, 8.0D),
			2,
			"sheep-2",
			"sHeEp",
			Set.of("MINECRAFT:SHEEP"),
			true,
			true,
			false,
			false,
			0L,
			32
		);

		var page = ClientTickEntityQueryService.select(observations, query, 1, PLAYER_POSITION);

		assertEquals(1, page.totalMatchCount());
		assertEquals(List.of(2), page.entities().stream().map(ClientTickEntityQueryService.EntityObservation::entityId).toList());
		assertTrue(page.complete());
	}

	@Test
	void regionUsesInclusiveBlockBounds() {
		var observations = List.of(
			observation(2, "inside", "Item", "minecraft:item", 2.999D, 10.5D, 2.999D, true, false, false),
			observation(3, "outside", "Item", "minecraft:item", 3.0D, 10.5D, 2.0D, true, false, false)
		);
		var query = new ClientTickEntityQueryService.EntityQuery(
			new ClientTickWorldQueryService.RegionBounds(0, 10, 0, 2, 10, 2),
			null,
			null,
			null,
			null,
			Set.of(),
			null,
			false,
			false,
			false,
			0L,
			32
		);

		var page = ClientTickEntityQueryService.select(observations, query, 1, PLAYER_POSITION);

		assertEquals(List.of(2), page.entities().stream().map(ClientTickEntityQueryService.EntityObservation::entityId).toList());
	}

	@Test
	void queryUsesStableDistanceThenEntityIdOrderAcrossPages() {
		var observations = List.of(
			observation(9, "nine", "Nine", "minecraft:item", 1.0D, 64.0D, 0.0D, true, false, false),
			observation(7, "seven", "Seven", "minecraft:item", 2.0D, 64.0D, 0.0D, true, false, false),
			observation(3, "three", "Three", "minecraft:item", -1.0D, 64.0D, 0.0D, true, false, false)
		);
		var firstQuery = new ClientTickEntityQueryService.EntityQuery(
			null, null, null, null, null, Set.of(), null, false, false, false, 0L, 2
		);
		var secondQuery = new ClientTickEntityQueryService.EntityQuery(
			null, null, null, null, null, Set.of(), null, false, false, false, 2L, 2
		);

		var firstPage = ClientTickEntityQueryService.select(observations, firstQuery, 1, PLAYER_POSITION);
		var secondPage = ClientTickEntityQueryService.select(observations, secondQuery, 1, PLAYER_POSITION);

		assertEquals(List.of(3, 9), firstPage.entities().stream().map(ClientTickEntityQueryService.EntityObservation::entityId).toList());
		assertEquals(3, firstPage.totalMatchCount());
		assertFalse(firstPage.complete());
		assertEquals(List.of(7), secondPage.entities().stream().map(ClientTickEntityQueryService.EntityObservation::entityId).toList());
		assertTrue(secondPage.complete());
	}

	@Test
	void queryExcludesSelfUnlessRequested() {
		var observations = List.of(
			observation(1, "self", "Player", "minecraft:player", 0.0D, 64.0D, 0.0D, true, true, true)
		);

		var excluded = ClientTickEntityQueryService.select(
			observations,
			ClientTickEntityQueryService.EntityQuery.all(),
			1,
			PLAYER_POSITION
		);
		var included = ClientTickEntityQueryService.select(
			observations,
			new ClientTickEntityQueryService.EntityQuery(
				null, null, null, null, null, Set.of(), null, false, false, true, 0L, 32
			),
			1,
			PLAYER_POSITION
		);

		assertTrue(excluded.entities().isEmpty());
		assertEquals(1, included.entities().size());
	}

	@Test
	void materializesOnlyTheSelectedPageAfterFiltering() {
		var observations = List.of(
			observation(2, "item-2", "Item", "minecraft:item", 1.0D, 64.0D, 0.0D, true, false, false),
			observation(3, "item-3", "Item", "minecraft:item", 2.0D, 64.0D, 0.0D, true, false, false),
			observation(4, "sheep-4", "Sheep", "minecraft:sheep", 3.0D, 64.0D, 0.0D, true, true, false)
		);
		var query = new ClientTickEntityQueryService.EntityQuery(
			null, null, null, null, null, Set.of("minecraft:sheep"), null, false, false, false, 0L, 1
		);
		var page = ClientTickEntityQueryService.select(observations, query, 1, PLAYER_POSITION);
		var observationsBuilt = new AtomicInteger();

		List<Integer> entityIds = ClientTickEntityQueryService.observeSelected(
			page.entities(),
			entity -> {
				observationsBuilt.incrementAndGet();
				return entity.entityId();
			}
		);

		assertEquals(1, observationsBuilt.get());
		assertEquals(List.of(4), entityIds);
	}

	@Test
	void queryRejectsConflictingSpatialSelectorsAndInvalidPages() {
		var region = new ClientTickWorldQueryService.RegionBounds(0, 0, 0, 1, 1, 1);
		var radius = new ClientTickEntityQueryService.RadiusBounds(0.0D, 0.0D, 0.0D, 1.0D);

		assertThrows(
			BridgeUnavailableException.class,
			() -> new ClientTickEntityQueryService.EntityQuery(
				region, radius, null, null, null, Set.of(), null, false, false, false, 0L, 32
			)
		);
		assertThrows(
			BridgeUnavailableException.class,
			() -> new ClientTickEntityQueryService.EntityQuery(
				null, null, null, null, null, Set.of(), null, false, false, false, -1L, 32
			)
		);
	}

	private static ClientTickEntityQueryService.EntityObservation observation(
		int entityId,
		String uuid,
		String name,
		String entityTypeId,
		double x,
		double y,
		double z,
		boolean alive,
		boolean living,
		boolean player
	) {
		return new ClientTickEntityQueryService.EntityObservation(
			entityId,
			uuid,
			name,
			entityTypeId,
			x,
			y,
			z,
			alive,
			living,
			player,
			Map.of("entityId", entityId)
		);
	}
}
