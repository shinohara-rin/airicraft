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

		assertTrue(prompt.contains("craft_recipe task completed"));
		assertTrue(prompt.contains("Do not call craft_recipe again"));
		assertTrue(prompt.contains("next distinct craft_recipe"));
		assertTrue(prompt.contains("call clear_goal"));
		assertTrue(prompt.contains("unless the user explicitly requested a multi-step craft"));
	}

	@Test
	void systemPromptKeepsToolNarrationOutOfPlaintextContent() {
		String prompt = PlannerPromptPolicy.systemPrompt(PlannerVisionMode.EXTERNAL_SUMMARY);

		assertTrue(prompt.contains("Do not write narration as assistant content"));
		assertTrue(prompt.contains("assistant content must be empty or null"));
		assertTrue(prompt.contains("must be inspect_inventory.narration"));
	}
}
