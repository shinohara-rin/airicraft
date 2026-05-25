package ai.moeru.airicraft.agent.commonsense;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommonsenseLoaderTest {
	@Test
	void fromMapReadsAllFields() {
		CommonsenseConfig defaults = CommonsenseConfig.defaults();

		CommonsenseConfig parsed = CommonsenseLoader.fromMap(Map.of(
			"enabled", false,
			"rules", List.of("alpha", "beta")
		), defaults);

		assertFalse(parsed.enabled());
		assertEquals(List.of("alpha", "beta"), parsed.rules());
	}

	@Test
	void fromMapFallsBackWhenRulesMissing() {
		CommonsenseConfig defaults = CommonsenseConfig.defaults();

		CommonsenseConfig parsed = CommonsenseLoader.fromMap(Map.of(
			"enabled", true
		), defaults);

		assertEquals(defaults.rules(), parsed.rules());
		assertTrue(parsed.enabled());
	}

	@Test
	void fromMapTrimsAndDropsBlankRules() {
		CommonsenseConfig defaults = CommonsenseConfig.defaults();

		CommonsenseConfig parsed = CommonsenseLoader.fromMap(Map.of(
			"rules", List.of("  surrounded  ", "", "  ")
		), defaults);

		assertEquals(List.of("surrounded"), parsed.rules());
	}

	@Test
	void fromMapStrictRejectsInvalidBoolean() {
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
			CommonsenseLoader.fromMapStrict(Map.of(
				"enabled", "yes"
			), CommonsenseConfig.defaults())
		);

		assertEquals("enabled must be true or false", exception.getMessage());
	}

	@Test
	void fromMapStrictRejectsNonListRules() {
		assertThrows(IllegalArgumentException.class, () ->
			CommonsenseLoader.fromMapStrict(Map.of(
				"rules", "not a list"
			), CommonsenseConfig.defaults())
		);
	}

	@Test
	void effectiveRulesEmptyWhenDisabled() {
		CommonsenseConfig parsed = CommonsenseLoader.fromMap(Map.of(
			"enabled", false,
			"rules", List.of("a", "b")
		), CommonsenseConfig.defaults());

		assertTrue(parsed.effectiveRules().isEmpty());
	}
}
