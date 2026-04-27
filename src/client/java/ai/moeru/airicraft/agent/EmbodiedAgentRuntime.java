package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.AiricraftConfig;
import ai.moeru.airicraft.AiricraftConfigLoader;
import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.SingleplayerWorldService;
import ai.moeru.airicraft.agent.behavior.BehaviorTreeRuntime;
import ai.moeru.airicraft.agent.behavior.BehaviorTreeSnapshot;
import ai.moeru.airicraft.agent.chat.ChatService;
import ai.moeru.airicraft.agent.debug.AgentDebugRecorder;
import ai.moeru.airicraft.agent.debug.AgentDebugTimelineQueryResult;
import ai.moeru.airicraft.agent.debug.ChatDebugSnapshot;
import ai.moeru.airicraft.agent.debug.CollectResourceTaskDebugSnapshot;
import ai.moeru.airicraft.agent.debug.ConversationSourcesDebugSnapshot;
import ai.moeru.airicraft.agent.debug.DialogueDebugSnapshot;
import ai.moeru.airicraft.agent.debug.EventPipelineDebugSnapshot;
import ai.moeru.airicraft.agent.debug.PlannerAttemptDebugSnapshot;
import ai.moeru.airicraft.agent.dialogue.DialogueIntent;
import ai.moeru.airicraft.agent.dialogue.DialogueIntentType;
import ai.moeru.airicraft.agent.dialogue.DialogueResponse;
import ai.moeru.airicraft.agent.dialogue.DialogueSpeakerLabels;
import ai.moeru.airicraft.agent.dialogue.DialogueSnapshot;
import ai.moeru.airicraft.agent.dialogue.DialogueRuntime;
import ai.moeru.airicraft.agent.events.AgentEventPipeline;
import ai.moeru.airicraft.agent.events.EventPolicyChanges;
import ai.moeru.airicraft.agent.events.EventPolicyDecision;
import ai.moeru.airicraft.agent.events.EventPolicyEffect;
import ai.moeru.airicraft.agent.events.EventPolicyIntervention;
import ai.moeru.airicraft.agent.events.EventPolicyMatch;
import ai.moeru.airicraft.agent.events.EventPolicyRule;
import ai.moeru.airicraft.agent.events.EventPolicyRuleUpsert;
import ai.moeru.airicraft.agent.events.EventPolicyState;
import ai.moeru.airicraft.agent.events.EventRoutingProfile;
import ai.moeru.airicraft.agent.observability.AgentObservability;
import ai.moeru.airicraft.agent.events.SemanticEventBuffer;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.events.SemanticEventQueryResult;
import ai.moeru.airicraft.agent.follow.FollowCapability;
import ai.moeru.airicraft.agent.follow.FollowState;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.job.ActiveJob;
import ai.moeru.airicraft.agent.job.ActiveJobProposal;
import ai.moeru.airicraft.agent.job.ActiveJobType;
import ai.moeru.airicraft.agent.job.ActiveJobRuntime;
import ai.moeru.airicraft.agent.llm.CompactionExecutionResult;
import ai.moeru.airicraft.agent.llm.CurrentViewVisionService;
import ai.moeru.airicraft.agent.llm.LlmBackendException;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleChatClient;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleLlmBackend;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleVisionBackend;
import ai.moeru.airicraft.agent.llm.PlannerConversationDebugSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerContextAggregator;
import ai.moeru.airicraft.agent.llm.PlannerExecutor;
import ai.moeru.airicraft.agent.llm.PlannerIntent;
import ai.moeru.airicraft.agent.llm.PlannerCompactionService;
import ai.moeru.airicraft.agent.llm.PlannerOrchestratorDebugSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerOrchestrator;
import ai.moeru.airicraft.agent.llm.PlannerResponse;
import ai.moeru.airicraft.agent.llm.PlannerToolCall;
import ai.moeru.airicraft.agent.llm.PlannerToolCatalog;
import ai.moeru.airicraft.agent.llm.PlannerTriggerType;
import ai.moeru.airicraft.agent.llm.VisionDescription;
import ai.moeru.airicraft.agent.session.AutoLanOpenState;
import ai.moeru.airicraft.agent.session.LanHostingService;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.session.SessionRuntime;
import ai.moeru.airicraft.agent.shell.PlannerShellComponents;
import ai.moeru.airicraft.agent.shell.PlannerShellEvent;
import ai.moeru.airicraft.agent.shell.PlannerShellFactory;
import ai.moeru.airicraft.agent.shell.PlannerShellJournal;
import ai.moeru.airicraft.agent.social.ChatIngestService;
import ai.moeru.airicraft.agent.social.NearbyPlayerSnapshot;
import ai.moeru.airicraft.agent.social.NearbyPlayerTracker;
import ai.moeru.airicraft.agent.social.PrimaryInteractionPlayer;
import ai.moeru.airicraft.agent.social.PrimaryInteractionResolver;
import ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskExecutionState;
import ai.moeru.airicraft.agent.tasks.WorldTaskRequest;
import ai.moeru.airicraft.agent.tasks.CollectResourceTaskHandler;
import ai.moeru.airicraft.agent.tasks.InventoryItemCounter;
import ai.moeru.airicraft.agent.tasks.InventoryResourceCounter;
import ai.moeru.airicraft.agent.tasks.MissionExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.NearbyEntityService;
import ai.moeru.airicraft.agent.tasks.TaskResourceKind;
import ai.moeru.airicraft.agent.tasks.TaskSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskState;
import ai.moeru.airicraft.agent.tasks.TaskSpec;
import ai.moeru.airicraft.agent.tasks.TaskLedger;
import ai.moeru.airicraft.agent.tasks.TaskTerminalEvent;
import ai.moeru.airicraft.agent.tasks.TaskType;
import ai.moeru.airicraft.agent.tasks.WorldEvidence;
import ai.moeru.airicraft.agent.tasks.WorldTaskExecutor;
import ai.moeru.airicraft.agent.tasks.CraftRecipeStepArgs;
import ai.moeru.airicraft.agent.tasks.DropItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.EntityAttackMode;
import ai.moeru.airicraft.agent.tasks.EntityInteractionStepArgs;
import ai.moeru.airicraft.agent.tasks.EntitySelector;
import ai.moeru.airicraft.agent.verification.VerificationReport;
import ai.moeru.airicraft.agent.verification.VerificationRunner;
import ai.moeru.airicraft.agent.verification.VerificationPlayerProbe;
import ai.moeru.airicraft.agent.verification.scenarios.DialogueVerification;
import ai.moeru.airicraft.agent.verification.scenarios.DialogueChatSanitizationVerification;
import ai.moeru.airicraft.agent.verification.scenarios.DialogueClearGoalVerification;
import ai.moeru.airicraft.agent.verification.scenarios.DialogueProactiveSocialModeVerification;
import ai.moeru.airicraft.agent.verification.scenarios.DamageFallContextVerification;
import ai.moeru.airicraft.agent.verification.scenarios.EventPolicyIgnoreSystemVerification;
import ai.moeru.airicraft.agent.verification.scenarios.FollowVerification;
import ai.moeru.airicraft.agent.verification.scenarios.FollowSingleplayerLocalPauseVerification;
import ai.moeru.airicraft.agent.verification.scenarios.FollowReacquireTargetVerification;
import ai.moeru.airicraft.agent.verification.scenarios.LlmDegradationVerification;
import ai.moeru.airicraft.agent.verification.scenarios.LlmDegradationGoalPreservedVerification;
import ai.moeru.airicraft.agent.verification.scenarios.ManualInputIdlePassthroughVerification;
import ai.moeru.airicraft.agent.verification.scenarios.MineBlocksVerification;
import ai.moeru.airicraft.agent.verification.scenarios.NavigateVerification;
import ai.moeru.airicraft.agent.verification.scenarios.PlannerObservabilityVerification;
import ai.moeru.airicraft.agent.verification.scenarios.SessionLanVerification;
import ai.moeru.airicraft.agent.verification.scenarios.SessionVerification;
import ai.moeru.airicraft.agent.verification.scenarios.SocialPrimaryInteractionTtlVerification;
import ai.moeru.airicraft.agent.verification.scenarios.SocialChatIngestVerification;
import ai.moeru.airicraft.agent.session.SessionMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.registry.Registries;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.time.Clock;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

public final class EmbodiedAgentRuntime {
	static final long CHAT_ECHO_SUPPRESSION_TICKS = 40L;
	private static final Map<String, EventRoutingProfile> EVENT_ROUTING_PROFILES = createEventRoutingProfiles();

	private final AiricraftConfig airicraftConfig;
	private final AgentConfig config;
	private final VerificationRunner verificationRunner = new VerificationRunner();
	private final SingleplayerWorldService singleplayerWorldService = new SingleplayerWorldService();
	private final SessionRuntime sessionRuntime = new SessionRuntime();
	private final LanHostingService lanHostingService = new LanHostingService();
	private final AutoLanOpenState autoLanOpenState = new AutoLanOpenState();
	private final AgentObservability observability;
	private final AgentDebugRecorder debugRecorder = new AgentDebugRecorder();
	private final SemanticEventBuffer eventBuffer = new SemanticEventBuffer(512);
	private final SemanticEventBuffer plannerEventBuffer = new SemanticEventBuffer(512);
	private final EventPolicyState eventPolicyState = new EventPolicyState();
	private final AgentEventPipeline eventPipeline = new AgentEventPipeline(eventBuffer, plannerEventBuffer, eventPolicyState, EVENT_ROUTING_PROFILES, debugRecorder);
	private final ChatIngestService chatIngestService = new ChatIngestService();
	private final LocalDamageTracker localDamageTracker = new LocalDamageTracker();
	private final NearbyPlayerTracker nearbyPlayerTracker;
	private final PrimaryInteractionResolver primaryInteractionResolver = new PrimaryInteractionResolver(200L);
	private final ActiveJobRuntime activeJobRuntime = new ActiveJobRuntime();
	private final FollowCapability followCapability = new FollowCapability();
	private final BehaviorTreeRuntime behaviorTreeRuntime = new BehaviorTreeRuntime();
	private final ChatService chatService = new ChatService();
	private final CurrentViewVisionService visionService;
	private final DialogueRuntime dialogueRuntime;
	private final PlannerShellJournal plannerJournal;
	private final WorldTaskExecutor worldTaskExecutor;
	private final InventoryResourceCounter inventoryResourceCounter = new InventoryResourceCounter();
	private final InventoryItemCounter inventoryItemCounter = new InventoryItemCounter();

	private boolean initialized;
	private long tickCount;
	private long worldLoadTick = -1L;
	private Boolean proactiveSocialModeOverride;
	private SessionSnapshot sessionSnapshot = SessionSnapshot.initial();
	private SessionSnapshot sessionSnapshotOverrideForTests;
	private FollowState followState = FollowState.idle();
	private TaskSnapshot taskSnapshot = TaskSnapshot.idle();
	private TaskExecutionSnapshot taskExecutionSnapshot = TaskExecutionSnapshot.idle();
	private MissionExecutionSnapshot missionExecutionSnapshot = MissionExecutionSnapshot.idle();
	private long lastSystemChatTick = -1L;
	private String lastSystemChatText;
	private Float lastKnownPlayerHealth;
	private final Map<UUID, String> seenPlayerNames = new LinkedHashMap<>();

	public EmbodiedAgentRuntime(
		AiricraftConfig airicraftConfig,
		AgentConfig config,
		FirstPersonScreenshotService screenshotService,
		WorldTaskExecutor worldTaskExecutor,
		AgentObservability observability
	) {
		this.airicraftConfig = Objects.requireNonNull(airicraftConfig, "airicraftConfig");
		this.config = Objects.requireNonNull(config, "config");
		this.worldTaskExecutor = Objects.requireNonNull(worldTaskExecutor, "worldTaskExecutor");
		this.observability = Objects.requireNonNull(observability, "observability");
		this.nearbyPlayerTracker = new NearbyPlayerTracker(resolveNearbyPlayerTrackingRadius(airicraftConfig));
		Clock clock = Clock.systemDefaultZone();
		PlannerShellComponents plannerShell = PlannerShellFactory.create(
			config,
				Objects.requireNonNull(screenshotService, "screenshotService"),
				this.observability,
				clock,
				debugRecorder,
				this::executePlannerToolCall,
				this::emitPlannerToolNarration
			);
		this.visionService = plannerShell.visionService();
		this.dialogueRuntime = plannerShell.dialogueRuntime();
		this.plannerJournal = plannerShell.plannerJournal();
		this.debugRecorder.recordDialogueState(this.dialogueRuntime.snapshot());
		registerDefaultScenarios();
	}

	public EmbodiedAgentRuntime(
		AiricraftConfig airicraftConfig,
		AgentConfig config,
		FirstPersonScreenshotService screenshotService,
		WorldTaskExecutor worldTaskExecutor
	) {
		this(airicraftConfig, config, screenshotService, worldTaskExecutor,
			AgentObservability.create(config == null ? null : config.observability()));
	}

	public EmbodiedAgentRuntime(AiricraftConfig airicraftConfig, AgentConfig config, FirstPersonScreenshotService screenshotService) {
		this(airicraftConfig, config, screenshotService, NoopWorldTaskExecutor.INSTANCE);
	}

	public EmbodiedAgentRuntime(
		AiricraftConfig airicraftConfig,
		AgentConfig config,
		FirstPersonScreenshotService screenshotService,
		AgentObservability observability
	) {
		this(airicraftConfig, config, screenshotService, NoopWorldTaskExecutor.INSTANCE, observability);
	}

	public static EmbodiedAgentRuntime createDefault(
		AiricraftConfig airicraftConfig,
		FirstPersonScreenshotService screenshotService,
		WorldTaskExecutor worldTaskExecutor
	) {
		return new EmbodiedAgentRuntime(airicraftConfig, AgentConfigLoader.load(), screenshotService, worldTaskExecutor);
	}

	public static EmbodiedAgentRuntime createDefault(AiricraftConfig airicraftConfig, FirstPersonScreenshotService screenshotService) {
		return createDefault(airicraftConfig, screenshotService, NoopWorldTaskExecutor.INSTANCE);
	}

	public static EmbodiedAgentRuntime createDefault(FirstPersonScreenshotService screenshotService) {
		return createDefault(AiricraftConfigLoader.load(), screenshotService);
	}

	static EmbodiedAgentRuntime createForTests(WorldTaskExecutor worldTaskExecutor) {
		return new EmbodiedAgentRuntime(
			AiricraftConfig.defaults(),
			AgentConfig.defaults(),
			new FirstPersonScreenshotService(),
			worldTaskExecutor
		);
	}

	public AgentConfig config() {
		return config;
	}

