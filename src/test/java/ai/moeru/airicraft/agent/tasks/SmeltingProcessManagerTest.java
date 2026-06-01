package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmeltingProcessManagerTest {
	@Test
	void occupiedStationRequiresConfirmationBeforeMutation() {
		SmeltingProcessManager manager = new SmeltingProcessManager();
		SmeltingStationObservation occupied = occupiedStation(1);
		SmeltItemsStepArgs request = new SmeltItemsStepArgs(
			"smelt:iron:nearby-occupied",
			1,
			SmeltingFuelMode.AUTO,
			null,
			0,
			null
		);

		SmeltingActionResult first = manager.startProcess(request, occupied, 100L);
		SmeltingActionResult confirmed = manager.startProcess(
			new SmeltItemsStepArgs(
				request.optionId(),
				request.inputQuantity(),
				request.fuelMode(),
				request.fuelItemId(),
				request.fuelQuantity(),
				first.confirmationToken()
			),
			occupied,
			101L
		);

		assertTrue(first.confirmationRequired());
		assertNotNull(first.confirmationToken());
		assertFalse(first.accepted());
		assertTrue(first.message().contains("OCCUPIED"));
		assertTrue(confirmed.accepted());
		assertNotNull(confirmed.processId());
		assertEquals(SmeltingStationState.AIRICRAFT_OWNED, manager.classify(occupied, 102L));
	}

	@Test
	void confirmationTokenIsBoundToObservedSlotFingerprint() {
		SmeltingProcessManager manager = new SmeltingProcessManager();
		SmeltingStationObservation occupied = occupiedStation(1);
		SmeltingActionResult first = manager.collectUntrackedOutput(
			new CollectSmeltedItemsStepArgs(null, null),
			occupied,
			200L
		);
		SmeltingStationObservation changed = occupiedStation(2);

		SmeltingActionResult stale = manager.collectUntrackedOutput(
			new CollectSmeltedItemsStepArgs(null, first.confirmationToken()),
			changed,
			201L
		);

		assertTrue(first.confirmationRequired());
		assertFalse(stale.accepted());
		assertFalse(stale.confirmationRequired());
		assertEquals("invalid_confirmation_token", stale.errorCode());
	}

	@Test
	void stationRankingPrefersNearbyExistingFurnacesBeforePlaceableFurnace() {
		SmeltingProcessManager manager = new SmeltingProcessManager();
		SmeltingStationCandidate emptyExisting = new SmeltingStationCandidate(
			SmeltingStationSource.NEARBY_EXISTING,
			SmeltingStationState.EMPTY,
			SmeltingStationKind.FURNACE,
			new SmeltingStationKey("minecraft:overworld", 3, 64, 3),
			3.5D,
			false
		);
		SmeltingStationCandidate placeable = new SmeltingStationCandidate(
			SmeltingStationSource.PLACE_FROM_INVENTORY,
			SmeltingStationState.EMPTY,
			SmeltingStationKind.FURNACE,
			null,
			0.0D,
			false
		);
		SmeltingStationCandidate occupiedExisting = new SmeltingStationCandidate(
			SmeltingStationSource.NEARBY_EXISTING,
			SmeltingStationState.OCCUPIED,
			SmeltingStationKind.FURNACE,
			new SmeltingStationKey("minecraft:overworld", 1, 64, 1),
			1.0D,
			true
		);

		assertEquals(
			java.util.List.of(emptyExisting, occupiedExisting, placeable),
			manager.rankCandidates(java.util.List.of(placeable, occupiedExisting, emptyExisting))
		);
	}

	@Test
	void registeredOptionResolvesToObservedStationForConfirmation() {
		SmeltingProcessManager manager = new SmeltingProcessManager();
		SmeltingStationObservation occupied = occupiedStation(1);
		SmeltingOption option = new SmeltingOption(
			"smelt:minecraft_raw_iron:nearby-1",
			"minecraft:raw_iron",
			"minecraft:iron_ingot",
			1,
			1,
			200,
			new SmeltingStationCandidate(
				SmeltingStationSource.NEARBY_EXISTING,
				SmeltingStationState.OCCUPIED,
				SmeltingStationKind.FURNACE,
				occupied.key(),
				1.0D,
				true
			),
			occupied
		);
		manager.registerOptions(java.util.List.of(option));

		SmeltingActionResult first = manager.startRegisteredProcess(
			new SmeltItemsStepArgs(option.optionId(), 1, SmeltingFuelMode.AUTO, null, 0, null),
			300L
		);
		SmeltingActionResult confirmed = manager.startRegisteredProcess(
			new SmeltItemsStepArgs(option.optionId(), 1, SmeltingFuelMode.AUTO, null, 0, first.confirmationToken()),
			301L
		);

		assertTrue(first.confirmationRequired());
		assertTrue(confirmed.accepted());
		assertEquals(SmeltingStationState.AIRICRAFT_OWNED, manager.classify(occupied, 302L));
	}

	@Test
	void ownedProcessFingerprintCanAdvanceAfterExecutorMutation() {
		SmeltingProcessManager manager = new SmeltingProcessManager();
		SmeltingStationObservation empty = new SmeltingStationObservation(
			new SmeltingStationKey("minecraft:overworld", 1, 64, 1),
			SmeltingStationKind.FURNACE,
			new SmeltingSlotSnapshot(null, 0, null, 0, null, 0, 0, 200, false),
			false,
			1.0D
		);
		SmeltingActionResult started = manager.startProcess(
			new SmeltItemsStepArgs("smelt:iron:nearby-1", 1, SmeltingFuelMode.AUTO, null, 0, null),
			empty,
			400L
		);
		SmeltingSlotSnapshot insertedSlots = new SmeltingSlotSnapshot(
			"minecraft:raw_iron",
			1,
			"minecraft:coal",
			1,
			null,
			0,
			0,
			200,
			false
		);
		SmeltingStationObservation inserted = new SmeltingStationObservation(
			empty.key(),
			empty.kind(),
			insertedSlots,
			false,
			1.0D
		);

		manager.updateProcessFingerprint("smelt:iron:nearby-1", empty.key(), insertedSlots, 401L);

		assertTrue(started.accepted());
		assertEquals(SmeltingStationState.AIRICRAFT_OWNED, manager.classify(inserted, 401L));
	}

	@Test
	void carriedFurnaceProcessCanRelocateWhenPlacementCandidateGoesStale() {
		SmeltingProcessManager manager = new SmeltingProcessManager();
		SmeltingStationKey originalKey = new SmeltingStationKey("minecraft:overworld", 1, 64, 1);
		SmeltingStationKey relocatedKey = new SmeltingStationKey("minecraft:overworld", 2, 64, 1);
		SmeltingStationObservation empty = new SmeltingStationObservation(
			originalKey,
			SmeltingStationKind.FURNACE,
			new SmeltingSlotSnapshot(null, 0, null, 0, null, 0, 0, 200, false),
			false,
			1.0D
		);
		SmeltingOption option = new SmeltingOption(
			"smelt:log:carried_furnace-1",
			"minecraft:oak_log",
			"minecraft:charcoal",
			1,
			4,
			200,
			new SmeltingStationCandidate(
				SmeltingStationSource.PLACE_FROM_INVENTORY,
				SmeltingStationState.EMPTY,
				SmeltingStationKind.FURNACE,
				originalKey,
				1.0D,
				false
			),
			empty
		);
		manager.registerOptions(java.util.List.of(option));
		SmeltingActionResult started = manager.startRegisteredProcess(
			new SmeltItemsStepArgs(option.optionId(), 4, SmeltingFuelMode.AUTO, null, 0, null),
			500L
		);

		boolean relocated = manager.relocatePlacementProcess(option.optionId(), originalKey, relocatedKey, 2.0D);
		SmeltingOption relocatedOption = manager.registeredOption(option.optionId());

		assertTrue(started.accepted());
		assertTrue(relocated);
		assertEquals(relocatedKey, relocatedOption.stationObservation().key());
		assertEquals(relocatedKey, manager.processStationKey(started.processId()));
		assertEquals(SmeltingStationState.AIRICRAFT_OWNED, manager.classify(relocatedOption.stationObservation(), 501L));
	}

	@Test
	void trackedProcessOutputReadyFiresOnceWhenExpectedOutputAppears() {
		SmeltingProcessManager manager = new SmeltingProcessManager();
		SmeltingStationObservation empty = new SmeltingStationObservation(
			new SmeltingStationKey("minecraft:overworld", 1, 64, 1),
			SmeltingStationKind.FURNACE,
			new SmeltingSlotSnapshot(null, 0, null, 0, null, 0, 0, 200, false),
			false,
			1.0D
		);
		SmeltingOption option = new SmeltingOption(
			"smelt:iron:nearby-1",
			"minecraft:raw_iron",
			"minecraft:iron_ingot",
			1,
			1,
			200,
			new SmeltingStationCandidate(
				SmeltingStationSource.NEARBY_EXISTING,
				SmeltingStationState.EMPTY,
				SmeltingStationKind.FURNACE,
				empty.key(),
				1.0D,
				false
			),
			empty
		);
		manager.registerOptions(java.util.List.of(option));
		SmeltingActionResult started = manager.startRegisteredProcess(
			new SmeltItemsStepArgs(option.optionId(), 1, SmeltingFuelMode.AUTO, null, 0, null),
			600L
		);
		SmeltingStationObservation ready = new SmeltingStationObservation(
			empty.key(),
			empty.kind(),
			new SmeltingSlotSnapshot(null, 0, null, 0, "minecraft:iron_ingot", 1, 0, 200, false),
			false,
			1.0D
		);
		manager.registerOptions(java.util.List.of());

		java.util.List<SmeltingOutputReadyEvent> first = manager.markReadyOutputs(java.util.List.of(ready), 700L);
		java.util.List<SmeltingOutputReadyEvent> second = manager.markReadyOutputs(java.util.List.of(ready), 701L);

		assertTrue(started.accepted());
		assertEquals(1, first.size());
		assertEquals(started.processId(), first.getFirst().processId());
		assertEquals("smelt:iron:nearby-1", first.getFirst().optionId());
		assertEquals("minecraft:iron_ingot", first.getFirst().outputItemId());
		assertEquals(1, first.getFirst().outputCount());
		assertFalse(first.getFirst().estimated());
		assertTrue(second.isEmpty());
	}

	@Test
	void trackedProcessOutputReadyIgnoresUnexpectedOutputItem() {
		SmeltingProcessManager manager = new SmeltingProcessManager();
		SmeltingStationObservation empty = new SmeltingStationObservation(
			new SmeltingStationKey("minecraft:overworld", 1, 64, 1),
			SmeltingStationKind.FURNACE,
			new SmeltingSlotSnapshot(null, 0, null, 0, null, 0, 0, 200, false),
			false,
			1.0D
		);
		SmeltingOption option = new SmeltingOption(
			"smelt:iron:nearby-1",
			"minecraft:raw_iron",
			"minecraft:iron_ingot",
			1,
			1,
			200,
			new SmeltingStationCandidate(
				SmeltingStationSource.NEARBY_EXISTING,
				SmeltingStationState.EMPTY,
				SmeltingStationKind.FURNACE,
				empty.key(),
				1.0D,
				false
			),
			empty
		);
		manager.registerOptions(java.util.List.of(option));
		manager.startRegisteredProcess(
			new SmeltItemsStepArgs(option.optionId(), 1, SmeltingFuelMode.AUTO, null, 0, null),
			800L
		);
		manager.registerOptions(java.util.List.of());
		SmeltingStationObservation unexpected = new SmeltingStationObservation(
			empty.key(),
			empty.kind(),
			new SmeltingSlotSnapshot(null, 0, null, 0, "minecraft:charcoal", 1, 0, 200, false),
			false,
			1.0D
		);

		assertTrue(manager.markReadyOutputs(java.util.List.of(unexpected), 900L).isEmpty());
	}

	@Test
	void trackedProcessOutputReadyCanFireFromCookTimeEstimateWithoutVisibleSlots() {
		SmeltingProcessManager manager = new SmeltingProcessManager();
		SmeltingStationObservation empty = new SmeltingStationObservation(
			new SmeltingStationKey("minecraft:overworld", 1, 64, 1),
			SmeltingStationKind.FURNACE,
			new SmeltingSlotSnapshot(null, 0, null, 0, null, 0, 0, 200, false),
			false,
			1.0D
		);
		SmeltingOption option = new SmeltingOption(
			"smelt:iron:nearby-1",
			"minecraft:raw_iron",
			"minecraft:iron_ingot",
			1,
			3,
			200,
			new SmeltingStationCandidate(
				SmeltingStationSource.NEARBY_EXISTING,
				SmeltingStationState.EMPTY,
				SmeltingStationKind.FURNACE,
				empty.key(),
				1.0D,
				false
			),
			empty
		);
		manager.registerOptions(java.util.List.of(option));
		SmeltingActionResult started = manager.startRegisteredProcess(
			new SmeltItemsStepArgs(option.optionId(), 2, SmeltingFuelMode.AUTO, null, 0, null),
			1000L
		);
		manager.updateProcessFingerprint(
			option.optionId(),
			empty.key(),
			new SmeltingSlotSnapshot("minecraft:raw_iron", 2, "minecraft:coal", 1, null, 0, 0, 200, true),
			1100L
		);
		manager.registerOptions(java.util.List.of());

		java.util.List<SmeltingOutputReadyEvent> early = manager.markReadyOutputs(java.util.List.of(), 1539L);
		java.util.List<SmeltingOutputReadyEvent> ready = manager.markReadyOutputs(java.util.List.of(), 1540L);
		java.util.List<SmeltingOutputReadyEvent> repeated = manager.markReadyOutputs(java.util.List.of(), 1541L);

		assertTrue(started.accepted());
		assertTrue(early.isEmpty());
		assertEquals(1, ready.size());
		assertEquals(started.processId(), ready.getFirst().processId());
		assertEquals("minecraft:iron_ingot", ready.getFirst().outputItemId());
		assertEquals(2, ready.getFirst().outputCount());
		assertTrue(ready.getFirst().estimated());
		assertTrue(repeated.isEmpty());
	}

	private static SmeltingStationObservation occupiedStation(int inputCount) {
		return new SmeltingStationObservation(
			new SmeltingStationKey("minecraft:overworld", 1, 64, 1),
			SmeltingStationKind.FURNACE,
			new SmeltingSlotSnapshot(
				"minecraft:raw_iron",
				inputCount,
				"minecraft:coal",
				1,
				"minecraft:iron_ingot",
				1,
				40,
				200,
				true
			),
			false,
			1.0D
		);
	}
}
