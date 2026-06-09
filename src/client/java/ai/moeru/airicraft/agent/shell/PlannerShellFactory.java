package ai.moeru.airicraft.agent.shell;

import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.debug.AgentDebugRecorder;
import ai.moeru.airicraft.agent.dialogue.DialogueRuntime;
import ai.moeru.airicraft.agent.integration.map.MapIntegrationBridge;
import ai.moeru.airicraft.agent.integration.map.MapPlannerToolProvider;
import ai.moeru.airicraft.agent.integration.rei.ReiRecipeSearchToolProvider;
import ai.moeru.airicraft.agent.llm.CurrentInventoryService;
import ai.moeru.airicraft.agent.llm.CurrentWorldQueryService;
import ai.moeru.airicraft.agent.llm.CurrentWorldQueryToolProvider;
import ai.moeru.airicraft.agent.llm.CurrentViewVisionService;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleChatClient;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleLlmBackend;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleVisionBackend;
import ai.moeru.airicraft.agent.llm.PlannerActionToolExecutor;
import ai.moeru.airicraft.agent.llm.PlannerCompactionService;
import ai.moeru.airicraft.agent.llm.PlannerContextAggregator;
import ai.moeru.airicraft.agent.llm.PlannerExecutor;
import ai.moeru.airicraft.agent.llm.PlannerOrchestrator;
import ai.moeru.airicraft.agent.llm.PlannerToolExecutionObserver;
import ai.moeru.airicraft.agent.llm.PlannerToolNarrationSink;
import ai.moeru.airicraft.agent.llm.PlannerToolRegistry;
import ai.moeru.airicraft.agent.llm.WorldFeatureSearchService;
import ai.moeru.airicraft.agent.llm.WorldFeatureSearchToolProvider;
import ai.moeru.airicraft.agent.observability.AgentObservability;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

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
			PlannerToolNarrationSink.NO_OP,
			PlannerToolExecutionObserver.NO_OP,
			ignored -> {
			},
			new CameraController()
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
		return create(
			config,
			screenshotService,
			observability,
			clock,
			debugRecorder,
			actionToolExecutor,
			narrationSink,
			PlannerToolExecutionObserver.NO_OP,
			ignored -> {
			},
			new CameraController()
		);
	}

	public static PlannerShellComponents create(
		AgentConfig config,
		FirstPersonScreenshotService screenshotService,
		AgentObservability observability,
		Clock clock,
		AgentDebugRecorder debugRecorder,
		PlannerActionToolExecutor actionToolExecutor,
		PlannerToolNarrationSink narrationSink,
		PlannerToolExecutionObserver toolExecutionObserver,
		Consumer<List<BlockPos>> worldReadObserver
	) {
		return create(
			config,
			screenshotService,
			observability,
			clock,
			debugRecorder,
			actionToolExecutor,
			narrationSink,
			toolExecutionObserver,
			worldReadObserver,
			new CameraController()
		);
	}

	public static PlannerShellComponents create(
		AgentConfig config,
		FirstPersonScreenshotService screenshotService,
		AgentObservability observability,
		Clock clock,
		AgentDebugRecorder debugRecorder,
		PlannerActionToolExecutor actionToolExecutor,
		PlannerToolNarrationSink narrationSink,
		PlannerToolExecutionObserver toolExecutionObserver,
		Consumer<List<BlockPos>> worldReadObserver,
		CameraController cameraController
	) {
		Objects.requireNonNull(config, "config");
		Objects.requireNonNull(screenshotService, "screenshotService");
		Objects.requireNonNull(observability, "observability");
		CameraController effectiveCameraController = Objects.requireNonNull(cameraController, "cameraController");
		PlannerActionToolExecutor effectiveActionToolExecutor = Objects.requireNonNull(actionToolExecutor, "actionToolExecutor");
		PlannerToolNarrationSink effectiveNarrationSink = Objects.requireNonNull(narrationSink, "narrationSink");
		PlannerToolExecutionObserver effectiveToolExecutionObserver = Objects.requireNonNull(toolExecutionObserver, "toolExecutionObserver");
		Consumer<List<BlockPos>> effectiveWorldReadObserver = Objects.requireNonNull(worldReadObserver, "worldReadObserver");
		Clock effectiveClock = Objects.requireNonNull(clock, "clock");
		PlannerShellJournal journal = new PlannerShellJournal(128, effectiveClock);
		CurrentViewVisionService visionService = new CurrentViewVisionService(
			screenshotService,
			new OpenAiCompatibleVisionBackend(config.llm(), observability),
			MinecraftClient::getInstance,
			observability,
			effectiveCameraController
		);
		CurrentInventoryService inventoryService = new CurrentInventoryService(MinecraftClient::getInstance);
		CurrentWorldQueryService worldQueryService = new CurrentWorldQueryService(MinecraftClient::getInstance);
		WorldFeatureSearchService worldFeatureSearchService = new WorldFeatureSearchService(MinecraftClient::getInstance);
		PlannerToolRegistry toolRegistry = PlannerToolRegistry.of(
			new CurrentWorldQueryToolProvider(worldQueryService, result -> effectiveWorldReadObserver.accept(result.observedPositions())),
			new WorldFeatureSearchToolProvider(worldFeatureSearchService, result -> effectiveWorldReadObserver.accept(result.observedPositions())),
			new ReiRecipeSearchToolProvider(),
			new MapPlannerToolProvider(MapIntegrationBridge::registry)
		);
		PlannerOrchestrator orchestrator = new PlannerOrchestrator(
			new PlannerExecutor(new OpenAiCompatibleLlmBackend(config.llm(), observability, toolRegistry), observability),
			new PlannerCompactionService(new OpenAiCompatibleChatClient(config.llm(), observability, toolRegistry), observability),
			new PlannerContextAggregator(
				effectiveClock,
				config.llm().plannerCompactionTriggerTokens(),
				config.llm().plannerPendingSemanticEventCap(),
				config.llm().plannerVisionMode(),
				toolRegistry
			),
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
				toolRegistry,
				effectiveToolExecutionObserver
			);
		return new PlannerShellComponents(
			visionService,
			new DialogueRuntime(orchestrator, config.llm().maxRecentConversationTurns(), effectiveClock),
			journal
		);
	}
}
