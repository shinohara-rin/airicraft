package ai.moeru.airicraft.agent.evaluation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ScenarioEvaluationRunner {
	private EvaluationScenario scenario;
	private EvaluationStatus status = EvaluationStatus.IDLE;
	private String message;
	private long startTick;
	private long startMillis;
	private long finishTick = Long.MIN_VALUE;
	private long lastTriggerTick;
	private int plannerTurns;
	private List<EvaluationCheckResult> latestCheckResults = List.of();
	private boolean evidenceReviewRequired;
	private String executionMode = "planner";
	private String goalExecutionId;

	public void start(EvaluationScenario scenario, long tick, long nowMs) {
		this.scenario = scenario;
		this.status = EvaluationStatus.PENDING_WORLD;
		this.message = "Waiting for evaluation world";
		this.startTick = tick;
		this.startMillis = nowMs;
		this.finishTick = Long.MIN_VALUE;
		this.lastTriggerTick = Long.MIN_VALUE;
		this.plannerTurns = 0;
		this.latestCheckResults = List.of();
		this.goalExecutionId = null;
		this.executionMode = "planner";
		this.evidenceReviewRequired = !scenario.hasDeterministicChecks();
	}

	public void onTick(Context context) {
		if (scenario == null || terminal(status)) {
			return;
		}
		executionMode = context.noLlmActive() ? "no_llm" : context.externalDriverActive() ? "external_driver" : "planner";
		if (!context.worldLoaded()) {
			status = EvaluationStatus.PENDING_WORLD;
			message = "Waiting for evaluation world";
			if (worldLoadBudgetExhausted(context)) {
				finish(EvaluationStatus.FAILED, "Evaluation world did not load before budget was exhausted", true, context.tick());
			}
			return;
		}
		if (!context.plannerConfigured() && !context.externalDriverActive() && !context.noLlmActive()) {
			finish(EvaluationStatus.FAILED, "Planner LLM is not configured", true, context.tick());
			return;
		}
		Optional<String> declaredFailure = context.declaredFailure();
		if (declaredFailure.isPresent()) {
			finish(EvaluationStatus.FAILED, declaredFailure.get(), true, context.tick());
			return;
		}

		if (status == EvaluationStatus.PENDING_WORLD) {
			status = EvaluationStatus.RUNNING;
			if (context.noLlmActive()) {
				if (scenario.goal() == null) {
					finish(EvaluationStatus.FAILED, "No-LLM mode requires a structured scenario goal", true, context.tick());
					return;
				}
				try {
					goalExecutionId = context.startGoal(scenario.goal());
				}
				catch (IllegalArgumentException | IllegalStateException exception) {
					finish(EvaluationStatus.FAILED, "No-LLM goal submission failed: " + exception.getMessage(), true, context.tick());
					return;
				}
				message = "Evaluation running without LLM";
			}
			else if (context.externalDriverActive()) {
				message = "Evaluation running under external driver";
			}
			else {
				message = "Evaluation running";
				context.emitInitialPrompt(scenario.prompt());
				plannerTurns++;
			}
			lastTriggerTick = context.tick();
		}

		latestCheckResults = evaluateChecks(context);
		if (checksPassed(latestCheckResults) && scenario.hasDeterministicChecks()) {
			finish(EvaluationStatus.PASSED, "Expected outcome reached", false, context.tick());
			return;
		}

		if (goalExecutionId != null) {
			Optional<String> failure = context.goalFailure(goalExecutionId);
			if (failure.isPresent()) {
				finish(EvaluationStatus.FAILED, failure.get(), true, context.tick());
				return;
			}
		}

		if (budgetExhausted(context)) {
			if (scenario.hasDeterministicChecks()) {
				finish(EvaluationStatus.FAILED, "Evaluation budget exhausted before expected outcome", true, context.tick());
			}
			else {
				finish(
					EvaluationStatus.NEEDS_REVIEW,
					"Evaluation budget exhausted; no deterministic expected outcome is configured",
					true,
					context.tick()
				);
			}
			return;
		}

		if (!context.externalDriverActive() && !context.noLlmActive()
			&& !context.plannerInFlight()
			&& context.tick() - lastTriggerTick >= scenario.budget().heartbeatIntervalTicks()) {
			context.emitHeartbeat(heartbeatMessage());
			plannerTurns++;
			lastTriggerTick = context.tick();
		}
	}

	public EvaluationReport report(long currentTick) {
		if (scenario == null) {
			return EvaluationReport.idle();
		}
		long effectiveTick = terminal() && finishTick != Long.MIN_VALUE ? finishTick : currentTick;
		return new EvaluationReport(
			status,
			scenario.id(),
			message,
			Math.max(0L, effectiveTick - startTick),
			plannerTurns,
			latestCheckResults,
			evidenceReviewRequired,
			diagnostics()
		);
	}

	public void reset() {
		scenario = null;
		status = EvaluationStatus.IDLE;
		message = null;
		startTick = 0L;
		startMillis = 0L;
		lastTriggerTick = Long.MIN_VALUE;
		finishTick = Long.MIN_VALUE;
		plannerTurns = 0;
		latestCheckResults = List.of();
		evidenceReviewRequired = false;
		goalExecutionId = null;
		executionMode = "planner";
	}

	public EvaluationScenario scenario() {
		return scenario;
	}

	public boolean terminal() {
		return terminal(status);
	}

	public void failSetup(String failureMessage, long tick) {
		if (scenario == null || terminal(status)) {
			return;
		}
		finish(EvaluationStatus.FAILED, failureMessage, true, tick);
	}

	private List<EvaluationCheckResult> evaluateChecks(Context context) {
		if (scenario.checks().isEmpty()) {
			return List.of();
		}
		ArrayList<EvaluationCheckResult> results = new ArrayList<>();
		for (EvaluationCheck check : scenario.checks()) {
			results.add(evaluateCheck(context, check));
		}
		return List.copyOf(results);
	}

	private EvaluationCheckResult evaluateCheck(Context context, EvaluationCheck check) {
		return switch (check.type()) {
			case "inventory_contains" -> {
				String itemId = check.string("itemId");
				int expected = check.integer("count", 1);
				int actual = context.inventoryCount(itemId);
				yield actual >= expected
					? EvaluationCheckResult.passed(check, "inventory contains " + actual + "x " + itemId)
					: EvaluationCheckResult.failed(check, "inventory contains " + actual + "x " + itemId + ", expected at least " + expected);
			}
			case "block_state" -> {
				String expectedBlockId = check.string("blockId");
				Map<String, String> expectedState = check.stringMap("state");
				int x = check.integer("x", 0);
				int y = check.integer("y", 0);
				int z = check.integer("z", 0);
				String actualBlockId = context.blockIdAt(x, y, z);
				if (expectedBlockId == null || !expectedBlockId.equals(actualBlockId)) {
					yield EvaluationCheckResult.failed(check, "block at " + x + "," + y + "," + z + " was " + actualBlockId + ", expected " + expectedBlockId);
				}
				Optional<String> stateMismatch = firstStateMismatch(expectedState, context.blockPropertiesAt(x, y, z));
				yield stateMismatch.isEmpty()
					? EvaluationCheckResult.passed(check, "block matched at " + x + "," + y + "," + z + stateDescription(expectedState))
					: EvaluationCheckResult.failed(check, "block at " + x + "," + y + "," + z + " matched " + expectedBlockId + " but " + stateMismatch.get());
			}
			case "block_count" -> {
				String expectedBlockId = check.string("blockId");
				if (expectedBlockId == null) {
					yield EvaluationCheckResult.failed(check, "block_count missing blockId");
				}
				BlockCountQuery query = blockCountQuery(context, check);
				if (query.errorMessage() != null) {
					yield EvaluationCheckResult.failed(check, query.errorMessage());
				}
				int expected = check.integer("count", 1);
				Map<String, String> expectedState = check.stringMap("state");
				int actual = countBlocks(context, query, expectedBlockId, expectedState);
				yield actual >= expected
					? EvaluationCheckResult.passed(check, "found " + actual + "x " + expectedBlockId + stateDescription(expectedState) + " in " + query.description())
					: EvaluationCheckResult.failed(check, "found " + actual + "x " + expectedBlockId + stateDescription(expectedState) + " in " + query.description() + ", expected at least " + expected);
			}
			case "event_contains" -> {
				String eventType = check.string("eventType");
				yield context.eventContains(eventType)
					? EvaluationCheckResult.passed(check, "event seen: " + eventType)
					: EvaluationCheckResult.failed(check, "event not seen: " + eventType);
			}
			case "last_chat_contains" -> {
				String text = check.string("text");
				String lastChat = context.lastChatText();
				yield text != null && lastChat != null && lastChat.contains(text)
					? EvaluationCheckResult.passed(check, "last chat contains expected text")
					: EvaluationCheckResult.failed(check, "last chat did not contain expected text");
			}
			case "task_state" -> {
				String expected = check.string("state");
				String actual = context.taskState();
				yield expected != null && expected.equalsIgnoreCase(actual)
					? EvaluationCheckResult.passed(check, "task state matched " + actual)
					: EvaluationCheckResult.failed(check, "task state was " + actual + ", expected " + expected);
			}
			case "task_execution_state" -> {
				String expected = check.string("state");
				String actual = context.taskExecutionState();
				yield expected != null && expected.equalsIgnoreCase(actual)
					? EvaluationCheckResult.passed(check, "task execution state matched " + actual)
					: EvaluationCheckResult.failed(check, "task execution state was " + actual + ", expected " + expected);
			}
			case "external_judge", "subjective" -> EvaluationCheckResult.failed(check, "external review required");
			default -> EvaluationCheckResult.failed(check, "unsupported check type: " + check.type());
		};
	}

	private boolean checksPassed(List<EvaluationCheckResult> results) {
		if (results.isEmpty()) {
			return false;
		}
		return results.stream().filter(result -> !"external_judge".equals(result.type()) && !"subjective".equals(result.type()))
			.allMatch(EvaluationCheckResult::passed);
	}

	private boolean budgetExhausted(Context context) {
		if ("planner".equals(executionMode) && plannerTurns >= scenario.budget().maxPlannerTurns()) {
			return true;
		}
		if (context.tick() - startTick >= scenario.budget().maxElapsedTicks()) {
			return true;
		}
		return scenario.budget().maxElapsedMillis() > 0L
			&& context.nowMs() - startMillis >= scenario.budget().maxElapsedMillis();
	}

	private boolean worldLoadBudgetExhausted(Context context) {
		if (context.tick() - startTick >= scenario.budget().maxElapsedTicks()) {
			return true;
		}
		return scenario.budget().maxElapsedMillis() > 0L
			&& context.nowMs() - startMillis >= scenario.budget().maxElapsedMillis();
	}

	private void finish(EvaluationStatus nextStatus, String nextMessage, boolean reviewRequired, long tick) {
		status = nextStatus;
		message = nextMessage;
		evidenceReviewRequired = reviewRequired;
		finishTick = tick;
	}

	private String heartbeatMessage() {
		return "EVALUATION HEARTBEAT: Continue working on scenario " + scenario.id()
			+ ". Stop only when the expected outcome is reached, the task is impossible, or you need to report a blocking failure.";
	}

	private static BlockCountQuery blockCountQuery(Context context, EvaluationCheck check) {
		String scope = check.string("scope");
		if (scope == null || "self".equals(scope)) {
			int horizontalRadius = clamp(check.integer("horizontalRadius", 8), 0, 16);
			int verticalRadius = clamp(check.integer("verticalRadius", 4), 0, 8);
			int x = context.playerBlockX();
			int y = context.playerBlockY();
			int z = context.playerBlockZ();
			return new BlockCountQuery(
				x - horizontalRadius,
				y - verticalRadius,
				z - horizontalRadius,
				x + horizontalRadius,
				y + verticalRadius,
				z + horizontalRadius,
				"self radius h=" + horizontalRadius + " v=" + verticalRadius,
				null
			);
		}
		if ("box".equals(scope)) {
			String missing = firstMissing(check, "x1", "y1", "z1", "x2", "y2", "z2");
			if (missing != null) {
				return BlockCountQuery.error("block_count box missing " + missing);
			}
			int x1 = check.integer("x1", 0);
			int y1 = check.integer("y1", 0);
			int z1 = check.integer("z1", 0);
			int x2 = check.integer("x2", 0);
			int y2 = check.integer("y2", 0);
			int z2 = check.integer("z2", 0);
			return new BlockCountQuery(
				Math.min(x1, x2),
				Math.min(y1, y2),
				Math.min(z1, z2),
				Math.max(x1, x2),
				Math.max(y1, y2),
				Math.max(z1, z2),
				"box " + x1 + "," + y1 + "," + z1 + " to " + x2 + "," + y2 + "," + z2,
				null
			);
		}
		return BlockCountQuery.error("unsupported block_count scope: " + scope);
	}

	private static int countBlocks(Context context, BlockCountQuery query, String blockId, Map<String, String> expectedState) {
		int count = 0;
		for (int y = query.y1(); y <= query.y2(); y++) {
			for (int z = query.z1(); z <= query.z2(); z++) {
				for (int x = query.x1(); x <= query.x2(); x++) {
					if (blockId.equals(context.blockIdAt(x, y, z)) && firstStateMismatch(expectedState, context.blockPropertiesAt(x, y, z)).isEmpty()) {
						count++;
					}
				}
			}
		}
		return count;
	}

	private static Optional<String> firstStateMismatch(Map<String, String> expectedState, Map<String, String> actualState) {
		if (expectedState == null || expectedState.isEmpty()) {
			return Optional.empty();
		}
		Map<String, String> safeActual = actualState == null ? Map.of() : actualState;
		for (Map.Entry<String, String> expected : expectedState.entrySet()) {
			String actual = safeActual.get(expected.getKey());
			if (!Objects.equals(expected.getValue(), actual)) {
				return Optional.of("state " + expected.getKey() + " was " + actual + ", expected " + expected.getValue());
			}
		}
		return Optional.empty();
	}

	private static String stateDescription(Map<String, String> expectedState) {
		return expectedState == null || expectedState.isEmpty() ? "" : " state=" + expectedState;
	}

	private static String firstMissing(EvaluationCheck check, String... keys) {
		for (String key : keys) {
			if (!check.fields().containsKey(key)) {
				return key;
			}
		}
		return null;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private Map<String, Object> diagnostics() {
		LinkedHashMap<String, Object> diagnostics = new LinkedHashMap<>();
		diagnostics.put("executionMode", executionMode);
		if (goalExecutionId != null) {
			diagnostics.put("goalExecutionId", goalExecutionId);
		}
		if (scenario != null) {
			diagnostics.put("maxPlannerTurns", scenario.budget().maxPlannerTurns());
			diagnostics.put("maxElapsedTicks", scenario.budget().maxElapsedTicks());
			diagnostics.put("heartbeatIntervalTicks", scenario.budget().heartbeatIntervalTicks());
			diagnostics.put("deterministicChecks", scenario.hasDeterministicChecks());
		}
		if (lastTriggerTick != Long.MIN_VALUE) {
			diagnostics.put("lastTriggerTick", lastTriggerTick);
		}
		return diagnostics;
	}

	private static boolean terminal(EvaluationStatus status) {
		return status == EvaluationStatus.PASSED || status == EvaluationStatus.FAILED || status == EvaluationStatus.NEEDS_REVIEW;
	}

	public interface Context {
		long tick();

		long nowMs();

		boolean worldLoaded();

		boolean plannerConfigured();

		boolean externalDriverActive();

		boolean noLlmActive();

		String startGoal(EvaluationGoal goal);

		Optional<String> goalFailure(String executionId);

		boolean plannerInFlight();

		Optional<String> declaredFailure();

		int inventoryCount(String itemId);

		String blockIdAt(int x, int y, int z);

		Map<String, String> blockPropertiesAt(int x, int y, int z);

		int playerBlockX();

		int playerBlockY();

		int playerBlockZ();

		boolean eventContains(String eventType);

		String lastChatText();

		String taskState();

		String taskExecutionState();

		void emitInitialPrompt(String prompt);

		void emitHeartbeat(String message);
	}

	private record BlockCountQuery(
		int x1,
		int y1,
		int z1,
		int x2,
		int y2,
		int z2,
		String description,
		String errorMessage
	) {
		private static final int MAX_VOLUME = 20_000;

		private BlockCountQuery {
			if (errorMessage == null && volume(x1, y1, z1, x2, y2, z2) > MAX_VOLUME) {
				errorMessage = "block_count query too large, maxVolume=" + MAX_VOLUME;
			}
		}

		private static BlockCountQuery error(String message) {
			return new BlockCountQuery(0, 0, 0, 0, 0, 0, "", message);
		}

		private static int volume(int x1, int y1, int z1, int x2, int y2, int z2) {
			return (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
		}
	}
}
