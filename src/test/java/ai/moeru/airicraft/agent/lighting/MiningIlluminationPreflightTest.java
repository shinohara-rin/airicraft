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
	void requiresTorchUnlessPlannerExplicitlyOverrides() {
		var prediction = new MiningIlluminationPreflight.Result(true, "target_type_likely_underground");

		assertFalse(MiningIlluminationPreflight.admit(prediction, 0, false).allowed());
		assertEquals("torch_available", MiningIlluminationPreflight.admit(prediction, 1, false).reason());
		assertEquals("planner_override", MiningIlluminationPreflight.admit(prediction, 0, true).reason());
	}
}
