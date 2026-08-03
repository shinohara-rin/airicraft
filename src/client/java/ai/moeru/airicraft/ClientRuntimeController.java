package ai.moeru.airicraft;

import ai.moeru.airicraft.agent.EmbodiedAgentRuntime;
import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.AgentConfigLoader;
import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.baritone.LiveBaritoneFacade;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.idle.IdleIdeasConfig;
import ai.moeru.airicraft.agent.idle.IdleIdeasLoader;
import ai.moeru.airicraft.agent.tasks.BaritoneTaskExecutor;
import ai.moeru.airicraft.agent.tasks.BlockBreakTaskExecutor;
import ai.moeru.airicraft.agent.tasks.BlockInteractionTaskExecutor;
import ai.moeru.airicraft.agent.tasks.UnderwaterHarvestTaskExecutor;
import ai.moeru.airicraft.agent.tasks.CraftingTaskExecutor;
import ai.moeru.airicraft.agent.tasks.DispatchingWorldTaskExecutor;
import ai.moeru.airicraft.agent.tasks.DropItemsTaskExecutor;
import ai.moeru.airicraft.agent.tasks.EntityInteractionTaskExecutor;
import ai.moeru.airicraft.agent.tasks.HybridMiningTaskExecutor;
import ai.moeru.airicraft.agent.tasks.LiveHybridMiningEnvironment;
import ai.moeru.airicraft.agent.tasks.ReturnToSurfaceTaskExecutor;
import ai.moeru.airicraft.agent.tasks.SmeltingProcessManager;
import ai.moeru.airicraft.agent.tasks.SmeltingTaskExecutor;
import ai.moeru.airicraft.agent.tasks.WorldTaskExecutor;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.debug.ClientTickDebugRuntime;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.damage.DamageSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ClientRuntimeController {
	private volatile AiricraftConfig config;
	private final HighlightManager highlightManager = new HighlightManager();
	private final FirstPersonScreenshotService screenshotService = new FirstPersonScreenshotService();
	private final ClientTickDebugRuntime clientTickDebugRuntime = new ClientTickDebugRuntime(screenshotService);
	private final BaritoneFacade baritoneFacade = new LiveBaritoneFacade();
	private final CameraController cameraController;
	private volatile EmbodiedAgentRuntime agentRuntime;
	private final ModBridgeServer bridgeServer;
	private final PlannerDebugOverlay plannerDebugOverlay = new PlannerDebugOverlay();
	private final ClientTickPauseIndicator clientTickPauseIndicator = new ClientTickPauseIndicator();
	private final ClientTickTraceIndicator clientTickTraceIndicator = new ClientTickTraceIndicator();

	public ClientRuntimeController() {
		this.config = AiricraftConfigLoader.load();
		this.cameraController = new CameraController(config.cameraLerpDefaultTicks());
		this.agentRuntime = createRuntime(config, AgentConfigLoader.load());
		this.agentRuntime.updateIdleIdeasConfig(IdleIdeasLoader.load());
		this.bridgeServer = new ModBridgeServer(
			this::highlightManager,
			this::agentRuntime,
			this::screenshotService,
			this::clientTickDebugRuntime,
			this::reload,
			cameraController
		);
	}

	public AiricraftConfig config() {
		return config;
	}

	public HighlightManager highlightManager() {
		return highlightManager;
	}

	public EmbodiedAgentRuntime agentRuntime() {
		return currentAgentRuntime();
	}

	public PlannerDebugOverlayMode plannerDebugOverlayMode() {
		return plannerDebugOverlay.mode();
	}

	public boolean plannerDebugOverlayEnabled() {
		return plannerDebugOverlay.enabled();
	}

	public void setPlannerDebugOverlayMode(PlannerDebugOverlayMode mode) {
		plannerDebugOverlay.setMode(mode);
	}

	public FirstPersonScreenshotService screenshotService() {
		return screenshotService;
	}

	public ClientTickDebugRuntime clientTickDebugRuntime() {
		return clientTickDebugRuntime;
	}

	public void onClientStarted(MinecraftClient client) {
		currentAgentRuntime().onClientStarted(client);
		bridgeServer.start();
	}

	public void onWorldLeave() {
		clientTickDebugRuntime.reset("world_left", "The world closed during a client tick debug capture");
		screenshotService.failActiveCapture("capture_failed", "Screenshot capture was interrupted");
		currentAgentRuntime().onWorldLeave();
		cameraController.clear();
		highlightManager.clear();
	}

	public void onClientTick(MinecraftClient client) {
		currentAgentRuntime().onClientTick(client);
		cameraController.tick(client);
		highlightManager.tick();
		clientTickDebugRuntime.onClientTickCompleted(client, currentAgentRuntime());
	}

	public boolean startClientTick() {
		if (!clientTickDebugRuntime.allowVanillaTick(true)) {
			return false;
		}
		clientTickDebugRuntime.onClientTickStarted();
		return true;
	}

	public boolean allowRenderTickCounter(boolean vanillaAllowsTick) {
		return clientTickDebugRuntime.allowVanillaTick(vanillaAllowsTick);
	}

	public void onChatReceived(String senderName, String plainTextMessage) {
		currentAgentRuntime().onChatReceived(senderName, plainTextMessage);
	}

	public void onSystemChatReceived(String plainTextMessage) {
		currentAgentRuntime().onSystemChatReceived(plainTextMessage);
	}

	public void onPlayerCraftedItem(String itemId, int count) {
		currentAgentRuntime().onPlayerCraftedItem(itemId, count);
	}

	public void onPlayerPickedUpItem(String itemId, int count) {
		currentAgentRuntime().onPlayerPickedUpItem(itemId, count);
	}

	public void onPlayerItemPickupObserved(
		int entityId,
		UUID entityUuid,
		String itemId,
		int pickupDelta,
		int agentAttributedQuantity,
		UUID collectorIdentity,
		UUID observationId
	) {
		currentAgentRuntime().onPlayerItemPickupObserved(
			entityId,
			entityUuid,
			itemId,
			pickupDelta,
			agentAttributedQuantity,
			collectorIdentity,
			observationId
		);
	}

	public void onPlayerMinedBlock(String blockId, int x, int y, int z) {
		currentAgentRuntime().onPlayerMinedBlock(blockId, x, y, z);
	}

	public void onPlayerDamageObserved(DamageSource damageSource) {
		currentAgentRuntime().onPlayerDamageObserved(damageSource);
	}

	public void onPlayerHealthUpdated(boolean healthInitialized, float healthBefore, float healthAfter) {
		currentAgentRuntime().onPlayerHealthUpdated(healthInitialized, healthBefore, healthAfter);
	}

	public void onPlayerRespawned() {
		currentAgentRuntime().onPlayerRespawned();
	}

	public void onPlayerJoinedGame(UUID playerUuid, String playerName) {
		currentAgentRuntime().onPlayerJoinedGame(playerUuid, playerName);
	}

	public void onPlayerLeftGame(UUID playerUuid) {
		currentAgentRuntime().onPlayerLeftGame(playerUuid);
	}

	public void onWorldRender(WorldRenderContext context) {
		highlightManager.render(context);
	}

	public void onHudRender(DrawContext drawContext, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.currentScreen != null) {
			return;
		}
		plannerDebugOverlay.render(client, drawContext, currentAgentRuntime(), System.currentTimeMillis());
		renderClientTickIndicators(client, drawContext);
	}

	public void onScreenRender(DrawContext drawContext) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.currentScreen == null) {
			return;
		}
		plannerDebugOverlay.render(client, drawContext, currentAgentRuntime(), System.currentTimeMillis());
		renderClientTickIndicators(client, drawContext);
	}

	private void renderClientTickIndicators(MinecraftClient client, DrawContext drawContext) {
		clientTickPauseIndicator.render(client, drawContext, clientTickDebugRuntime.status());
		clientTickTraceIndicator.render(client, drawContext, clientTickDebugRuntime.traceStatus());
	}

	public boolean onScreenMouseScroll(double mouseX, double mouseY, double verticalAmount) {
		return plannerDebugOverlay.onMouseScroll(mouseX, mouseY, verticalAmount);
	}

	public void onFirstPersonFrameRendered() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null) {
			clientTickDebugRuntime.beforeFirstPersonFrame(client, currentAgentRuntime());
			screenshotService.onWorldRendered(client);
		}
	}

	public synchronized ReloadResult reload() {
		AiricraftConfig nextConfig;
		AgentConfig nextAgentConfig;
		IdleIdeasConfig nextIdleIdeasConfig;
		try {
			nextConfig = AiricraftConfigLoader.loadStrict();
			nextAgentConfig = AgentConfigLoader.loadStrict();
			nextIdleIdeasConfig = IdleIdeasLoader.loadStrict();
		}
		catch (ConfigLoadException exception) {
			throw new BridgeUnavailableException("invalid_config", exception.getMessage());
		}

		clientTickDebugRuntime.reset("runtime_reloaded", "Airicraft reloaded during a client tick debug capture");
		screenshotService.failActiveCapture("capture_failed", "Screenshot capture was interrupted");
		cameraController.clear();
		cameraController.updateDefaultLerpTicks(nextConfig.cameraLerpDefaultTicks());
		EmbodiedAgentRuntime previousRuntime = currentAgentRuntime();
		EmbodiedAgentRuntime nextRuntime = createRuntime(nextConfig, nextAgentConfig);
		nextRuntime.updateIdleIdeasConfig(nextIdleIdeasConfig);
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null) {
			nextRuntime.onClientStarted(client);
		}

		config = nextConfig;
		agentRuntime = nextRuntime;
		previousRuntime.shutdown();
		return new ReloadResult(nextConfig, nextAgentConfig, nextIdleIdeasConfig, nextRuntime.sessionSnapshot());
	}

	public void shutdown() {
		clientTickDebugRuntime.reset("client_stopping", "The client stopped during a client tick debug capture");
		screenshotService.failActiveCapture("capture_failed", "Screenshot capture was interrupted");
		plannerDebugOverlay.setMode(PlannerDebugOverlayMode.OFF);
		currentAgentRuntime().shutdown();
		cameraController.clear();
		highlightManager.clear();
		bridgeServer.stop();
	}

	private EmbodiedAgentRuntime currentAgentRuntime() {
		return agentRuntime;
	}

	private EmbodiedAgentRuntime createRuntime(AiricraftConfig airicraftConfig, AgentConfig agentConfig) {
		SmeltingProcessManager smeltingProcessManager = new SmeltingProcessManager();
		BaritoneTaskExecutor baritoneTaskExecutor = new BaritoneTaskExecutor(baritoneFacade);
		UnderwaterHarvestTaskExecutor underwaterHarvestTaskExecutor = new UnderwaterHarvestTaskExecutor(
			baritoneFacade,
			cameraController
		);
		LiveHybridMiningEnvironment miningEnvironment = new LiveHybridMiningEnvironment(baritoneFacade);
		HybridMiningTaskExecutor miningCoordinator = new HybridMiningTaskExecutor(
			baritoneTaskExecutor,
			underwaterHarvestTaskExecutor,
			miningEnvironment,
			miningEnvironment,
			miningEnvironment
		);
		WorldTaskExecutor worldTaskExecutor = new DispatchingWorldTaskExecutor(
			miningCoordinator,
			new CraftingTaskExecutor(baritoneFacade, cameraController),
			new DropItemsTaskExecutor(baritoneFacade),
			new EntityInteractionTaskExecutor(baritoneFacade, cameraController),
			new SmeltingTaskExecutor(smeltingProcessManager, baritoneFacade),
			new ReturnToSurfaceTaskExecutor(baritoneFacade),
			new BlockInteractionTaskExecutor(airicraftConfig.blockInteractionDelayTicks(), cameraController, baritoneFacade),
			new BlockBreakTaskExecutor(),
			baritoneFacade
		);
		return new EmbodiedAgentRuntime(
			airicraftConfig,
			agentConfig,
			screenshotService,
			worldTaskExecutor,
			smeltingProcessManager,
			cameraController,
			baritoneFacade
		);
	}

	public record ReloadResult(
		AiricraftConfig airicraftConfig,
		AgentConfig agentConfig,
		IdleIdeasConfig idleIdeasConfig,
		SessionSnapshot sessionSnapshot
	) {
		public Map<String, Object> toPayload() {
			LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
			payload.put("available", true);
			payload.put("reloaded", true);
			payload.put("agentStateReset", true);
			payload.put("sessionMode", sessionSnapshot.mode().name());
			payload.put("worldLoaded", sessionSnapshot.worldLoaded());
			payload.put("plannerVisionMode", agentConfig.llm().plannerVisionMode().wireValue());
			payload.put("llmConfigured", agentConfig.llm().isConfigured());
			payload.put("visionConfigured", agentConfig.llm().visionConfigured());
			payload.put("observabilityEnabled", agentConfig.observability().enabled());
			payload.put("config", configPayload());
			payload.put("llm", llmPayload());
			payload.put("observability", observabilityPayload());
			payload.put("idleIdeas", idleIdeasPayload());
			return payload;
		}

		public String feedbackText() {
			return "Airicraft reloaded: plannerVisionMode=%s, sessionMode=%s, worldLoaded=%s"
				.formatted(agentConfig.llm().plannerVisionMode().wireValue(), sessionSnapshot.mode().name(), sessionSnapshot.worldLoaded());
		}

		private Map<String, Object> configPayload() {
			LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
			payload.put("socialChatMaxDistanceBlocks", airicraftConfig.socialChatMaxDistanceBlocks());
			payload.put("readSystemChatMessages", airicraftConfig.readSystemChatMessages());
			payload.put("enableProactiveSocialMode", airicraftConfig.enableProactiveSocialMode());
			payload.put("suppressAutoPauseOnFocusLost", airicraftConfig.suppressAutoPauseOnFocusLost());
			payload.put("blockInteractionDelayTicks", airicraftConfig.blockInteractionDelayTicks());
			payload.put("cameraLerpDefaultTicks", airicraftConfig.cameraLerpDefaultTicks());
			return payload;
		}

		private Map<String, Object> llmPayload() {
			LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
			payload.put("plannerBackend", agentConfig.llm().plannerBackend().wireValue());
			payload.put("providerBaseUrl", agentConfig.llm().providerBaseUrl());
			payload.put("model", agentConfig.llm().model());
			payload.put("requestTimeoutMillis", agentConfig.llm().requestTimeoutMillis());
			payload.put("visionProviderBaseUrl", agentConfig.llm().visionProviderBaseUrl());
			payload.put("visionModel", agentConfig.llm().visionModel());
			payload.put("visionRequestTimeoutMillis", agentConfig.llm().visionRequestTimeoutMillis());
			payload.put("maxRecentConversationTurns", agentConfig.llm().maxRecentConversationTurns());
			payload.put("plannerCompactionTriggerTokens", agentConfig.llm().plannerCompactionTriggerTokens());
			payload.put("plannerPendingSemanticEventCap", agentConfig.llm().plannerPendingSemanticEventCap());
			payload.put("plannerSessionMaxConcurrentAttempts", agentConfig.llm().plannerSessionMaxConcurrentAttempts());
			payload.put("plannerSessionCoalesceStepMillis", agentConfig.llm().plannerSessionCoalesceStepMillis());
			payload.put("plannerSessionCoalesceMinMillis", agentConfig.llm().plannerSessionCoalesceMinMillis());
			payload.put("plannerSessionCoalesceMaxMillis", agentConfig.llm().plannerSessionCoalesceMaxMillis());
			payload.put("visionImageDetail", agentConfig.llm().visionImageDetail());
			payload.put("plannerNativeVisionEnabled", agentConfig.llm().plannerNativeVisionEnabled());
			payload.put("codexExecutable", agentConfig.llm().codexAppServer().executable());
			payload.put("codexModel", agentConfig.llm().codexAppServer().model());
			payload.put("codexReasoningEffort", agentConfig.llm().codexAppServer().reasoningEffort());
			payload.put("codexStartupTimeoutMillis", agentConfig.llm().codexAppServer().startupTimeoutMillis());
			payload.put("codexTurnTimeoutMillis", agentConfig.llm().codexAppServer().turnTimeoutMillis());
			return payload;
		}

		private Map<String, Object> observabilityPayload() {
			LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
			payload.put("enabled", agentConfig.observability().enabled());
			payload.put("exporter", agentConfig.observability().exporter());
			payload.put("otlpEndpoint", agentConfig.observability().otlpEndpoint());
			payload.put("otlpHeaders", agentConfig.observability().otlpHeaders());
			payload.put("resourceAttributes", agentConfig.observability().resourceAttributes());
			payload.put("vendorProfile", agentConfig.observability().vendorProfile());
			payload.put("debugLogExports", agentConfig.observability().debugLogExports());
			payload.put("captureInputs", agentConfig.observability().captureInputs());
			payload.put("captureOutputs", agentConfig.observability().captureOutputs());
			payload.put("captureImages", agentConfig.observability().captureImages());
			return payload;
		}

		private Map<String, Object> idleIdeasPayload() {
			LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
			payload.put("enabled", idleIdeasConfig.enabled() && agentConfig.idle().automaticEnabled());
			payload.put("initialDelaySeconds", agentConfig.idle().initialDelaySeconds());
			payload.put("cooldownSeconds", agentConfig.idle().cooldownSeconds());
			payload.put("ideaCount", idleIdeasConfig.ideas().size());
			return payload;
		}
	}
}
