package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockBreakTaskExecutorTest {
	@Test
	void pausesWhenSessionGateBlocksActuation() {
		BlockBreakTaskExecutor executor = new BlockBreakTaskExecutor(() -> null);

		Optional<TaskTerminalEvent> event = executor.tick(
			new SessionSnapshot(SessionMode.SINGLEPLAYER_LOCAL, true, true, "minecraft:overworld", false, 0, 1L),
			Optional.of(request())
		);

		assertTrue(event.isEmpty());
		assertEquals(TaskExecutionState.PAUSED_BY_SESSION_GATE, executor.snapshot().state());
		assertEquals("session_gate", executor.snapshot().lastPathEvent());
	}

	@Test
	void failsWhenWorldUnavailable() {
		BlockBreakTaskExecutor executor = new BlockBreakTaskExecutor(() -> null);

		Optional<TaskTerminalEvent> event = executor.tick(
			new SessionSnapshot(SessionMode.REMOTE_MULTIPLAYER, true, true, "minecraft:overworld", false, 0, 1L),
			Optional.of(request())
		);

		assertTrue(event.isPresent());
		assertEquals(TaskExecutionState.FAILED, event.orElseThrow().terminalState());
		assertEquals("world_unavailable", event.orElseThrow().message());
	}

	private static WorldTaskRequest request() {
		return WorldTaskRequest.breakBlocks(
			"task-1",
			"job-1",
			new BlockBreakStepArgs(List.of(new BlockBreakStepArgs.Target(
				new GoalPosition(1, 64, 2, true),
				List.of("minecraft:grass_block")
			)))
		);
	}
}
