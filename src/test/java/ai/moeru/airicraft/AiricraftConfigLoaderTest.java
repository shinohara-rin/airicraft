package ai.moeru.airicraft;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiricraftConfigLoaderTest {
	@Test
	void fromMapReadsChatRuntimeSettings() {
		AiricraftConfig defaults = AiricraftConfig.defaults();

		AiricraftConfig parsed = AiricraftConfigLoader.fromMap(Map.of(
			"socialChatMaxDistanceBlocks", 96,
			"readSystemChatMessages", false,
			"enableProactiveSocialMode", true,
			"suppressAutoPauseOnFocusLost", false,
			"blockInteractionDelayTicks", 4
		), defaults);

		assertEquals(96, parsed.socialChatMaxDistanceBlocks());
		assertFalse(parsed.readSystemChatMessages());
		assertTrue(parsed.enableProactiveSocialMode());
		assertFalse(parsed.suppressAutoPauseOnFocusLost());
		assertEquals(4, parsed.blockInteractionDelayTicks());
	}

	@Test
	void fromMapDefaultsSuppressAutoPauseOnFocusLostWhenAbsent() {
		AiricraftConfig defaults = AiricraftConfig.defaults();

		AiricraftConfig parsed = AiricraftConfigLoader.fromMap(Map.of(), defaults);

		assertEquals(defaults.suppressAutoPauseOnFocusLost(), parsed.suppressAutoPauseOnFocusLost());
		assertEquals(defaults.blockInteractionDelayTicks(), parsed.blockInteractionDelayTicks());
	}

	@Test
	void fromMapClampsNegativeBlockInteractionDelay() {
		AiricraftConfig parsed = AiricraftConfigLoader.fromMap(Map.of(
			"blockInteractionDelayTicks", -5
		), AiricraftConfig.defaults());

		assertEquals(0, parsed.blockInteractionDelayTicks());
	}

	@Test
	void fromMapStrictRejectsInvalidBoolean() {
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
			AiricraftConfigLoader.fromMapStrict(Map.of(
				"readSystemChatMessages", "sometimes"
			), AiricraftConfig.defaults())
		);

		assertEquals("readSystemChatMessages must be true or false", exception.getMessage());
	}
}