	public Map<String, Object> observabilityDebugSnapshot() {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("implementation", observability.getClass().getName());
		snapshot.put("enabled", config.observability().enabled());
		snapshot.put("exporter", config.observability().exporter());
		snapshot.put("vendorProfile", config.observability().vendorProfile());
		snapshot.put("otlpEndpoint", config.observability().otlpEndpoint());
		snapshot.put("captureInputs", config.observability().captureInputs());
		snapshot.put("captureOutputs", config.observability().captureOutputs());
		snapshot.put("captureImages", config.observability().captureImages());
		snapshot.put("debugLogExports", config.observability().debugLogExports());
		return snapshot;
	}

	public VerificationRunner verificationRunner() {
		return verificationRunner;
	}

	public List<String> verificationScenarioNames() {
		return verificationRunner.scenarioNames();
	}

	public void onClientStarted(MinecraftClient client) {
		initialized = true;
		sessionRuntime.onClientStarted(client, tickCount, eventBuffer);
		sessionSnapshot = sessionRuntime.snapshot();
	}

	public void onWorldLeave() {
		sessionRuntime.onWorldLeave(tickCount, eventBuffer);
		sessionSnapshot = sessionRuntime.snapshot();
		autoLanOpenState.clear();
		localDamageTracker.clear();
		sessionSnapshotOverrideForTests = null;
		nearbyPlayerTracker.clear(tickCount, eventBuffer);
		primaryInteractionResolver.clear();
		eventPolicyState.clear();
		eventPipeline.clearPlannerFeed();
		dialogueRuntime.clear();
		worldTaskExecutor.onWorldLeave();
		activeJobRuntime.clear();
		followCapability.clear();
		followState = FollowState.idle();
		taskSnapshot = TaskSnapshot.idle();
		taskExecutionSnapshot = TaskExecutionSnapshot.idle();
		missionExecutionSnapshot = MissionExecutionSnapshot.idle();
		behaviorTreeRuntime.stop(MinecraftClient.getInstance());
		chatService.clear();
		proactiveSocialModeOverride = null;
		lastSystemChatTick = -1L;
		lastSystemChatText = null;
		lastKnownPlayerHealth = null;
		seenPlayerNames.clear();
	}

	public void onClientTick(MinecraftClient client) {
		tickCount++;
		localDamageTracker.pruneStale(tickCount);
		FollowState previousFollowState = followState;
		BehaviorTreeSnapshot previousTreeSnapshot = behaviorTreeRuntime.snapshot();
		boolean wasWorldLoaded = sessionSnapshot.worldLoaded();
		sessionSnapshot = sessionSnapshotOverrideForTests != null
			? sessionSnapshotOverrideForTests.withTickCount(tickCount)
			: sessionRuntime.poll(client, tickCount, eventBuffer);
		if (!wasWorldLoaded && sessionSnapshot.worldLoaded()) {
			worldLoadTick = tickCount;
			localDamageTracker.onLifecycleReset(tickCount);
		}
		openLanIfSingleplayerLocal(client);

		nearbyPlayerTracker.poll(client, tickCount, eventBuffer);
		primaryInteractionResolver.current().ifPresent(current ->
			primaryInteractionResolver.clearIfNotNearby(current.uuid(), nearbyPlayerTracker.isNearby(current.uuid()))
		);
		primaryInteractionResolver.expireInactive(tickCount);
		drainEventPipeline();

		WorldEvidence worldEvidence = currentWorldEvidence(client);
		DialogueResponse completedDialogueResponse = dialogueRuntime.poll(tickCount, eventBuffer);
		if (completedDialogueResponse != null) {
			Optional<GoalSnapshot> previousGoal = activeGoal();
			applyPlannerEventPolicyChanges(completedDialogueResponse.eventPolicyChanges());
			applyTaskIntent(completedDialogueResponse, worldEvidence);
			recordPlannerOutcome(completedDialogueResponse, previousGoal, activeGoal());
			drainEventPipeline();
		}
		debugRecorder.recordDialogueState(dialogueRuntime.snapshot());

		TaskSnapshot previousTaskSnapshot = taskSnapshot;
		activeJobRuntime.tick(
			taskExecutionSnapshot,
			worldEvidence,
			sessionSnapshot.companionActuationAllowed(),
			hasNearbyTaskResourceTarget(client, activeJobRuntime.current().taskSpec()),
			tickCount
		);
		TaskSnapshot projectedTaskSnapshot = activeJobRuntime.taskSnapshot();
		if (isSemanticTaskSnapshot(projectedTaskSnapshot)) {
			taskSnapshot = projectedTaskSnapshot;
			missionExecutionSnapshot = activeJobRuntime.missionExecutionSnapshot();
		}
		debugRecorder.recordCollectResourceProbe(activeJobRuntime.collectResourceDebugSnapshot());
		recordSemanticTaskTransition(previousTaskSnapshot, taskSnapshot);
		Optional<GoalSnapshot> activeGoal = activeGoal();
		Optional<WorldTaskRequest> activeTaskRequest = activeJobRuntime.activeTaskRequest();

		followState = followCapability.tick(
			client,
			sessionSnapshot,
			activeGoal,
			nearbyPlayerTracker,
			tickCount,
			eventBuffer
		);
		TaskExecutionSnapshot previousTaskExecutionSnapshot = taskExecutionSnapshot;
		Optional<TaskTerminalEvent> terminalTaskEvent = worldTaskExecutor.tick(sessionSnapshot, activeTaskRequest);
		taskExecutionSnapshot = worldTaskExecutor.snapshot();
		boolean semanticTaskContext = hasSemanticTaskContext(previousTaskSnapshot, taskSnapshot);
		recordTaskStateTransition(previousTaskExecutionSnapshot, taskExecutionSnapshot, semanticTaskContext);
		terminalTaskEvent.ifPresent(event -> handleTerminalTaskEvent(event, semanticTaskContext));
		behaviorTreeRuntime.tick(
			client,
			sessionSnapshot,
			dialogueRuntime,
			chatService,
			debugRecorder,
			activeGoal,
			followState,
			taskExecutionSnapshot,
			tickCount
		);
		if (previousFollowState.targetNearby() && !followState.targetNearby() && previousFollowState.targetPlayer() != null) {
			activeJobRuntime.clearFollowTarget(previousFollowState.targetPlayer());
		}

		BehaviorTreeSnapshot currentTreeSnapshot = behaviorTreeRuntime.snapshot();
		if (
			followState.goalActive()
			&& followState.targetNearby()
			&& !previousTreeSnapshot.movement().stuck()
			&& currentTreeSnapshot.movement().stuck()
		) {
			eventBuffer.append(tickCount, "follow.stuck", Map.of(
				"player", followState.targetPlayer(),
				"distanceToTarget", followState.distanceToTarget()
			));
		}
		drainEventPipeline();

		verificationRunner.onTick();
		lastKnownPlayerHealth = currentPlayerHealth(client);
	}

	private void openLanIfSingleplayerLocal(MinecraftClient client) {
		if (!autoLanOpenState.shouldAttempt(sessionSnapshot)) {
			return;
		}

		try {
			lanHostingService.openLan(sessionSnapshot);
			if (sessionSnapshotOverrideForTests == null) {
				sessionSnapshot = sessionRuntime.poll(client, tickCount, eventBuffer);
			}
		}
		catch (LanHostingService.LanHostingException exception) {
			if ("minecraft_unavailable".equals(exception.code())) {
				return;
			}
			autoLanOpenState.recordFailure();
			eventBuffer.append(tickCount, "session.lan_open_failed", Map.of(
				"errorCode", exception.code(),
				"message", exception.getMessage()
			));
		}
	}

	public void shutdown() {
		initialized = false;
		tickCount = 0L;
		worldLoadTick = -1L;
		sessionSnapshotOverrideForTests = null;
		autoLanOpenState.clear();
		verificationRunner.reset();
		localDamageTracker.clear();
		nearbyPlayerTracker.clear(tickCount, eventBuffer);
		eventPipeline.clear();
		primaryInteractionResolver.clear();
		dialogueRuntime.shutdown();
		observability.shutdown();
		visionService.shutdown();
		worldTaskExecutor.shutdown();
		activeJobRuntime.clear();
		followCapability.clear();
		followState = FollowState.idle();
		taskSnapshot = TaskSnapshot.idle();
		taskExecutionSnapshot = TaskExecutionSnapshot.idle();
		missionExecutionSnapshot = MissionExecutionSnapshot.idle();
		behaviorTreeRuntime.stop(MinecraftClient.getInstance());
		chatService.clear();
		proactiveSocialModeOverride = null;
		lastSystemChatTick = -1L;
		lastSystemChatText = null;
		lastKnownPlayerHealth = null;
		seenPlayerNames.clear();
		sessionSnapshot = SessionSnapshot.initial();
	}

	public SessionSnapshot sessionSnapshot() {
		return sessionSnapshot.withTickCount(tickCount);
	}

	public AgentRuntimeSnapshot snapshot() {
		return new AgentRuntimeSnapshot(
			initialized,
			tickCount,
			sessionSnapshot(),
			taskSnapshot,
			taskExecutionSnapshot,
			missionExecutionSnapshot,
			verificationRunner.report()
		);
	}

	public VerificationReport verificationReport() {
		return verificationRunner.report();
	}

	public Optional<GoalSnapshot> activeGoal() {
		return activeJobRuntime.activeGoal(tickCount);
	}

	public BehaviorTreeSnapshot behaviorTreeSnapshot() {
		return behaviorTreeRuntime.snapshot();
	}

	public ActiveJob activeJob() {
		return activeJobRuntime.current();
	}

	public TaskExecutionSnapshot taskExecutionSnapshot() {
		return taskExecutionSnapshot;
	}

	public TaskSnapshot taskSnapshot() {
		return taskSnapshot;
	}

	public MissionExecutionSnapshot missionExecutionSnapshot() {
		return missionExecutionSnapshot;
	}

	public Optional<DialogueResponse> lastDialogueResponse() {
		return dialogueRuntime.lastResponse();
	}

	public DialogueSnapshot dialogueSnapshot() {
		return dialogueRuntime.snapshot();
	}

	public boolean llmAvailable() {
		return dialogueRuntime.llmAvailable();
	}

	public boolean visionAvailable() {
		return visionService.isConfigured();
	}

	public boolean isDegraded() {
		return dialogueRuntime.isDegraded();
	}

	public PlannerOrchestratorDebugSnapshot plannerDebugSnapshot() {
		return dialogueRuntime.plannerDebugSnapshot();
	}

	public PlannerConversationDebugSnapshot plannerConversationDebugSnapshot() {
		return dialogueRuntime.plannerConversationDebugSnapshot();
	}

	public PlannerConversationDebugSnapshot plannerCanonicalConversationDebugSnapshot() {
		return dialogueRuntime.plannerCanonicalConversationDebugSnapshot();
	}

	public DialogueDebugSnapshot debugDialogueState() {
		return debugRecorder.dialogueSnapshot();
	}

	public ChatDebugSnapshot debugChatState() {
		return debugRecorder.chatSnapshot();
	}

	public CollectResourceTaskDebugSnapshot debugCollectResourceState() {
		return debugRecorder.collectResourceSnapshot();
	}

	public EventPipelineDebugSnapshot debugEventPipelineState() {
		return debugRecorder.eventPipelineSnapshot();
	}

	public ConversationSourcesDebugSnapshot debugConversationSources() {
		return debugRecorder.conversationSourcesSnapshot();
	}

	public List<PlannerAttemptDebugSnapshot> debugPlannerAttempts() {
		return debugRecorder.plannerAttempts();
	}

	public AgentDebugTimelineQueryResult debugTimeline(Long sinceEntryId) {
		return debugRecorder.queryTimeline(sinceEntryId);
	}

	public List<String> plannerContextExcerpt() {
		return dialogueRuntime.plannerContextExcerpt();
	}

	public List<PlannerShellEvent> plannerShellJournal() {
		return plannerJournal.snapshot();
	}

	public int activeEventPolicyRuleCount() {
		return eventPolicyState.activeRuleCount();
	}

	public int recentEventPolicyInterventionCount() {
		return eventPolicyState.recentInterventionCount();
	}

	public EventPolicyDecision lastEventPolicyDecision() {
		return eventPolicyState.lastDecision().orElse(null);
	}

	public List<EventPolicyRule> activeEventPolicyRules() {
		return eventPolicyState.activeRules();
	}

	public List<EventPolicyIntervention> recentEventPolicyInterventions() {
		return eventPolicyState.recentInterventions();
	}

	public void clearEventPolicy() {
		eventPolicyState.clear();
	}

	public boolean verificationAvailable() {
		MinecraftClient client = MinecraftClient.getInstance();
		return sessionSnapshot.mode() == SessionMode.SINGLEPLAYER_LOCAL
			&& sessionSnapshot.worldLoaded()
			&& client != null
			&& client.player != null
			&& client.isIntegratedServerRunning()
			&& client.getServer() != null;
	}

	public long latestEventSeqNo() {
		return eventBuffer.latestSeqNo();
	}

	public VerificationPlayerProbe verificationPlayerProbe() {
		prepareClientForVerification();
		return onVerificationServer((server, player) -> verificationPlayerProbe(player));
	}

