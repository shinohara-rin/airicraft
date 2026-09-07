package ai.moeru.airicraft.systemone.voxel;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ai.moeru.airicraft.systemone.voxel.LightingPolicy.*;
import ai.moeru.airicraft.systemone.voxel.VoxelObservation.Pos;

class LightingPolicyTest {
	private final LightingPolicy policy = new LightingPolicy(new Parameters(7, 10, 8, 80, 4));
	private static final Pos ORIGIN = new Pos(0, 10, 0);
	@Test void recoveryHasHysteresisAndExistingLightIsPreferredToResupply() {
		assertEquals(Action.CONTINUE, policy.assess(State.begin(), 8, 2, true, ORIGIN, 0).action());
		var low = policy.assess(State.begin(), 6, 2, true, ORIGIN, 1);
		assertEquals(Action.PLACE, low.action());
		assertEquals(Action.PLACE, policy.assess(low.state(), 8, 2, true, ORIGIN, 2).action());
		assertEquals(State.begin(), policy.assess(low.state(), 10, 1, true, ORIGIN, 3).state());
	}
	@Test void failedSupplyCreatesOneBoundedAllowanceInsteadOfARepairLoop() {
		var low = policy.assess(State.begin(), 4, 0, true, ORIGIN, 1);
		assertEquals(Action.SUPPLY, low.action());
		var allowed = policy.assess(low.state().failed(Repair.SUPPLY), 4, 0, true, ORIGIN, 2);
		assertEquals(Action.REDUCED_LIGHT_PROGRESS, allowed.action());
		assertEquals(Action.REDUCED_LIGHT_PROGRESS, policy.assess(allowed.state(), 4, 0, true, ORIGIN.offset(2, 0, 0), 20).action());
		assertEquals(Action.RETREAT, policy.assess(allowed.state(), 4, 0, true, ORIGIN, 82).action());
		assertEquals(Action.RETREAT, policy.assess(allowed.state(), 4, 0, true, ORIGIN.offset(5, 0, 0), 3).action());
	}
	@Test void aTorchBootstrapCannotRecursivelyRequestTorchSupply() {
		assertEquals(Action.REDUCED_LIGHT_PROGRESS, policy.assess(State.begin(), 4, 0, false, ORIGIN, 1).action());
	}
	@Test void unusablePlacementDoesNotRequestAlreadyOwnedSuppliesForever() {
		assertEquals(Action.REDUCED_LIGHT_PROGRESS, policy.assess(State.begin().failed(Repair.PLACEMENT), 4, 8, true, ORIGIN, 1).action());
	}
}
