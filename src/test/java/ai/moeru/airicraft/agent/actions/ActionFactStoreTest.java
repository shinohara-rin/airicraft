package ai.moeru.airicraft.agent.actions;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionFactStoreTest {
	@Test
	void inventoryItemFactsMergeByWorldActorAndItemIdentity() {
		ActionFactStore store = new ActionFactStore();
		ActionFactIdentity wheat = ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:wheat");

		store.upsert(new ActionFact(
			wheat,
			Map.of("count", 2),
			ActionFactProvenance.OBSERVED,
			10,
			30
		));
		store.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:wheat"),
			Map.of("count", 5, "slots", List.of(0, 1)),
			ActionFactProvenance.EXECUTOR_REPORTED,
			12,
			42
		));
		store.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-b", "bot", "minecraft:wheat"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			12,
			42
		));

		assertEquals(2, store.size());
		ActionFact stored = store.find(wheat).orElseThrow();
		assertEquals(5, stored.payload().get("count"));
		assertEquals(ActionFactProvenance.EXECUTOR_REPORTED, stored.provenance());
		assertEquals(42, stored.staleAfterTick());
		assertEquals(1, store.query(ActionFactType.INVENTORY_ITEM, Map.of("worldId", "world-a", "itemId", "minecraft:wheat")).size());
	}

	@Test
	void cropAndCropGroupFactsUseDifferentIdentityKeys() {
		ActionFactStore store = new ActionFactStore();
		ActionFactIdentity crop = ActionFactIdentity.worldCrop("world-a", "minecraft:overworld", "10,64,10");
		ActionFactIdentity group = ActionFactIdentity.worldCropGroup("world-a", "minecraft:overworld", "farm-1", "minecraft:wheat");

		store.upsert(new ActionFact(
			crop,
			Map.of("cropId", "minecraft:wheat", "mature", false, "siteId", "farm-1"),
			ActionFactProvenance.OBSERVED,
			20,
			220
		));
		store.upsert(new ActionFact(
			group,
			Map.of("matureCount", 3, "totalCount", 9),
			ActionFactProvenance.INFERRED,
			20,
			220
		));

		assertEquals("10,64,10", crop.keys().get("blockPos"));
		assertEquals("farm-1", group.keys().get("siteId"));
		assertEquals(1, store.query(ActionFactType.WORLD_CROP).size());
		assertEquals(1, store.query(ActionFactType.WORLD_CROP_GROUP).size());
	}

	@Test
	void provenanceDistinguishesAuthoritativeFactsFromExpectedFacts() {
		assertTrue(ActionFactProvenance.OBSERVED.authoritative());
		assertTrue(ActionFactProvenance.EXECUTOR_REPORTED.authoritative());
		assertFalse(ActionFactProvenance.EXPECTED.authoritative());
		assertFalse(ActionFactProvenance.INFERRED.authoritative());
		assertFalse(ActionFactProvenance.FAILED.authoritative());
		assertFalse(ActionFactProvenance.STALE.authoritative());
	}

	@Test
	void expectedFactDoesNotOverwriteAuthoritativeObservation() {
		ActionFactStore store = new ActionFactStore();
		ActionFactIdentity bread = ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:bread");

		store.upsert(new ActionFact(
			bread,
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			100,
			200
		));
		ActionFact retained = store.upsert(new ActionFact(
			bread,
			Map.of("count", 2),
			ActionFactProvenance.EXPECTED,
			101,
			201
		));

		assertEquals(ActionFactProvenance.OBSERVED, retained.provenance());
		assertEquals(1, store.find(bread).orElseThrow().payload().get("count"));
	}

	@Test
	void staleAfterTickControlsFreshnessWithoutChangingIdentity() {
		ActionFact fact = new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:bread"),
			Map.of("count", 1),
			ActionFactProvenance.EXPECTED,
			100,
			120
		);
		ActionFact neverStale = new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stick"),
			Map.of("count", 4),
			ActionFactProvenance.OBSERVED,
			100,
			ActionFact.NEVER_STALE
		);

		assertFalse(fact.isStaleAt(119));
		assertTrue(fact.isStaleAt(120));
		assertFalse(neverStale.isStaleAt(1_000_000));
	}
}