	public VerificationPlayerProbe verificationTeleportPlayer(double x, double y, double z) {
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
			throw new BridgeUnavailableException("invalid_request", "x, y, and z must be finite numbers");
		}
		prepareClientForVerification();
		return onVerificationServer((server, player) -> {
			player.requestTeleport(x, y, z);
			return verificationPlayerProbe(player);
		});
	}

	public VerificationPlayerProbe verificationSetPlayerVelocity(double x, double y, double z) {
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
			throw new BridgeUnavailableException("invalid_request", "x, y, and z must be finite numbers");
		}
		prepareClientForVerification();
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null) {
			throw new BridgeUnavailableException("verification_unavailable", "Local verification player is unavailable");
		}
		client.player.setVelocityClient(x, y, z);
		client.player.setOnGround(false);
		return onVerificationServer((server, player) -> {
			player.setVelocity(x, y, z);
			player.velocityDirty = true;
			player.setOnGround(false);
			return verificationPlayerProbe(player);
		});
	}

	public VerificationPlayerProbe verificationSetGameMode(String modeId) {
		GameMode gameMode = verificationGameMode(modeId);
		prepareClientForVerification();
		return onVerificationServer((server, player) -> {
			player.changeGameMode(gameMode);
			return verificationPlayerProbe(player);
		});
	}

	public VerificationPlayerProbe verificationRunCommand(String command) {
		String normalizedCommand = normalizedVerificationCommand(command);
		prepareClientForVerification();
		return onVerificationServer((server, player) -> {
			server.getCommandManager().executeWithPrefix(
				server.getCommandSource()
					.withEntity(player)
					.withPosition(new Vec3d(player.getX(), player.getY(), player.getZ()))
					.withWorld((ServerWorld) player.getWorld())
					.withSilent(),
				normalizedCommand
			);
			return verificationPlayerProbe(player);
		});
	}

	public boolean verificationRequestRespawn() {
		prepareClientForVerification();
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null) {
			throw new BridgeUnavailableException("verification_unavailable", "Local verification player is unavailable");
		}
		client.player.requestRespawn();
		return true;
	}

	public boolean startDebugCompaction() {
		return dialogueRuntime.startDebugCompaction();
	}

	public CompactionExecutionResult pollDebugCompaction() {
		return dialogueRuntime.pollDebugCompaction();
	}

	public long lastChatTick() {
		return chatService.lastChatTick();
	}

	public String lastChatText() {
		return chatService.lastChatText();
	}

	public boolean startVerification(String scenarioName) {
		proactiveSocialModeOverride = null;
		prepareClientForVerification();
		return verificationRunner.start(scenarioName);
	}

	public void onChatReceived(String senderName, String plainTextMessage) {
		if (isAgentChatEcho(
			senderName,
			plainTextMessage,
			localPlayerName(),
			chatService.lastChatText(),
			tickCount,
			chatService.lastChatTick()
		)) {
			return;
		}
		if (isLocalControllerMessage(senderName, localPlayerName())) {
			eventBuffer.append(tickCount, "social.local_controller_spoke", Map.of(
				"player", senderName,
				"message", plainTextMessage,
				"normalizedMessage", ChatIngestService.normalize(plainTextMessage)
			));

			String plannerSender = DialogueSpeakerLabels.SAME_CLIENT_ADMIN;
			if (dialogueRuntime.handleResetCommand(plannerSender, plainTextMessage, tickCount, eventBuffer)) {
				eventPolicyState.clear();
				drainEventPipeline();
				return;
			}
			drainEventPipeline();
			return;
		}

		chatIngestService.ingest(
			senderName,
			plainTextMessage,
			tickCount,
			nearbyPlayerTracker,
			primaryInteractionResolver,
			eventBuffer
		);

		if (dialogueRuntime.handleResetCommand(senderName, plainTextMessage, tickCount, eventBuffer)) {
			eventPolicyState.clear();
			drainEventPipeline();
			return;
		}
		drainEventPipeline();
	}

	public void onSystemChatReceived(String plainTextMessage) {
		if (!airicraftConfig.readSystemChatMessages()) {
			return;
		}
		if (isDuplicateSystemChat(plainTextMessage, tickCount)) {
			return;
		}

		chatIngestService.ingestSystemMessage(plainTextMessage, tickCount, eventBuffer);
		drainEventPipeline();
	}

	public void onPlayerCraftedItem(String itemId, int count) {
		if (itemId == null || itemId.isBlank() || count <= 0) {
			return;
		}

		eventBuffer.append(tickCount, "crafting.item_crafted", Map.of(
			"actor", "self",
			"itemId", itemId,
			"count", count
		));
		drainEventPipeline();
	}

	public void onPlayerPickedUpItem(String itemId, int count) {
		if (itemId == null || itemId.isBlank() || count <= 0) {
			return;
		}

		eventBuffer.append(tickCount, "pickup.item_picked_up", Map.of(
			"actor", "self",
			"itemId", itemId,
			"count", count
		));
		drainEventPipeline();
	}

	public void onPlayerDamageObserved(DamageSource damageSource) {
		localDamageTracker.observeDamageSource(tickCount, damageSource);
	}

	public void onPlayerHealthUpdated(boolean healthInitialized, float healthBefore, float healthAfter) {
		float effectiveHealthBefore = resolveEffectiveHealthBefore(healthBefore, healthAfter);
		Map<String, Object> payload = localDamageTracker.consumeDamage(healthInitialized, tickCount, effectiveHealthBefore, healthAfter);
		lastKnownPlayerHealth = healthAfter;
		if (payload == null) {
			return;
		}

		eventBuffer.append(tickCount, "combat.damage_taken", payload);
		drainEventPipeline();
	}

	public void onPlayerRespawned() {
		localDamageTracker.onLifecycleReset(tickCount);
		lastKnownPlayerHealth = null;
	}

	public void onPlayerJoinedGame(UUID playerUuid, String playerName) {
		if (playerUuid == null || playerName == null || playerName.isBlank()) {
			return;
		}
		if (isLocalPlayer(playerUuid, playerName)) {
			seenPlayerNames.put(playerUuid, playerName);
			return;
		}
		if (seenPlayerNames.putIfAbsent(playerUuid, playerName) != null) {
			return;
		}

		eventBuffer.append(tickCount, "social.player_joined_game", Map.of(
			"player", playerName
		));
		forwardSyntheticPresenceMessage(playerName + " joined the game");
		drainEventPipeline();
	}

	public void onPlayerLeftGame(UUID playerUuid) {
		if (playerUuid == null) {
			return;
		}

		String playerName = seenPlayerNames.remove(playerUuid);
		if (playerName == null || playerName.isBlank() || isLocalPlayer(playerUuid, playerName)) {
			return;
		}

		eventBuffer.append(tickCount, "social.player_left_game", Map.of(
			"player", playerName
		));
		forwardSyntheticPresenceMessage(playerName + " left the game");
		drainEventPipeline();
	}

	public SemanticEventQueryResult recentEvents(Long sinceSeqNo) {
		return eventBuffer.query(sinceSeqNo);
	}

	public Optional<PrimaryInteractionPlayer> primaryInteractionPlayer() {
		return primaryInteractionResolver.current();
	}

	public List<NearbyPlayerSnapshot> nearbyPlayers() {
		return nearbyPlayerTracker.snapshot();
	}

	public Map<String, Object> openLan() {
		return lanHostingService.openLan(sessionSnapshot);
	}

	public void injectMockPlannerResponse(PlannerResponse response) {
		dialogueRuntime.injectMockResponse(response);
	}

	public void injectPlannerTimeout() {
		dialogueRuntime.injectTimeout();
	}

	public TaskSnapshot submitTask(TaskSpec spec, String source) {
		Objects.requireNonNull(spec, "spec");
		activeJobRuntime.submitTask(
			spec,
			currentTaskResourceCount(MinecraftClient.getInstance(), spec),
			source == null || source.isBlank() ? "bridge_debug" : source,
			tickCount
		);
		taskSnapshot = activeJobRuntime.taskSnapshot();
		missionExecutionSnapshot = activeJobRuntime.missionExecutionSnapshot();
		debugRecorder.recordCollectResourceProbe(activeJobRuntime.collectResourceDebugSnapshot());
		eventBuffer.append(tickCount, "task.submitted", Map.of(
			"type", spec.type().name(),
			"resourceKind", spec.resourceKind().name(),
			"quantity", spec.quantity(),
			"source", taskSnapshot.source()
		));
		return taskSnapshot;
	}

	public TaskSnapshot submitMissionLedger(TaskLedger ledger, String source) {
		Objects.requireNonNull(ledger, "ledger");
		activeJobRuntime.submitMissionLedger(
			ledger,
			currentWorldEvidence(MinecraftClient.getInstance()).inventoryCounts().getOrDefault(TaskResourceKind.WOOD_LOGS, 0),
			source == null || source.isBlank() ? "bridge_debug_mission" : source,
			tickCount
		);
		taskSnapshot = activeJobRuntime.taskSnapshot();
		missionExecutionSnapshot = activeJobRuntime.missionExecutionSnapshot();
		debugRecorder.recordCollectResourceProbe(activeJobRuntime.collectResourceDebugSnapshot());
		eventBuffer.append(tickCount, "mission.submitted", Map.of(
			"missionId", ledger.missionId(),
			"missionType", ledger.missionType().name(),
			"activeStepId", ledger.activeStepId() == null ? "" : ledger.activeStepId(),
			"source", taskSnapshot.source()
		));
		return taskSnapshot;
	}

	public TaskSnapshot submitAttackEntity(EntityInteractionStepArgs entityInteraction, String source) {
		return submitActiveJobProposal(ActiveJobProposal.attackEntity(entityInteraction), source, entityInteractionEventPayload(entityInteraction, "ATTACK_ENTITY", source));
	}

	public TaskSnapshot submitUseEntity(EntityInteractionStepArgs entityInteraction, String source) {
		return submitActiveJobProposal(ActiveJobProposal.useEntity(entityInteraction), source, entityInteractionEventPayload(entityInteraction, "USE_ENTITY", source));
	}

	public TaskSnapshot submitActionGraphJob(ActiveJobProposal proposal, String source, Map<String, Object> dispatchPayload) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("type", proposal.type().name());
		payload.put("source", source == null || source.isBlank() ? "action_graph_debug" : source);
		if (dispatchPayload != null) {
			payload.putAll(dispatchPayload);
		}
		return submitActiveJobProposal(proposal, source == null || source.isBlank() ? "action_graph_debug" : source, payload);
	}

	public TaskSnapshot cancelTask(String reason) {
		TaskSnapshot previousTaskSnapshot = taskSnapshot;
		activeJobRuntime.cancel(reason == null || reason.isBlank() ? "cancelled" : reason, tickCount);
		taskSnapshot = activeJobRuntime.taskSnapshot();
		missionExecutionSnapshot = activeJobRuntime.missionExecutionSnapshot();
		debugRecorder.recordCollectResourceProbe(activeJobRuntime.collectResourceDebugSnapshot());
		recordSemanticTaskTransition(previousTaskSnapshot, taskSnapshot);
		return taskSnapshot;
	}

	private TaskSnapshot submitActiveJobProposal(ActiveJobProposal proposal, String source, Map<String, Object> submittedPayload) {
		Objects.requireNonNull(proposal, "proposal");
		WorldEvidence worldEvidence = currentWorldEvidence(MinecraftClient.getInstance());
		int currentResourceCount = worldEvidence.inventoryCounts().getOrDefault(TaskResourceKind.WOOD_LOGS, 0);
		activeJobRuntime.applyPlannerResponse(
			new DialogueResponse("", new DialogueIntent(DialogueIntentType.JOB_UPDATE, proposal), tickCount),
			currentResourceCount,
			source == null || source.isBlank() ? "bridge_debug" : source,
			tickCount
		);
		taskSnapshot = activeJobRuntime.taskSnapshot();
		missionExecutionSnapshot = activeJobRuntime.missionExecutionSnapshot();
		debugRecorder.recordCollectResourceProbe(activeJobRuntime.collectResourceDebugSnapshot());
		eventBuffer.append(tickCount, "task.submitted", submittedPayload);
		return taskSnapshot;
	}

	private Map<String, Object> entityInteractionEventPayload(EntityInteractionStepArgs entityInteraction, String type, String source) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("type", type);
		payload.put("source", source == null || source.isBlank() ? "bridge_debug" : source);
		if (entityInteraction != null && entityInteraction.selector() != null) {
			if (entityInteraction.selector().uuid() != null) {
				payload.put("uuid", entityInteraction.selector().uuid());
			}
			if (entityInteraction.selector().name() != null) {
				payload.put("name", entityInteraction.selector().name());
			}
			if (entityInteraction.selector().entityTypeId() != null) {
				payload.put("entityTypeId", entityInteraction.selector().entityTypeId());
			}
		}
		if (entityInteraction != null && entityInteraction.itemId() != null) {
			payload.put("itemId", entityInteraction.itemId());
		}
		if ("ATTACK_ENTITY".equals(type) && entityInteraction != null && entityInteraction.attackMode() != null) {
			payload.put("mode", entityInteraction.attackMode().wireValue());
		}
		return payload;
	}

	void injectDialogueResponseForTests(DialogueResponse response) {
		Optional<GoalSnapshot> previousGoal = activeGoal();
		applyTaskIntent(response, currentWorldEvidence(MinecraftClient.getInstance()));
		recordPlannerOutcome(response, previousGoal, activeGoal());
	}

	void overrideSessionSnapshotForTests(SessionSnapshot sessionSnapshot) {
		sessionSnapshotOverrideForTests = sessionSnapshot;
		this.sessionSnapshot = sessionSnapshot == null ? SessionSnapshot.initial() : sessionSnapshot;
	}

	void injectNearbyPlayerForTests(String playerName, Vec3d pos) {
		nearbyPlayerTracker.injectPlayerNearby(playerName, pos, tickCount, eventBuffer);
	}

	void injectGoalForTests(GoalSnapshot goalSnapshot) {
		if (goalSnapshot == null) {
			activeJobRuntime.clear();
			taskSnapshot = activeJobRuntime.taskSnapshot();
			missionExecutionSnapshot = activeJobRuntime.missionExecutionSnapshot();
			debugRecorder.recordCollectResourceProbe(activeJobRuntime.collectResourceDebugSnapshot());
			return;
		}
		activeJobRuntime.applyPlannerResponse(new DialogueResponse(
			"",
			new DialogueIntent(
				DialogueIntentType.SET_GOAL,
				goalSnapshot.type(),
				goalSnapshot.targetPlayer(),
				goalSnapshot.position(),
				goalSnapshot.mineSpec()
			),
			goalSnapshot.updatedTick()
		), 0, "test", goalSnapshot.updatedTick());
		debugRecorder.recordCollectResourceProbe(activeJobRuntime.collectResourceDebugSnapshot());
	}

	private CompletableFuture<String> executePlannerToolCall(PlannerToolCall toolCall) {
		try {
			return CompletableFuture.completedFuture(executePlannerToolCallNow(toolCall));
		}
		catch (RuntimeException exception) {
			String name = toolCall == null ? "unknown" : toolCall.name();
			return CompletableFuture.completedFuture("TOOL_ERROR: " + name + " " + safeToolError(exception));
		}
	}

	private String executePlannerToolCallNow(PlannerToolCall toolCall) {
		if (toolCall == null) {
			return "TOOL_ERROR: missing_tool_call";
		}
		JsonObject args = toolCall.arguments();
		return switch (PlannerToolCatalog.normalizeName(toolCall.name())) {
			case PlannerToolCatalog.FOLLOW_PLAYER -> {
				String targetPlayer = stringArg(args, "targetPlayer").orElseThrow(() -> new IllegalArgumentException("targetPlayer is required"));
				applyPlannerJobTool(ActiveJobProposal.followPlayer(targetPlayer));
				yield "Tool result for follow_player: accepted targetPlayer=" + targetPlayer;
			}
			case PlannerToolCatalog.NAVIGATE_TO -> {
				GoalPosition position = new GoalPosition(
					intArg(args, "x").orElseThrow(() -> new IllegalArgumentException("x is required")),
					intArg(args, "y").orElseThrow(() -> new IllegalArgumentException("y is required")),
					intArg(args, "z").orElseThrow(() -> new IllegalArgumentException("z is required")),
					booleanArg(args, "exactY").orElse(false)
				);
				applyPlannerJobTool(ActiveJobProposal.navigateTo(position));
				yield "Tool result for navigate_to: accepted x=" + position.x() + " y=" + position.y() + " z=" + position.z() + " exactY=" + position.exactY();
			}
			case PlannerToolCatalog.MINE_BLOCKS -> {
				GoalMineSpec mineSpec = new GoalMineSpec(
					stringArrayArg(args, "blockIds"),
					intArg(args, "quantity").orElseThrow(() -> new IllegalArgumentException("quantity is required"))
				);
				applyPlannerJobTool(ActiveJobProposal.mineBlocks(mineSpec));
				yield "Tool result for mine_blocks: accepted blockIds=" + String.join(",", mineSpec.blockIds()) + " quantity=" + mineSpec.quantity();
			}
			case PlannerToolCatalog.COLLECT_RESOURCE -> {
				TaskResourceKind resourceKind = resourceKindArg(args, "resourceKind");
				int quantity = intArg(args, "quantity").orElseThrow(() -> new IllegalArgumentException("quantity is required"));
				applyPlannerJobTool(ActiveJobProposal.collectResource(new TaskSpec(TaskType.COLLECT_RESOURCE, resourceKind, quantity)));
				yield "Tool result for collect_resource: accepted resourceKind=" + resourceKind.name() + " quantity=" + quantity;
			}
			case PlannerToolCatalog.CRAFT_RECIPE -> {
				CraftRecipeStepArgs craftRecipe = new CraftRecipeStepArgs(
					stringArg(args, "recipeId").orElseThrow(() -> new IllegalArgumentException("recipeId is required")),
					intArg(args, "times").orElseThrow(() -> new IllegalArgumentException("times is required"))
				);
				applyPlannerJobTool(ActiveJobProposal.craftRecipe(craftRecipe));
				yield queuedActionToolResult("craft_recipe", "recipeId=" + craftRecipe.recipeId() + " times=" + craftRecipe.times());
			}
			case PlannerToolCatalog.DROP_ITEMS -> {
				DropItemsStepArgs dropItems = new DropItemsStepArgs(
					stringArg(args, "itemId").orElseThrow(() -> new IllegalArgumentException("itemId is required")),
					intArg(args, "quantity").orElseThrow(() -> new IllegalArgumentException("quantity is required")),
					null
				);
				applyPlannerJobTool(ActiveJobProposal.dropItems(dropItems));
				yield queuedActionToolResult("drop_items", "itemId=" + dropItems.itemId() + " quantity=" + dropItems.quantity());
			}
			case PlannerToolCatalog.GIVE_PLAYER -> {
				String targetPlayer = stringArg(args, "targetPlayer").orElseThrow(() -> new IllegalArgumentException("targetPlayer is required"));
				ensureGiveTargetNearby(targetPlayer);
				DropItemsStepArgs dropItems = new DropItemsStepArgs(
					stringArg(args, "itemId").orElseThrow(() -> new IllegalArgumentException("itemId is required")),
					intArg(args, "quantity").orElseThrow(() -> new IllegalArgumentException("quantity is required")),
					targetPlayer
				);
				applyPlannerJobTool(ActiveJobProposal.dropItems(dropItems));
				yield queuedActionToolResult("give_player", "targetPlayer=" + targetPlayer + " itemId=" + dropItems.itemId() + " quantity=" + dropItems.quantity());
			}
			case PlannerToolCatalog.ATTACK_ENTITY -> {
				EntityInteractionStepArgs entityInteraction = new EntityInteractionStepArgs(
					parseEntitySelectorArgs(args),
					null,
					EntityAttackMode.fromWireValue(stringArg(args, "mode").orElse(null))
				);
				applyPlannerJobTool(ActiveJobProposal.attackEntity(entityInteraction));
				yield queuedActionToolResult("attack_entity", describeEntitySelector(entityInteraction.selector()) + " mode=" + entityInteraction.attackMode().wireValue());
			}
			case PlannerToolCatalog.USE_ENTITY -> {
				EntityInteractionStepArgs entityInteraction = new EntityInteractionStepArgs(
					parseEntitySelectorArgs(args),
					stringArg(args, "itemId").orElse(null)
				);
				applyPlannerJobTool(ActiveJobProposal.useEntity(entityInteraction));
				String details = describeEntitySelector(entityInteraction.selector())
					+ (entityInteraction.itemId() == null ? "" : " itemId=" + entityInteraction.itemId());
				yield queuedActionToolResult("use_entity", details);
			}
			case PlannerToolCatalog.CANCEL_TASK -> {
				String reason = stringArg(args, "reason").orElse("planner_tool_cancelled");
				TaskSnapshot snapshot = cancelTask(reason);
				yield "Tool result for cancel_task: accepted state=" + snapshot.state().name();
			}
			case PlannerToolCatalog.CLEAR_GOAL -> {
				applyPlannerClearGoalTool();
				yield "Tool result for clear_goal: accepted";
			}
			case PlannerToolCatalog.UPDATE_EVENT_POLICY -> {
				EventPolicyChanges changes = parseToolEventPolicyChanges(args);
				applyPlannerEventPolicyChanges(changes);
				yield "Tool result for update_event_policy: applied clearAll=" + changes.clearAll()
					+ " removeRuleIds=" + changes.removeRuleIds().size()
					+ " upserts=" + changes.upserts().size();
			}
			default -> "TOOL_ERROR: unknown_tool " + toolCall.name();
		};
	}

	private static String queuedActionToolResult(String toolName, String details) {
		return "Tool result for " + toolName + ": accepted queued " + details
			+ ". Accepted does not mean completed. Wait for TASK UPDATE before saying the action completed.";
	}

	String executePlannerToolCallForTests(PlannerToolCall toolCall) {
		return executePlannerToolCall(toolCall).join();
	}

	private void emitPlannerToolNarration(PlannerToolCall toolCall) {
		if (toolCall == null || toolCall.narration() == null || toolCall.narration().isBlank()) {
			return;
		}
		chatService.send(MinecraftClient.getInstance(), toolCall.narration(), tickCount);
	}

	private void applyPlannerJobTool(ActiveJobProposal proposal) {
		Optional<GoalSnapshot> previousGoal = activeGoal();
		DialogueResponse response = new DialogueResponse(
			"",
			new DialogueIntent(DialogueIntentType.JOB_UPDATE, proposal),
			tickCount
		);
		applyTaskIntent(response, currentWorldEvidence(MinecraftClient.getInstance()), "planner_tool");
		recordPlannerOutcome(response, previousGoal, activeGoal());
		drainEventPipeline();
	}

	private void ensureGiveTargetNearby(String targetPlayer) {
		NearbyPlayerSnapshot target = nearbyPlayerTracker.findByName(targetPlayer)
			.orElseThrow(() -> new IllegalStateException("target_not_nearby"));
		Vec3d selfPos = currentPlayerPosition();
		Vec3d targetPos = new Vec3d(target.x(), target.y(), target.z());
		if (selfPos.squaredDistanceTo(targetPos) > 16.0D) {
			throw new IllegalStateException("target_not_nearby");
		}
	}

	private static EntitySelector parseEntitySelectorArgs(JsonObject object) {
		return new EntitySelector(
			stringArg(object, "uuid").orElse(null),
			stringArg(object, "name").orElse(null),
			stringArg(object, "entityTypeId").orElse(null)
		);
	}

	private static String describeEntitySelector(EntitySelector selector) {
		if (selector == null) {
			return "selector=missing";
		}
		if (selector.uuid() != null) {
			return "uuid=" + NearbyEntityService.plannerUuidToken(selector.uuid());
		}
		if (selector.name() != null) {
			return "name=" + selector.name();
		}
		return "entityTypeId=" + selector.entityTypeId();
	}

	private Vec3d currentPlayerPosition() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null && client.player != null) {
			return new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
		}
		WorldEvidence evidence = currentWorldEvidence(client);
		return new Vec3d(evidence.x(), evidence.y(), evidence.z());
	}

	private void applyPlannerClearGoalTool() {
		Optional<GoalSnapshot> previousGoal = activeGoal();
		DialogueResponse response = new DialogueResponse(
			"",
			new DialogueIntent(DialogueIntentType.CLEAR_GOAL, null, null),
			tickCount
		);
		applyTaskIntent(response, currentWorldEvidence(MinecraftClient.getInstance()), "planner_tool");
		recordPlannerOutcome(response, previousGoal, activeGoal());
		drainEventPipeline();
	}

	private void applyTaskIntent(DialogueResponse response, WorldEvidence worldEvidence) {
		applyTaskIntent(response, worldEvidence, "planner_response");
	}

	private void applyTaskIntent(DialogueResponse response, WorldEvidence worldEvidence, String source) {
		if (response == null || response.intent() == null || response.intent().type() == null) {
			return;
		}
		int currentResourceCount = worldEvidence == null
			? currentTaskResourceCount(MinecraftClient.getInstance())
			: worldEvidence.inventoryCounts().getOrDefault(TaskResourceKind.WOOD_LOGS, 0);
		if (isDirectGoalIntent(response.intent()) && isSemanticTaskSnapshot(taskSnapshot)) {
			TaskSnapshot previousTaskSnapshot = taskSnapshot;
			activeJobRuntime.cancel("preempted_by_direct_goal", response.tick());
			taskSnapshot = activeJobRuntime.taskSnapshot();
			missionExecutionSnapshot = activeJobRuntime.missionExecutionSnapshot();
			debugRecorder.recordCollectResourceProbe(activeJobRuntime.collectResourceDebugSnapshot());
			recordSemanticTaskTransition(previousTaskSnapshot, taskSnapshot);
		}
		activeJobRuntime.applyPlannerResponse(response, currentResourceCount, source == null || source.isBlank() ? "planner_response" : source, response.tick());
		TaskSnapshot projectedTaskSnapshot = activeJobRuntime.taskSnapshot();
		if (isSemanticTaskSnapshot(projectedTaskSnapshot)) {
			taskSnapshot = projectedTaskSnapshot;
			missionExecutionSnapshot = activeJobRuntime.missionExecutionSnapshot();
		}
		debugRecorder.recordCollectResourceProbe(activeJobRuntime.collectResourceDebugSnapshot());
	}

	private int currentTaskResourceCount(MinecraftClient client) {
		return currentTaskResourceCount(client, taskSnapshot.spec());
	}

	private int currentTaskResourceCount(MinecraftClient client, TaskSpec spec) {
		if (client == null || client.player == null || spec == null) {
			return 0;
		}
		java.util.ArrayList<net.minecraft.item.ItemStack> stacks = new java.util.ArrayList<>();
		for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
			stacks.add(client.player.getInventory().getStack(slot));
		}
		return inventoryResourceCounter.count(stacks, spec.resourceKind());
	}

	private static Optional<String> stringArg(JsonObject object, String key) {
		if (object == null || !object.has(key) || object.get(key).isJsonNull() || !object.get(key).isJsonPrimitive()) {
			return Optional.empty();
		}
		try {
			String value = object.get(key).getAsString();
			return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
		}
		catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	private static Optional<Integer> intArg(JsonObject object, String key) {
		if (object == null || !object.has(key) || object.get(key).isJsonNull() || !object.get(key).isJsonPrimitive()) {
			return Optional.empty();
		}
		try {
			return Optional.of(object.get(key).getAsInt());
		}
		catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	private static Optional<Boolean> booleanArg(JsonObject object, String key) {
		if (object == null || !object.has(key) || object.get(key).isJsonNull() || !object.get(key).isJsonPrimitive()) {
			return Optional.empty();
		}
		try {
			return Optional.of(object.get(key).getAsBoolean());
		}
		catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	private static List<String> stringArrayArg(JsonObject object, String key) {
		if (object == null || !object.has(key) || !object.get(key).isJsonArray()) {
			throw new IllegalArgumentException(key + " is required");
		}
		JsonArray array = object.getAsJsonArray(key);
		ArrayList<String> values = new ArrayList<>(array.size());
		for (JsonElement element : array) {
			if (!element.isJsonPrimitive()) {
				throw new IllegalArgumentException(key + " must contain strings");
			}
			String value = element.getAsString();
			if (value == null || value.isBlank()) {
				throw new IllegalArgumentException(key + " must contain non-empty strings");
			}
			values.add(value);
		}
		if (values.isEmpty()) {
			throw new IllegalArgumentException(key + " must not be empty");
		}
		return List.copyOf(values);
	}

	private static TaskResourceKind resourceKindArg(JsonObject object, String key) {
		String value = stringArg(object, key).orElseThrow(() -> new IllegalArgumentException(key + " is required"));
		try {
			return TaskResourceKind.valueOf(value.toUpperCase(Locale.ROOT));
		}
		catch (RuntimeException exception) {
			throw new IllegalArgumentException("unsupported resourceKind " + value, exception);
		}
	}

	private static EventPolicyChanges parseToolEventPolicyChanges(JsonObject object) {
		if (object == null) {
			return new EventPolicyChanges(false, List.of(), List.of());
		}
		boolean clearAll = booleanArg(object, "clearAll").orElse(false);
		List<String> removeRuleIds = object.has("removeRuleIds") && object.get("removeRuleIds").isJsonArray()
			? stringArrayAllowEmptyArg(object, "removeRuleIds")
			: List.of();
		ArrayList<EventPolicyRuleUpsert> upserts = new ArrayList<>();
		if (object.has("upserts") && object.get("upserts").isJsonArray()) {
			for (JsonElement element : object.getAsJsonArray("upserts")) {
				if (!element.isJsonObject()) {
					throw new IllegalArgumentException("upserts must contain objects");
				}
				JsonObject upsert = element.getAsJsonObject();
				upserts.add(new EventPolicyRuleUpsert(
					stringArg(upsert, "ruleId").orElse(null),
					stringArg(upsert, "effect").orElse(null),
					parseToolEventPolicyMatch(upsert.has("match") && upsert.get("match").isJsonObject() ? upsert.getAsJsonObject("match") : null),
					stringArg(upsert, "reason").orElse(null)
				));
			}
		}
		return new EventPolicyChanges(clearAll, removeRuleIds, upserts);
	}

	private static List<String> stringArrayAllowEmptyArg(JsonObject object, String key) {
		JsonArray array = object.getAsJsonArray(key);
		ArrayList<String> values = new ArrayList<>(array.size());
		for (JsonElement element : array) {
			if (!element.isJsonPrimitive()) {
				throw new IllegalArgumentException(key + " must contain strings");
			}
			String value = element.getAsString();
			if (value != null && !value.isBlank()) {
				values.add(value);
			}
		}
		return List.copyOf(values);
	}

	private static EventPolicyMatch parseToolEventPolicyMatch(JsonObject object) {
		if (object == null) {
			return null;
		}
		return new EventPolicyMatch(
			stringArg(object, "eventType").orElse(null),
			stringArg(object, "player").orElse(null),
			stringArg(object, "speaker").orElse(null),
			stringArg(object, "actor").orElse(null),
			stringArg(object, "itemId").orElse(null),
			stringArg(object, "damageTypeId").orElse(null),
			stringArg(object, "attackerName").orElse(null)
		);
	}

	private static String safeToolError(RuntimeException exception) {
		String message = exception.getMessage();
		if (message == null || message.isBlank()) {
			return exception.getClass().getSimpleName();
		}
		return message.replace('\n', ' ').replace('\r', ' ').strip();
	}

	private WorldEvidence currentWorldEvidence(MinecraftClient client) {
		if (client == null || client.player == null) {
			return new WorldEvidence(Map.of(), Map.of(), Map.of(), null, 0, 0, 0, null, tickCount);
		}

		java.util.ArrayList<net.minecraft.item.ItemStack> stacks = new java.util.ArrayList<>();
		for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
			stacks.add(client.player.getInventory().getStack(slot));
		}

		java.util.EnumMap<ai.moeru.airicraft.agent.tasks.TaskResourceKind, Integer> resourceCounts =
			new java.util.EnumMap<>(ai.moeru.airicraft.agent.tasks.TaskResourceKind.class);
		for (ai.moeru.airicraft.agent.tasks.TaskResourceKind kind : ai.moeru.airicraft.agent.tasks.TaskResourceKind.values()) {
			resourceCounts.put(kind, inventoryResourceCounter.count(stacks, kind));
		}

		String equippedItemId = Registries.ITEM.getId(client.player.getMainHandStack().getItem()).toString();
		BlockPos origin = client.player.getBlockPos();
		Map<String, Integer> itemCounts = inventoryItemCounter.count(client.player.getInventory());
		return new WorldEvidence(
			resourceCounts,
			itemCounts,
			collectNearbyBlocks(client, origin),
			client.world == null ? null : client.world.getRegistryKey().getValue().toString(),
			origin.getX(),
			origin.getY(),
			origin.getZ(),
			equippedItemId,
			tickCount
		);
	}

	private boolean hasNearbyTaskResourceTarget(MinecraftClient client, TaskSpec spec) {
		if (client == null || client.world == null || client.player == null || spec == null) {
			return false;
		}
		List<String> targetBlockIds = CollectResourceTaskHandler.targetBlockIds(spec);
		if (targetBlockIds.isEmpty()) {
			return false;
		}

		BlockPos origin = client.player.getBlockPos();
		for (int dx = -12; dx <= 12; dx++) {
			for (int dy = -6; dy <= 6; dy++) {
				for (int dz = -12; dz <= 12; dz++) {
					BlockPos pos = origin.add(dx, dy, dz);
					if (!client.world.isChunkLoaded(pos)) {
						continue;
					}
					String blockId = Registries.BLOCK.getId(client.world.getBlockState(pos).getBlock()).toString();
					if (targetBlockIds.contains(blockId)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private Map<String, Integer> collectNearbyBlocks(MinecraftClient client, BlockPos origin) {
		if (client == null || client.world == null) {
			return Map.of();
		}
		java.util.HashMap<String, Integer> counts = new java.util.HashMap<>();
		for (int dx = -8; dx <= 8; dx++) {
			for (int dy = -4; dy <= 4; dy++) {
				for (int dz = -8; dz <= 8; dz++) {
					BlockPos pos = origin.add(dx, dy, dz);
					if (!client.world.isChunkLoaded(pos)) {
						continue;
					}
					String blockId = Registries.BLOCK.getId(client.world.getBlockState(pos).getBlock()).toString();
					counts.merge(blockId, 1, Integer::sum);
				}
			}
		}
		return Map.copyOf(counts);
	}

	public VisionDescription describeCapturedView(FirstPersonScreenshotService.CapturedScreenshot screenshot, String prompt) throws LlmBackendException {
		return visionService.describe(screenshot, prompt);
	}

	private boolean proactiveSocialModeEnabled() {
		return proactiveSocialModeOverride != null
			? proactiveSocialModeOverride.booleanValue()
			: airicraftConfig.enableProactiveSocialMode();
	}

	private void setProactiveSocialModeOverride(Boolean enabled) {
		proactiveSocialModeOverride = enabled;
	}

	private boolean playerChatWithinConfiguredDistance(String senderName) {
		if (airicraftConfig.socialChatDistanceUnlimited()) {
			return true;
		}

		Optional<NearbyPlayerSnapshot> nearbyPlayer = nearbyPlayerTracker.findByName(senderName);
		if (nearbyPlayer.isEmpty()) {
			return false;
		}

		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null) {
			return false;
		}

		Vec3d selfPos = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
		Vec3d senderPos = new Vec3d(nearbyPlayer.get().x(), nearbyPlayer.get().y(), nearbyPlayer.get().z());
		double maxDistance = airicraftConfig.socialChatMaxDistanceBlocks();
		return selfPos.squaredDistanceTo(senderPos) <= maxDistance * maxDistance;
	}

	private static double resolveNearbyPlayerTrackingRadius(AiricraftConfig airicraftConfig) {
		if (airicraftConfig.socialChatDistanceUnlimited()) {
			return 32.0D;
		}
		return Math.max(32.0D, airicraftConfig.socialChatMaxDistanceBlocks());
	}

	private String localPlayerName() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null) {
			return client != null && client.getSession() != null ? client.getSession().getUsername() : null;
		}
		Text playerName = client.player.getName();
		return playerName == null ? null : playerName.getString();
	}

	static boolean isAgentChatEcho(
		String senderName,
		String plainTextMessage,
		String localPlayerName,
		String lastAgentChatText,
		long currentTick,
		long lastAgentChatTick
	) {
		if (senderName == null || plainTextMessage == null || localPlayerName == null || lastAgentChatText == null) {
			return false;
		}
		if (!senderName.equals(localPlayerName)) {
			return false;
		}
		if (!plainTextMessage.equals(lastAgentChatText)) {
			return false;
		}
		if (lastAgentChatTick < 0L || currentTick < lastAgentChatTick) {
			return false;
		}
		return currentTick - lastAgentChatTick <= CHAT_ECHO_SUPPRESSION_TICKS;
	}

	static boolean isLocalControllerMessage(String senderName, String localPlayerName) {
		if (senderName == null || localPlayerName == null) {
			return false;
		}
		return senderName.equals(localPlayerName);
	}

	private boolean isDuplicateSystemChat(String plainTextMessage, long currentTick) {
		if (plainTextMessage == null || plainTextMessage.isBlank()) {
			return true;
		}
		boolean duplicate = currentTick == lastSystemChatTick && plainTextMessage.equals(lastSystemChatText);
		lastSystemChatTick = currentTick;
		lastSystemChatText = plainTextMessage;
		return duplicate;
	}

	private void forwardSyntheticPresenceMessage(String plainTextMessage) {
		if (!airicraftConfig.readSystemChatMessages()) {
			return;
		}
		if (isDuplicateSystemChat(plainTextMessage, tickCount)) {
			return;
		}

		chatIngestService.ingestSystemMessage(plainTextMessage, tickCount, eventBuffer);
		drainEventPipeline();
	}

	private boolean isLocalPlayer(UUID playerUuid, String playerName) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			return false;
		}
		if (client.player != null && playerUuid.equals(client.player.getUuid())) {
			return true;
		}
		return client.getSession() != null && playerName.equals(client.getSession().getUsername());
	}

	private void recordPlannerOutcome(
		DialogueResponse response,
		Optional<GoalSnapshot> previousGoal,
		Optional<GoalSnapshot> currentGoal
	) {
		if (response == null || response.intent() == null || response.intent().type() == null) {
			return;
		}

		java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
		payload.put("intentType", response.intent().type().name());
		if (response.intent().targetPlayer() != null && !response.intent().targetPlayer().isBlank()) {
			payload.put("targetPlayer", response.intent().targetPlayer());
		}
		if (response.intent().goalType() != null) {
			payload.put("goalType", response.intent().goalType().name());
		}
		if (response.intent().activeJob() != null) {
			payload.put("activeJobType", response.intent().activeJob().type().name());
		}
		if (response.intent().taskLedger() != null) {
			payload.put("missionId", response.intent().taskLedger().missionId());
			payload.put("missionType", response.intent().taskLedger().missionType().name());
			if (response.intent().taskLedger().activeStepId() != null) {
				payload.put("activeStepId", response.intent().taskLedger().activeStepId());
			}
		}
		if (response.text() != null && !response.text().isBlank()) {
			payload.put("replyText", response.text());
		}
		eventBuffer.append(tickCount, "planner.response_applied", payload);

		if (isDirectGoalIntent(response.intent()) && currentGoal.isPresent()) {
			java.util.LinkedHashMap<String, Object> goalPayload = new java.util.LinkedHashMap<>();
			goalPayload.put("goalType", currentGoal.get().type().name());
			if (currentGoal.get().targetPlayer() != null && !currentGoal.get().targetPlayer().isBlank()) {
				goalPayload.put("targetPlayer", currentGoal.get().targetPlayer());
			}
			goalPayload.put("source", currentGoal.get().source());
			eventBuffer.append(tickCount, "planner.goal_set", goalPayload);
			return;
		}

		if (response.intent().type() == DialogueIntentType.CLEAR_GOAL && previousGoal.isPresent() && currentGoal.isEmpty()) {
			java.util.LinkedHashMap<String, Object> goalPayload = new java.util.LinkedHashMap<>();
			goalPayload.put("goalType", previousGoal.get().type().name());
			if (previousGoal.get().targetPlayer() != null && !previousGoal.get().targetPlayer().isBlank()) {
				goalPayload.put("targetPlayer", previousGoal.get().targetPlayer());
			}
			goalPayload.put("source", previousGoal.get().source());
			eventBuffer.append(tickCount, "planner.goal_cleared", goalPayload);
		}
	}

	private void drainEventPipeline() {
		List<ai.moeru.airicraft.agent.llm.PlannerTrigger> triggers = eventPipeline.drain(this::createPlannerTrigger);
		if (triggers.isEmpty()) {
			return;
		}
		String primaryInteractionPlayer = primaryInteractionResolver.current().map(PrimaryInteractionPlayer::name).orElse(null);
		Optional<GoalSnapshot> activeGoal = activeGoal();
		for (ai.moeru.airicraft.agent.llm.PlannerTrigger trigger : triggers) {
			dialogueRuntime.onPlannerTrigger(
				trigger,
				sessionSnapshot,
				primaryInteractionPlayer,
				activeGoal,
				taskSnapshot,
				missionExecutionSnapshot,
				plannerEventBuffer
			);
		}
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createPlannerTrigger(SemanticEvent event, EventRoutingProfile profile) {
		String eventType = event.type();
		if (eventType == null) {
			return null;
		}
		return switch (eventType) {
			case "social.player_spoke" -> createPlayerSpokeTrigger(event);
			case "social.player_addressed_agent" -> createAddressedChatTrigger(event);
			case "social.local_controller_spoke" -> createLocalControllerTrigger(event);
			case "social.system_message" -> createSystemTrigger(event);
			case "pickup.item_picked_up" -> createPickupTrigger(event);
			case "crafting.item_crafted" -> createCraftTrigger(event);
			case "combat.damage_taken" -> createDamageTrigger(event);
			case "task.blocked" -> createTaskBlockedTrigger(event);
			default -> null;
		};
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createPlayerSpokeTrigger(SemanticEvent event) {
		String player = stringPayloadValue(event.payload(), "player");
		String message = stringPayloadValue(event.payload(), "message");
		if (player == null || message == null) {
			return null;
		}
		if (ChatIngestService.isAddressedToAgent(message)) {
			return null;
		}
		if (!proactiveSocialModeEnabled() || !playerChatWithinConfiguredDistance(player)) {
			return null;
		}
		return ai.moeru.airicraft.agent.llm.PlannerTrigger.pending(PlannerTriggerType.CHAT, player, message, event.tick(), event.timestampMs());
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createAddressedChatTrigger(SemanticEvent event) {
		String player = stringPayloadValue(event.payload(), "player");
		String message = stringPayloadValue(event.payload(), "message");
		if (player == null || message == null) {
			return null;
		}
		if (DialogueRuntime.isResetCommand(message) || !playerChatWithinConfiguredDistance(player)) {
			return null;
		}
		return ai.moeru.airicraft.agent.llm.PlannerTrigger.pending(PlannerTriggerType.CHAT, player, message, event.tick(), event.timestampMs());
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createLocalControllerTrigger(SemanticEvent event) {
		String message = stringPayloadValue(event.payload(), "message");
		if (message == null || DialogueRuntime.isResetCommand(message)) {
			return null;
		}
		return ai.moeru.airicraft.agent.llm.PlannerTrigger.pending(
			PlannerTriggerType.CHAT,
			DialogueSpeakerLabels.SAME_CLIENT_ADMIN,
			message,
			event.tick(),
			event.timestampMs()
		);
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createSystemTrigger(SemanticEvent event) {
		String message = stringPayloadValue(event.payload(), "message");
		if (message == null || !proactiveSocialModeEnabled()) {
			return null;
		}
		return ai.moeru.airicraft.agent.llm.PlannerTrigger.pending(PlannerTriggerType.SYSTEM, "server", message, event.tick(), event.timestampMs());
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createPickupTrigger(SemanticEvent event) {
		if (suppressPlannerTriggersForCollectResourceProgress()) {
			return null;
		}
		String itemId = stringPayloadValue(event.payload(), "itemId");
		Float count = floatPayloadValue(event.payload(), "count");
		if (itemId == null || count == null) {
			return null;
		}
		return ai.moeru.airicraft.agent.llm.PlannerTrigger.pending(
			PlannerTriggerType.PICKUP,
			"self",
			"Picked up " + formatDecimal(count) + "x " + itemId + ".",
			event.tick(),
			event.timestampMs()
		);
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createCraftTrigger(SemanticEvent event) {
		if (suppressPlannerTriggersForCollectResourceProgress()) {
			return null;
		}
		String itemId = stringPayloadValue(event.payload(), "itemId");
		Float count = floatPayloadValue(event.payload(), "count");
		if (itemId == null || count == null) {
			return null;
		}
		return ai.moeru.airicraft.agent.llm.PlannerTrigger.pending(
			PlannerTriggerType.CRAFT,
			"self",
			"I crafted " + formatDecimal(count) + "x " + itemId + ".",
			event.tick(),
			event.timestampMs()
		);
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createDamageTrigger(SemanticEvent event) {
		Map<String, Object> payload = event.payload();
		String damageTypeId = stringPayloadValue(payload, "damageTypeId");
		String attackerName = stringPayloadValue(payload, "attackerName");
		Float amount = floatPayloadValue(payload, "amount");
		Float resultingHealth = floatPayloadValue(payload, "healthAfter");
		if (amount == null && resultingHealth == null) {
			return null;
		}

		StringBuilder message = new StringBuilder("I took ")
			.append(formatDecimal(amount == null ? 0.0F : amount))
			.append(" damage");
		if (attackerName != null) {
			message.append(" from ").append(attackerName);
		}
		else if (damageTypeId != null) {
			message.append(" from ").append(damageTypeId);
		}
		if (resultingHealth != null) {
			message.append(" and dropped to ").append(formatDecimal(resultingHealth)).append(" health");
		}
		message.append('.');
		return ai.moeru.airicraft.agent.llm.PlannerTrigger.pending(
			PlannerTriggerType.DAMAGE,
			"self",
			message.toString(),
			event.tick(),
			event.timestampMs()
		);
	}

	private ai.moeru.airicraft.agent.llm.PlannerTrigger createTaskBlockedTrigger(SemanticEvent event) {
		Map<String, Object> payload = event.payload();
		String taskType = stringPayloadValue(payload, "taskType");
		String resourceKind = stringPayloadValue(payload, "resourceKind");
		String blockedReason = stringPayloadValue(payload, "blockedReason");
		Float collected = floatPayloadValue(payload, "collected");
		Float remaining = floatPayloadValue(payload, "remaining");
		if (taskType == null || blockedReason == null) {
			return null;
		}

		StringBuilder message = new StringBuilder("Task blocked: taskType=").append(taskType);
		if (resourceKind != null) {
			message.append(" resourceKind=").append(resourceKind);
		}
		message.append(" reason=").append(blockedReason);
		if (collected != null) {
			message.append(" collected=").append(formatDecimal(collected));
		}
		if (remaining != null) {
			message.append(" remaining=").append(formatDecimal(remaining));
		}
		message.append('.');
		return ai.moeru.airicraft.agent.llm.PlannerTrigger.pending(
			PlannerTriggerType.SYSTEM,
			"runtime",
			message.toString(),
			event.tick(),
			event.timestampMs()
		);
	}

	private void applyPlannerEventPolicyChanges(EventPolicyChanges changes) {
		if (changes == null) {
			return;
		}
		long timestampMs = System.currentTimeMillis();
		if (changes.clearAll()) {
			eventPolicyState.clear();
		}
		eventPolicyState.removeRuleIds(changes.removeRuleIds());
		for (EventPolicyRuleUpsert upsert : changes.upserts()) {
			applyPlannerEventPolicyUpsert(upsert, timestampMs);
		}
	}

	private void applyPlannerEventPolicyUpsert(EventPolicyRuleUpsert upsert, long timestampMs) {
		if (upsert == null) {
			return;
		}
		EventPolicyMatch match = upsert.match();
		String eventType = match == null ? null : match.eventType();
		EventPolicyEffect effect = EventPolicyEffect.parse(upsert.effect());
		String ruleId = normalizeRuleId(upsert.ruleId());
		if (ruleId == null) {
			ruleId = "planner-rule-" + timestampMs + "-" + eventPolicyState.activeRuleCount();
		}
		if (match == null || !match.isValid()) {
			recordPolicyRuleRejected(ruleId, eventType, upsert.effect(), "eventType is required");
			return;
		}
		EventRoutingProfile profile = EVENT_ROUTING_PROFILES.get(eventType);
		if (profile != null && profile.policyBypass()) {
			recordPolicyRuleRejected(ruleId, eventType, upsert.effect(), "event type bypasses planner-authored policy");
			return;
		}
		if (effect == null) {
			recordPolicyRuleRejected(ruleId, eventType, upsert.effect(), "effect must be allow, ignore, semantic_only, or trigger_only");
			return;
		}
		eventPolicyState.upsert(new EventPolicyRule(
			ruleId,
			effect,
			match,
			upsert.reason(),
			timestampMs,
			null,
			0L,
			"planner"
		));
	}

	private void recordPolicyRuleRejected(String ruleId, String eventType, String effect, String reason) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		if (ruleId != null) {
			payload.put("ruleId", ruleId);
		}
		if (eventType != null) {
			payload.put("eventType", eventType);
		}
		if (effect != null && !effect.isBlank()) {
			payload.put("effect", effect);
		}
		payload.put("reason", reason == null || reason.isBlank() ? "rule rejected" : reason);
		eventBuffer.append(tickCount, "policy.rule_rejected", payload);
	}

	private static String normalizeRuleId(String ruleId) {
		if (ruleId == null) {
			return null;
		}
		String trimmed = ruleId.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static Map<String, EventRoutingProfile> createEventRoutingProfiles() {
		LinkedHashMap<String, EventRoutingProfile> profiles = new LinkedHashMap<>();
		profiles.put("social.player_spoke", new EventRoutingProfile("social.player_spoke", false, PlannerTriggerType.CHAT, false));
		profiles.put("social.player_addressed_agent", new EventRoutingProfile("social.player_addressed_agent", false, PlannerTriggerType.CHAT, true));
		profiles.put("social.local_controller_spoke", new EventRoutingProfile("social.local_controller_spoke", false, PlannerTriggerType.CHAT, true));
		profiles.put("social.system_message", new EventRoutingProfile("social.system_message", false, PlannerTriggerType.SYSTEM, false));
		profiles.put("pickup.item_picked_up", new EventRoutingProfile("pickup.item_picked_up", true, PlannerTriggerType.PICKUP, false));
		profiles.put("crafting.item_crafted", new EventRoutingProfile("crafting.item_crafted", true, PlannerTriggerType.CRAFT, false));
		profiles.put("combat.damage_taken", new EventRoutingProfile("combat.damage_taken", true, PlannerTriggerType.DAMAGE, false));
		profiles.put("session.world_loaded", new EventRoutingProfile("session.world_loaded", true, null, false));
		profiles.put("session.world_unloaded", new EventRoutingProfile("session.world_unloaded", true, null, false));
		profiles.put("session.connection_lost", new EventRoutingProfile("session.connection_lost", true, null, false));
		profiles.put("session.lan_opened", new EventRoutingProfile("session.lan_opened", true, null, false));
		profiles.put("social.player_joined_game", new EventRoutingProfile("social.player_joined_game", true, null, false));
		profiles.put("social.player_left_game", new EventRoutingProfile("social.player_left_game", true, null, false));
		profiles.put("social.player_joined_nearby", new EventRoutingProfile("social.player_joined_nearby", true, null, false));
		profiles.put("social.player_left_nearby", new EventRoutingProfile("social.player_left_nearby", true, null, false));
		profiles.put("follow.target_acquired", new EventRoutingProfile("follow.target_acquired", true, null, false));
		profiles.put("follow.target_lost", new EventRoutingProfile("follow.target_lost", true, null, false));
		profiles.put("follow.stuck", new EventRoutingProfile("follow.stuck", true, null, false));
		profiles.put("planner.goal_set", new EventRoutingProfile("planner.goal_set", true, null, false));
		profiles.put("planner.goal_cleared", new EventRoutingProfile("planner.goal_cleared", true, null, false));
		profiles.put("planner.degraded_entered", new EventRoutingProfile("planner.degraded_entered", true, null, false));
		profiles.put("planner.degraded_cleared", new EventRoutingProfile("planner.degraded_cleared", true, null, false));
		profiles.put("planner.reset_requested", new EventRoutingProfile("planner.reset_requested", true, null, true));
		profiles.put("task.blocked", new EventRoutingProfile("task.blocked", true, PlannerTriggerType.SYSTEM, true));
		profiles.put("policy.event_intervened", EventRoutingProfile.rawOnly("policy.event_intervened"));
		profiles.put("policy.rule_rejected", EventRoutingProfile.rawOnly("policy.rule_rejected"));
		return Map.copyOf(profiles);
	}

	private boolean suppressPlannerTriggersForCollectResourceProgress() {
		ActiveJob current = activeJobRuntime.current();
		return current.type() == ActiveJobType.COLLECT_RESOURCE && !current.status().terminal();
	}

	private void recordTaskStateTransition(TaskExecutionSnapshot previous, TaskExecutionSnapshot current, boolean semanticTaskContext) {
		if (current == null || previous == null || current.state() == previous.state()) {
			return;
		}
		if (semanticTaskContext) {
			return;
		}

		java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
		if (current.activeGoal() != null) {
			payload.put("goalType", current.activeGoal().type().name());
		}
		if (current.processName() != null && !current.processName().isBlank()) {
			payload.put("process", current.processName());
		}

		if (current.state() == TaskExecutionState.RUNNING) {
			eventBuffer.append(tickCount, "task.started", payload);
			return;
		}
		if (current.state() == TaskExecutionState.PAUSED_BY_SESSION_GATE) {
			eventBuffer.append(tickCount, "task.paused_by_session_gate", payload);
		}
	}

	private void recordSemanticTaskTransition(TaskSnapshot previous, TaskSnapshot current) {
		if (previous == null || current == null || current.state() == previous.state() || !isSemanticTaskSnapshot(current)) {
			return;
		}

		java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
		if (current.spec() != null) {
			payload.put("taskType", current.spec().type().name());
			payload.put("resourceKind", current.spec().resourceKind().name());
			payload.put("quantity", current.spec().quantity());
		}
		if (current.mission() != null) {
			payload.put("missionId", current.mission().missionId());
			payload.put("missionType", current.mission().missionType().name());
		}
		if (current.activeStepId() != null) {
			payload.put("activeStepId", current.activeStepId());
		}
		if (current.activeStepKind() != null) {
			payload.put("activeStepKind", current.activeStepKind().name());
		}
		payload.put("state", current.state().name());
		payload.put("collected", current.progress().collected());
		payload.put("remaining", current.progress().remaining());
		if (current.state() == TaskState.WAITING_FOR_PICKUP) {
			String blockedReason = activeJobRuntime.current().blockedReason();
			if (blockedReason != null && !blockedReason.isBlank()) {
				payload.put("blockedReason", blockedReason);
			}
		}
		if (current.source() != null && !current.source().isBlank()) {
			payload.put("source", current.source());
		}
		if (current.lastFailure() != null && !current.lastFailure().isBlank()) {
			payload.put("failure", current.lastFailure());
		}

		String eventType = switch (current.state()) {
			case RUNNING -> "task.started";
			case WAITING_FOR_PICKUP -> "task.blocked";
			case PAUSED_BY_SESSION_GATE -> "task.paused_by_session_gate";
			case COMPLETED -> "task.completed";
			case FAILED -> "task.failed";
			case CANCELLED -> "task.cancelled";
			default -> null;
		};
		if (eventType != null) {
			eventBuffer.append(tickCount, eventType, payload);
		}

		if (
			current.state() == TaskState.PAUSED_BY_SESSION_GATE
				|| current.state() == TaskState.COMPLETED
				|| current.state() == TaskState.FAILED
				|| current.state() == TaskState.CANCELLED
		) {
			dialogueRuntime.onInternalTaskUpdate(
				"TASK UPDATE: state=" + current.state().name()
					+ " missionId=" + (current.mission() == null ? "" : current.mission().missionId())
					+ " missionType=" + (current.mission() == null ? "" : current.mission().missionType().name())
					+ " activeStepId=" + (current.activeStepId() == null ? "" : current.activeStepId())
					+ " activeStepKind=" + (current.activeStepKind() == null ? "" : current.activeStepKind().name())
					+ " taskType=" + (current.spec() == null ? "" : current.spec().type().name())
					+ " resourceKind=" + (current.spec() == null ? "" : current.spec().resourceKind().name())
					+ " collected=" + current.progress().collected()
					+ " remaining=" + current.progress().remaining()
					+ " failure=" + (current.lastFailure() == null ? "" : current.lastFailure()),
				tickCount,
				sessionSnapshot,
				activeGoal(),
				current,
				missionExecutionSnapshot,
				eventBuffer
			);
		}
	}

	private static boolean hasSemanticTaskContext(TaskSnapshot previous, TaskSnapshot current) {
		return (previous != null && isSemanticTaskSnapshot(previous) && isActiveSemanticTaskState(previous.state()))
			|| (current != null && isSemanticTaskSnapshot(current) && isActiveSemanticTaskState(current.state()));
	}

	private static boolean isDirectGoalIntent(DialogueIntent intent) {
		if (intent == null || intent.type() == null) {
			return false;
		}
		if (intent.type() == DialogueIntentType.SET_GOAL) {
			return true;
		}
		if (intent.type() != DialogueIntentType.JOB_UPDATE || intent.activeJob() == null) {
			return false;
		}
		return switch (intent.activeJob().type()) {
			case FOLLOW_PLAYER, NAVIGATE_TO, MINE_BLOCKS -> true;
			case IDLE, COLLECT_RESOURCE, CRAFT_RECIPE, DROP_ITEMS, ATTACK_ENTITY, USE_ENTITY, ASK_USER -> false;
		};
	}

	private static boolean isSemanticTaskSnapshot(TaskSnapshot snapshot) {
		if (snapshot == null) {
			return false;
		}
		if (snapshot.spec() != null) {
			return true;
		}
		return snapshot.activeStepKind() == ai.moeru.airicraft.agent.tasks.LedgerStepKind.COLLECT_RESOURCE
			|| snapshot.activeStepKind() == ai.moeru.airicraft.agent.tasks.LedgerStepKind.CRAFT_RECIPE
			|| snapshot.activeStepKind() == ai.moeru.airicraft.agent.tasks.LedgerStepKind.DROP_ITEMS
			|| snapshot.activeStepKind() == ai.moeru.airicraft.agent.tasks.LedgerStepKind.ATTACK_ENTITY
			|| snapshot.activeStepKind() == ai.moeru.airicraft.agent.tasks.LedgerStepKind.USE_ENTITY
			|| snapshot.activeStepKind() == ai.moeru.airicraft.agent.tasks.LedgerStepKind.ASK_USER;
	}

	private static boolean isActiveSemanticTaskState(TaskState state) {
		return state == TaskState.QUEUED
			|| state == TaskState.RUNNING
			|| state == TaskState.WAITING_FOR_PICKUP
			|| state == TaskState.PAUSED_BY_SESSION_GATE;
	}

	private void handleTerminalTaskEvent(TaskTerminalEvent event, boolean semanticTaskContext) {
		if (event == null || event.goal() == null || event.terminalState() == null) {
			return;
		}
		if (semanticTaskContext) {
			return;
		}

		String eventType = switch (event.terminalState()) {
			case COMPLETED -> "task.completed";
			case FAILED -> "task.failed";
			case CANCELLED -> "task.cancelled";
			default -> null;
		};
		if (eventType != null) {
			java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
			payload.put("taskId", event.taskId());
			payload.put("goalType", event.goal().type().name());
			payload.put("message", event.message() == null ? "" : event.message());
			if (event.terminationCause() != null) {
				payload.put("terminationCause", event.terminationCause().name());
			}
			if (event.goal().targetPlayer() != null && !event.goal().targetPlayer().isBlank()) {
				payload.put("targetPlayer", event.goal().targetPlayer());
			}
			eventBuffer.append(tickCount, eventType, payload);
		}

		if (event.terminalState() != TaskExecutionState.FAILED) {
				dialogueRuntime.onInternalTaskUpdate(
					"TASK UPDATE: state=" + event.terminalState().name()
						+ " taskId=" + event.taskId()
						+ " goalType=" + event.goal().type().name()
						+ " message=" + (event.message() == null ? "" : event.message()),
					tickCount,
					sessionSnapshot,
					activeGoal(),
					taskSnapshot,
					missionExecutionSnapshot,
					eventBuffer
				);
		}
	}

	private void registerDefaultScenarios() {
		verificationRunner.register(new SessionVerification(
			() -> sessionSnapshot.mode(),
			this::joinFirstWorld,
			this::leaveCurrentWorld,
			() -> eventBuffer.containsType("session.world_loaded"),
			() -> worldLoadTick >= 0L && tickCount - worldLoadTick >= 20L
		));
		verificationRunner.register(new SessionLanVerification(
			() -> sessionSnapshot.mode(),
			this::openLan,
			() -> sessionSnapshot.lanPort() > 0,
			() -> eventBuffer.containsType("session.lan_opened")
		));
		verificationRunner.register(new SocialChatIngestVerification(
			() -> sessionSnapshot.worldLoaded(),
			() -> nearbyPlayerTracker.injectPlayerNearby("Alice", new Vec3d(5.0D, 64.0D, 0.0D), tickCount, eventBuffer),
			() -> chatIngestService.injectMessage("Alice", "hello everyone", tickCount, nearbyPlayerTracker, primaryInteractionResolver, eventBuffer),
			() -> chatIngestService.injectMessage("Alice", "@agent follow me", tickCount, nearbyPlayerTracker, primaryInteractionResolver, eventBuffer),
			() -> eventBuffer.containsTypeForPlayer("social.player_joined_nearby", "Alice"),
			() -> eventBuffer.containsTypeForPlayer("social.player_spoke", "Alice"),
			() -> eventBuffer.containsTypeForPlayer("social.player_addressed_agent", "Alice"),
			() -> primaryInteractionResolver.current().map(PrimaryInteractionPlayer::name).filter("Alice"::equals).isPresent()
		));
		verificationRunner.register(new FollowSingleplayerLocalPauseVerification(
			() -> sessionSnapshot.mode() == SessionMode.SINGLEPLAYER_LOCAL,
			() -> injectMockPlannerResponse(new PlannerResponse(
				"I'll follow once LAN or multiplayer is active.",
				new PlannerIntent("set_goal", GoalType.FOLLOW_PLAYER, "PausedAlice")
			)),
			() -> nearbyPlayerTracker.injectPlayerNearby("PausedAlice", playerOffset(5.0D), tickCount, eventBuffer),
			() -> onChatReceived("PausedAlice", "@agent follow me"),
			() -> lastDialogueResponse().isPresent(),
			() -> lastDialogueResponse()
				.map(response -> response.intent().type() == DialogueIntentType.SET_GOAL && "PausedAlice".equals(response.intent().targetPlayer()))
				.orElse(false),
			() -> eventBuffer.containsTypeForPlayer("follow.target_acquired", "PausedAlice"),
			() -> activeGoal().map(goal -> goal.type() == GoalType.FOLLOW_PLAYER && "PausedAlice".equals(goal.targetPlayer())).orElse(false),
			() -> behaviorTreeSnapshot().activeNodePath().contains("ActuationBlockedBySession"),
			() -> !behaviorTreeSnapshot().movement().movingForward()
				&& !behaviorTreeSnapshot().movement().sprinting()
				&& !behaviorTreeSnapshot().movement().jumping()
		));
		verificationRunner.register(new ManualInputIdlePassthroughVerification(
			() -> sessionSnapshot.mode() == SessionMode.SINGLEPLAYER_LOCAL,
			() -> activeGoal().isEmpty(),
			() -> setForwardKeyPressed(true),
			this::isForwardKeyPressed,
			() -> setForwardKeyPressed(false)
		));
		verificationRunner.register(new FollowVerification(
			() -> sessionSnapshot.mode() == SessionMode.SINGLEPLAYER_LOCAL,
			() -> injectMockPlannerResponse(new PlannerResponse(
				"I'll follow once LAN is open.",
				new PlannerIntent("set_goal", GoalType.FOLLOW_PLAYER, "LanAlice")
			)),
			() -> nearbyPlayerTracker.injectPlayerNearby("LanAlice", playerOffset(5.0D), tickCount, eventBuffer),
			() -> onChatReceived("LanAlice", "@agent follow me"),
			() -> lastDialogueResponse().isPresent(),
			() -> lastDialogueResponse()
				.map(response -> response.intent().type() == DialogueIntentType.SET_GOAL && "LanAlice".equals(response.intent().targetPlayer()))
				.orElse(false),
			() -> eventBuffer.containsTypeForPlayer("follow.target_acquired", "LanAlice"),
			() -> activeGoal().map(goal -> goal.type() == GoalType.FOLLOW_PLAYER && "LanAlice".equals(goal.targetPlayer())).orElse(false),
			this::openLan,
			() -> sessionSnapshot.mode() == SessionMode.SINGLEPLAYER_LAN_HOST,
			() -> nearbyPlayerTracker.injectPlayerMove("LanAlice", playerOffset(20.0D), tickCount, eventBuffer),
			() -> behaviorTreeSnapshot().activeNodePath().stream().anyMatch(node -> node.contains("MoveCloser")),
			() -> nearbyPlayerTracker.injectPlayerDisconnect("LanAlice", tickCount, eventBuffer),
			() -> eventBuffer.containsTypeForPlayer("follow.target_lost", "LanAlice")
		));
		GoalPosition[] navigateTarget = new GoalPosition[1];
		long[] navigateTaskBaselineSeqNo = new long[1];
		verificationRunner.register(new NavigateVerification(
			() -> sessionSnapshot.mode() == SessionMode.SINGLEPLAYER_LOCAL,
			this::openLan,
			() -> sessionSnapshot.mode() == SessionMode.SINGLEPLAYER_LAN_HOST,
			() -> navigateTarget[0] = findNearbyNavigationTarget(),
			() -> navigateTaskBaselineSeqNo[0] = eventBuffer.latestSeqNo(),
			() -> injectGoalForTests(new GoalSnapshot(
				GoalType.NAVIGATE_TO,
				null,
				navigateTarget[0],
				null,
				tickCount,
				"verification"
			)),
			() -> activeGoal()
				.map(goal -> goal.type() == GoalType.NAVIGATE_TO && Objects.equals(goal.position(), navigateTarget[0]))
				.orElse(false),
			() -> taskExecutionSnapshot.state() == TaskExecutionState.RUNNING
				&& behaviorTreeSnapshot().activeNodePath().contains("NavigateToSubtree"),
			() -> eventBuffer.containsTypeSince(navigateTaskBaselineSeqNo[0], "task.completed")
				|| taskExecutionSnapshot.state() == TaskExecutionState.COMPLETED,
			() -> playerNear(navigateTarget[0], 1.75D)
		));
		verificationRunner.register(new MineBlocksVerification(
			() -> sessionSnapshot.worldLoaded(),
			() -> sessionSnapshot.companionActuationAllowed(),
			() -> injectGoalForTests(new GoalSnapshot(
				GoalType.MINE_BLOCKS,
				null,
				null,
				new GoalMineSpec(List.of("minecraft:oak_log"), 1),
				tickCount,
				"verification"
			)),
			() -> activeGoal()
				.map(goal -> goal.type() == GoalType.MINE_BLOCKS
					&& goal.mineSpec() != null
					&& List.of("minecraft:oak_log").equals(goal.mineSpec().blockIds()))
				.orElse(false),
			() -> taskExecutionSnapshot.state() == TaskExecutionState.RUNNING
		));
		verificationRunner.register(new DialogueVerification(
			() -> sessionSnapshot.worldLoaded(),
			() -> nearbyPlayerTracker.injectPlayerNearby("Alice", playerOffset(5.0D), tickCount, eventBuffer),
			() -> injectMockPlannerResponse(new PlannerResponse(
				"Sure, I'll follow you!",
				new PlannerIntent("set_goal", GoalType.FOLLOW_PLAYER, "Alice")
			)),
			() -> onChatReceived("Alice", "@agent follow me"),
			() -> lastDialogueResponse().isPresent(),
			() -> lastDialogueResponse().map(response -> response.text() != null && !response.text().isBlank()).orElse(false),
			() -> lastChatTick() > 0L,
			() -> activeGoal().map(goal -> goal.type() == GoalType.FOLLOW_PLAYER).orElse(false)
		));
		verificationRunner.register(new DialogueChatSanitizationVerification(
			() -> sessionSnapshot.worldLoaded(),
			() -> nearbyPlayerTracker.injectPlayerNearby("SanitizeAlice", playerOffset(5.0D), tickCount, eventBuffer),
			() -> lastChatTick(),
			this::lastChatText,
			() -> injectMockPlannerResponse(new PlannerResponse(
				"/follow me\n\n§a".repeat(40),
				new PlannerIntent("reply_only", null, null)
			)),
			() -> onChatReceived("SanitizeAlice", "@agent say something")
		));
		verificationRunner.register(new DialogueClearGoalVerification(
			() -> sessionSnapshot.worldLoaded(),
			() -> nearbyPlayerTracker.injectPlayerNearby("ClearGoalAlice", playerOffset(5.0D), tickCount, eventBuffer),
			() -> eventBuffer.latestSeqNo(),
			() -> injectMockPlannerResponse(new PlannerResponse(
				"Following ClearGoalAlice.",
				new PlannerIntent("set_goal", GoalType.FOLLOW_PLAYER, "ClearGoalAlice")
			)),
			() -> onChatReceived("ClearGoalAlice", "@agent follow me"),
			() -> activeGoal().map(goal -> goal.type() == GoalType.FOLLOW_PLAYER && "ClearGoalAlice".equals(goal.targetPlayer())).orElse(false),
			() -> injectMockPlannerResponse(new PlannerResponse(
				"Stopping.",
				new PlannerIntent("clear_goal", null, null)
			)),
			() -> onChatReceived("ClearGoalAlice", "@agent stop following"),
			() -> activeGoal().isEmpty(),
			() -> !behaviorTreeSnapshot().activeNodePath().contains("FollowPlayerSubtree"),
			sinceSeqNo -> eventBuffer.containsTypeSince(sinceSeqNo, "planner.goal_set"),
			sinceSeqNo -> eventBuffer.containsTypeSince(sinceSeqNo, "planner.goal_cleared")
		));
		verificationRunner.register(new SocialPrimaryInteractionTtlVerification(
			() -> sessionSnapshot.worldLoaded(),
			() -> nearbyPlayerTracker.injectPlayerNearby("TtlAlice", playerOffset(5.0D), tickCount, eventBuffer),
			() -> chatIngestService.injectMessage("TtlAlice", "hello", tickCount, nearbyPlayerTracker, primaryInteractionResolver, eventBuffer),
			() -> primaryInteractionResolver.current().map(PrimaryInteractionPlayer::name).filter("TtlAlice"::equals).isPresent(),
			() -> primaryInteractionResolver.current().isEmpty(),
			() -> nearbyPlayerTracker.injectPlayerNearby("TtlBob", playerOffset(6.0D), tickCount, eventBuffer),
			() -> chatIngestService.injectMessage("TtlBob", "hey there", tickCount, nearbyPlayerTracker, primaryInteractionResolver, eventBuffer),
			() -> primaryInteractionResolver.current().map(PrimaryInteractionPlayer::name).filter("TtlBob"::equals).isPresent()
		));
		verificationRunner.register(new DialogueProactiveSocialModeVerification(
			() -> sessionSnapshot.worldLoaded(),
			() -> nearbyPlayerTracker.injectPlayerNearby("ProactiveAlice", playerOffset(5.0D), tickCount, eventBuffer),
			enabled -> setProactiveSocialModeOverride(enabled),
			() -> lastDialogueResponse().map(DialogueResponse::tick).orElse(-1L),
			() -> tickCount,
			() -> onChatReceived("ProactiveAlice", "hello there"),
			() -> injectMockPlannerResponse(new PlannerResponse(
				"Hi ProactiveAlice.",
				new PlannerIntent("reply_only", null, null)
			))
		));
		verificationRunner.register(new LlmDegradationVerification(
			() -> sessionSnapshot.worldLoaded(),
			() -> injectPlannerTimeout(),
			() -> injectPlannerTimeout(),
			() -> injectPlannerTimeout(),
			() -> isDegraded(),
			() -> behaviorTreeSnapshot().activeNodePath() != null && !behaviorTreeSnapshot().activeNodePath().isEmpty(),
			() -> eventBuffer.containsType("planner.degraded_entered"),
			() -> lastChatTick() > 0L,
			() -> onChatReceived("Alice", "@agent reset"),
			() -> !isDegraded(),
			() -> eventBuffer.containsType("planner.degraded_cleared"),
			() -> eventBuffer.containsType("planner.reset_requested"),
			() -> "Planner state reset.".equals(lastChatText())
		));
		verificationRunner.register(new LlmDegradationGoalPreservedVerification(
			() -> sessionSnapshot.worldLoaded(),
			() -> nearbyPlayerTracker.injectPlayerNearby("DegradedAlice", playerOffset(5.0D), tickCount, eventBuffer),
			() -> injectMockPlannerResponse(new PlannerResponse(
				"Following DegradedAlice.",
				new PlannerIntent("set_goal", GoalType.FOLLOW_PLAYER, "DegradedAlice")
			)),
			() -> onChatReceived("DegradedAlice", "@agent follow me"),
			() -> activeGoal().map(goal -> goal.type() == GoalType.FOLLOW_PLAYER && "DegradedAlice".equals(goal.targetPlayer())).orElse(false),
			this::injectPlannerTimeout,
			this::injectPlannerTimeout,
			this::injectPlannerTimeout,
			this::isDegraded,
			() -> activeGoal().map(goal -> goal.type() == GoalType.FOLLOW_PLAYER && "DegradedAlice".equals(goal.targetPlayer())).orElse(false),
			() -> behaviorTreeSnapshot().activeNodePath() != null && !behaviorTreeSnapshot().activeNodePath().isEmpty(),
			() -> onChatReceived("DegradedAlice", "@agent reset"),
			() -> !isDegraded()
		));
		verificationRunner.register(new FollowReacquireTargetVerification(
			() -> sessionSnapshot.worldLoaded(),
			() -> nearbyPlayerTracker.injectPlayerNearby("ReacquireAlice", playerOffset(5.0D), tickCount, eventBuffer),
			() -> eventBuffer.latestSeqNo(),
			() -> injectMockPlannerResponse(new PlannerResponse(
				"Following ReacquireAlice.",
				new PlannerIntent("set_goal", GoalType.FOLLOW_PLAYER, "ReacquireAlice")
			)),
			() -> onChatReceived("ReacquireAlice", "@agent follow me"),
			sinceSeqNo -> eventBuffer.containsTypeForPlayerSince(sinceSeqNo, "follow.target_acquired", "ReacquireAlice"),
			() -> nearbyPlayerTracker.injectPlayerDisconnect("ReacquireAlice", tickCount, eventBuffer),
			sinceSeqNo -> eventBuffer.containsTypeForPlayerSince(sinceSeqNo, "follow.target_lost", "ReacquireAlice"),
			() -> activeGoal().isEmpty(),
			() -> nearbyPlayerTracker.injectPlayerNearby("ReacquireAlice", playerOffset(5.0D), tickCount, eventBuffer),
			() -> injectMockPlannerResponse(new PlannerResponse(
				"Following ReacquireAlice again.",
				new PlannerIntent("set_goal", GoalType.FOLLOW_PLAYER, "ReacquireAlice")
			)),
			() -> onChatReceived("ReacquireAlice", "@agent follow me"),
			sinceSeqNo -> eventBuffer.containsTypeForPlayerSince(sinceSeqNo, "follow.target_acquired", "ReacquireAlice"),
			() -> activeGoal().map(goal -> goal.type() == GoalType.FOLLOW_PLAYER && "ReacquireAlice".equals(goal.targetPlayer())).orElse(false)
		));
		verificationRunner.register(new DamageFallContextVerification(
			this::verificationAvailable,
			this::verificationPlayerProbe,
			() -> verificationSetGameMode("survival"),
			() -> verificationRunCommand("effect give @s resistance 10 3 true"),
			(x, y, z) -> verificationSetPlayerVelocity(x, y, z),
			this::latestEventSeqNo,
			sinceSeqNo -> recentEvents(sinceSeqNo),
			this::plannerContextExcerpt
		));
		verificationRunner.register(new PlannerObservabilityVerification(
			() -> sessionSnapshot.worldLoaded(),
			() -> nearbyPlayerTracker.injectPlayerNearby("ObserveAlice", playerOffset(5.0D), tickCount, eventBuffer),
			() -> eventBuffer.latestSeqNo(),
			() -> injectMockPlannerResponse(new PlannerResponse(
				"Hi ObserveAlice.",
				new PlannerIntent("reply_only", null, null)
			)),
			() -> onChatReceived("ObserveAlice", "@agent hi"),
			sinceSeqNo -> eventBuffer.containsTypeSince(sinceSeqNo, "planner.response_applied"),
			() -> lastDialogueResponse().map(response -> response.intent().type() == DialogueIntentType.REPLY_ONLY).orElse(false),
			() -> injectMockPlannerResponse(new PlannerResponse(
				"Following ObserveAlice.",
				new PlannerIntent("set_goal", GoalType.FOLLOW_PLAYER, "ObserveAlice")
			)),
			() -> onChatReceived("ObserveAlice", "@agent follow me"),
			sinceSeqNo -> eventBuffer.containsTypeSince(sinceSeqNo, "planner.goal_set"),
			() -> injectMockPlannerResponse(new PlannerResponse(
				"Stopping.",
				new PlannerIntent("clear_goal", null, null)
			)),
			() -> onChatReceived("ObserveAlice", "@agent stop"),
			sinceSeqNo -> eventBuffer.containsTypeSince(sinceSeqNo, "planner.goal_cleared")
		));
		verificationRunner.register(new EventPolicyIgnoreSystemVerification(
			() -> sessionSnapshot.worldLoaded(),
			() -> nearbyPlayerTracker.injectPlayerNearby("PolicyAlice", playerOffset(5.0D), tickCount, eventBuffer),
			() -> injectMockPlannerResponse(new PlannerResponse(
				"Okay, I'll ignore repeated system messages for now.",
				new PlannerIntent("reply_only", null, null),
				null,
				new EventPolicyChanges(
					false,
					List.of(),
					List.of(new EventPolicyRuleUpsert(
						"mute-system-server",
						"ignore",
						new EventPolicyMatch("social.system_message", null, "server", null, null, null, null),
						"Ignore repeated server system chatter for this session."
					))
				)
			)),
			() -> onChatReceived("PolicyAlice", "@agent ignore repeated server system messages"),
			this::activeEventPolicyRules,
			this::recentEventPolicyInterventions,
			() -> onSystemChatReceived("Policy harness system noise"),
			() -> injectMockPlannerResponse(new PlannerResponse(
				"Bypass chat still works.",
				new PlannerIntent("reply_only", null, null)
			)),
			() -> onChatReceived("PolicyAlice", "@agent say hi again"),
			this::latestEventSeqNo,
			sinceSeqNo -> eventBuffer.containsTypeSince(sinceSeqNo, "planner.response_applied"),
			sinceSeqNo -> eventBuffer.containsTypeSince(sinceSeqNo, "social.system_message"),
			sinceSeqNo -> eventBuffer.containsTypeSince(sinceSeqNo, "policy.event_intervened")
		));
	}

	private static String stringPayloadValue(Map<String, Object> payload, String key) {
		if (payload == null) {
			return null;
		}
		Object value = payload.get(key);
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value);
		return text.isBlank() ? null : text;
	}

	private static Float floatPayloadValue(Map<String, Object> payload, String key) {
		if (payload == null) {
			return null;
		}
		Object value = payload.get(key);
		if (value instanceof Number number) {
			return number.floatValue();
		}
		if (value == null) {
			return null;
		}
		try {
			return Float.parseFloat(String.valueOf(value));
		}
		catch (NumberFormatException ignored) {
			return null;
		}
	}

	private static String formatDecimal(float value) {
		if (Math.abs(value - Math.round(value)) < 0.001F) {
			return Integer.toString(Math.round(value));
		}
		String text = String.format(java.util.Locale.ROOT, "%.2f", value);
		int trimIndex = text.length();
		while (trimIndex > 0 && text.charAt(trimIndex - 1) == '0') {
			trimIndex--;
		}
		if (trimIndex > 0 && text.charAt(trimIndex - 1) == '.') {
			trimIndex--;
		}
		return text.substring(0, trimIndex);
	}

	private void ensureVerificationSessionAvailable() {
		if (sessionSnapshot.mode() != SessionMode.SINGLEPLAYER_LOCAL) {
			throw new BridgeUnavailableException("unsupported_session_state", "Verification actions require a singleplayer local world");
		}
		if (!sessionSnapshot.worldLoaded()) {
			throw new BridgeUnavailableException("verification_unavailable", "No singleplayer local world is loaded for verification");
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null || !client.isIntegratedServerRunning() || client.getServer() == null) {
			throw new BridgeUnavailableException("verification_unavailable", "Integrated singleplayer verification controls are unavailable");
		}
	}

	private void prepareClientForVerification() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			return;
		}
		if (client.options != null && client.options.pauseOnLostFocus) {
			client.options.pauseOnLostFocus = false;
			client.options.write();
		}
		if (client.currentScreen != null && "GameMenuScreen".equals(client.currentScreen.getClass().getSimpleName())) {
			client.setScreen(null);
		}
	}

	private <T> T onVerificationServer(BiFunction<IntegratedServer, ServerPlayerEntity, T> action) {
		ensureVerificationSessionAvailable();
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null) {
			throw new BridgeUnavailableException("verification_unavailable", "Local verification player is unavailable");
		}
		IntegratedServer server = client.getServer();
		if (server == null) {
			throw new BridgeUnavailableException("verification_unavailable", "Integrated server is unavailable");
		}
		UUID playerUuid = client.player.getUuid();
		CompletableFuture<T> future = new CompletableFuture<>();
		server.executeSync(() -> {
			try {
				ServerPlayerEntity serverPlayer = server.getPlayerManager().getPlayer(playerUuid);
				if (serverPlayer == null) {
					throw new BridgeUnavailableException("verification_unavailable", "Server-side verification player is unavailable");
				}
				future.complete(action.apply(server, serverPlayer));
			}
			catch (Throwable throwable) {
				future.completeExceptionally(throwable);
			}
		});

		try {
			return future.get(5L, TimeUnit.SECONDS);
		}
		catch (ExecutionException exception) {
			if (exception.getCause() instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw new IllegalStateException("Verification action failed on integrated server thread", exception.getCause());
		}
		catch (Exception exception) {
			throw new BridgeUnavailableException("verification_unavailable", "Timed out waiting for integrated server verification action");
		}
	}

	private static VerificationPlayerProbe verificationPlayerProbe(ServerPlayerEntity player) {
		return new VerificationPlayerProbe(
			player.getX(),
			player.getY(),
			player.getZ(),
			player.getHealth(),
			player.getMaxHealth(),
			player.getHungerManager().getFoodLevel(),
			player.getHungerManager().getSaturationLevel(),
			player.isOnGround(),
			player.fallDistance,
			player.getGameMode().asString(),
			player.getWorld().getRegistryKey().getValue().toString()
		);
	}

	private static GameMode verificationGameMode(String modeId) {
		if (modeId == null || modeId.isBlank()) {
			throw new BridgeUnavailableException("invalid_request", "mode must be survival, creative, or spectator");
		}
		GameMode mode = GameMode.byId(modeId.trim().toLowerCase(java.util.Locale.ROOT), null);
		if (mode != GameMode.SURVIVAL && mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR) {
			throw new BridgeUnavailableException("invalid_request", "mode must be survival, creative, or spectator");
		}
		return mode;
	}

	private static String normalizedVerificationCommand(String command) {
		if (command == null) {
			throw new BridgeUnavailableException("invalid_request", "Missing command");
		}
		String normalized = command.trim();
		if (normalized.startsWith("/")) {
			normalized = normalized.substring(1).trim();
		}
		if (normalized.isBlank()) {
			throw new BridgeUnavailableException("invalid_request", "Missing command");
		}
		return normalized;
	}

	private void joinFirstWorld() {
		List<Map<String, Object>> worlds = singleplayerWorldService.listWorlds();
		if (worlds.isEmpty()) {
			throw new IllegalStateException("No singleplayer worlds are available for session.basic");
		}

		Object worldId = worlds.get(0).get("worldId");
		if (!(worldId instanceof String worldIdValue) || worldIdValue.isBlank()) {
			throw new IllegalStateException("First singleplayer world is missing a valid worldId");
		}

		singleplayerWorldService.joinWorld(worldIdValue);
	}

	private void leaveCurrentWorld() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			throw new IllegalStateException("Minecraft client is not initialized");
		}
		if (client.world == null && client.player == null) {
			return;
		}

		client.disconnect(null, false);
	}

	private GoalPosition findNearbyNavigationTarget() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.world == null || client.player == null) {
			throw new IllegalStateException("Minecraft world is not loaded");
		}

		BlockPos origin = client.player.getBlockPos();
		for (int radius = 1; radius <= 8; radius++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
						continue;
					}
					GoalPosition candidate = findWalkableNavigationTargetInColumn(
						client,
						origin.getX() + dx,
						origin.getZ() + dz,
						origin.getY()
					);
					if (candidate != null) {
						return candidate;
					}
				}
			}
		}

		throw new IllegalStateException("No nearby walkable navigation target was found");
	}

	private GoalPosition findWalkableNavigationTargetInColumn(
		MinecraftClient client,
		int x,
		int z,
		int originY
	) {
		for (int y = originY + 1; y >= originY - 6; y--) {
			BlockPos candidate = new BlockPos(x, y, z);
			if (isWalkableNavigationTarget(client, candidate)) {
				return new GoalPosition(candidate.getX(), candidate.getY(), candidate.getZ(), true);
			}
		}
		return null;
	}

	private boolean isWalkableNavigationTarget(MinecraftClient client, BlockPos target) {
		if (client.world == null || client.player == null) {
			return false;
		}
		if (target.equals(client.player.getBlockPos())) {
			return false;
		}
		BlockPos below = target.down();
		BlockPos above = target.up();
		return client.world.isAir(target)
			&& client.world.isAir(above)
			&& client.world.getBlockState(below).isSideSolidFullSquare(client.world, below, Direction.UP);
	}

	private boolean playerNear(GoalPosition target, double maxDistance) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (target == null || client == null || client.player == null) {
			return false;
		}
		Vec3d center = new Vec3d(target.x() + 0.5D, target.y(), target.z() + 0.5D);
		return client.player.getPos().squaredDistanceTo(center) <= maxDistance * maxDistance;
	}

	private Vec3d playerOffset(double xOffset) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null) {
			return new Vec3d(xOffset, 64.0D, 0.0D);
		}
		return new Vec3d(
			client.player.getX() + xOffset,
			client.player.getY(),
			client.player.getZ()
		);
	}

	private void setForwardKeyPressed(boolean pressed) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.options == null) {
			throw new IllegalStateException("Minecraft client input is not initialized");
		}
		client.options.forwardKey.setPressed(pressed);
	}

	private boolean isForwardKeyPressed() {
		MinecraftClient client = MinecraftClient.getInstance();
		return client != null
			&& client.options != null
			&& client.options.forwardKey.isPressed();
	}

	private float resolveEffectiveHealthBefore(float observedHealthBefore, float healthAfter) {
		return effectiveHealthBefore(lastKnownPlayerHealth, observedHealthBefore, healthAfter);
	}

	private static Float currentPlayerHealth(MinecraftClient client) {
		if (client == null || client.player == null || !client.isOnThread()) {
			return null;
		}
		return client.player.getHealth();
	}

	static float effectiveHealthBefore(Float lastKnownPlayerHealth, float observedHealthBefore, float healthAfter) {
		if (lastKnownPlayerHealth != null
			&& Float.isFinite(lastKnownPlayerHealth.floatValue())
			&& lastKnownPlayerHealth.floatValue() > healthAfter
		) {
			return lastKnownPlayerHealth.floatValue();
		}
		return observedHealthBefore;
	}

	private static final class NoopWorldTaskExecutor implements WorldTaskExecutor {
		private static final NoopWorldTaskExecutor INSTANCE = new NoopWorldTaskExecutor();

		@Override
		public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
			return Optional.empty();
		}

		@Override
		public TaskExecutionSnapshot snapshot() {
			return TaskExecutionSnapshot.idle();
		}

		@Override
		public void onWorldLeave() {
		}

		@Override
		public void shutdown() {
		}
	}
}
