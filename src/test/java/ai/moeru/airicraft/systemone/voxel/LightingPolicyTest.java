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
		assertEquals(Action.CONTINUE, policy.assess(State.begin(), 8, 2, true, ORIGIN, 0, SurvivalPolicy.Vitals.healthy()).action());
		var low = policy.assess(State.begin(), 6, 2, true, ORIGIN, 1, SurvivalPolicy.Vitals.healthy());
		assertEquals(Action.PLACE, low.action());
		assertEquals(Action.PLACE, policy.assess(low.state(), 8, 2, true, ORIGIN, 2, SurvivalPolicy.Vitals.healthy()).action());
		assertEquals(State.begin(), policy.assess(low.state(), 10, 1, true, ORIGIN, 3, SurvivalPolicy.Vitals.healthy()).state());
	}
	@Test void failedSupplyCreatesOneBoundedAllowanceInsteadOfARepairLoop() {
		var low = policy.assess(State.begin(), 4, 0, true, ORIGIN, 1, SurvivalPolicy.Vitals.healthy());
		assertEquals(Action.SUPPLY, low.action());
		var allowed = policy.assess(low.state().failed(Repair.SUPPLY), 4, 0, true, ORIGIN, 2, SurvivalPolicy.Vitals.healthy());
		assertEquals(Action.REDUCED_LIGHT_PROGRESS, allowed.action());
		assertEquals(Action.REDUCED_LIGHT_PROGRESS, policy.assess(allowed.state(), 4, 0, true, ORIGIN.offset(2, 0, 0), 20, SurvivalPolicy.Vitals.healthy()).action());
		assertEquals(Action.RETREAT, policy.assess(allowed.state(), 4, 0, true, ORIGIN, 82, SurvivalPolicy.Vitals.healthy()).action());
		assertEquals(Action.RETREAT, policy.assess(allowed.state(), 4, 0, true, ORIGIN.offset(5, 0, 0), 3, SurvivalPolicy.Vitals.healthy()).action());
	}
	@Test void aTorchBootstrapCannotRecursivelyRequestTorchSupply() {
		assertEquals(Action.REDUCED_LIGHT_PROGRESS, policy.assess(State.begin(), 4, 0, false, ORIGIN, 1, SurvivalPolicy.Vitals.healthy()).action());
	}
	@Test void unusablePlacementDoesNotRequestAlreadyOwnedSuppliesForever() {
		assertEquals(Action.REDUCED_LIGHT_PROGRESS, policy.assess(State.begin().failed(Repair.PLACEMENT), 4, 8, true, ORIGIN, 1, SurvivalPolicy.Vitals.healthy()).action());
	}
	@Test void healthLossStaysRevokedAfterHealingAndNewSuppliesUntilLightRecovers() {
		var initial = policy.assess(State.begin(), 4, 0, false, ORIGIN, 1, new SurvivalPolicy.Vitals(3, 18, false, false));
		assertEquals(Action.REDUCED_LIGHT_PROGRESS, initial.action(), "stable low health alone does not invalidate a new allowance");
		var healed = policy.assess(initial.state(), 4, 0, false, ORIGIN, 2, new SurvivalPolicy.Vitals(3, 20, false, false));
		var injured = policy.assess(healed.state(), 4, 0, false, ORIGIN, 3, new SurvivalPolicy.Vitals(3, 19, false, false));
		assertEquals(Action.RETREAT, injured.action());
		assertEquals(Validity.HEALTH_LOSS, injured.state().allowance().orElseThrow().validity());
		var stillRevoked = policy.assess(injured.state(), 8, 8, true, ORIGIN, 4, new SurvivalPolicy.Vitals(3, 20, false, false));
		assertEquals(Action.RETREAT, stillRevoked.action());
		assertEquals(81, stillRevoked.state().allowance().orElseThrow().expiresAt());
		assertFalse(policy.permits(stillRevoked.state().allowance().orElseThrow(), ORIGIN, 4));
		assertEquals(State.begin(), policy.assess(stillRevoked.state(), 10, 8, true, ORIGIN, 5, new SurvivalPolicy.Vitals(3, 20, false, false)).state());
	}
	@Test void changedLifeAndObservedBurningInvalidateAnExistingAllowance() {
		var allowed = policy.assess(State.begin(), 4, 0, false, ORIGIN, 1, SurvivalPolicy.Vitals.healthy());
		var changedLife = policy.assess(allowed.state(), 4, 0, false, ORIGIN, 2, new SurvivalPolicy.Vitals(1, 20, false, false));
		assertEquals(Validity.LIFE_CHANGED, changedLife.state().allowance().orElseThrow().validity());
		assertEquals(Action.RETREAT, changedLife.action());
		var burning = policy.assess(allowed.state(), 4, 0, false, ORIGIN, 2, new SurvivalPolicy.Vitals(0, 20, false, true));
		assertEquals(Validity.HAZARD, burning.state().allowance().orElseThrow().validity());
		assertEquals(Action.RETREAT, burning.action());
	}
}
