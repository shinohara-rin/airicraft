package ai.moeru.airicraft.agent.actions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ActionGraphExecutionSnapshot(
	boolean available,
	String executionId,
	ActionGraphExecutionState state,
	ActionGoal goal,
	ActionRoute route,
	int cursor,
	ActionPlanStep currentStep,
	int stepAttempt,
	int replanCount,
	int watchCount,
	String pendingWatch,
	String activeTaskId,
	String failureCode,
	String message,
	Map<String, Integer> factSourceCounts,
	List<ActionTraceEvent> trace,
	List<Map<String, Object>> recoveryHistory,
	Map<String, Object> dispatch,
	Map<String, Object> task,
	Map<String, Object> taskExecution
) {
	private static final int VERBOSE_TRACE_LIMIT = 40;
	private static final int VERBOSE_RECOVERY_LIMIT = 10;

	public ActionGraphExecutionSnapshot {
		executionId = executionId == null ? "" : executionId;
		state = state == null ? ActionGraphExecutionState.IDLE : state;
		route = route == null ? ActionRoute.empty() : route;
		cursor = Math.max(0, cursor);
		stepAttempt = Math.max(0, stepAttempt);
		replanCount = Math.max(0, replanCount);
		watchCount = Math.max(0, watchCount);
		pendingWatch = pendingWatch == null ? "" : pendingWatch;
		activeTaskId = activeTaskId == null ? "" : activeTaskId;
		failureCode = failureCode == null ? "" : failureCode;
		message = message == null ? "" : message;
		factSourceCounts = factSourceCounts == null || factSourceCounts.isEmpty()
			? Map.of()
			: Collections.unmodifiableMap(new LinkedHashMap<>(factSourceCounts));
		trace = trace == null ? List.of() : List.copyOf(trace);
		recoveryHistory = recoveryHistory == null ? List.of() : copyMaps(recoveryHistory);
		dispatch = dispatch == null || dispatch.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(dispatch));
		task = task == null || task.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(task));
		taskExecution = taskExecution == null || taskExecution.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(taskExecution));
	}

	public static ActionGraphExecutionSnapshot idle() {
		return new ActionGraphExecutionSnapshot(
			true,
			"",
			ActionGraphExecutionState.IDLE,
			null,
			ActionRoute.empty(),
			0,
			null,
			0,
			0,
			0,
			"",
			"",
			"",
			"",
			Map.of(),
			List.of(),
			List.of(),
			Map.of(),
			Map.of(),
			Map.of()
		);
	}

	public Map<String, Object> toPayload(boolean verbose) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("available", available);
		payload.put("executionId", executionId);
		payload.put("state", state.name());
		payload.put("resolved", route != null && (!route.steps().isEmpty() || isTerminalSuccess()));
		payload.put("accepted", isAccepted());
		payload.put("cursor", cursor);
		payload.put("stepAttempt", stepAttempt);
		payload.put("replanCount", replanCount);
		payload.put("watchCount", watchCount);
		payload.put("pendingWatch", pendingWatch);
		payload.put("activeTaskId", activeTaskId);
		payload.put("failureCode", failureCode);
		payload.put("message", message);
		if (goal != null) {
			payload.put("goal", goalPayload(goal));
		}
		payload.put("route", routePayload(route));
		payload.put("traceEventCount", trace.size());
		if (currentStep != null) {
			payload.put("currentStep", stepPayload(currentStep));
			payload.put("selectedStep", stepPayload(currentStep));
		}
		if (!dispatch.isEmpty()) {
			payload.put("dispatch", dispatch);
		}
		if (!task.isEmpty()) {
			payload.put("task", task);
		}
		if (!taskExecution.isEmpty()) {
			payload.put("taskExecution", taskExecution);
		}
		if (verbose) {
			payload.put("factSourceCounts", factSourceCounts);
			payload.put("traceOmitted", Math.max(0, trace.size() - VERBOSE_TRACE_LIMIT));
			payload.put("trace", trace.stream()
				.skip(Math.max(0, trace.size() - VERBOSE_TRACE_LIMIT))
				.map(ActionGraphExecutionSnapshot::tracePayload)
				.toList());
			payload.put("recoveryHistoryOmitted", Math.max(0, recoveryHistory.size() - VERBOSE_RECOVERY_LIMIT));
			payload.put("recoveryHistory", recoveryHistory.stream()
				.skip(Math.max(0, recoveryHistory.size() - VERBOSE_RECOVERY_LIMIT))
				.toList());
		}
		else {
			payload.put("trace", List.of());
		}
		return payload;
	}

	private boolean isAccepted() {
		return state == ActionGraphExecutionState.WAITING_PRIMITIVE
			|| state == ActionGraphExecutionState.WATCHING
			|| state == ActionGraphExecutionState.SUCCEEDED;
	}

	private boolean isTerminalSuccess() {
		return state == ActionGraphExecutionState.SUCCEEDED;
	}

	private static Map<String, Object> goalPayload(ActionGoal goal) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("fact", goal.factType().id());
		payload.putAll(goal.keys());
		payload.putAll(goal.minimums());
		return payload;
	}

	private static Map<String, Object> routePayload(ActionRoute route) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("cost", route.cost());
		payload.put("steps", route.steps().stream()
			.map(ActionGraphExecutionSnapshot::stepPayload)
			.toList());
		return payload;
	}

	private static Map<String, Object> stepPayload(ActionPlanStep step) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("kind", step.kind().name());
		payload.put("actionId", step.actionId());
		payload.put("alternativeId", step.alternativeId());
		payload.put("stepId", step.stepId());
		payload.put("targetId", step.targetId());
		payload.put("methodKey", Map.of(
			"providerId", step.methodKey().providerId().value(),
			"methodId", step.methodKey().methodId()
		));
		payload.put("args", step.args());
		return payload;
	}

	private static Map<String, Object> tracePayload(ActionTraceEvent event) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("eventType", event.eventType());
		payload.put("actionId", event.actionId());
		payload.put("alternativeId", event.alternativeId());
		payload.put("stepId", event.stepId());
		payload.put("payload", event.payload());
		return payload;
	}

	private static List<Map<String, Object>> copyMaps(List<Map<String, Object>> maps) {
		return maps.stream()
			.map(map -> map == null || map.isEmpty() ? Map.<String, Object>of() : Collections.unmodifiableMap(new LinkedHashMap<>(map)))
			.toList();
	}
}
