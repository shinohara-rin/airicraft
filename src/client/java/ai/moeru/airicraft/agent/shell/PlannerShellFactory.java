package ai.moeru.airicraft.agent.shell;

import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.debug.AgentDebugRecorder;
import ai.moeru.airicraft.agent.dialogue.DialogueRuntime;
import ai.moeru.airicraft.agent.llm.CurrentInventoryService;
import ai.moeru.airicraft.agent.llm.CurrentViewVisionService;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleChatClient;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleLlmBackend;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleVisionBackend;
import ai.moeru.airicraft.agent.llm.PlannerCompactionService;
import ai.moeru.airicraft.agent.llm.PlannerContextAggregator;
import ai.moeru.airicraft.agent.llm.PlannerExecutor;
import ai.moeru.airicraft.agent.llm.PlannerOrchestrator;
import ai.moeru.airicraft.agent.observability.AgentObservability;
import net.minecraft.client.MinecraftClient;

import java.time.Clock;
import java.util.Objects;

public final class PlannerShellFactory {
	private PlannerShellFactory() {
	}

	public static PlannerShellComponents create(
		AgentConfig config,
		FirstPersonScreenshotService screenshotService,
		AgentObservability observability,
		Clock clock,
		AgentDebugRecorder debugRecorder
	) {
		Objects.requireNonNull(config, "config");
		Objects.requireNonNull(screenshotService, "screenshotService");
		Objects.requireNonNull(observability, "observability");
		Clock effectiveClock = Objects.requireNonNull(clock, "clock");
		PlannerShellJournal journal = new PlannerShellJournal(128, effectiveClock);
		CurrentViewVisionService visionService = new CurrentViewVisionService(
			screenshotService,
			new OpenAiCompatibleVisionBackend(config.llm(), observability),
			MinecraftClient::getInstance,
			observability
		);
		CurrentInventoryService inventoryService = new CurrentInventoryService(MinecraftClient::getInstance);
		PlannerOrchestrator orchestrator = new PlannerOrchestrator(
			new PlannerExecutor(new OpenAiCompatibleLlmBackend(config.llm(), observability), observability),
			new PlannerCompactionService(new OpenAiCompatibleChatClient(config.llm(), observability), observability),
			new PlannerContextAggregator(
				effectiveClock,
				config.llm().plannerCompactionTriggerTokens(),
				config.llm().plannerPendingSemanticEventCap(),
				config.llm().plannerVisionMode()
			),
			visionService,
			inventoryService,
			config.llm().plannerVisionMode(),
			config.llm().visionImageDetail(),
			config.llm().plannerSessionMaxConcurrentAttempts(),
			config.llm().plannerSessionCoalesceStepMillis(),
			config.llm().plannerSessionCoalesceMinMillis(),
			config.llm().plannerSessionCoalesceMaxMillis(),
			observability,
			journal,
			debugRecorder
		);
		return new PlannerShellComponents(
			visionService,
			new DialogueRuntime(orchestrator, config.llm().maxRecentConversationTurns(), effectiveClock),
			journal
		);
	}
}
