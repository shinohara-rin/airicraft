package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntitySelectorResolverTest {
	@Test
	void selectsSingleNearbyEntityByType() {
		EntitySelectorResolver.SelectionResult result = EntitySelectorResolver.select(
			new EntitySelector(null, null, "minecraft:sheep"),
			List.of(
				new EntitySelectorResolver.EntityCandidate(1, "uuid-sheep", "Dinner", "minecraft:sheep", 5.0D, 64.0D, 0.0D, true),
				new EntitySelectorResolver.EntityCandidate(2, "uuid-cow", "Milk", "minecraft:cow", 2.0D, 64.0D, 0.0D, true)
			),
			0.0D,
			64.0D,
			0.0D
		);

		assertEquals(EntitySelectorResolver.SelectionStatus.SELECTED, result.status());
		assertNotNull(result.selected());
		assertEquals(1, result.selected().entityId());
	}

	@Test
	void reportsTargetNotNearbyWhenOnlyMatchIsOutOfNearbyRange() {
		EntitySelectorResolver.SelectionResult result = EntitySelectorResolver.select(
			new EntitySelector(null, "Dinner", null),
			List.of(
				new EntitySelectorResolver.EntityCandidate(1, "uuid-sheep", "Dinner", "minecraft:sheep", 40.0D, 64.0D, 0.0D, true)
			),
			0.0D,
			64.0D,
			0.0D
		);

		assertEquals(EntitySelectorResolver.SelectionStatus.TARGET_NOT_NEARBY, result.status());
		assertEquals(1, result.matchCount());
	}

	@Test
	void reportsAmbiguousWhenMultipleNearbyEntitiesMatch() {
		EntitySelectorResolver.SelectionResult result = EntitySelectorResolver.select(
			new EntitySelector(null, null, "minecraft:sheep"),
			List.of(
				new EntitySelectorResolver.EntityCandidate(1, "uuid-sheep-1", "Dinner", "minecraft:sheep", 3.0D, 64.0D, 0.0D, true),
				new EntitySelectorResolver.EntityCandidate(2, "uuid-sheep-2", "Wooly", "minecraft:sheep", 4.0D, 64.0D, 0.0D, true)
			),
			0.0D,
			64.0D,
			0.0D
		);

		assertEquals(EntitySelectorResolver.SelectionStatus.TARGET_AMBIGUOUS, result.status());
		assertEquals(2, result.matchCount());
	}

	@Test
	void selectsSingleNearbyEntityByUuidPrefix() {
		EntitySelectorResolver.SelectionResult result = EntitySelectorResolver.select(
			new EntitySelector("12345678-a", null, null),
			List.of(
				new EntitySelectorResolver.EntityCandidate(1, "12345678-aaaa-4d9d-8b9d-fb24b98cb81d", "Slime", "minecraft:slime", 3.0D, 64.0D, 0.0D, true),
				new EntitySelectorResolver.EntityCandidate(2, "87654321-bbbb-4d9d-8b9d-fb24b98cb81d", "Chicken", "minecraft:chicken", 2.0D, 64.0D, 0.0D, true)
			),
			0.0D,
			64.0D,
			0.0D
		);

		assertEquals(EntitySelectorResolver.SelectionStatus.SELECTED, result.status());
		assertNotNull(result.selected());
		assertEquals(1, result.selected().entityId());
	}

	@Test
	void reportsAmbiguousWhenUuidPrefixMatchesMultipleNearbyEntities() {
		EntitySelectorResolver.SelectionResult result = EntitySelectorResolver.select(
			new EntitySelector("12345678", null, null),
			List.of(
				new EntitySelectorResolver.EntityCandidate(1, "12345678-aaaa-4d9d-8b9d-fb24b98cb81d", "Slime A", "minecraft:slime", 3.0D, 64.0D, 0.0D, true),
				new EntitySelectorResolver.EntityCandidate(2, "12345678-bbbb-4d9d-8b9d-fb24b98cb81d", "Slime B", "minecraft:slime", 4.0D, 64.0D, 0.0D, true)
			),
			0.0D,
			64.0D,
			0.0D
		);

		assertEquals(EntitySelectorResolver.SelectionStatus.TARGET_AMBIGUOUS, result.status());
		assertEquals(2, result.matchCount());
	}

	@Test
	void interactionRangeUsesSharedThreshold() {
		assertTrue(EntitySelectorResolver.isWithinInteractionRange(0.0D, 64.0D, 0.0D, 4.4D, 64.0D, 0.0D));
		assertFalse(EntitySelectorResolver.isWithinInteractionRange(0.0D, 64.0D, 0.0D, 4.6D, 64.0D, 0.0D));
	}
}
