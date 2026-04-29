package ai.moeru.airicraft.agent.actions;

import java.util.Map;
import java.util.Objects;

public final class ActionsetDraftTrialRuntime {
	private final ActionsetAuthoringService authoringService;
	private final ActionGraphPrimitiveDispatcher primitiveDispatcher;

	private ActionsetTrialSpec spec;
	private ActionGraphExecutionRuntime executionRuntime;
	private ActionsetTrialState state = ActionsetTrialState.IDLE;
	private String contentHash = "";
	private String failureCode = "";
	private String message = "";
	private long timeoutTick = -1L;
	private ActionGraphExecutionSnapshot lastExecutionSnapshot = ActionGraphExecutionSnapshot.idle();

	public ActionsetDraftTrialRuntime(ActionsetAuthoringService authoringService, ActionGraphPrimitiveDispatcher primitiveDispatcher) {
		this.authoringService = Objects.requireNonNull(authoringService, "authoringService");
		this.primitiveDispatcher = Objects.requireNonNull(primitiveDispatcher, "primitiveDispatcher");
	}

	public synchronized ActionsetTrialSnapshot start(ActionsetTrialSpec trialSpec, ActionResolverContext context, long tick) {
		Objects.requireNonNull(trialSpec, "trialSpec");
		Objects.requireNonNull(context, "context");
		this.spec = trialSpec;
		this.contentHash = authoringService.draftSummary(trialSpec.draftId()).contentHash();
		this.failureCode = "";
		this.message = "";
		this.timeoutTick = tick + trialSpec.timeoutTicks();
		this.lastExecutionSnapshot = ActionGraphExecutionSnapshot.idle();
		if (!trialSpec.allowWorldMutation() && authoringService.draftUsesForegroundPrimitive(trialSpec.draftId())) {
			return fail("world_mutation_denied", "draft uses foreground primitives; allowWorldMutation is required");
		}
		this.executionRuntime = new ActionGraphExecutionRuntime(authoringService.temporaryTrialIndex(trialSpec.draftId()), primitiveDispatcher, true);
		executionRuntime.submit(trialSpec.goal(), trialSpec.assumedInventory(), context, tick);
		state = ActionsetTrialState.RUNNING;
		return snapshot();
	}

	public synchronized ActionsetTrialSnapshot tick(ActionGraphExecutionInput input) {
		Objects.requireNonNull(input, "input");
		if (state != ActionsetTrialState.RUNNING || executionRuntime == null || spec == null) {
			return snapshot();
		}
		lastExecutionSnapshot = executionRuntime.tick(input);
		ActionGraphExecutionState executionState = lastExecutionSnapshot.state();
		if (executionState == ActionGraphExecutionState.SUCCEEDED) {
			state = ActionsetTrialState.PASSED;
			message = "trial_passed";
			authoringService.recordTrialReport(ActionsetTrialReport.passed(spec.draftId(), contentHash, lastExecutionSnapshot.toPayload(true)));
			return snapshot();
		}
		if (executionState == ActionGraphExecutionState.FAILED || executionState == ActionGraphExecutionState.BLOCKED || executionState == ActionGraphExecutionState.CANCELLED) {
			return fail(nonEmpty(lastExecutionSnapshot.failureCode(), executionState.name().toLowerCase()), lastExecutionSnapshot.message());
		}
		if (input.context().currentTick() >= timeoutTick) {
			executionRuntime.cancel("trial_timeout", input.context().currentTick());
			lastExecutionSnapshot = executionRuntime.snapshot();
			return fail("trial_timeout", "draft trial timed out");
		}
		return snapshot();
	}

	public synchronized ActionsetTrialSnapshot cancel(String reason, long tick) {
		if (executionRuntime != null) {
			executionRuntime.cancel(reason, tick);
			lastExecutionSnapshot = executionRuntime.snapshot();
		}
		state = ActionsetTrialState.CANCELLED;
		failureCode = "cancelled";
		message = reason == null || reason.isBlank() ? "cancelled" : reason;
		return snapshot();
	}

	public synchronized ActionsetTrialSnapshot snapshot() {
		boolean passed = state == ActionsetTrialState.PASSED;
		return new ActionsetTrialSnapshot(
			true,
			state,
			spec == null ? "" : spec.draftId(),
			contentHash,
			passed,
			failureCode,
			message,
			lastExecutionSnapshot
		);
	}

	public synchronized boolean active() {
		return state == ActionsetTrialState.RUNNING;
	}

	private ActionsetTrialSnapshot fail(String code, String failureMessage) {
		state = ActionsetTrialState.FAILED;
		failureCode = nonEmpty(code, "trial_failed");
		message = nonEmpty(failureMessage, failureCode);
		if (spec != null) {
			authoringService.recordTrialReport(ActionsetTrialReport.failed(
				spec.draftId(),
				contentHash,
				failureCode,
				message,
				lastExecutionSnapshot == null ? Map.of() : lastExecutionSnapshot.toPayload(true)
			));
		}
		return snapshot();
	}

	private static String nonEmpty(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}
}
