package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaritoneTaskExecutorTest {
	@Test
	void navigateGoalStartsOnceAndReportsRunning() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(10, 64, 20, true),
			null,
			20L,
			"planner_response"
		);

		executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));
		executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));

		assertEquals(1, facade.applySettingsCalls);
		assertEquals(1, facade.navigateCalls.size());
		assertEquals(TaskExecutionState.RUNNING, executor.snapshot().state());
	}

	@Test
	void terminalPathEventBecomesCompletedTaskEvent() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(List.of("minecraft:oak_log"), 8),
			20L,
			"planner_response"
		);

		executor.tick(multiplayer(), Optional.of(request("mine-task", goal)));
		facade.pathEvents.add("AT_GOAL");

		Optional<TaskTerminalEvent> event = executor.tick(multiplayer(), Optional.of(request("mine-task", goal)));

		assertTrue(event.isPresent());
		assertEquals(TaskExecutionState.COMPLETED, event.orElseThrow().terminalState());
	}

	@Test
	void cancelledPathEventVariantIsRecognizedAndSnapshotStaysTerminal() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(1, 64, 1, true),
			null,
			20L,
			"planner_response"
		);

		executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));
		facade.pathEvents.add("cancelled");

		Optional<TaskTerminalEvent> event = executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));

		assertTrue(event.isPresent());
		assertEquals(TaskExecutionState.CANCELLED, event.orElseThrow().terminalState());
		assertEquals(TaskExecutionState.CANCELLED, executor.snapshot().state());

		executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));
		assertEquals(TaskExecutionState.CANCELLED, executor.snapshot().state());
	}

	@Test
	void cancelledNavigateAtReachedGoalCountsAsCompleted() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		facade.navigationGoalReached = true;
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(-7, 68, -4, true),
			null,
			20L,
			"verification"
		);

		executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));
		facade.pathEvents.add("CANCELED");

		Optional<TaskTerminalEvent> event = executor.tick(multiplayer(), Optional.of(request("nav-task", goal)));

		assertTrue(event.isPresent());
		assertEquals(TaskExecutionState.COMPLETED, event.orElseThrow().terminalState());
		assertEquals(TaskExecutionState.COMPLETED, executor.snapshot().state());
	}

	@Test
	void repeatedTerminalPathEventsOnlyEmitOneTerminalCallbackPerGoal() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.FOLLOW_PLAYER,
			"LanAlice",
			null,
			null,
			20L,
			"planner_response"
		);

		executor.tick(multiplayer(), Optional.of(request("follow-task", goal)));
		facade.pathEvents.add("CANCELED");
		facade.pathEvents.add("CANCELED");

		Optional<TaskTerminalEvent> first = executor.tick(multiplayer(), Optional.of(request("follow-task", goal)));
		Optional<TaskTerminalEvent> second = executor.tick(multiplayer(), Optional.of(request("follow-task", goal)));

		assertTrue(first.isPresent());
		assertTrue(second.isEmpty());
		assertEquals(TaskExecutionState.CANCELLED, executor.snapshot().state());
	}

	@Test
	void sameGoalTargetWithDifferentTickDoesNotRestartPathing() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot first = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(10, 64, 20, true),
			null,
			20L,
			"planner_response"
		);
		GoalSnapshot second = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(10, 64, 20, true),
			null,
			21L,
			"planner_response"
		);

		executor.tick(multiplayer(), Optional.of(request("nav-task", first)));
		executor.tick(multiplayer(), Optional.of(request("nav-task", second)));

		assertEquals(1, facade.navigateCalls.size());
	}

	@Test
	void sessionGatePausesWithoutCancellingGoalState() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.FOLLOW_PLAYER,
			"Alice",
			null,
			null,
			20L,
			"planner_response"
		);

		executor.tick(singleplayerLocal(), Optional.of(request("follow-task", goal)));

		assertEquals(TaskExecutionState.PAUSED_BY_SESSION_GATE, executor.snapshot().state());
		assertEquals("follow-task", executor.snapshot().taskId());
		assertEquals(goal, executor.snapshot().activeGoal());
		assertEquals(0, facade.followCalls.size());
	}

	@Test
	void noGoalStaysIdleEvenWhenActuationIsBlocked() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);

		executor.tick(singleplayerLocal(), Optional.empty());

		assertEquals(TaskExecutionState.IDLE, executor.snapshot().state());
	}

	@Test
	void replacingCollectMineTaskDoesNotSurfaceInternalCancelledEvent() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot first = new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(List.of("minecraft:oak_log"), 8),
			20L,
			"task_runtime"
		);
		GoalSnapshot second = new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(List.of("minecraft:oak_log"), 7),
			21L,
			"task_runtime"
		);

		executor.tick(multiplayer(), Optional.of(request("job-1:mine:1", "job-1", first)));
		facade.pathEvents.add("CANCELED");

		Optional<TaskTerminalEvent> event = executor.tick(multiplayer(), Optional.of(request("job-1:mine:2", "job-1", second)));

		assertTrue(event.isEmpty());
		assertEquals(TaskExecutionState.RUNNING, executor.snapshot().state());
		assertEquals("job-1:mine:2", executor.snapshot().taskId());
		assertEquals(2, facade.mineCalls.size());
		assertEquals(1, facade.cancelCalls);
	}

	@Test
	void replacingMineTaskAfterCompletionResetsSnapshotToRunning() {
		FakeBaritoneFacade facade = new FakeBaritoneFacade();
		BaritoneTaskExecutor executor = new BaritoneTaskExecutor(facade);
		GoalSnapshot first = new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(List.of("minecraft:oak_log"), 8),
			20L,
			"task_runtime"
		);
		GoalSnapshot second = new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(List.of("minecraft:oak_log"), 7),
			21L,
			"task_runtime"
		);

		executor.tick(multiplayer(), Optional.of(request("job-1:mine:1", "job-1", first)));
		facade.pathEvents.add("AT_GOAL");
		Optional<TaskTerminalEvent> completed = executor.tick(multiplayer(), Optional.of(request("job-1:mine:1", "job-1", first)));
		Optional<TaskTerminalEvent> replacement = executor.tick(multiplayer(), Optional.of(request("job-1:mine:2", "job-1", second)));

		assertTrue(completed.isPresent());
		assertTrue(replacement.isEmpty());
		assertEquals(TaskExecutionState.RUNNING, executor.snapshot().state());
		assertEquals("job-1:mine:2", executor.snapshot().taskId());
		assertEquals(2, facade.mineCalls.size());
	}

	private static WorldTaskRequest request(String taskId, GoalSnapshot goal) {
		return WorldTaskRequest.direct(taskId, goal);
	}

	private static WorldTaskRequest request(String taskId, String sourceJobId, GoalSnapshot goal) {
		return new WorldTaskRequest(taskId, sourceJobId, WorldTaskType.MINE, goal);
	}

	private static SessionSnapshot multiplayer() {
		return new SessionSnapshot(SessionMode.REMOTE_MULTIPLAYER, true, true, "minecraft:overworld", false, 0, 30L);
	}

	private static SessionSnapshot singleplayerLocal() {
		return new SessionSnapshot(SessionMode.SINGLEPLAYER_LOCAL, true, true, "minecraft:overworld", false, 0, 30L);
	}

	private static final class FakeBaritoneFacade implements BaritoneFacade {
		private int applySettingsCalls;
		private final List<GoalPosition> navigateCalls = new ArrayList<>();
		private final List<String> followCalls = new ArrayList<>();
		private final List<GoalMineSpec> mineCalls = new ArrayList<>();
		private final ArrayDeque<String> pathEvents = new ArrayDeque<>();
		private boolean navigationGoalReached;
		private int cancelCalls;

		@Override
		public boolean isLoaded() {
			return true;
		}

		@Override
		public void applySettings() {
			applySettingsCalls++;
		}

		@Override
		public void startFollow(String playerName) {
			followCalls.add(playerName);
		}

		@Override
		public void startNavigate(GoalPosition position) {
			navigateCalls.add(position);
		}

		@Override
		public void startMine(GoalMineSpec spec) {
			mineCalls.add(spec);
		}

		@Override
		public void cancel() {
			cancelCalls++;
		}

		@Override
		public Optional<String> activeProcessName() {
			return Optional.of("fake");
		}

		@Override
		public Optional<Double> estimatedTicksToGoal() {
			return Optional.of(42.0D);
		}

		@Override
		public Optional<String> pollPathEvent() {
			return Optional.ofNullable(pathEvents.pollFirst());
		}

		@Override
		public boolean navigationGoalReached(GoalPosition position) {
			return navigationGoalReached;
		}
	}
}
