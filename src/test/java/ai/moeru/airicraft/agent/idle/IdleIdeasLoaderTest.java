package ai.moeru.airicraft.agent.idle;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdleIdeasLoaderTest {
	@Test
	void fromMapReadsAllFields() {
		IdleIdeasConfig defaults = IdleIdeasConfig.defaults();

		IdleIdeasConfig parsed = IdleIdeasLoader.fromMap(Map.of(
			"enabled", false,
			"initialDelaySeconds", 15,
			"cooldownSeconds", 45,
			"ideas", List.of("first", "second")
		), defaults);

		assertFalse(parsed.enabled());
		assertEquals(15, parsed.initialDelaySeconds());
		assertEquals(45, parsed.cooldownSeconds());
		assertEquals(List.of("first", "second"), parsed.ideas());
	}

	@Test
	void fromMapFallsBackWhenIdeasMissing() {
		IdleIdeasConfig defaults = IdleIdeasConfig.defaults();

		IdleIdeasConfig parsed = IdleIdeasLoader.fromMap(Map.of(
			"enabled", true,
			"initialDelaySeconds", 5,
			"cooldownSeconds", 5
		), defaults);

		assertEquals(defaults.ideas(), parsed.ideas());
		assertTrue(parsed.enabled());
	}

	@Test
	void fromMapTrimsAndDropsBlankIdeas() {
		IdleIdeasConfig defaults = IdleIdeasConfig.defaults();

		IdleIdeasConfig parsed = IdleIdeasLoader.fromMap(Map.of(
			"ideas", List.of("  surrounded  ", "", "  ")
		), defaults);

		assertEquals(List.of("surrounded"), parsed.ideas());
	}

	@Test
	void fromMapStrictRejectsInvalidBoolean() {
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
			IdleIdeasLoader.fromMapStrict(Map.of(
				"enabled", "occasionally"
			), IdleIdeasConfig.defaults())
		);

		assertEquals("enabled must be true or false", exception.getMessage());
	}

	@Test
	void fromMapStrictRejectsNonListIdeas() {
		assertThrows(IllegalArgumentException.class, () ->
			IdleIdeasLoader.fromMapStrict(Map.of(
				"ideas", "not a list"
			), IdleIdeasConfig.defaults())
		);
	}
}
