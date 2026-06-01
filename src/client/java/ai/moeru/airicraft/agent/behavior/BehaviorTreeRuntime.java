package ai.moeru.airicraft.agent.behavior;

import ai.moeru.airicraft.agent.control.LookController;
import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.agent.chat.ChatService;
import ai.moeru.airicraft.agent.debug.AgentDebugRecorder;
import ai.moeru.airicraft.agent.dialogue.DialogueResponse;
import ai.moeru.airicraft.agent.follow.FollowState;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.dialogue.DialogueRuntime;
import ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskExecutionState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Optional;

public final class BehaviorTreeRuntime {
	private static final double FOLLOW_STOP_DISTANCE = 4.0D;
	private static final float LOOK_YAW_STEP = 8.0F;
	private static final float LOOK_PITCH_STEP = 6.0F;

	private final LookController lookController = new LookController();
	private final MovementController movementController = new MovementController();

	private BehaviorTreeSnapshot snapshot = BehaviorTreeSnapshot.idle();

	public void tick(
		MinecraftClient client,
		SessionSnapshot sessionSnapshot,
		DialogueRuntime dialogueRuntime,
		ChatService chatService,
		AgentDebugRecorder debugRecorder,
		Optional<GoalSnapshot> activeGoal,
		FollowState followState,
		TaskExecutionSnapshot taskExecutionSnapshot,
		long tick
	) {
		if (client == null || !sessionSnapshot.worldLoaded() || client.player == null) {
			movementController.stop(client);
			snapshot = new BehaviorTreeSnapshot(NodeStatus.RUNNING, List.of("Root", "WaitForSession"), movementController.snapshot());
			return;
		}

		if (dialogueRuntime.hasPendingReply()) {
			movementController.stop(client);
			String source = dialogueRuntime.pendingReplyReason();
			boolean reusedPriorResponse = "failure_reused_last_response".equals(source);
			dialogueRuntime.lastResponse()
				.map(DialogueResponse::text)
				.ifPresent(text -> {
					String sanitizedText = ChatService.sanitizeForChat(text);
					debugRecorder.recordChatAttempt(tick, sanitizedText, source, reusedPriorResponse);
					boolean sent = chatService.send(client, text, tick);
					debugRecorder.recordChatResult(
						tick,
						sent ? chatService.lastChatText() : sanitizedText,
						source,
						reusedPriorResponse,
						sent
					);
					if (sent) {
						dialogueRuntime.markReplyObserved();
						debugRecorder.recordDialogueState(dialogueRuntime.snapshot());
					}
				});
			snapshot = new BehaviorTreeSnapshot(NodeStatus.RUNNING, List.of("Root", "ReplyToPlayer"), movementController.snapshot());
			return;
		}

		if (taskOwnsMovement(taskExecutionSnapshot)) {
			snapshot = new BehaviorTreeSnapshot(NodeStatus.RUNNING, List.of("Root", "EntityInteractionSubtree", "TaskOwnedMovement"), movementController.snapshot());
			return;
		}

		movementController.stop(client);
		if (activeGoal.isEmpty() || taskExecutionSnapshot == null || taskExecutionSnapshot.state() == TaskExecutionState.IDLE) {
			snapshot = new BehaviorTreeSnapshot(NodeStatus.RUNNING, List.of("Root", "ObserveAndWait"), movementController.snapshot());
			return;
		}

		if (activeGoal.get().type() == GoalType.FOLLOW_PLAYER && followState.targetNearby()) {
			Vec3d targetPos = new Vec3d(followState.targetX(), followState.targetY() + 1.62D, followState.targetZ());
			lookController.lookAt(client, targetPos, LOOK_YAW_STEP, LOOK_PITCH_STEP);
		}

		snapshot = new BehaviorTreeSnapshot(
			NodeStatus.RUNNING,
			nodePathFor(activeGoal.get(), followState, taskExecutionSnapshot.state()),
			movementController.snapshot()
		);
	}

	public BehaviorTreeSnapshot snapshot() {
		return snapshot;
	}

	public void stop(MinecraftClient client) {
		movementController.stop(client);
		snapshot = BehaviorTreeSnapshot.idle();
	}

	private static List<String> nodePathFor(GoalSnapshot activeGoal, FollowState followState, TaskExecutionState taskState) {
		String subtree = switch (activeGoal.type()) {
			case FOLLOW_PLAYER -> "FollowPlayerSubtree";
			case NAVIGATE_TO -> "NavigateToSubtree";
			case MINE_BLOCKS -> "MineBlocksSubtree";
		};
		return switch (taskState) {
			case PAUSED_BY_SESSION_GATE -> List.of("Root", subtree, "ActuationBlockedBySession");
			case RUNNING -> runningPathFor(activeGoal, followState, subtree);
			case COMPLETED -> List.of("Root", subtree, "TaskCompleted");
			case FAILED -> List.of("Root", subtree, "TaskFailed");
			case CANCELLED -> List.of("Root", subtree, "TaskCancelled");
			case IDLE -> List.of("Root", "ObserveAndWait");
		};
	}

	static boolean taskOwnsMovement(TaskExecutionSnapshot taskExecutionSnapshot) {
		return taskExecutionSnapshot != null
			&& taskExecutionSnapshot.state() == TaskExecutionState.RUNNING
			&& "EntityInteraction".equals(taskExecutionSnapshot.processName())
			&& ("direct_chase".equals(taskExecutionSnapshot.lastPathEvent())
				|| "baritone_chase".equals(taskExecutionSnapshot.lastPathEvent()));
	}

	private static List<String> runningPathFor(GoalSnapshot activeGoal, FollowState followState, String subtree) {
		if (activeGoal.type() == GoalType.FOLLOW_PLAYER && followState.targetNearby() && followState.distanceToTarget() > FOLLOW_STOP_DISTANCE) {
			return List.of("Root", subtree, "MoveCloserWhenTooFar");
		}
		return List.of("Root", subtree, "ObserveAndWait");
	}
}
