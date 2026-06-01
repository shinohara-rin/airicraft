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

		manager.updateProcessFingerprint("smelt:iron:nearby-1", empty.key(), insertedSlots);

		assertTrue(started.accepted());
		assertEquals(SmeltingStationState.AIRICRAFT_OWNED, manager.classify(inserted, 401L));
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
