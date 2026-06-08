package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockInteractionTaskExecutorTest {
	@Test
	void directWaterPlacementRequiresAHorizontalCavity() {
		assertFalse(BlockInteractionTaskExecutor.isSafeDirectWaterTarget(0));
		assertFalse(BlockInteractionTaskExecutor.isSafeDirectWaterTarget(1));
		assertFalse(BlockInteractionTaskExecutor.isSafeDirectWaterTarget(2));
		assertTrue(BlockInteractionTaskExecutor.isSafeDirectWaterTarget(3));
		assertTrue(BlockInteractionTaskExecutor.isSafeDirectWaterTarget(4));
	}

	@Test
	void batchedRequestPausesWhenSessionGateBlocksActuation() {
		BlockInteractionTaskExecutor executor = new BlockInteractionTaskExecutor(() -> null);

		Optional<TaskTerminalEvent> event = executor.tick(
			new SessionSnapshot(SessionMode.SINGLEPLAYER_LOCAL, true, true, "minecraft:overworld", false, 0, 1L),
			Optional.of(request())
		);

		assertTrue(event.isEmpty());
		assertEquals(TaskExecutionState.PAUSED_BY_SESSION_GATE, executor.snapshot().state());
		assertEquals("session_gate", executor.snapshot().lastPathEvent());
	}

	@Test
	void batchedRequestFailsWhenWorldUnavailable() {
		BlockInteractionTaskExecutor executor = new BlockInteractionTaskExecutor(() -> null);

		Optional<TaskTerminalEvent> event = executor.tick(
			new SessionSnapshot(SessionMode.REMOTE_MULTIPLAYER, true, true, "minecraft:overworld", false, 0, 1L),
			Optional.of(request())
		);

		assertTrue(event.isPresent());
		assertEquals(TaskExecutionState.FAILED, event.orElseThrow().terminalState());
		assertEquals("world_unavailable", event.orElseThrow().message());
	}

	private static WorldTaskRequest request() {
		return WorldTaskRequest.useBlock(
			"task-1",
			"job-1",
			new BlockUseStepArgs(
				"minecraft:wheat_seeds",
				List.of(
					new BlockUseStepArgs.Target(new GoalPosition(1, 65, 2, true), "down", List.of("minecraft:farmland"), "air"),
					new BlockUseStepArgs.Target(new GoalPosition(2, 65, 2, true), "down", List.of("minecraft:farmland"), "air")
				)
			)
		);
	}
}
