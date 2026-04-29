package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.agent.tasks.CraftingOpportunity;
import ai.moeru.airicraft.agent.tasks.TaskExecutionState;
import ai.moeru.airicraft.agent.tasks.TaskTerminalEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public final class ActionGraphExecutionRuntime {
	private static final int MAX_STEP_RETRIES = 2;
	private static final int MAX_REPLANS = 2;
	private static final int MAX_TICK_TRANSITIONS = 32;

	private final Supplier<ActionsetLoadResult> actionsetLoader;
	private final ActionGraphPrimitiveDispatcher primitiveDispatcher;
	private final boolean preferActionsetRoutes;

	private ActionGraphExecutionState state = ActionGraphExecutionState.IDLE;
	private String executionId = "";
	private ActionGoal goal;
	private ActionRoute route = ActionRoute.empty();
	private int cursor;
	private ActionPlanStep currentStep;
	private int stepAttempt;
	private int replanCount;
	private String activeTaskId = "";
	private String failureCode = "";
	private String message = "";
	private long startedTick = -1L;
	private ActionResolverContext lastContext;
	private final ActionFactStore facts = new ActionFactStore();
	private final List<ActionTraceEvent> trace = new ArrayList<>();
	private final List<Map<String, Object>> recoveryHistory = new ArrayList<>();
	private final Set<String> blockedAlternatives = new LinkedHashSet<>();
	private final Map<String, PendingWatch> watches = new LinkedHashMap<>();
	private final Map<ActionFactIdentity, FactTraceFingerprint> tracedFactObservations = new LinkedHashMap<>();
	private Map<String, Object> dispatchPayload = Map.of();
	private Map<String, Object> taskPayload = Map.of();
	private Map<String, Object> taskExecutionPayload = Map.of();
	private int observedInventoryFactCount;
	private int assumedInventoryFactCount;

	public ActionGraphExecutionRuntime(Path actionsetRoot, ActionGraphPrimitiveDispatcher primitiveDispatcher) {
		this(() -> ActionsetLibraryLoader.defaults().load(actionsetRoot == null ? ActionsetLibraryPaths.defaultRoot() : actionsetRoot), primitiveDispatcher, false);
	}

	public ActionGraphExecutionRuntime(ActionsetIndex index, ActionGraphPrimitiveDispatcher primitiveDispatcher) {
		this(index, primitiveDispatcher, false);
	}

	public ActionGraphExecutionRuntime(ActionsetIndex index, ActionGraphPrimitiveDispatcher primitiveDispatcher, boolean preferActionsetRoutes) {
		this(() -> new ActionsetLoadResult(index, List.of()), primitiveDispatcher, preferActionsetRoutes);
	}

	private ActionGraphExecutionRuntime(
		Supplier<ActionsetLoadResult> actionsetLoader,
		ActionGraphPrimitiveDispatcher primitiveDispatcher,
		boolean preferActionsetRoutes
	) {
		this.actionsetLoader = Objects.requireNonNull(actionsetLoader, "actionsetLoader");
		this.primitiveDispatcher = Objects.requireNonNull(primitiveDispatcher, "primitiveDispatcher");
		this.preferActionsetRoutes = preferActionsetRoutes;
	}

	public synchronized ActionGraphExecutionSnapshot submit(
		ActionGoal goal,
		Map<String, Integer> assumedInventory,
		ActionResolverContext context,
		long tick
	) {
		this.executionId = "action-graph-" + UUID.randomUUID();
		this.goal = Objects.requireNonNull(goal, "goal");
		this.route = ActionRoute.empty();
		this.cursor = 0;
		this.currentStep = null;
		this.stepAttempt = 0;
		this.replanCount = 0;
		this.activeTaskId = "";
		this.failureCode = "";
		this.message = "";
		this.startedTick = tick;
		this.lastContext = Objects.requireNonNull(context, "context");
		this.state = ActionGraphExecutionState.RESOLVING;
		this.facts.clear();
		this.trace.clear();
		this.recoveryHistory.clear();
		this.blockedAlternatives.clear();
		this.watches.clear();
		this.tracedFactObservations.clear();
		this.dispatchPayload = Map.of();
		this.taskPayload = Map.of();
		this.taskExecutionPayload = Map.of();
		this.observedInventoryFactCount = 0;
		this.assumedInventoryFactCount = 0;
		addInventoryFacts(assumedInventory, ActionFactProvenance.EXECUTOR_REPORTED, context, false);
		trace("execution_started", "", "", "", Map.of("goal", goal.normalizedKey(), "executionId", executionId));
		return snapshot();
	}

	public synchronized ActionGraphExecutionSnapshot tick(ActionGraphExecutionInput input) {
		Objects.requireNonNull(input, "input");
		lastContext = input.context();
		ingestObservedFacts(input);
		if (state == ActionGraphExecutionState.IDLE
			|| state == ActionGraphExecutionState.SUCCEEDED
			|| state == ActionGraphExecutionState.FAILED
			|| state == ActionGraphExecutionState.CANCELLED) {
			return snapshot();
		}
		if (!input.worldLoaded()) {
			block("world_not_loaded", "World is not loaded");
			return snapshot();
		}
		if (state == ActionGraphExecutionState.BLOCKED && input.actuationAllowed()) {
			state = !activeTaskId.isBlank()
				? ActionGraphExecutionState.WAITING_PRIMITIVE
				: currentStep == null ? ActionGraphExecutionState.READY : ActionGraphExecutionState.DISPATCHING;
			trace("recovery_selected", actionId(currentStep), alternativeId(currentStep), stepId(currentStep), Map.of("decision", "resume_after_block"));
		}

		TaskTerminalEvent terminalEvent = input.terminalTaskEvent();
		if (terminalEvent != null && matchesActiveTask(terminalEvent)) {
			handleTerminalEvent(terminalEvent);
		}

		for (int transitions = 0; transitions < MAX_TICK_TRANSITIONS; transitions++) {
			if (terminal()) {
				return snapshot();
			}
			switch (state) {
				case RESOLVING, REPLANNING -> resolveRoute(input.context());
				case READY, OBSERVING -> advance(input);
				case DISPATCHING -> dispatchCurrentStep(input);
				case WATCHING -> {
					pollWatch(input);
					if (state == ActionGraphExecutionState.WATCHING) {
						return snapshot();
					}
				}
				case WAITING_PRIMITIVE -> {
					if (!input.actuationAllowed()) {
						block("session_gate", "Actuation is currently blocked by session gate");
					}
					return snapshot();
				}
				case BLOCKED -> {
					return snapshot();
				}
				case IDLE, SUCCEEDED, FAILED, CANCELLED -> {
					return snapshot();
				}
			}
		}
		fail("budget_exceeded", "Action graph tick transition budget exceeded");
		return snapshot();
	}

	public synchronized ActionGraphExecutionSnapshot cancel(String reason, long tick) {
		if (state == ActionGraphExecutionState.IDLE || terminal()) {
			return snapshot();
		}
		state = ActionGraphExecutionState.CANCELLED;
		message = reason == null || reason.isBlank() ? "cancelled" : reason;
		trace("execution_cancelled", actionId(currentStep), alternativeId(currentStep), stepId(currentStep), Map.of("tick", tick, "reason", message));
		return snapshot();
	}

	public synchronized void clear() {
		state = ActionGraphExecutionState.IDLE;
		executionId = "";
		goal = null;
		route = ActionRoute.empty();
		cursor = 0;
		currentStep = null;
		stepAttempt = 0;
		replanCount = 0;
		activeTaskId = "";
		failureCode = "";
		message = "";
		startedTick = -1L;
		lastContext = null;
		facts.clear();
		trace.clear();
		recoveryHistory.clear();
		blockedAlternatives.clear();
		watches.clear();
		tracedFactObservations.clear();
		dispatchPayload = Map.of();
		taskPayload = Map.of();
		taskExecutionPayload = Map.of();
		observedInventoryFactCount = 0;
		assumedInventoryFactCount = 0;
	}

	public synchronized ActionGraphExecutionSnapshot snapshot() {
		return new ActionGraphExecutionSnapshot(
			true,
			executionId,
			state,
			goal,
			route,
			cursor,
			currentStep,
			stepAttempt,
			replanCount,
			watches.size(),
			watches.keySet().stream().findFirst().orElse(""),
			activeTaskId,
			failureCode,
			message,
			Map.of(
				"observedInventory", observedInventoryFactCount,
				"assumedInventory", assumedInventoryFactCount,
				"knownFacts", facts.size()
			),
			trace,
			recoveryHistory,
			dispatchPayload,
			taskPayload,
			taskExecutionPayload
		);
	}

	public synchronized boolean active() {
		return state != ActionGraphExecutionState.IDLE && !terminal();
	}

	private void resolveRoute(ActionResolverContext context) {
		ActionsetLoadResult loadResult = actionsetLoader.get();
		if (!loadResult.valid()) {
			fail("actionset_validation_failed", "Actionset library validation failed");
			return;
		}
		ActionResolveResult result = new ActionResolver(loadResult.index(), facts, context, 8, blockedAlternatives, preferActionsetRoutes).resolve(goal);
		trace.addAll(result.trace());
		if (!result.resolved()) {
			fail(result.failureCode(), result.message());
			return;
		}
		route = result.route();
		cursor = 0;
		currentStep = null;
		stepAttempt = 0;
		activeTaskId = "";
		watches.clear();
		state = ActionGraphExecutionState.READY;
		trace("route_started", "", "", "", Map.of(
			"executionId", executionId,
			"cost", route.cost(),
			"stepCount", route.steps().size(),
			"replanCount", replanCount
		));
	}

	private void advance(ActionGraphExecutionInput input) {
		if (goalSatisfied(input.context())) {
			trace("step_skipped", "", "", "", Map.of("reason", "goal_already_satisfied", "cursor", cursor));
			succeed("goal_satisfied");
			return;
		}
		if (cursor >= route.steps().size()) {
			if (replanCount < MAX_REPLANS) {
				replanCount++;
				state = ActionGraphExecutionState.REPLANNING;
				trace("route_replanned", "", "", "", Map.of("reason", "terminal_goal_not_satisfied", "replanCount", replanCount));
				return;
			}
			fail("goal_not_satisfied", "Route completed but terminal goal was not observed");
			return;
		}
		currentStep = route.steps().get(cursor);
		trace("step_selected", currentStep.actionId(), currentStep.alternativeId(), currentStep.stepId(), Map.of(
			"kind", currentStep.kind().name(),
			"targetId", currentStep.targetId(),
			"cursor", cursor
		));
		if (currentStep.kind() == ActionStepKind.PRIMITIVE) {
			state = ActionGraphExecutionState.DISPATCHING;
			return;
		}
		if (currentStep.kind() == ActionStepKind.WATCH) {
			registerWatch(input);
			return;
		}
		fail("unsupported_step_kind", "Action graph execution cannot run step kind " + currentStep.kind().name());
	}

	private void dispatchCurrentStep(ActionGraphExecutionInput input) {
		if (!input.actuationAllowed()) {
			block("session_gate", "Actuation is currently blocked by session gate");
			return;
		}
		ActionGraphPrimitiveDispatchResult result = primitiveDispatcher.dispatch(currentStep);
		if (!result.accepted()) {
			stepAttempt++;
			handleStepFailure(nonEmpty(result.failureCode(), "dispatch_failed"), result.message(), false);
			return;
		}
		stepAttempt++;
		activeTaskId = result.taskId();
		dispatchPayload = result.payload();
		taskPayload = result.task();
		taskExecutionPayload = result.taskExecution().isEmpty()
			? Map.of("taskId", activeTaskId, "state", TaskExecutionState.RUNNING.name())
			: result.taskExecution();
		state = ActionGraphExecutionState.WAITING_PRIMITIVE;
		trace("primitive_dispatched", currentStep.actionId(), currentStep.alternativeId(), currentStep.stepId(), Map.of(
			"taskId", activeTaskId,
			"attempt", stepAttempt,
			"payload", result.payload()
		));
	}

	private void handleTerminalEvent(TaskTerminalEvent event) {
		taskExecutionPayload = Map.of(
			"taskId", nonEmpty(event.taskId(), activeTaskId),
			"state", event.terminalState().name(),
			"message", nonEmpty(event.message(), "")
		);
		trace("primitive_terminal", actionId(currentStep), alternativeId(currentStep), stepId(currentStep), Map.of(
			"taskId", nonEmpty(event.taskId(), activeTaskId),
			"state", event.terminalState().name(),
			"message", nonEmpty(event.message(), "")
		));
		if (event.terminalState() == TaskExecutionState.COMPLETED) {
			trace("step_succeeded", actionId(currentStep), alternativeId(currentStep), stepId(currentStep), Map.of("attempt", stepAttempt));
			cursor++;
			activeTaskId = "";
			stepAttempt = 0;
			state = ActionGraphExecutionState.OBSERVING;
			return;
		}
		handleStepFailure(classifyFailure(nonEmpty(event.message(), event.terminalState().name())), event.message(), true);
	}

	private void handleStepFailure(String rawFailureCode, String failureMessage, boolean fromTerminalEvent) {
		String classified = normalizeFailureCode(rawFailureCode, failureMessage);
		LinkedHashMap<String, Object> recovery = new LinkedHashMap<>();
		recovery.put("stepId", stepId(currentStep));
		recovery.put("targetId", currentStep == null ? "" : currentStep.targetId());
		recovery.put("failureCode", classified);
		recovery.put("message", nonEmpty(failureMessage, rawFailureCode));
		recovery.put("attempt", stepAttempt);
		recoveryHistory.add(recovery);
		trace("step_failed", actionId(currentStep), alternativeId(currentStep), stepId(currentStep), recovery);

		if ("transient".equals(classified) && stepAttempt <= MAX_STEP_RETRIES) {
			activeTaskId = "";
			state = ActionGraphExecutionState.DISPATCHING;
			trace("recovery_selected", actionId(currentStep), alternativeId(currentStep), stepId(currentStep), Map.of(
				"decision", "retry_step",
				"nextAttempt", stepAttempt + 1
			));
			return;
		}
		if (canReplan(classified)) {
			blockCurrentAlternative();
			activeTaskId = "";
			stepAttempt = 0;
			replanCount++;
			state = ActionGraphExecutionState.REPLANNING;
			trace("recovery_selected", actionId(currentStep), alternativeId(currentStep), stepId(currentStep), Map.of(
				"decision", "replan",
				"replanCount", replanCount
			));
			trace("route_replanned", actionId(currentStep), alternativeId(currentStep), stepId(currentStep), Map.of("failureCode", classified));
			return;
		}
		if (fromTerminalEvent && "transient".equals(classified)) {
			fail("budget_exceeded", "Step retry budget exceeded for " + stepId(currentStep));
			return;
		}
		fail(classified, nonEmpty(failureMessage, rawFailureCode));
	}

	private void registerWatch(ActionGraphExecutionInput input) {
		String watchId = executionId + ":" + currentStep.stepId();
		PendingWatch watch = new PendingWatch(watchId, currentStep, input.context().currentTick(), longArg(currentStep.args(), "timeoutTicks", 24000L));
		watches.put(currentStep.stepId(), watch);
		state = ActionGraphExecutionState.WATCHING;
		trace("watch_registered", currentStep.actionId(), currentStep.alternativeId(), currentStep.stepId(), Map.of(
			"watchId", watchId,
			"timeoutTick", watch.timeoutTick()
		));
		pollWatch(input);
	}

	private void pollWatch(ActionGraphExecutionInput input) {
		if (currentStep == null) {
			state = ActionGraphExecutionState.READY;
			return;
		}
		PendingWatch watch = watches.get(currentStep.stepId());
		if (watch == null) {
			state = ActionGraphExecutionState.READY;
			return;
		}
		if (goalSatisfied(input.context()) || factSpecSatisfied(currentStep.args(), input.context())) {
			watches.remove(currentStep.stepId());
			trace("watch_fulfilled", currentStep.actionId(), currentStep.alternativeId(), currentStep.stepId(), Map.of("watchId", watch.watchId()));
			cursor++;
			state = ActionGraphExecutionState.OBSERVING;
			return;
		}
		if (input.context().currentTick() >= watch.timeoutTick()) {
			watches.remove(currentStep.stepId());
			trace("watch_timed_out", currentStep.actionId(), currentStep.alternativeId(), currentStep.stepId(), Map.of("watchId", watch.watchId()));
			handleStepFailure("missing_fact", "watch timed out", false);
		}
	}

	private void block(String code, String blockMessage) {
		failureCode = code;
		message = blockMessage;
		state = ActionGraphExecutionState.BLOCKED;
		trace("execution_blocked", actionId(currentStep), alternativeId(currentStep), stepId(currentStep), Map.of(
			"failureCode", code,
			"message", blockMessage
		));
	}

	private void succeed(String successMessage) {
		state = ActionGraphExecutionState.SUCCEEDED;
		failureCode = "";
		message = successMessage;
		trace("execution_succeeded", "", "", "", Map.of("executionId", executionId, "message", successMessage));
	}

	private void fail(String code, String failureMessage) {
		state = ActionGraphExecutionState.FAILED;
		failureCode = nonEmpty(code, "failed");
		message = nonEmpty(failureMessage, failureCode);
		trace("execution_failed", actionId(currentStep), alternativeId(currentStep), stepId(currentStep), Map.of(
			"failureCode", failureCode,
			"message", message
		));
	}

	private boolean terminal() {
		return state == ActionGraphExecutionState.SUCCEEDED
			|| state == ActionGraphExecutionState.FAILED
			|| state == ActionGraphExecutionState.CANCELLED;
	}

	private boolean matchesActiveTask(TaskTerminalEvent event) {
		return event != null && !activeTaskId.isBlank() && Objects.equals(activeTaskId, event.taskId());
	}

	private boolean canReplan(String classifiedFailure) {
		if (replanCount >= MAX_REPLANS) {
			return false;
		}
		return "missing_fact".equals(classifiedFailure) || "environment_changed".equals(classifiedFailure);
	}

	private void blockCurrentAlternative() {
		if (currentStep != null && !currentStep.actionId().isBlank() && !currentStep.alternativeId().isBlank()) {
			blockedAlternatives.add(currentStep.actionId() + ":" + currentStep.alternativeId());
		}
	}

	private void ingestObservedFacts(ActionGraphExecutionInput input) {
		addInventoryFacts(input.observedInventory(), ActionFactProvenance.OBSERVED, input.context(), true);
		addCraftRecipeFacts(input.availableCrafts(), input.context());
	}

	private void addCraftRecipeFacts(List<CraftingOpportunity> availableCrafts, ActionResolverContext context) {
		if (availableCrafts == null || availableCrafts.isEmpty()) {
			return;
		}
		for (CraftingOpportunity opportunity : availableCrafts) {
			LinkedHashMap<String, Integer> inputCounts = new LinkedHashMap<>();
			for (String inputItemId : opportunity.inputItemIds()) {
				inputCounts.merge(inputItemId, 1, Integer::sum);
			}
			ActionFact fact = new ActionFact(
				ActionFactIdentity.craftRecipe(context.worldId(), context.actorId(), opportunity.recipeId()),
				Map.of(
					"outputItemId", opportunity.outputItemId(),
					"outputCount", opportunity.outputCount(),
					"inputItemIds", opportunity.inputItemIds(),
					"inputCounts", inputCounts,
					"gridKind", opportunity.gridKind().name()
				),
				ActionFactProvenance.OBSERVED,
				context.currentTick(),
				context.currentTick() + 1
			);
			facts.upsert(fact);
			traceFactObservedIfChanged(fact, Map.of(
				"fact", ActionFactType.CRAFT_RECIPE.id(),
				"recipeId", opportunity.recipeId(),
				"outputItemId", opportunity.outputItemId(),
				"provenance", ActionFactProvenance.OBSERVED.name()
			));
		}
	}

	private void addInventoryFacts(
		Map<String, Integer> inventory,
		ActionFactProvenance provenance,
		ActionResolverContext context,
		boolean observed
	) {
		if (inventory == null || inventory.isEmpty()) {
			return;
		}
		int count = 0;
		for (Map.Entry<String, Integer> entry : inventory.entrySet()) {
			if (entry.getKey() == null || entry.getKey().isBlank()) {
				continue;
			}
			int itemCount = entry.getValue() == null ? 0 : Math.max(0, entry.getValue());
			ActionFact fact = new ActionFact(
				ActionFactIdentity.inventoryItem(context.worldId(), context.actorId(), entry.getKey()),
				Map.of("count", itemCount),
				provenance,
				context.currentTick(),
				ActionFact.NEVER_STALE
			);
			facts.upsert(fact);
			count++;
			traceFactObservedIfChanged(fact, Map.of(
				"fact", ActionFactType.INVENTORY_ITEM.id(),
				"itemId", entry.getKey(),
				"count", itemCount,
				"provenance", provenance.name()
			));
		}
		if (observed) {
			observedInventoryFactCount = count;
		}
		else {
			assumedInventoryFactCount = count;
		}
	}

	private boolean goalSatisfied(ActionResolverContext context) {
		return facts.query(goal.factType(), goal.keys()).stream()
			.filter(fact -> fact.provenance().authoritative())
			.filter(fact -> !fact.isStaleAt(context.currentTick()))
			.anyMatch(fact -> minimumsSatisfied(goal.minimums(), fact.payload()));
	}

	private boolean factSpecSatisfied(Map<String, Object> factSpec, ActionResolverContext context) {
		ActionFactType type = ActionFactType.fromId(String.valueOf(factSpec.getOrDefault("fact", ""))).orElse(null);
		if (type == null) {
			return false;
		}
		LinkedHashMap<String, String> keys = new LinkedHashMap<>();
		for (String key : List.of("itemId", "toolTag", "cropId", "siteId", "entityId", "recipeId")) {
			Object value = factSpec.get(key);
			if (value != null && !String.valueOf(value).isBlank()) {
				keys.put(key, String.valueOf(value));
			}
		}
		LinkedHashMap<String, Integer> minimums = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : factSpec.entrySet()) {
			if (entry.getKey().endsWith("AtLeast") && entry.getValue() instanceof Number number) {
				minimums.put(entry.getKey(), number.intValue());
			}
		}
		return facts.query(type, keys).stream()
			.filter(fact -> fact.provenance().authoritative())
			.filter(fact -> !fact.isStaleAt(context.currentTick()))
			.anyMatch(fact -> minimumsSatisfied(minimums, fact.payload()));
	}

	private static boolean minimumsSatisfied(Map<String, Integer> minimums, Map<String, Object> payload) {
		for (Map.Entry<String, Integer> entry : minimums.entrySet()) {
			String payloadKey = payloadKeyForMinimum(entry.getKey());
			Object value = payload.get(payloadKey);
			if (!(value instanceof Number number) || number.intValue() < entry.getValue()) {
				return false;
			}
		}
		return true;
	}

	private static String payloadKeyForMinimum(String minimumKey) {
		if ("countAtLeast".equals(minimumKey)) {
			return "count";
		}
		if (minimumKey.endsWith("AtLeast")) {
			return minimumKey.substring(0, minimumKey.length() - "AtLeast".length());
		}
		return minimumKey;
	}

	private static String normalizeFailureCode(String rawFailureCode, String failureMessage) {
		String code = classifyFailure(nonEmpty(rawFailureCode, failureMessage));
		if ("invalid_step_args".equals(rawFailureCode) || "unsupported_primitive".equals(rawFailureCode) || "unsupported_step_kind".equals(rawFailureCode)) {
			return "invalid_action";
		}
		if ("recipe_not_found".equals(rawFailureCode) || "missing_fact".equals(rawFailureCode)) {
			return "missing_fact";
		}
		return code;
	}

	private static String classifyFailure(String raw) {
		String text = raw == null ? "" : raw.toLowerCase();
		if (text.contains("timeout") || text.contains("busy") || text.contains("temporary") || text.contains("path")) {
			return "transient";
		}
		if (text.contains("missing") || text.contains("not_found") || text.contains("not found") || text.contains("target")) {
			return "missing_fact";
		}
		if (text.contains("unloaded") || text.contains("changed") || text.contains("gone")) {
			return "environment_changed";
		}
		if (text.contains("unsupported") || text.contains("invalid")) {
			return "invalid_action";
		}
		if (text.contains("denied") || text.contains("destructive")) {
			return "destructive_denied";
		}
		return text.isBlank() ? "failed" : text;
	}

	private void trace(String eventType, String actionId, String alternativeId, String stepId, Map<String, Object> payload) {
		trace.add(new ActionTraceEvent(eventType, actionId, alternativeId, stepId, payload));
	}

	private void traceFactObservedIfChanged(ActionFact fact, Map<String, Object> payload) {
		FactTraceFingerprint fingerprint = new FactTraceFingerprint(fact.provenance(), fact.payload());
		if (fingerprint.equals(tracedFactObservations.get(fact.identity()))) {
			return;
		}
		tracedFactObservations.put(fact.identity(), fingerprint);
		trace("fact_observed", "", "", "", payload);
	}

	private static long longArg(Map<String, Object> args, String key, long defaultValue) {
		Object value = args.get(key);
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (value instanceof String text) {
			try {
				return Long.parseLong(text.trim());
			}
			catch (NumberFormatException ignored) {
				return defaultValue;
			}
		}
		return defaultValue;
	}

	private static String nonEmpty(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private static String actionId(ActionPlanStep step) {
		return step == null ? "" : step.actionId();
	}

	private static String alternativeId(ActionPlanStep step) {
		return step == null ? "" : step.alternativeId();
	}

	private static String stepId(ActionPlanStep step) {
		return step == null ? "" : step.stepId();
	}

	private record PendingWatch(String watchId, ActionPlanStep step, long startedTick, long timeoutTicks) {
		long timeoutTick() {
			return startedTick + Math.max(1L, timeoutTicks);
		}
	}

	private record FactTraceFingerprint(ActionFactProvenance provenance, Map<String, Object> payload) {
		FactTraceFingerprint {
			payload = payload == null || payload.isEmpty() ? Map.of() : Map.copyOf(payload);
		}
	}
}
