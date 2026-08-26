package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.session.SessionSnapshot;

import java.util.Objects;
import java.util.Optional;

/**
 * Keeps Baritone as the primary mining owner and performs a one-way handoff to
 * the local underwater harvester after a Baritone target-search failure or a
 * confirmed water stall. The local scan never preempts active Baritone work.
 * The external task remains a single task throughout the handoff.
 */
public final class HybridMiningTaskExecutor implements WorldTaskExecutor {
	private final WorldTaskExecutor baritoneExecutor;
	private final WorldTaskExecutor underwaterHarvestExecutor;
	private final UnderwaterSourceProbe underwaterSourceProbe;
	private final MiningProgressProbe miningProgressProbe;
	private final BaritoneReleaseProbe baritoneReleaseProbe;

	private Phase phase = Phase.BARITONE_PRIMARY;
	private WorldTaskRequest appliedTask;
	private WorldTaskRequest underwaterTask;
	private UnderwaterHarvestStepArgs underwaterStepArgs;
	private HybridMiningPolicy.PostReplanStall postReplanStall;
	private long baritonePrimaryActiveTicks;
	private long releaseActiveTicks;
	private boolean terminalEventEmitted;
	private boolean releaseQuarantined;
	private WorldTaskRequest failedTask;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public HybridMiningTaskExecutor(
		WorldTaskExecutor baritoneExecutor,
		WorldTaskExecutor underwaterHarvestExecutor,
		UnderwaterSourceProbe underwaterSourceProbe,
		MiningProgressProbe miningProgressProbe,
		BaritoneReleaseProbe baritoneReleaseProbe
	) {
		this.baritoneExecutor = Objects.requireNonNull(baritoneExecutor, "baritoneExecutor");
		this.underwaterHarvestExecutor = Objects.requireNonNull(underwaterHarvestExecutor, "underwaterHarvestExecutor");
		this.underwaterSourceProbe = Objects.requireNonNull(underwaterSourceProbe, "underwaterSourceProbe");
		this.miningProgressProbe = Objects.requireNonNull(miningProgressProbe, "miningProgressProbe");
		this.baritoneReleaseProbe = Objects.requireNonNull(baritoneReleaseProbe, "baritoneReleaseProbe");
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		Objects.requireNonNull(sessionSnapshot, "sessionSnapshot");
		Objects.requireNonNull(activeTask, "activeTask");
		if (releaseQuarantined) {
			return tickQuarantinedRelease(sessionSnapshot, activeTask);
		}
		if (phase == Phase.RELEASING_BARITONE
			&& (activeTask.isEmpty() || !sameExternalTask(activeTask.orElseThrow(), appliedTask))) {
			return tickDetachedRelease(sessionSnapshot, activeTask);
		}
		if (activeTask.isEmpty()) {
			clearActiveTask(sessionSnapshot);
			return Optional.empty();
		}

		WorldTaskRequest request = activeTask.orElseThrow();
		if (!sameExternalTask(request, appliedTask)) {
			if (appliedTask != null) {
				beginDetachedRelease(sessionSnapshot, request);
				return tickDetachedRelease(sessionSnapshot, activeTask);
			}
			startExternalTask(sessionSnapshot, request);
		}
		else {
			appliedTask = request;
			if (phase == Phase.UNDERWATER_HARVEST && request.type() == WorldTaskType.UNDERWATER_HARVEST) {
				underwaterTask = request;
				underwaterStepArgs = underwaterArgs(request);
			}
			else if (underwaterTask != null) {
				underwaterTask = toUnderwaterTask(request, underwaterStepArgs);
			}
		}

		return switch (phase) {
			case BARITONE_PRIMARY -> tickBaritonePrimary(sessionSnapshot, request);
			case RELEASING_BARITONE -> tickBaritoneRelease(sessionSnapshot, request);
			case UNDERWATER_HARVEST -> tickUnderwaterHarvest(sessionSnapshot, request);
			case TERMINAL -> Optional.empty();
		};
	}

