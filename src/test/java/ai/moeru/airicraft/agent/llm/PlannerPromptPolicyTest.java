package ai.moeru.airicraft.agent.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

	@Test
	void systemPromptExplainsDropAndGiveItemToolsUseExactInventoryIds() {
		String prompt = PlannerPromptPolicy.systemPrompt(PlannerVisionMode.EXTERNAL_SUMMARY);

		assertTrue(prompt.contains("drop_items"));
		assertTrue(prompt.contains("give_player"));
		assertTrue(prompt.contains("inspect_inventory"));
		assertTrue(prompt.contains("itemCounts"));
		assertTrue(prompt.contains("namespaced itemId"));
		assertTrue(prompt.contains("4 blocks"));
		assertTrue(prompt.contains("accepted action tool"));
		assertTrue(prompt.contains("does not mean the action completed"));
		assertTrue(prompt.contains("TASK UPDATE"));
	}

	@Test
	void systemPromptExplainsEntityInteractionToolSelectors() {
		String prompt = PlannerPromptPolicy.systemPrompt(PlannerVisionMode.EXTERNAL_SUMMARY);

		assertTrue(prompt.contains("attack_entity"));
		assertTrue(prompt.contains("use_entity"));
		assertTrue(prompt.contains("uuid"));
		assertTrue(prompt.contains("name"));
		assertTrue(prompt.contains("entityTypeId"));
		assertTrue(prompt.contains("minecraft:shears"));
		assertTrue(prompt.contains("accepted action tool"));
		assertTrue(prompt.contains("TASK UPDATE"));
		assertTrue(prompt.contains("choose exactly one nearby alive target"));
		assertTrue(prompt.contains("prefer the nearest one"));
		assertTrue(prompt.contains("Always copy the uuid token exactly as shown"));
	}

	@Test
	void systemPromptExplainsNearbyEntityInspectionTool() {
		String prompt = PlannerPromptPolicy.systemPrompt(PlannerVisionMode.EXTERNAL_SUMMARY);

		assertTrue(prompt.contains("inspect_nearby_entities"));
		assertTrue(prompt.contains("nearby entities"));
		assertTrue(prompt.contains("uuid"));
		assertTrue(prompt.contains("entityTypeId"));
		assertTrue(prompt.contains("distance"));
	}

	@Test
	void systemPromptExplainsSmeltingConfirmationAndAsyncCompletion() {
		String prompt = PlannerPromptPolicy.systemPrompt(PlannerVisionMode.EXTERNAL_SUMMARY);

		assertTrue(prompt.contains("check_smeltables"));
		assertTrue(prompt.contains("smelt_items"));
		assertTrue(prompt.contains("inspect_smelting"));
		assertTrue(prompt.contains("collect_smelted_items"));
		assertTrue(prompt.contains("confirmationToken"));
		assertTrue(prompt.contains("occupied"));
		assertTrue(prompt.contains("Accepted does not mean completed") || prompt.contains("accepted does not mean completed"));
	}

	@Test
	void systemPromptDistinguishesStartupInventoryFromLatestToolFollowUp() {
		String prompt = PlannerPromptPolicy.systemPrompt(PlannerVisionMode.EXTERNAL_SUMMARY);

		assertTrue(prompt.contains("startup inspect_inventory"));
		assertTrue(prompt.contains("current inventory context"));
		assertTrue(prompt.contains("does not prevent calling another required tool"));
		assertTrue(prompt.contains("latest-request tool call"));
		assertTrue(prompt.contains("tool follow-up"));
	}

	@Test
	void systemPromptIncludesProviderInstructions() {
		String prompt = PlannerPromptPolicy.systemPrompt(
			PlannerVisionMode.EXTERNAL_SUMMARY,
			PlannerToolRegistry.of(new PromptOnlyProvider("Use search_recipes for broad recipe-viewer searches before inventing recipe ids."))
		);

		assertTrue(prompt.contains("Use search_recipes for broad recipe-viewer searches"));
		assertTrue(prompt.contains("Available tools:"));
		assertTrue(prompt.contains("search_recipes"));
	}

	@Test
	void systemPromptRendersMarkdownTemplatePlaceholders() {
		String prompt = PlannerPromptPolicy.systemPrompt(PlannerVisionMode.NATIVE_TOOL_IMAGE);

		assertTrue(prompt.startsWith("You are the planner for a Minecraft companion."));
		assertTrue(prompt.contains("If you need visual information, call take_a_look."));
		assertFalse(prompt.contains("{{"));
	}

	@Test
	void compactionInstructionLoadsMarkdownTemplate() {
		String prompt = PlannerPromptPolicy.compactionInstruction();

		assertTrue(prompt.startsWith("COMPACTION TASK:"));
		assertTrue(prompt.contains("\"forgettable_noise\": string[]"));
		assertFalse(prompt.contains("{{"));
	}

	private record PromptOnlyProvider(String promptInstructions) implements PlannerToolProvider {
		@Override
		public String id() {
			return "prompt_only";
		}

		@Override
		public java.util.List<java.util.Map<String, Object>> openAiTools() {
			return java.util.List.of(PlannerToolCatalog.toolForProvider(
				"search_recipes",
				"Search recipe-viewer recipes.",
				PlannerToolCatalog.propertiesForProvider(),
				java.util.List.of()
			));
		}

		@Override
		public boolean handles(String toolName) {
			return false;
		}

		@Override
		public java.util.concurrent.CompletableFuture<String> execute(PlannerToolCall toolCall) {
			return java.util.concurrent.CompletableFuture.completedFuture("unused");
		}
	}
}
