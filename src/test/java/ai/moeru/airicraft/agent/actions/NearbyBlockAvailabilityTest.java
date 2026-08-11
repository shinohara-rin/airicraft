package ai.moeru.airicraft.agent.actions;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NearbyBlockAvailabilityTest {
	private static final ActionResolverContext CONTEXT = new ActionResolverContext(
		"world-a",
		"bot",
		"minecraft:overworld",
		100
	);

	@Test
	void observedSnapshotCopiesAndNormalizesCounts() {
		LinkedHashMap<String, Integer> source = new LinkedHashMap<>();
		source.put("minecraft:oak_log", 3);
		source.put("minecraft:oak_leaves", -2);
		source.put("", 9);

		NearbyBlockAvailability availability = NearbyBlockAvailability.observed(source);
		source.put("minecraft:oak_log", 99);

		assertTrue(availability.observed());
		assertEquals(3, availability.count("minecraft:oak_log"));
		assertEquals(0, availability.count("minecraft:oak_leaves"));
		assertFalse(availability.counts().containsKey(""));
		assertThrows(UnsupportedOperationException.class, () -> availability.counts().put("minecraft:stone", 1));
	}

	@Test
	void unknownIsDistinctFromObservedEmptySnapshot() {
		NearbyBlockAvailability unknown = NearbyBlockAvailability.unknown();
		NearbyBlockAvailability observedEmpty = NearbyBlockAvailability.observed(Map.of());

		assertFalse(unknown.observed());
		assertTrue(observedEmpty.observed());
		assertEquals(Map.of(), unknown.counts());
		assertEquals(Map.of(), observedEmpty.counts());
	}

	@Test
	void executionInputAndPlanningSnapshotDefaultAvailabilityToUnknown() {
		ActionGraphExecutionInput executionInput = new ActionGraphExecutionInput(
			CONTEXT,
			Map.of(),
			Map.of(),
			true,
			true,
			null,
			List.of(), List.of(), List.of(), List.of(), List.of(), null, Map.of(),
			BlockAcquisitionIndex.empty(),
			null
		);
		AiricraftPlanningSnapshot snapshot = new AiricraftPlanningSnapshot(
			List.of(), BlockAcquisitionIndex.empty(), null, CONTEXT
		);

		assertFalse(executionInput.nearbyBlockAvailability().observed());
		assertFalse(snapshot.nearbyBlockAvailability().observed());
	}

	@Test
	void planningSnapshotRetainsObservedAvailability() {
		NearbyBlockAvailability availability = NearbyBlockAvailability.observed(Map.of("minecraft:oak_log", 4));
		AiricraftPlanningSnapshot snapshot = new AiricraftPlanningSnapshot(
			List.of(), BlockAcquisitionIndex.empty(), availability, CONTEXT
		);

		assertEquals(availability, snapshot.nearbyBlockAvailability());
	}
}
