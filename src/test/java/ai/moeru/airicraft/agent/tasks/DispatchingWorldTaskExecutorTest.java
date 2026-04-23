package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DispatchingWorldTaskExecutorTest {
	@Test
	void attackEntityRequestsRouteToEntityExecutor() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor crafting = new RecordingExecutor();
		RecordingExecutor dropItems = new RecordingExecutor();
		RecordingExecutor entityInteraction = new RecordingExecutor();
		DispatchingWorldTaskExecutor executor = new DispatchingWorldTaskExecutor(baritone, crafting, dropItems, entityInteraction);

		executor.tick(snapshot(), Optional.of(WorldTaskRequest.attackEntity(
			"task-1",
			"job-1",
			new EntityInteractionStepArgs(new EntitySelector(null, null, "minecraft:sheep"), null)
		)));

		assertEquals(WorldTaskType.ATTACK_ENTITY, entityInteraction.lastTask.orElseThrow().type());
		assertEquals(Optional.empty(), baritone.lastTask);
		assertEquals(Optional.empty(), crafting.lastTask);
		assertEquals(Optional.empty(), dropItems.lastTask);
	}

	@Test
	void useEntityRequestsRouteToEntityExecutor() {
		RecordingExecutor baritone = new RecordingExecutor();
		RecordingExecutor crafting = new RecordingExecutor();
		RecordingExecutor dropItems = new RecordingExecutor();
		RecordingExecutor entityInteraction = new RecordingExecutor();
		DispatchingWorldTaskExecutor executor = new DispatchingWorldTaskExecutor(baritone, crafting, dropItems, entityInteraction);

		executor.tick(snapshot(), Optional.of(WorldTaskRequest.useEntity(
			"task-2",
			"job-2",
			new EntityInteractionStepArgs(new EntitySelector(null, "Dinner", null), "minecraft:shears")
		)));

		assertEquals(WorldTaskType.USE_ENTITY, entityInteraction.lastTask.orElseThrow().type());
		assertEquals(Optional.empty(), baritone.lastTask);
		assertEquals(Optional.empty(), crafting.lastTask);
		assertEquals(Optional.empty(), dropItems.lastTask);
	}

	private static SessionSnapshot snapshot() {
		return new SessionSnapshot(SessionMode.REMOTE_MULTIPLAYER, true, true, "minecraft:overworld", false, 0, 0L);
	}

	private static final class RecordingExecutor implements WorldTaskExecutor {
		private Optional<WorldTaskRequest> lastTask = Optional.empty();

		@Override
		public Optional<TaskTerminalEvent> tick(ai.moeru.airicraft.agent.session.SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
			lastTask = activeTask;
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