	private Optional<TaskTerminalEvent> tickBaritonePrimary(SessionSnapshot sessionSnapshot, WorldTaskRequest request) {
		Optional<TaskTerminalEvent> terminal = baritoneExecutor.tick(sessionSnapshot, Optional.of(request));
		snapshot = baritoneExecutor.snapshot();

		if (!eligibleMiningRequest(request)) {
			postReplanStall = null;
			return emitOnce(terminal, request);
		}
		if (!sessionSnapshot.companionActuationAllowed()) {
			return emitOnce(terminal, request);
		}
		baritonePrimaryActiveTicks++;

		if (terminal.isPresent()) {
			TaskTerminalEvent event = terminal.orElseThrow();
			if (HybridMiningPolicy.shouldTryUnderwaterFallback(event.terminationCause())) {
				Optional<UnderwaterHarvestStepArgs> fallback = findUnderwaterFallback(request);
				if (fallback.isPresent()) {
					beginHandoff(sessionSnapshot, request, fallback.orElseThrow());
					return Optional.empty();
				}
			}
			return emitOnce(terminal, request);
		}

		String pathEvent = snapshot.lastPathEvent();
		if ("WATER_STALL_REPLAN".equalsIgnoreCase(pathEvent)) {
			postReplanStall = HybridMiningPolicy.startPostReplanStall(
				baritonePrimaryActiveTicks,
				observeProgress().orElse(null)
			).state();
			return Optional.empty();
		}
		if ("WATER_STALL_RECOVERED".equalsIgnoreCase(pathEvent)) {
			postReplanStall = null;
			return Optional.empty();
		}
		if (postReplanStall == null || !sessionSnapshot.companionActuationAllowed()) {
			return Optional.empty();
		}

		HybridMiningPolicy.StallUpdate update = HybridMiningPolicy.observePostReplanStall(
			postReplanStall,
			baritonePrimaryActiveTicks,
			observeProgress().orElse(null)
		);
		postReplanStall = update.state();
		if (!update.fallbackDue()) {
			return Optional.empty();
		}
		Optional<UnderwaterHarvestStepArgs> fallback = findUnderwaterFallback(request);
		if (fallback.isEmpty()) {
			return Optional.empty();
		}
		beginHandoff(sessionSnapshot, request, fallback.orElseThrow());
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> tickBaritoneRelease(SessionSnapshot sessionSnapshot, WorldTaskRequest request) {
		HybridMiningPolicy.ReleaseStatus status = observeReleaseStatus();
		if (!sessionSnapshot.companionActuationAllowed()) {
			snapshot = new TaskExecutionSnapshot(
				TaskExecutionState.PAUSED_BY_SESSION_GATE,
				request.taskId(),
				request.goal(),
				"HybridMining",
				"releasing_baritone_session_gate",
				null,
				null
			);
			return Optional.empty();
		}
		releaseActiveTicks++;
		HybridMiningPolicy.ReleaseDecision decision = HybridMiningPolicy.releaseDecision(
			releaseActiveTicks,
			status
		);
		if (decision == HybridMiningPolicy.ReleaseDecision.WAIT) {
			snapshot = handoffSnapshot(request, "releasing_baritone");
			return Optional.empty();
		}
		if (decision == HybridMiningPolicy.ReleaseDecision.FAIL_TIMEOUT) {
			return failHandoff(request);
		}

		phase = Phase.UNDERWATER_HARVEST;
		underwaterTask = toUnderwaterTask(request, underwaterStepArgs);
		return tickUnderwaterHarvest(sessionSnapshot, request);
	}

	private Optional<TaskTerminalEvent> tickDetachedRelease(
		SessionSnapshot sessionSnapshot,
		Optional<WorldTaskRequest> activeTask
	) {
		HybridMiningPolicy.ReleaseStatus status = observeReleaseStatus();
		WorldTaskRequest visibleTask = activeTask.orElse(appliedTask);
		if (!sessionSnapshot.companionActuationAllowed()) {
			snapshot = releaseSnapshot(visibleTask, TaskExecutionState.PAUSED_BY_SESSION_GATE, "releasing_baritone_session_gate");
			return Optional.empty();
		}
		releaseActiveTicks++;
		HybridMiningPolicy.ReleaseDecision decision = HybridMiningPolicy.releaseDecision(releaseActiveTicks, status);
		if (decision == HybridMiningPolicy.ReleaseDecision.WAIT) {
			snapshot = releaseSnapshot(visibleTask, TaskExecutionState.RUNNING, "waiting_for_previous_baritone_release");
			return Optional.empty();
		}
		if (decision == HybridMiningPolicy.ReleaseDecision.FAIL_TIMEOUT) {
			enterReleaseQuarantine(null);
			snapshot = releaseSnapshot(visibleTask, TaskExecutionState.RUNNING, "baritone_release_quarantined");
			return Optional.empty();
		}

		resetState();
		return activeTask.isEmpty() ? Optional.empty() : tick(sessionSnapshot, activeTask);
	}

	private Optional<TaskTerminalEvent> tickQuarantinedRelease(
		SessionSnapshot sessionSnapshot,
		Optional<WorldTaskRequest> activeTask
	) {
		HybridMiningPolicy.ReleaseStatus status = observeReleaseStatus();
		WorldTaskRequest visibleTask = activeTask.orElse(appliedTask);
		if (failedTask != null
			&& (activeTask.isEmpty() || sameExternalTask(activeTask.orElseThrow(), failedTask))) {
			if (status.ownershipReleased() && status.cancellationDrained()
				&& sessionSnapshot.companionActuationAllowed()) {
				baritoneReleaseProbe.resetRelease();
				releaseQuarantined = false;
			}
			return Optional.empty();
		}
		if (!status.ownershipReleased() || !status.cancellationDrained()
			|| !sessionSnapshot.companionActuationAllowed()) {
			snapshot = releaseSnapshot(
				visibleTask,
				sessionSnapshot.companionActuationAllowed()
					? TaskExecutionState.RUNNING
					: TaskExecutionState.PAUSED_BY_SESSION_GATE,
				"baritone_release_quarantined"
			);
			return Optional.empty();
		}

		resetState();
		return activeTask.isEmpty() ? Optional.empty() : tick(sessionSnapshot, activeTask);
	}

	private HybridMiningPolicy.ReleaseStatus observeReleaseStatus() {
		try {
			return Objects.requireNonNullElseGet(
				baritoneReleaseProbe.observeRelease(),
				HybridMiningPolicy.ReleaseStatus::waiting
			);
		}
		catch (RuntimeException ignored) {
			return HybridMiningPolicy.ReleaseStatus.waiting();
		}
	}

	private Optional<TaskTerminalEvent> tickUnderwaterHarvest(SessionSnapshot sessionSnapshot, WorldTaskRequest request) {
		underwaterTask = request.type() == WorldTaskType.UNDERWATER_HARVEST
			? request
			: toUnderwaterTask(request, underwaterStepArgs);
		Optional<TaskTerminalEvent> terminal = underwaterHarvestExecutor.tick(
			sessionSnapshot,
			Optional.of(underwaterTask)
		);
		snapshot = underwaterHarvestExecutor.snapshot();
		return emitOnce(terminal, request);
	}

	private void beginHandoff(
		SessionSnapshot sessionSnapshot,
		WorldTaskRequest request,
		UnderwaterHarvestStepArgs stepArgs
	) {
		underwaterStepArgs = stepArgs;
		underwaterTask = toUnderwaterTask(request, stepArgs);
		postReplanStall = null;
		phase = Phase.RELEASING_BARITONE;
		releaseActiveTicks = 0L;
		// This is the single cancellation request. The release probe must prove
		// that ownership is gone and the expected cancellation was drained
		// before the underwater child can observe the shared Baritone facade.
		baritoneReleaseProbe.beginRelease();
		baritoneExecutor.tick(sessionSnapshot, Optional.empty());
		snapshot = handoffSnapshot(request, "releasing_baritone");
	}

	private void beginDetachedRelease(SessionSnapshot sessionSnapshot, WorldTaskRequest replacement) {
		postReplanStall = null;
		phase = Phase.RELEASING_BARITONE;
		releaseActiveTicks = 0L;
		baritoneReleaseProbe.beginRelease();
		baritoneExecutor.tick(sessionSnapshot, Optional.empty());
		underwaterHarvestExecutor.tick(sessionSnapshot, Optional.empty());
		snapshot = releaseSnapshot(replacement, TaskExecutionState.RUNNING, "waiting_for_previous_baritone_release");
	}

	private Optional<UnderwaterHarvestStepArgs> findUnderwaterFallback(WorldTaskRequest request) {
		try {
			Optional<UnderwaterHarvestStepArgs> observed = underwaterSourceProbe.findFallback(request);
			return observed == null ? Optional.empty() : observed;
		}
		catch (RuntimeException ignored) {
			return Optional.empty();
		}
	}

	private Optional<HybridMiningPolicy.ProgressSample> observeProgress() {
		try {
			Optional<HybridMiningPolicy.ProgressSample> observed = miningProgressProbe.observeProgress();
			return observed == null ? Optional.empty() : observed;
		}
		catch (RuntimeException ignored) {
			return Optional.empty();
		}
	}

	private Optional<TaskTerminalEvent> emitOnce(Optional<TaskTerminalEvent> event, WorldTaskRequest request) {
		if (event.isEmpty() || terminalEventEmitted) {
			return Optional.empty();
		}
		TaskTerminalEvent childEvent = event.orElseThrow();
		terminalEventEmitted = true;
		phase = Phase.TERMINAL;
		return Optional.of(new TaskTerminalEvent(
			request.taskId(),
			request.goal(),
			childEvent.terminalState(),
			childEvent.message(),
			childEvent.terminationCause(),
			childEvent.failureCode()
		));
	}

	private Optional<TaskTerminalEvent> failHandoff(WorldTaskRequest request) {
		String message = "baritone_handoff_timeout";
		snapshot = new TaskExecutionSnapshot(
			TaskExecutionState.FAILED,
			request.taskId(),
			request.goal(),
			"HybridMining",
			message,
			null,
			null
		);
		terminalEventEmitted = true;
		phase = Phase.TERMINAL;
		enterReleaseQuarantine(request.taskId());
		return Optional.of(new TaskTerminalEvent(
			request.taskId(),
			request.goal(),
			TaskExecutionState.FAILED,
			message,
			null,
			TaskFailureCode.TRANSIENT
		));
	}

	private void startExternalTask(SessionSnapshot sessionSnapshot, WorldTaskRequest request) {
		if (appliedTask != null) {
			baritoneExecutor.tick(sessionSnapshot, Optional.empty());
			underwaterHarvestExecutor.tick(sessionSnapshot, Optional.empty());
		}
		resetState();
		appliedTask = request;
		if (request.type() == WorldTaskType.UNDERWATER_HARVEST) {
			phase = Phase.UNDERWATER_HARVEST;
			underwaterTask = request;
			underwaterStepArgs = underwaterArgs(request);
		}
		else if (eligibleMiningRequest(request)) {
			underwaterSourceProbe.beginTask(request);
		}
	}

	private void clearActiveTask(SessionSnapshot sessionSnapshot) {
		baritoneExecutor.tick(sessionSnapshot, Optional.empty());
		underwaterHarvestExecutor.tick(sessionSnapshot, Optional.empty());
		resetState();
	}

	private void resetState() {
		underwaterSourceProbe.clearTask();
		baritoneReleaseProbe.resetRelease();
		phase = Phase.BARITONE_PRIMARY;
		appliedTask = null;
		underwaterTask = null;
		underwaterStepArgs = null;
		postReplanStall = null;
		baritonePrimaryActiveTicks = 0L;
		releaseActiveTicks = 0L;
		terminalEventEmitted = false;
		releaseQuarantined = false;
		failedTask = null;
		snapshot = TaskExecutionSnapshot.idle();
	}

	private static boolean eligibleMiningRequest(WorldTaskRequest request) {
		return request.type() == WorldTaskType.MINE
			&& request.goal() != null
			&& request.goal().type() == GoalType.MINE_BLOCKS;
	}

	private static boolean sameExternalTask(WorldTaskRequest left, WorldTaskRequest right) {
		return left != null
			&& right != null
			&& left.type() == right.type()
			&& Objects.equals(left.taskId(), right.taskId())
			&& Objects.equals(left.sourceJobId(), right.sourceJobId())
			&& Objects.equals(left.goal(), right.goal())
			&& sameUnderwaterArgs(left, right);
	}

	private static WorldTaskRequest toUnderwaterTask(
		WorldTaskRequest request,
		UnderwaterHarvestStepArgs stepArgs
	) {
		return new WorldTaskRequest(
			request.taskId(),
			request.sourceJobId(),
			new WorldTaskRequest.UnderwaterHarvest(
				request.goal(),
				Objects.requireNonNull(stepArgs, "stepArgs"),
				mineGoalSatisfied(request)
			)
		);
	}

	private static UnderwaterHarvestStepArgs underwaterArgs(WorldTaskRequest request) {
		return ((WorldTaskRequest.UnderwaterHarvest) request.task()).args();
	}

	private static boolean sameUnderwaterArgs(WorldTaskRequest left, WorldTaskRequest right) {
		if (left.task() instanceof WorldTaskRequest.UnderwaterHarvest leftTask) {
			return right.task() instanceof WorldTaskRequest.UnderwaterHarvest rightTask
				&& Objects.equals(leftTask.args(), rightTask.args());
		}
		return !(right.task() instanceof WorldTaskRequest.UnderwaterHarvest);
	}

	private static boolean mineGoalSatisfied(WorldTaskRequest request) {
		return switch (request.task()) {
			case WorldTaskRequest.Mine task -> task.mineGoalSatisfied();
			case WorldTaskRequest.UnderwaterHarvest task -> task.mineGoalSatisfied();
			default -> false;
		};
	}

	private static TaskExecutionSnapshot handoffSnapshot(WorldTaskRequest request, String event) {
		return new TaskExecutionSnapshot(
			TaskExecutionState.RUNNING,
			request.taskId(),
			request.goal(),
			"HybridMining",
			event,
			null,
			null
		);
	}

	private static TaskExecutionSnapshot releaseSnapshot(
		WorldTaskRequest request,
		TaskExecutionState state,
		String event
	) {
		return request == null
			? TaskExecutionSnapshot.idle()
			: new TaskExecutionSnapshot(state, request.taskId(), request.goal(), "HybridMining", event, null, null);
	}

	private void enterReleaseQuarantine(String taskId) {
		releaseQuarantined = true;
		failedTask = taskId == null || appliedTask == null || !taskId.equals(appliedTask.taskId())
			? null
			: appliedTask;
		phase = Phase.TERMINAL;
	}

	@Override
	public TaskExecutionSnapshot snapshot() {
		return snapshot;
	}

	@Override
	public void onWorldLeave() {
		if (underwaterTask != null) {
			underwaterHarvestExecutor.onWorldLeave();
		}
		else {
			baritoneExecutor.onWorldLeave();
			underwaterHarvestExecutor.onWorldLeave();
		}
		resetState();
	}

	@Override
	public void shutdown() {
		if (underwaterTask != null) {
			underwaterHarvestExecutor.shutdown();
		}
		else {
			baritoneExecutor.shutdown();
			underwaterHarvestExecutor.shutdown();
		}
		resetState();
	}

	Phase phase() {
		return phase;
	}

	public enum Phase {
		BARITONE_PRIMARY,
		RELEASING_BARITONE,
		UNDERWATER_HARVEST,
		TERMINAL
	}

	@FunctionalInterface
	public interface UnderwaterSourceProbe {
		Optional<UnderwaterHarvestStepArgs> findFallback(WorldTaskRequest request);

		default void beginTask(WorldTaskRequest request) {
		}

		default void clearTask() {
		}
	}

	@FunctionalInterface
	public interface MiningProgressProbe {
		Optional<HybridMiningPolicy.ProgressSample> observeProgress();
	}

	@FunctionalInterface
	public interface BaritoneReleaseProbe {
		HybridMiningPolicy.ReleaseStatus observeRelease();

		default void beginRelease() {
		}

		default void resetRelease() {
		}

	}
}
