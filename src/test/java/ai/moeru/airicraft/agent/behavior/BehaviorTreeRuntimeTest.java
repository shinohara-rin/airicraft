package ai.moeru.airicraft.agent.behavior;

import ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskExecutionState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BehaviorTreeRuntimeTest {
	@Test
	void preservesMovementOwnedByEntityInteractionExecutor() {
		assertTrue(BehaviorTreeRuntime.taskOwnsMovement(new TaskExecutionSnapshot(
			TaskExecutionState.RUNNING,
			"task-1",
			null,
			"EntityInteraction",
			"direct_chase",
			null,
			null
		)));

		assertFalse(BehaviorTreeRuntime.taskOwnsMovement(TaskExecutionSnapshot.idle()));
		assertFalse(BehaviorTreeRuntime.taskOwnsMovement(new TaskExecutionSnapshot(
			TaskExecutionState.RUNNING,
			"task-2",
			null,
			"ItemDrop",
			"inventory_busy",
			null,
			null
		)));
	}
}
