package ai.moeru.airicraft.agent.lighting;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningIlluminationPreflightTest {
	@Test
	void prioritizesObservedEvidenceOverTargetTypeFallback() {
		var current = MiningIlluminationPreflight.assess(true, true, true);
		var loaded = MiningIlluminationPreflight.assess(false, true, true);
		var fallback = MiningIlluminationPreflight.assess(false, false, true);

		assertEquals("current_position_underground", current.reason());
		assertEquals("loaded_target_unilluminated", loaded.reason());
		assertEquals("target_type_likely_underground", fallback.reason());
	}

	@Test
	void recognizesOreAndBaseStoneWithoutClassifyingSurfaceMaterials() {
		assertTrue(MiningIlluminationPreflight.likelyUndergroundTarget(List.of("minecraft:deepslate_iron_ore")));
		assertTrue(MiningIlluminationPreflight.likelyUndergroundTarget(List.of("minecraft:stone")));
		assertFalse(MiningIlluminationPreflight.likelyUndergroundTarget(List.of("minecraft:oak_log", "minecraft:sand")));
	}

	@Test
	void permitsObservedSurfaceTaskWhenNoUndergroundSignalExists() {
		var result = MiningIlluminationPreflight.assess(false, false, false);

		assertFalse(result.illuminationRequired());
		assertEquals("not_predicted", result.reason());
	}

	@Test
	void observedSurfaceBootstrapOverridesStoneTypeFallback() {
		var observed = MiningIlluminationPreflight.assess(false, false, true, true);
		var unobserved = MiningIlluminationPreflight.assess(false, false, false, true);

		assertFalse(observed.illuminationRequired());
		assertEquals("target_type_likely_underground", unobserved.reason());
	}

	@Test
	void permitsSurfaceStoneBootstrapButGuardsUndergroundStoneAndDarkOre() {
		assertFalse(MiningIlluminationPreflight.loadedTargetRequiresIllumination("minecraft:stone", true, 0, 7));
		assertFalse(MiningIlluminationPreflight.loadedTargetRequiresIllumination("minecraft:cobblestone", true, 0, 7));
		assertTrue(MiningIlluminationPreflight.loadedTargetRequiresIllumination("minecraft:stone", false, 0, 7));
		assertTrue(MiningIlluminationPreflight.loadedTargetRequiresIllumination("minecraft:iron_ore", true, 0, 7));
	}

	@Test
	void treatsStoneBelowAShallowSurfaceOpeningAsAccessible() {
		assertTrue(MiningIlluminationPreflight.hasSurfaceOpeningWithinProbe(offset -> offset == 2));
		assertFalse(MiningIlluminationPreflight.hasSurfaceOpeningWithinProbe(offset -> false));
	}

	@Test
	void doesNotClassifyTreeCanopyAsUndergroundWhenSkyIsVisibleNearby() {
		assertTrue(MiningIlluminationPreflight.hasNearbySurfaceOpening((x, y, z) -> x == 3 && y == 2 && z == 0));
		assertFalse(MiningIlluminationPreflight.hasNearbySurfaceOpening((x, y, z) -> false));
	}

	@Test
	void surfaceBootstrapUsesAccessibleCandidateInsteadOfRejectingForBuriedCandidates() {
		assertFalse(MiningIlluminationPreflight.aggregateLoadedTargetEvidence(true, true, true));
		assertTrue(MiningIlluminationPreflight.aggregateLoadedTargetEvidence(true, true, false));
		assertTrue(MiningIlluminationPreflight.aggregateLoadedTargetEvidence(false, true, true));
	}

	@Test
	void doesNotRequireTorchBeforeUnilluminatedMining() {
		var prediction = new MiningIlluminationPreflight.Result(true, "target_type_likely_underground");

		assertTrue(MiningIlluminationPreflight.admit(prediction, 0, false).allowed());
		assertEquals("illumination_advisory", MiningIlluminationPreflight.admit(prediction, 0, false).reason());
	}
}
