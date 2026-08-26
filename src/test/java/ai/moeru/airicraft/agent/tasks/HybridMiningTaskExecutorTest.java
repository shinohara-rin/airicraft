package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridMiningTaskExecutorTest {
	@Test
	void underwaterSourceNeverPreemptsActiveBaritoneMining() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor underwater = new RecordingExecutor();
		AtomicInteger sourceProbes = new AtomicInteger();
		HybridMiningTaskExecutor executor = executor(
			baritone,
			underwater,
			request -> {
				sourceProbes.incrementAndGet();
				return Optional.of(stepArgs());
			},
			Optional::empty,
			HybridMiningTaskExecutorTest::released
		);
		WorldTaskRequest request = request();

		for (int tick = 0; tick <= 60; tick++) {
			executor.tick(session(tick), Optional.of(request));
		}

		assertEquals(HybridMiningTaskExecutor.Phase.BARITONE_PRIMARY, executor.phase());
		assertEquals(61, baritone.activeCalls());
		assertEquals(0, baritone.emptyCalls());
		assertEquals(0, sourceProbes.get());
		assertEquals(0, underwater.activeCalls());
	}

	@Test
	void successfulBaritoneMiningNeverSwitchesWhenUnderwaterSourceExists() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor underwater = new RecordingExecutor();
		AtomicInteger sourceProbes = new AtomicInteger();
		HybridMiningTaskExecutor executor = executor(
			baritone,
			underwater,
			request -> {
				sourceProbes.incrementAndGet();
				return Optional.of(stepArgs());
			},
			Optional::empty,
			() -> released()
		);
		WorldTaskRequest request = request();

		executor.tick(session(0L), Optional.of(request));
		baritone.terminal = Optional.of(terminal(request, TaskExecutionState.COMPLETED, TaskTerminationCause.GOAL_REACHED));
		Optional<TaskTerminalEvent> completed = executor.tick(session(1L), Optional.of(request));

		assertTrue(completed.isPresent());
		assertEquals(TaskTerminationCause.GOAL_REACHED, completed.orElseThrow().terminationCause());
		assertEquals(0, sourceProbes.get());
		assertEquals(HybridMiningTaskExecutor.Phase.TERMINAL, executor.phase());
		assertEquals(0, underwater.activeCalls());
	}

	@Test
	void calculationFailureWithoutUnderwaterSourcePropagatesOriginalFailure() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor underwater = new RecordingExecutor();
		HybridMiningTaskExecutor executor = executor(
			baritone,
			underwater,
			request -> Optional.empty(),
			Optional::empty,
			() -> released()
		);
		WorldTaskRequest request = request();
		baritone.terminal = Optional.of(terminal(request, TaskExecutionState.FAILED, TaskTerminationCause.CALCULATION_FAILED));

		Optional<TaskTerminalEvent> failed = executor.tick(session(0L), Optional.of(request));

		assertTrue(failed.isPresent());
		assertEquals(TaskTerminationCause.CALCULATION_FAILED, failed.orElseThrow().terminationCause());
		assertEquals(0, underwater.activeCalls());
		assertEquals(0, baritone.emptyCalls());
	}

	@Test
	void calculationFailureHandsSameTaskToUnderwaterExecutorAfterRelease() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor underwater = new RecordingExecutor();
		AtomicReference<HybridMiningPolicy.ReleaseStatus> release = new AtomicReference<>(waiting());
		HybridMiningTaskExecutor executor = executor(
			baritone,
			underwater,
			request -> Optional.of(stepArgs()),
			Optional::empty,
			release::get
		);
		WorldTaskRequest request = request();
		baritone.terminal = Optional.of(terminal(request, TaskExecutionState.FAILED, TaskTerminationCause.CALCULATION_FAILED));

		assertTrue(executor.tick(session(5L), Optional.of(request)).isEmpty());
		assertEquals(HybridMiningTaskExecutor.Phase.RELEASING_BARITONE, executor.phase());
		assertEquals(1, baritone.emptyCalls());
		assertEquals(0, underwater.activeCalls());

		release.set(new HybridMiningPolicy.ReleaseStatus(true, false));
		assertTrue(executor.tick(session(6L), Optional.of(request)).isEmpty());
		assertEquals(0, underwater.activeCalls());

		release.set(released());
		assertTrue(executor.tick(session(7L), Optional.of(request)).isEmpty());
		WorldTaskRequest fallback = underwater.lastActive().orElseThrow();
		assertEquals(WorldTaskType.UNDERWATER_HARVEST, fallback.type());
		assertEquals(request.taskId(), fallback.taskId());
		assertEquals(request.sourceJobId(), fallback.sourceJobId());
		assertEquals(request.goal(), fallback.goal());
		assertEquals(stepArgs(), ((WorldTaskRequest.UnderwaterHarvest) fallback.task()).args());
		assertEquals(HybridMiningTaskExecutor.Phase.UNDERWATER_HARVEST, executor.phase());
		WorldTaskRequest progressed = request.withMineGoalSatisfied(true);
		executor.tick(session(8L), Optional.of(progressed));
		WorldTaskRequest.UnderwaterHarvest progressedFallback = (WorldTaskRequest.UnderwaterHarvest) underwater.lastActive().orElseThrow().task();
		assertEquals(stepArgs(), progressedFallback.args());
		assertTrue(progressedFallback.mineGoalSatisfied());

		underwater.terminal = Optional.of(new TaskTerminalEvent(
			request.taskId(),
			null,
			TaskExecutionState.COMPLETED,
			"underwater_harvest_succeeded",
			TaskTerminationCause.GOAL_REACHED
		));
		Optional<TaskTerminalEvent> completed = executor.tick(session(9L), Optional.of(progressed));
		assertTrue(completed.isPresent());
		assertEquals(request.goal(), completed.orElseThrow().goal());
		assertEquals(request.taskId(), completed.orElseThrow().taskId());

		underwater.terminal = Optional.of(new TaskTerminalEvent(
			request.taskId(),
			null,
			TaskExecutionState.COMPLETED,
			"duplicate",
			TaskTerminationCause.GOAL_REACHED
		));
		assertTrue(executor.tick(session(10L), Optional.of(progressed)).isEmpty());
	}

	@Test
	void baritoneCancellationHandsOffWhenUnderwaterSourceExists() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor underwater = new RecordingExecutor();
		AtomicInteger sourceProbes = new AtomicInteger();
		HybridMiningTaskExecutor executor = executor(
			baritone,
			underwater,
			request -> {
				sourceProbes.incrementAndGet();
				return Optional.of(stepArgs());
			},
			Optional::empty,
			() -> released()
		);
		WorldTaskRequest request = request();
		baritone.terminal = Optional.of(terminal(request, TaskExecutionState.CANCELLED, TaskTerminationCause.BARITONE_CANCELLED));

		Optional<TaskTerminalEvent> cancelled = executor.tick(session(0L), Optional.of(request));

		assertTrue(cancelled.isEmpty());
		assertEquals(HybridMiningTaskExecutor.Phase.RELEASING_BARITONE, executor.phase());
		assertEquals(1, sourceProbes.get());
		assertEquals(1, baritone.emptyCalls());
		assertEquals(0, underwater.activeCalls());
	}

	@Test
	void baritoneCancellationWithoutUnderwaterSourcePropagatesOriginalCancellation() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor underwater = new RecordingExecutor();
		AtomicInteger sourceProbes = new AtomicInteger();
		HybridMiningTaskExecutor executor = executor(
			baritone,
			underwater,
			request -> {
				sourceProbes.incrementAndGet();
				return Optional.empty();
			},
			Optional::empty,
			() -> released()
		);
		WorldTaskRequest request = request();
		baritone.terminal = Optional.of(terminal(request, TaskExecutionState.CANCELLED, TaskTerminationCause.BARITONE_CANCELLED));

		Optional<TaskTerminalEvent> cancelled = executor.tick(session(0L), Optional.of(request));

		assertTrue(cancelled.isPresent());
		assertEquals(TaskTerminationCause.BARITONE_CANCELLED, cancelled.orElseThrow().terminationCause());
		assertEquals(1, sourceProbes.get());
		assertEquals(0, baritone.emptyCalls());
		assertEquals(0, underwater.activeCalls());
	}

	@Test
	void secondSixtyTickWaterStallTriggersFallbackOnlyAfterProgressWindow() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor underwater = new RecordingExecutor();
		AtomicReference<Optional<HybridMiningPolicy.ProgressSample>> progress = new AtomicReference<>(Optional.of(sample(0.0D)));
		HybridMiningTaskExecutor executor = executor(
			baritone,
			underwater,
			request -> Optional.of(stepArgs()),
			progress::get,
			() -> waiting()
		);
		WorldTaskRequest request = request();
		baritone.snapshot = running(request, "WATER_STALL_REPLAN");

		executor.tick(session(10L), Optional.of(request));
		baritone.snapshot = running(request, null);
		for (int activeTick = 1; activeTick <= 30; activeTick++) {
			executor.tick(session(10L + activeTick), Optional.of(request));
		}
		assertEquals(HybridMiningTaskExecutor.Phase.BARITONE_PRIMARY, executor.phase());

		progress.set(Optional.of(sample(0.75D)));
		executor.tick(session(41L), Optional.of(request));
		for (int activeTick = 1; activeTick < 60; activeTick++) {
			executor.tick(session(41L + activeTick), Optional.of(request));
		}
		assertEquals(HybridMiningTaskExecutor.Phase.BARITONE_PRIMARY, executor.phase());

		executor.tick(session(101L), Optional.of(request));
		assertEquals(HybridMiningTaskExecutor.Phase.RELEASING_BARITONE, executor.phase());
		assertEquals(1, baritone.emptyCalls());
	}

	@Test
	void releaseMustDrainCancellationAndTimesOutOnceAtTwentyTicks() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor underwater = new RecordingExecutor();
		AtomicReference<HybridMiningPolicy.ReleaseStatus> release = new AtomicReference<>(new HybridMiningPolicy.ReleaseStatus(true, false));
		HybridMiningTaskExecutor executor = executor(
			baritone,
			underwater,
			request -> Optional.of(stepArgs()),
			Optional::empty,
			release::get
		);
		WorldTaskRequest request = request();
		baritone.terminal = Optional.of(terminal(request, TaskExecutionState.FAILED, TaskTerminationCause.CALCULATION_FAILED));
		executor.tick(session(100L), Optional.of(request));

		for (int activeTick = 1; activeTick < 20; activeTick++) {
			assertTrue(executor.tick(session(100L + activeTick), Optional.of(request)).isEmpty());
		}
		Optional<TaskTerminalEvent> timeout = executor.tick(session(120L), Optional.of(request));

		assertTrue(timeout.isPresent());
		assertEquals("baritone_handoff_timeout", timeout.orElseThrow().message());
		assertEquals(TaskExecutionState.FAILED, timeout.orElseThrow().terminalState());
		assertEquals(0, underwater.activeCalls());
		assertTrue(executor.tick(session(121L), Optional.of(request)).isEmpty());
		assertEquals(TaskExecutionState.FAILED, executor.snapshot().state());
		release.set(released());
		assertTrue(executor.tick(session(122L), Optional.of(request)).isEmpty());
		assertEquals(TaskExecutionState.FAILED, executor.snapshot().state());
		assertTrue(executor.tick(session(123L), Optional.of(request)).isEmpty());
	}

	@Test
	void sessionGatePausesReleaseTimeoutAndCannotStartFallback() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor underwater = new RecordingExecutor();
		AtomicReference<HybridMiningPolicy.ReleaseStatus> release = new AtomicReference<>(waiting());
		HybridMiningTaskExecutor executor = executor(
			baritone,
			underwater,
			request -> Optional.of(stepArgs()),
			Optional::empty,
			release::get
		);
		WorldTaskRequest request = request();
		baritone.terminal = Optional.of(terminal(request, TaskExecutionState.FAILED, TaskTerminationCause.CALCULATION_FAILED));
		executor.tick(session(0L), Optional.of(request));

		release.set(released());
		for (int tick = 1; tick <= 100; tick++) {
			assertTrue(executor.tick(gatedSession(tick), Optional.of(request)).isEmpty());
		}
		assertEquals(HybridMiningTaskExecutor.Phase.RELEASING_BARITONE, executor.phase());
		assertEquals(0, underwater.activeCalls());

		executor.tick(session(101L), Optional.of(request));
		assertEquals(HybridMiningTaskExecutor.Phase.UNDERWATER_HARVEST, executor.phase());
		assertEquals(1, underwater.activeCalls());
	}

	@Test
	void removedTimedOutTaskKeepsItsFailedSnapshotWhileReleaseIsQuarantined() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor underwater = new RecordingExecutor();
		HybridMiningTaskExecutor executor = executor(
			baritone,
			underwater,
			request -> Optional.of(stepArgs()),
			Optional::empty,
			HybridMiningTaskExecutorTest::waiting
		);
		WorldTaskRequest request = request();
		baritone.terminal = Optional.of(terminal(
			request,
			TaskExecutionState.FAILED,
			TaskTerminationCause.CALCULATION_FAILED
		));
		executor.tick(session(0L), Optional.of(request));
		for (int activeTick = 1; activeTick <= 20; activeTick++) {
			executor.tick(session(activeTick), Optional.of(request));
		}

		executor.tick(session(21L), Optional.empty());

		assertEquals(TaskExecutionState.FAILED, executor.snapshot().state());
		assertEquals("baritone_handoff_timeout", executor.snapshot().lastPathEvent());
	}

	@Test
	void sessionGatePausesThePostReplanMovementWindow() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor underwater = new RecordingExecutor();
		HybridMiningTaskExecutor executor = executor(
			baritone,
			underwater,
			request -> Optional.of(stepArgs()),
			() -> Optional.of(sample(0.0D)),
			HybridMiningTaskExecutorTest::waiting
		);
		WorldTaskRequest request = request();
		baritone.snapshot = running(request, "WATER_STALL_REPLAN");
		executor.tick(session(0L), Optional.of(request));
		baritone.snapshot = running(request, null);

		for (int tick = 1; tick <= 100; tick++) {
			executor.tick(gatedSession(tick), Optional.of(request));
		}
		for (int tick = 101; tick < 160; tick++) {
			executor.tick(session(tick), Optional.of(request));
		}
		assertEquals(HybridMiningTaskExecutor.Phase.BARITONE_PRIMARY, executor.phase());

		executor.tick(session(160L), Optional.of(request));
		assertEquals(HybridMiningTaskExecutor.Phase.RELEASING_BARITONE, executor.phase());
	}

	@Test
	void externalTaskRemovalDuringReleaseDoesNotStartFallback() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor underwater = new RecordingExecutor();
		HybridMiningTaskExecutor executor = executor(
			baritone,
			underwater,
			request -> Optional.of(stepArgs()),
			Optional::empty,
			() -> released()
		);
		WorldTaskRequest request = request();
		baritone.terminal = Optional.of(terminal(request, TaskExecutionState.FAILED, TaskTerminationCause.CALCULATION_FAILED));
		executor.tick(session(0L), Optional.of(request));

		assertTrue(executor.tick(session(1L), Optional.empty()).isEmpty());

		assertEquals(HybridMiningTaskExecutor.Phase.BARITONE_PRIMARY, executor.phase());
		assertEquals(0, underwater.activeCalls());
		assertEquals(TaskExecutionState.IDLE, executor.snapshot().state());
	}

	@Test
	void taskRemovalWaitsForThePreviousBaritoneOwnerToRelease() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor underwater = new RecordingExecutor();
		AtomicReference<HybridMiningPolicy.ReleaseStatus> release = new AtomicReference<>(waiting());
		HybridMiningTaskExecutor executor = executor(
			baritone,
			underwater,
			request -> Optional.of(stepArgs()),
			Optional::empty,
			release::get
		);
		WorldTaskRequest request = request();
		baritone.terminal = Optional.of(terminal(request, TaskExecutionState.FAILED, TaskTerminationCause.CALCULATION_FAILED));
		executor.tick(session(0L), Optional.of(request));

		executor.tick(session(1L), Optional.empty());
		assertEquals(HybridMiningTaskExecutor.Phase.RELEASING_BARITONE, executor.phase());
		release.set(released());
		executor.tick(session(2L), Optional.empty());

		assertEquals(HybridMiningTaskExecutor.Phase.BARITONE_PRIMARY, executor.phase());
		assertEquals(TaskExecutionState.IDLE, executor.snapshot().state());
		assertEquals(0, underwater.activeCalls());
	}

	@Test
	void taskReplacementDuringReleaseStartsTheReplacementWithBaritone() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor underwater = new RecordingExecutor();
		HybridMiningTaskExecutor executor = executor(
			baritone,
			underwater,
			request -> Optional.of(stepArgs()),
			Optional::empty,
			() -> released()
		);
		WorldTaskRequest original = request();
		baritone.terminal = Optional.of(terminal(original, TaskExecutionState.FAILED, TaskTerminationCause.CALCULATION_FAILED));
		executor.tick(session(0L), Optional.of(original));
		WorldTaskRequest replacement = WorldTaskRequest.collectMine("replacement-task", "replacement-job", original.goal());

		assertTrue(executor.tick(session(1L), Optional.of(replacement)).isEmpty());

		assertEquals(HybridMiningTaskExecutor.Phase.BARITONE_PRIMARY, executor.phase());
		assertEquals(replacement, baritone.lastActive().orElseThrow());
		assertEquals(0, underwater.activeCalls());
	}

	@Test
	void replacementCannotAcquireBaritoneBeforeThePreviousReleaseDrains() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor underwater = new RecordingExecutor();
		AtomicReference<HybridMiningPolicy.ReleaseStatus> release = new AtomicReference<>(waiting());
		HybridMiningTaskExecutor executor = executor(
			baritone,
			underwater,
			request -> Optional.of(stepArgs()),
			Optional::empty,
			release::get
		);
		WorldTaskRequest original = request();
		baritone.terminal = Optional.of(terminal(original, TaskExecutionState.FAILED, TaskTerminationCause.CALCULATION_FAILED));
		executor.tick(session(0L), Optional.of(original));
		WorldTaskRequest replacement = WorldTaskRequest.collectMine("replacement-task", "replacement-job", original.goal());

		executor.tick(session(1L), Optional.of(replacement));
		assertEquals(1, baritone.activeCalls());
		assertEquals(HybridMiningTaskExecutor.Phase.RELEASING_BARITONE, executor.phase());

		release.set(released());
		executor.tick(session(2L), Optional.of(replacement));
		assertEquals(replacement, baritone.lastActive().orElseThrow());
		assertEquals(2, baritone.activeCalls());
		assertEquals(HybridMiningTaskExecutor.Phase.BARITONE_PRIMARY, executor.phase());
	}

	@Test
	void explicitUnderwaterHarvestRoutesDirectlyWithoutTouchingBaritone() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor underwater = new RecordingExecutor();
		HybridMiningTaskExecutor executor = executor(
			baritone,
			underwater,
			request -> Optional.empty(),
			Optional::empty,
			HybridMiningTaskExecutorTest::released
		);
		WorldTaskRequest mine = request();
		WorldTaskRequest direct = WorldTaskRequest.underwaterHarvest(
			mine.taskId(),
			mine.sourceJobId(),
			mine.goal(),
			stepArgs()
		);

		executor.tick(session(0L), Optional.of(direct));

		assertEquals(HybridMiningTaskExecutor.Phase.UNDERWATER_HARVEST, executor.phase());
		assertEquals(direct, underwater.lastActive().orElseThrow());
		assertEquals(0, baritone.activeCalls());
	}

	@Test
	void explicitUnderwaterReplacementWaitsForThePreviousBaritoneOwner() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor underwater = new RecordingExecutor();
		AtomicReference<HybridMiningPolicy.ReleaseStatus> release = new AtomicReference<>(waiting());
		HybridMiningTaskExecutor executor = executor(
			baritone,
			underwater,
			request -> Optional.empty(),
			Optional::empty,
			release::get
		);
		WorldTaskRequest mine = request();
		executor.tick(session(0L), Optional.of(mine));
		WorldTaskRequest direct = WorldTaskRequest.underwaterHarvest(
			"underwater-task",
			mine.sourceJobId(),
			mine.goal(),
			stepArgs()
		);

		executor.tick(session(1L), Optional.of(direct));

		assertEquals(HybridMiningTaskExecutor.Phase.RELEASING_BARITONE, executor.phase());
		assertEquals(0, underwater.activeCalls());
		assertEquals(1, baritone.emptyCalls());

		release.set(released());
		executor.tick(session(2L), Optional.of(direct));

		assertEquals(HybridMiningTaskExecutor.Phase.UNDERWATER_HARVEST, executor.phase());
		assertEquals(direct, underwater.lastActive().orElseThrow());
	}

	@Test
	void replacementAfterUnderwaterHarvestWaitsForItsBaritoneOwner() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor underwater = new RecordingExecutor();
		AtomicReference<HybridMiningPolicy.ReleaseStatus> release = new AtomicReference<>(waiting());
		HybridMiningTaskExecutor executor = executor(
			baritone,
			underwater,
			request -> Optional.empty(),
			Optional::empty,
			release::get
		);
		WorldTaskRequest mine = request();
		WorldTaskRequest direct = WorldTaskRequest.underwaterHarvest(
			"underwater-task",
			mine.sourceJobId(),
			mine.goal(),
			stepArgs()
		);
		executor.tick(session(0L), Optional.of(direct));
		WorldTaskRequest replacement = WorldTaskRequest.collectMine(
			"replacement-task",
			"replacement-job",
			mine.goal()
		);

		executor.tick(session(1L), Optional.of(replacement));

		assertEquals(HybridMiningTaskExecutor.Phase.RELEASING_BARITONE, executor.phase());
		assertEquals(1, underwater.activeCalls());
		assertEquals(1, underwater.emptyCalls());
		assertEquals(0, baritone.activeCalls());

		release.set(released());
		executor.tick(session(2L), Optional.of(replacement));

		assertEquals(HybridMiningTaskExecutor.Phase.BARITONE_PRIMARY, executor.phase());
		assertEquals(replacement, baritone.lastActive().orElseThrow());
	}

	private static HybridMiningTaskExecutor executor(
		RecordingExecutor baritone,
		RecordingExecutor underwater,
		HybridMiningTaskExecutor.UnderwaterSourceProbe sourceProbe,
		HybridMiningTaskExecutor.MiningProgressProbe progressProbe,
		HybridMiningTaskExecutor.BaritoneReleaseProbe releaseProbe
	) {
		return new HybridMiningTaskExecutor(baritone, underwater, sourceProbe, progressProbe, releaseProbe);
	}

	private static WorldTaskRequest request() {
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(List.of("minecraft:clay"), 4, List.of("minecraft:clay_ball"), List.of()),
			20L,
			"action_graph"
		);
		return WorldTaskRequest.collectMine("mine-task", "mine-job", goal);
	}

	private static UnderwaterHarvestStepArgs stepArgs() {
		return new UnderwaterHarvestStepArgs(new GoalPosition(4, 52, -3, true));
	}

	private static HybridMiningPolicy.ProgressSample sample(double x) {
		return new HybridMiningPolicy.ProgressSample(true, x, 52.0D, -3.0D);
	}

	private static HybridMiningPolicy.ReleaseStatus waiting() {
		return HybridMiningPolicy.ReleaseStatus.waiting();
	}

	private static HybridMiningPolicy.ReleaseStatus released() {
		return new HybridMiningPolicy.ReleaseStatus(true, true);
	}

	private static TaskTerminalEvent terminal(
		WorldTaskRequest request,
		TaskExecutionState state,
		TaskTerminationCause cause
	) {
		return new TaskTerminalEvent(request.taskId(), request.goal(), state, state.name(), cause);
	}

	private static TaskExecutionSnapshot running(WorldTaskRequest request, String event) {
		return new TaskExecutionSnapshot(
			TaskExecutionState.RUNNING,
			request.taskId(),
			request.goal(),
			"Baritone",
			event,
			null,
			null
		);
	}

	private static SessionSnapshot session(long tick) {
		return new SessionSnapshot(SessionMode.REMOTE_MULTIPLAYER, true, true, "minecraft:overworld", false, 0, tick);
	}

	private static SessionSnapshot gatedSession(long tick) {
		return new SessionSnapshot(SessionMode.SINGLEPLAYER_LOCAL, true, true, "minecraft:overworld", false, 0, tick);
	}

	private static final class RecordingExecutor implements WorldTaskExecutor {
		private final List<Optional<WorldTaskRequest>> calls = new ArrayList<>();
		private Optional<TaskTerminalEvent> terminal = Optional.empty();
		private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

		@Override
		public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
			calls.add(activeTask);
			Optional<TaskTerminalEvent> next = terminal;
			terminal = Optional.empty();
			return next;
		}

		@Override
		public TaskExecutionSnapshot snapshot() {
			return snapshot;
		}

		@Override
		public void onWorldLeave() {
		}

		@Override
		public void shutdown() {
		}

		int emptyCalls() {
			return (int) calls.stream().filter(Optional::isEmpty).count();
		}

		int activeCalls() {
			return (int) calls.stream().filter(Optional::isPresent).count();
		}

		Optional<WorldTaskRequest> lastActive() {
			return calls.reversed().stream().flatMap(Optional::stream).findFirst();
		}
	}

}
