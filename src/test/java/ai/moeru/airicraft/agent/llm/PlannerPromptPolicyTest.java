package ai.moeru.airicraft.agent.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerPromptPolicyTest {
	@Test
	void systemPromptExplainsInventoryDeltaEvidenceUsesMissionGainNotAbsoluteInventory() {
		String prompt = PlannerPromptPolicy.systemPrompt(PlannerVisionMode.EXTERNAL_SUMMARY);

		assertTrue(prompt.contains("INVENTORY_DELTA_AT_LEAST"));
		assertTrue(prompt.contains("mission start") || prompt.contains("mission began"));
		assertTrue(prompt.contains("not absolute inventory") || prompt.contains("not the current total inventory"));
	}

	@Test
	void systemPromptTellsPlannerNotToRepeatCompletedCrafts() {
		String prompt = PlannerPromptPolicy.systemPrompt(PlannerVisionMode.EXTERNAL_SUMMARY);

		assertTrue(prompt.contains("CRAFT_RECIPE task completed"));
		assertTrue(prompt.contains("Do not issue another CRAFT_RECIPE"));
		assertTrue(prompt.contains("next distinct CRAFT_RECIPE"));
		assertTrue(prompt.contains("activeJob null"));
		assertTrue(prompt.contains("unless the user explicitly requested a multi-step craft"));
	}
}
