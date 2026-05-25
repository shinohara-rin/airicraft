package ai.moeru.airicraft.agent.shell;

import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.debug.AgentDebugRecorder;
import ai.moeru.airicraft.agent.dialogue.DialogueRuntime;
import ai.moeru.airicraft.agent.integration.map.MapIntegrationBridge;
import ai.moeru.airicraft.agent.integration.map.MapPlannerToolProvider;
import ai.moeru.airicraft.agent.integration.rei.ReiRecipeSearchToolProvider;
import ai.moeru.airicraft.agent.llm.CurrentInventoryService;
import ai.moeru.airicraft.agent.llm.CurrentViewVisionService;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleChatClient;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleLlmBackend;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleVisionBackend;
import ai.moeru.airicraft.agent.llm.PlannerActionToolExecutor;
import ai.moeru.airicraft.agent.llm.PlannerCompactionService;
import ai.moeru.airicraft.agent.llm.PlannerContextAggregator;
import ai.moeru.airicraft.agent.llm.PlannerExecutor;
import ai.moeru.airicraft.agent.llm.PlannerOrchestrator;
import ai.moeru.airicraft.agent.llm.PlannerToolNarrationSink;
import ai.moeru.airicraft.agent.llm.PlannerToolRegistry;
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
		return create(
			config,
			screenshotService,
			observability,
			clock,
			debugRecorder,
			PlannerActionToolExecutor.DISABLED,
			PlannerToolNarrationSink.NO_OP
		);
	}

	public static PlannerShellComponents create(
		AgentConfig config,
		FirstPersonScreenshotService screenshotService,
		AgentObservability observability,
		Clock clock,
		AgentDebugRecorder debugRecorder,
		PlannerActionToolExecutor actionToolExecutor,
		PlannerToolNarrationSink narrationSink
	) {
		Objects.requireNonNull(config, "config");
		Objects.requireNonNull(screenshotService, "screenshotService");
		Objects.requireNonNull(observability, "observability");
		PlannerActionToolExecutor effectiveActionToolExecutor = Objects.requireNonNull(actionToolExecutor, "actionToolExecutor");
		PlannerToolNarrationSink effectiveNarrationSink = Objects.requireNonNull(narrationSink, "narrationSink");
		Clock effectiveClock = Objects.requireNonNull(clock, "clock");
		PlannerShellJournal journal = new PlannerShellJournal(128, effectiveClock);
		CurrentViewVisionService visionService = new CurrentViewVisionService(
			screenshotService,
			new OpenAiCompatibleVisionBackend(config.llm(), observability),
			MinecraftClient::getInstance,
			observability
		);
		CurrentInventoryService inventoryService = new CurrentInventoryService(MinecraftClient::getInstance);
		PlannerToolRegistry toolRegistry = PlannerToolRegistry.of(
			new ReiRecipeSearchToolProvider(),
			new MapPlannerToolProvider(MapIntegrationBridge::registry)
		);
		PlannerContextAggregator contextAggregator = new PlannerContextAggregator(
			effectiveClock,
			config.llm().plannerCompactionTriggerTokens(),
			config.llm().plannerPendingSemanticEventCap(),
			config.llm().plannerVisionMode(),
			toolRegistry
		);
		PlannerOrchestrator orchestrator = new PlannerOrchestrator(
			new PlannerExecutor(new OpenAiCompatibleLlmBackend(config.llm(), observability, toolRegistry), observability),
			new PlannerCompactionService(new OpenAiCompatibleChatClient(config.llm(), observability, toolRegistry), observability),
			contextAggregator,
			visionService,
			inventoryService,
			config.llm().plannerVisionMode(),
			config.llm().visionImageDetail(),
			config.llm().plannerSessionMaxConcurrentAttempts(),
			config.llm().plannerSessionCoalesceStepMillis(),
			config.llm().plannerSessionCoalesceMinMillis(),
				config.llm().plannerSessionCoalesceMaxMillis(),
				effectiveClock,
				observability,
				journal,
				debugRecorder,
				effectiveActionToolExecutor,
				effectiveNarrationSink,
				toolRegistry
			);
		return new PlannerShellComponents(
			visionService,
			new DialogueRuntime(orchestrator, config.llm().maxRecentConversationTurns(), effectiveClock),
			journal,
			contextAggregator::setCommonsenseRules
		);
	}
}
