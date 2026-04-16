package ai.moeru.airicraft.agent.job;

import ai.moeru.airicraft.agent.debug.CollectResourceTaskDebugSnapshot;
import ai.moeru.airicraft.agent.dialogue.DialogueIntentType;
import ai.moeru.airicraft.agent.dialogue.DialogueResponse;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.tasks.AskUserStepArgs;
import ai.moeru.airicraft.agent.tasks.CollectResourceStepArgs;
import ai.moeru.airicraft.agent.tasks.CollectResourceTaskHandler;
import ai.moeru.airicraft.agent.tasks.CraftRecipeStepArgs;
import ai.moeru.airicraft.agent.tasks.LedgerStep;
import ai.moeru.airicraft.agent.tasks.LedgerStepKind;
import ai.moeru.airicraft.agent.tasks.MissionExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.MissionSpec;
import ai.moeru.airicraft.agent.tasks.MissionType;
import ai.moeru.airicraft.agent.tasks.StepExecutionResult;
import ai.moeru.airicraft.agent.tasks.StepExecutionStatus;
import ai.moeru.airicraft.agent.tasks.TaskLedger;
import ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskExecutionState;
import ai.moeru.airicraft.agent.tasks.TaskOwnership;
import ai.moeru.airicraft.agent.tasks.TaskProgressSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskSpec;
import ai.moeru.airicraft.agent.tasks.TaskState;
import ai.moeru.airicraft.agent.tasks.TaskStep;
import ai.moeru.airicraft.agent.tasks.WorldTaskRequest;
import ai.moeru.airicraft.agent.tasks.WorldEvidence;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ActiveJobRuntime {
	private final CollectResourceTaskHandler collectResourceTaskHandler = new CollectResourceTaskHandler();

	private ActiveJob activeJob = ActiveJob.idle();
	private TaskExecutionSnapshot lastPrimitiveExecution = TaskExecutionSnapshot.idle();
	private WorldEvidence lastEvidence = new WorldEvidence(Map.of(), Map.of(), Map.of(), null, 0, 0, 0, null, -1L);
	private MissionSpec compatibilityMission;
	private TaskLedger compatibilityLedger;
	private CollectResourceTaskDebugSnapshot collectResourceDebugSnapshot = CollectResourceTaskDebugSnapshot.empty();
	private WorldTaskRequest desiredPrimitiveTask;
	private String collectAttemptJobId;
	private int collectAttemptSequence;

	public void clear() {
		activeJob = ActiveJob.idle();
		lastPrimitiveExecution = TaskExecutionSnapshot.idle();
		lastEvidence = new WorldEvidence(Map.of(), Map.of(), Map.of(), null, 0, 0, 0, null, -1L);
		compatibilityMission = null;
		compatibilityLedger = null;
		collectResourceDebugSnapshot = CollectResourceTaskDebugSnapshot.empty();
		desiredPrimitiveTask = null;
		collectAttemptJobId = null;
		collectAttemptSequence = 0;
	}

	public ActiveJob current() {
		return activeJob;
	}

	public Optional<GoalSnapshot> activeGoal(long tick) {
		if (activeJob.isIdle() || activeJob.status().terminal()) {
			return Optional.empty();
		}
		if (desiredPrimitiveTask != null
			&& desiredPrimitiveTask.goal() != null
			&& Objects.equals(desiredPrimitiveTask.sourceJobId(), activeJob.jobId())) {
			return Optional.of(desiredPrimitiveTask.goal());
		}
		if (activeJob.directGoal() != null) {
			return Optional.of(activeJob.directGoal());
		}
		if (activeJob.type() == ActiveJobType.COLLECT_RESOURCE && activeJob.taskSpec() != null) {
			if (activeJob.status() == ActiveJobStatus.QUEUED) {
				return Optional.empty();
			}
			int remaining = Math.max(1, activeJob.taskSpec().quantity() - activeJob.collectedCount());
			return Optional.of(collectResourceTaskHandler.start(activeJob.taskSpec(), remaining, tick));
		}
		return Optional.empty();
	}

	public Optional<WorldTaskRequest> activeTaskRequest() {
		return Optional.ofNullable(desiredPrimitiveTask);
	}

	public CollectResourceTaskDebugSnapshot collectResourceDebugSnapshot() {
		return collectResourceDebugSnapshot;
	}

	public void submitTask(TaskSpec spec, int currentResourceCount, String source, long tick) {
		Objects.requireNonNull(spec, "spec");
		activeJob = new ActiveJob(
			newJobId(),
			ActiveJobType.COLLECT_RESOURCE,
			ActiveJobStatus.QUEUED,
			null,
			spec,
			null,
			null,
			-1L,
			currentResourceCount,
			0,
			normalizeSource(source),
			null,
			null,
			tick
		);
		compatibilityMission = new MissionSpec(activeJob.jobId(), MissionType.COLLECT_RESOURCE, goalText());
		compatibilityLedger = null;
		collectResourceDebugSnapshot = collectResourceProbe(
			activeJob,
			currentResourceCount,
			false,
			lastPrimitiveExecution.state(),
			null,
			tick
		);
		refreshDesiredTask(tick);
	}

	public void submitMissionLedger(TaskLedger ledger, int currentResourceCount, String source, long tick) {
		Objects.requireNonNull(ledger, "ledger");
		ActiveJob next = fromLedger(ledger, currentResourceCount, normalizeSource(source), tick);
		activeJob = preserveProgressIfSame(next, tick);
		compatibilityMission = missionFromLedger(ledger);
		compatibilityLedger = ledger;
		collectResourceDebugSnapshot = activeJob.type() == ActiveJobType.COLLECT_RESOURCE
			? collectResourceProbe(activeJob, currentResourceCount, false, lastPrimitiveExecution.state(), null, tick)
			: CollectResourceTaskDebugSnapshot.empty();
		refreshDesiredTask(tick);
	}

	public void applyPlannerResponse(DialogueResponse response, int currentResourceCount, String source, long tick) {
		if (response == null || response.intent() == null || response.intent().type() == null) {
			return;
		}

		if (response.intent().type() == DialogueIntentType.MISSION_UPDATE && response.intent().taskLedger() != null) {
			submitMissionLedger(response.intent().taskLedger(), currentResourceCount, source, tick);
			return;
		}
		if (response.intent().type() == DialogueIntentType.JOB_UPDATE && response.intent().activeJob() != null) {
			activeJob = preserveProgressIfSame(fromProposal(response.intent().activeJob(), currentResourceCount, normalizeSource(source), tick), tick);
			compatibilityMission = missionSpec();
			compatibilityLedger = null;
			refreshDesiredTask(tick);
			return;
		}
		if (response.intent().type() == DialogueIntentType.SUBMIT_TASK && response.intent().taskSpec() != null) {
			submitTask(response.intent().taskSpec(), currentResourceCount, source, tick);
			return;
		}
		if (response.intent().type() == DialogueIntentType.CANCEL_TASK) {
			cancel("planner_cancelled", tick);
			return;
		}
		if (response.intent().type() == DialogueIntentType.CLEAR_GOAL) {
			if (!activeJob.status().terminal()) {
				cancel("planner_cancelled", tick);
			}
			return;
		}
		if (response.intent().type() == DialogueIntentType.SET_GOAL && response.intent().goalType() != null) {
			ActiveJob next = fromGoalResponse(response, normalizeSource(source));
			activeJob = preserveProgressIfSame(next, tick);
			refreshDesiredTask(tick);
		}
	}

	public void cancel(String reason, long tick) {
		if (activeJob.isIdle()) {
			return;
		}
		activeJob = new ActiveJob(
			activeJob.jobId(),
			activeJob.type(),
			ActiveJobStatus.CANCELLED,
			activeJob.directGoal(),
			activeJob.taskSpec(),
			activeJob.craftRecipe(),
			activeJob.askPrompt(),
			activeJob.waitUntilTick(),
			activeJob.baselineResourceCount(),
			activeJob.collectedCount(),
			activeJob.source(),
			null,
			reason,
			tick
		);
		collectResourceDebugSnapshot = activeJob.type() == ActiveJobType.COLLECT_RESOURCE
			? collectResourceProbe(activeJob, activeJob.baselineResourceCount() + activeJob.collectedCount(), false, lastPrimitiveExecution.state(), reason, tick)
			: CollectResourceTaskDebugSnapshot.empty();
		refreshDesiredTask(tick);
	}

	public void clearFollowTarget(String targetPlayer) {
		if (activeJob.type() != ActiveJobType.FOLLOW_PLAYER || activeJob.directGoal() == null) {
			return;
		}
		if (!Objects.equals(activeJob.directGoal().targetPlayer(), targetPlayer)) {
			return;
		}
		activeJob = ActiveJob.idle();
		refreshDesiredTask(lastEvidence.tick());
	}

	public void tick(
		TaskExecutionSnapshot primitiveExecution,
		WorldEvidence evidence,
		boolean actuationAllowed,
		boolean nearbyResourceTargetAvailable,
		long tick
	) {
		lastPrimitiveExecution = primitiveExecution == null ? TaskExecutionSnapshot.idle() : primitiveExecution;
		lastEvidence = evidence == null ? new WorldEvidence(Map.of(), Map.of(), Map.of(), null, 0, 0, 0, null, tick) : evidence;

		if (activeJob.isIdle() || activeJob.status().terminal()) {
			collectResourceDebugSnapshot = CollectResourceTaskDebugSnapshot.empty();
			return;
		}

		activeJob = switch (activeJob.type()) {
			case COLLECT_RESOURCE -> tickCollectResource(activeJob, lastPrimitiveExecution, lastEvidence, actuationAllowed, nearbyResourceTargetAvailable, tick);
			case CRAFT_RECIPE -> tickCraftRecipe(activeJob, lastPrimitiveExecution, actuationAllowed, tick);
			case ASK_USER -> tickAskUser(activeJob, tick);
			case FOLLOW_PLAYER, NAVIGATE_TO, MINE_BLOCKS -> tickGoalJob(activeJob, lastPrimitiveExecution, actuationAllowed, tick);
			case IDLE -> ActiveJob.idle();
		};
		if (activeJob.type() != ActiveJobType.COLLECT_RESOURCE) {
			collectResourceDebugSnapshot = CollectResourceTaskDebugSnapshot.empty();
		}
		refreshDesiredTask(tick);
	}

	private void refreshDesiredTask(long tick) {
		if (activeJob.isIdle() || activeJob.status().terminal()) {
			clearDesiredTaskState();
			return;
		}
		if (activeJob.directGoal() != null) {
			clearCollectAttemptState();
			desiredPrimitiveTask = WorldTaskRequest.direct(activeJob.jobId(), activeJob.directGoal());
			return;
		}
		if (activeJob.type() == ActiveJobType.CRAFT_RECIPE && activeJob.craftRecipe() != null) {
			clearCollectAttemptState();
			desiredPrimitiveTask = WorldTaskRequest.craftRecipe(activeJob.jobId(), activeJob.jobId(), activeJob.craftRecipe());
			return;
		}
		if (activeJob.type() != ActiveJobType.COLLECT_RESOURCE || activeJob.taskSpec() == null) {
			clearDesiredTaskState();
			return;
		}
		int remaining = Math.max(1, activeJob.taskSpec().quantity() - activeJob.collectedCount());
		boolean collectTaskChanged = !Objects.equals(collectAttemptJobId, activeJob.jobId());
		boolean collectTaskMissing = desiredPrimitiveTask == null || !Objects.equals(desiredPrimitiveTask.sourceJobId(), activeJob.jobId());
		boolean primitiveCompleted = lastPrimitiveExecution.state() == TaskExecutionState.COMPLETED
			&& desiredPrimitiveTask != null
			&& Objects.equals(lastPrimitiveExecution.taskId(), desiredPrimitiveTask.taskId());
		if ((collectTaskChanged || collectTaskMissing) && activeJob.status() != ActiveJobStatus.RUNNING) {
			if (collectTaskChanged) {
				clearCollectAttemptState();
			}
			desiredPrimitiveTask = null;
			return;
		}
		if (collectTaskChanged || collectTaskMissing || primitiveCompleted) {
			startCollectAttempt(remaining, tick);
			return;
		}
	}

	private void startCollectAttempt(int remainingQuantity, long tick) {
		if (activeJob.taskSpec() == null) {
			clearDesiredTaskState();
			return;
		}
		if (!Objects.equals(collectAttemptJobId, activeJob.jobId())) {
			collectAttemptJobId = activeJob.jobId();
			collectAttemptSequence = 0;
		}
		collectAttemptSequence++;
		int absoluteInventoryTarget = activeJob.baselineResourceCount() + activeJob.taskSpec().quantity();
		GoalSnapshot goal = collectResourceTaskHandler.start(activeJob.taskSpec(), absoluteInventoryTarget, tick);
		desiredPrimitiveTask = WorldTaskRequest.collectMine(
			activeJob.jobId() + ":mine:" + collectAttemptSequence,
			activeJob.jobId(),
			goal
		);
	}

	private void clearDesiredTaskState() {
		desiredPrimitiveTask = null;
		clearCollectAttemptState();
	}

	private void clearCollectAttemptState() {
		collectAttemptJobId = null;
		collectAttemptSequence = 0;
	}

	public TaskSnapshot taskSnapshot() {
		TaskSpec spec = activeJob.taskSpec();
		TaskProgressSnapshot progress = spec == null
			? new TaskProgressSnapshot(0, 0)
			: TaskProgressSnapshot.of(activeJob.collectedCount(), spec.quantity());
		return new TaskSnapshot(
			toTaskState(activeJob),
			compatibilityMission,
			compatibilityLedger,
			spec,
			progress,
			TaskStep.NONE,
			TaskOwnership.NONE,
			activeJob.source(),
			activeJob.lastError(),
			compatibilityLedger == null ? activeStepId() : compatibilityLedger.activeStepId(),
			compatibilityActiveStepKind(),
			lastStepResult(),
			activeJob.updatedTick()
		);
	}

	public MissionExecutionSnapshot missionExecutionSnapshot() {
		return new MissionExecutionSnapshot(
			compatibilityMission,
			compatibilityLedger,
			null,
			lastEvidence,
			lastStepResult(),
			lastPrimitiveExecution
		);
	}

	private ActiveJob tickCollectResource(
		ActiveJob job,
		TaskExecutionSnapshot primitiveExecution,
		WorldEvidence evidence,
		boolean actuationAllowed,
		boolean nearbyResourceTargetAvailable,
		long tick
	) {
		TaskSpec spec = job.taskSpec();
		int currentCount = evidence.inventoryCounts().getOrDefault(spec.resourceKind(), job.baselineResourceCount());
		int collected = Math.max(0, currentCount - job.baselineResourceCount());
		if (collected >= spec.quantity()) {
			ActiveJob completed = updated(job, ActiveJobStatus.COMPLETED, null, null, collected, tick);
			collectResourceDebugSnapshot = collectResourceProbe(completed, currentCount, nearbyResourceTargetAvailable, primitiveExecution.state(), "inventory_delta_reached", tick);
			return completed;
		}
		if (!actuationAllowed || primitiveExecution.state() == TaskExecutionState.PAUSED_BY_SESSION_GATE) {
			ActiveJob blocked = updated(job, ActiveJobStatus.BLOCKED, "session_gate", null, collected, tick);
			collectResourceDebugSnapshot = collectResourceProbe(blocked, currentCount, nearbyResourceTargetAvailable, primitiveExecution.state(), null, tick);
			return blocked;
		}
		if (primitiveExecution.state() == TaskExecutionState.FAILED) {
			ActiveJob failed = updated(job, ActiveJobStatus.FAILED, null, nonEmpty(primitiveExecution.lastPathEvent(), "task_failed"), collected, tick);
			collectResourceDebugSnapshot = collectResourceProbe(failed, currentCount, nearbyResourceTargetAvailable, primitiveExecution.state(), failed.lastError(), tick);
			return failed;
		}
		if (primitiveExecution.state() == TaskExecutionState.CANCELLED) {
			ActiveJob cancelled = updated(job, ActiveJobStatus.CANCELLED, null, nonEmpty(primitiveExecution.lastPathEvent(), "task_cancelled"), collected, tick);
			collectResourceDebugSnapshot = collectResourceProbe(cancelled, currentCount, nearbyResourceTargetAvailable, primitiveExecution.state(), cancelled.lastError(), tick);
			return cancelled;
		}
		if (!nearbyResourceTargetAvailable) {
			ActiveJob blocked = updated(job, ActiveJobStatus.BLOCKED, "target_missing", null, collected, tick);
			collectResourceDebugSnapshot = collectResourceProbe(blocked, currentCount, false, primitiveExecution.state(), null, tick);
			return blocked;
		}
		ActiveJob running = updated(job, ActiveJobStatus.RUNNING, null, null, collected, tick);
		collectResourceDebugSnapshot = collectResourceProbe(running, currentCount, true, primitiveExecution.state(), null, tick);
		return running;
	}

	private static ActiveJob tickAskUser(ActiveJob job, long tick) {
		return updated(job, ActiveJobStatus.BLOCKED, "waiting_for_user", null, job.collectedCount(), tick);
	}

	private static ActiveJob tickCraftRecipe(
		ActiveJob job,
		TaskExecutionSnapshot primitiveExecution,
		boolean actuationAllowed,
		long tick
	) {
		if (!actuationAllowed || primitiveExecution.state() == TaskExecutionState.PAUSED_BY_SESSION_GATE) {
			return updated(job, ActiveJobStatus.BLOCKED, "session_gate", null, job.collectedCount(), tick);
		}
		return switch (primitiveExecution.state()) {
			case RUNNING, IDLE -> updated(job, ActiveJobStatus.RUNNING, null, null, job.collectedCount(), tick);
			case COMPLETED -> updated(job, ActiveJobStatus.COMPLETED, null, null, job.collectedCount(), tick);
			case FAILED -> updated(job, ActiveJobStatus.FAILED, null, nonEmpty(primitiveExecution.lastPathEvent(), "crafting_failed"), job.collectedCount(), tick);
			case CANCELLED -> updated(job, ActiveJobStatus.CANCELLED, null, nonEmpty(primitiveExecution.lastPathEvent(), "crafting_cancelled"), job.collectedCount(), tick);
			case PAUSED_BY_SESSION_GATE -> updated(job, ActiveJobStatus.BLOCKED, "session_gate", null, job.collectedCount(), tick);
		};
	}

	private static ActiveJob tickGoalJob(
		ActiveJob job,
		TaskExecutionSnapshot primitiveExecution,
		boolean actuationAllowed,
		long tick
	) {
		if (!actuationAllowed || primitiveExecution.state() == TaskExecutionState.PAUSED_BY_SESSION_GATE) {
			return updated(job, ActiveJobStatus.BLOCKED, "session_gate", null, job.collectedCount(), tick);
		}
		return switch (primitiveExecution.state()) {
			case RUNNING -> updated(job, ActiveJobStatus.RUNNING, null, null, job.collectedCount(), tick);
			case COMPLETED -> updated(job, ActiveJobStatus.COMPLETED, null, null, job.collectedCount(), tick);
			case FAILED -> updated(job, ActiveJobStatus.FAILED, null, nonEmpty(primitiveExecution.lastPathEvent(), "task_failed"), job.collectedCount(), tick);
			case CANCELLED -> updated(job, ActiveJobStatus.CANCELLED, null, nonEmpty(primitiveExecution.lastPathEvent(), "task_cancelled"), job.collectedCount(), tick);
			case IDLE, PAUSED_BY_SESSION_GATE -> updated(job, ActiveJobStatus.QUEUED, null, null, job.collectedCount(), tick);
		};
	}

	private ActiveJob fromLedger(TaskLedger ledger, int currentResourceCount, String source, long tick) {
		if (ledger.activeStepId() == null) {
			return new ActiveJob(ledger.missionId(), ActiveJobType.IDLE, ActiveJobStatus.COMPLETED, null, null, null, null, -1L, 0, 0, source, null, null, tick);
		}
		LedgerStep activeStep = ledger.steps().stream()
			.filter(step -> Objects.equals(step.id(), ledger.activeStepId()))
			.findFirst()
			.orElse(null);
		if (activeStep == null) {
			return ActiveJob.idle();
		}
		return switch (activeStep.kind()) {
			case COLLECT_RESOURCE -> fromCollectResourceStep(ledger.missionId(), activeStep.args().collectResource(), currentResourceCount, source, tick);
			case NAVIGATE_TO_POSITION -> fromDirectGoal(
				ledger.missionId(),
				new GoalSnapshot(GoalType.NAVIGATE_TO, null, activeStep.args().navigateToPosition(), null, tick, source),
				ActiveJobType.NAVIGATE_TO,
				source,
				tick
			);
			case MINE_BLOCKS -> fromDirectGoal(
				ledger.missionId(),
				new GoalSnapshot(GoalType.MINE_BLOCKS, null, null, activeStep.args().mineBlocks(), tick, source),
				ActiveJobType.MINE_BLOCKS,
				source,
				tick
			);
			case CRAFT_RECIPE -> fromCraftRecipeStep(ledger.missionId(), activeStep.args().craftRecipe(), source, tick);
			case ASK_USER -> fromAskUserStep(ledger.missionId(), activeStep.args().askUser(), source, tick);
			case FINISH -> new ActiveJob(ledger.missionId(), ActiveJobType.IDLE, ActiveJobStatus.COMPLETED, null, null, null, null, -1L, 0, 0, source, null, null, tick);
			default -> new ActiveJob(ledger.missionId(), ActiveJobType.ASK_USER, ActiveJobStatus.BLOCKED, null, null, null, "Unsupported step: " + activeStep.kind().name(), -1L, 0, 0, source, "unsupported_step", null, tick);
		};
	}

	private static ActiveJob fromAskUserStep(String jobId, AskUserStepArgs askUser, String source, long tick) {
		return new ActiveJob(jobId, ActiveJobType.ASK_USER, ActiveJobStatus.BLOCKED, null, null, null, askUser == null ? null : askUser.prompt(), -1L, 0, 0, source, "waiting_for_user", null, tick);
	}

	private static ActiveJob fromCraftRecipeStep(String jobId, CraftRecipeStepArgs craftRecipe, String source, long tick) {
		if (craftRecipe == null) {
			return new ActiveJob(jobId, ActiveJobType.ASK_USER, ActiveJobStatus.FAILED, null, null, null, null, -1L, 0, 0, source, null, "missing_craft_recipe_args", tick);
		}
		return new ActiveJob(jobId, ActiveJobType.CRAFT_RECIPE, ActiveJobStatus.QUEUED, null, null, craftRecipe, null, -1L, 0, 0, source, null, null, tick);
	}

	private ActiveJob fromCollectResourceStep(String jobId, CollectResourceStepArgs args, int currentResourceCount, String source, long tick) {
		if (args == null) {
			return new ActiveJob(jobId, ActiveJobType.ASK_USER, ActiveJobStatus.FAILED, null, null, null, null, -1L, 0, 0, source, null, "missing_collect_resource_args", tick);
		}
		return new ActiveJob(
			jobId,
			ActiveJobType.COLLECT_RESOURCE,
			ActiveJobStatus.QUEUED,
			null,
			new TaskSpec(ai.moeru.airicraft.agent.tasks.TaskType.COLLECT_RESOURCE, args.resourceKind(), args.quantity()),
			null,
			null,
			-1L,
			currentResourceCount,
			0,
			source,
			null,
			null,
			tick
		);
	}

	private static ActiveJob fromGoalResponse(DialogueResponse response, String source) {
		GoalSnapshot goal = new GoalSnapshot(
			response.intent().goalType(),
			response.intent().targetPlayer(),
			response.intent().position(),
			response.intent().mineSpec(),
			response.tick(),
			source
		);
		ActiveJobType type = switch (response.intent().goalType()) {
			case FOLLOW_PLAYER -> ActiveJobType.FOLLOW_PLAYER;
			case NAVIGATE_TO -> ActiveJobType.NAVIGATE_TO;
			case MINE_BLOCKS -> ActiveJobType.MINE_BLOCKS;
		};
		return fromDirectGoal(newJobId(), goal, type, source, response.tick());
	}

	private static ActiveJob fromProposal(ActiveJobProposal proposal, int currentResourceCount, String source, long tick) {
		return switch (proposal.type()) {
			case FOLLOW_PLAYER -> fromDirectGoal(
				newJobId(),
				new GoalSnapshot(GoalType.FOLLOW_PLAYER, proposal.targetPlayer(), null, null, tick, source),
				ActiveJobType.FOLLOW_PLAYER,
				source,
				tick
			);
			case NAVIGATE_TO -> fromDirectGoal(
				newJobId(),
				new GoalSnapshot(GoalType.NAVIGATE_TO, null, proposal.position(), null, tick, source),
				ActiveJobType.NAVIGATE_TO,
				source,
				tick
			);
			case MINE_BLOCKS -> fromDirectGoal(
				newJobId(),
				new GoalSnapshot(GoalType.MINE_BLOCKS, null, null, proposal.mineSpec(), tick, source),
				ActiveJobType.MINE_BLOCKS,
				source,
				tick
			);
			case COLLECT_RESOURCE -> new ActiveJob(
				newJobId(),
				ActiveJobType.COLLECT_RESOURCE,
				ActiveJobStatus.QUEUED,
				null,
				proposal.taskSpec(),
				null,
				null,
				-1L,
				currentResourceCount,
				0,
				source,
				null,
				null,
				tick
			);
			case CRAFT_RECIPE -> fromCraftRecipeStep(newJobId(), proposal.craftRecipe(), source, tick);
			case ASK_USER -> fromAskUserStep(newJobId(), new AskUserStepArgs(proposal.askPrompt()), source, tick);
			case IDLE -> ActiveJob.idle();
		};
	}

	private static ActiveJob fromDirectGoal(String jobId, GoalSnapshot goal, ActiveJobType type, String source, long tick) {
		return new ActiveJob(jobId, type, ActiveJobStatus.QUEUED, goal, null, null, null, -1L, 0, 0, source, null, null, tick);
	}

	private ActiveJob preserveProgressIfSame(ActiveJob next, long tick) {
		if (sameJobTarget(activeJob, next) && !activeJob.status().terminal()) {
			return new ActiveJob(
				activeJob.jobId(),
				next.type(),
				activeJob.status(),
				next.directGoal(),
				next.taskSpec(),
				next.craftRecipe(),
				next.askPrompt(),
				next.waitUntilTick(),
				activeJob.baselineResourceCount(),
				activeJob.collectedCount(),
				next.source(),
				activeJob.blockedReason(),
				activeJob.lastError(),
				tick
			);
		}
		return next;
	}

	private static boolean sameJobTarget(ActiveJob left, ActiveJob right) {
		if (left == null || right == null || left.type() != right.type()) {
			return false;
		}
		if (left.directGoal() != null || right.directGoal() != null) {
			return Objects.equals(left.directGoal(), right.directGoal());
		}
		if (left.taskSpec() != null || right.taskSpec() != null) {
			return Objects.equals(left.taskSpec(), right.taskSpec());
		}
		if (left.craftRecipe() != null || right.craftRecipe() != null) {
			return Objects.equals(left.craftRecipe(), right.craftRecipe());
		}
		return Objects.equals(left.askPrompt(), right.askPrompt()) && left.waitUntilTick() == right.waitUntilTick();
	}

	private MissionSpec missionSpec() {
		if (activeJob.isIdle()) {
			return null;
		}
		MissionType missionType = switch (activeJob.type()) {
			case COLLECT_RESOURCE -> MissionType.COLLECT_RESOURCE;
			case CRAFT_RECIPE -> MissionType.CRAFT_ITEM;
			default -> MissionType.COLLECT_RESOURCE;
		};
		return new MissionSpec(activeJob.jobId(), missionType, goalText());
	}

	private String goalText() {
		return switch (activeJob.type()) {
			case FOLLOW_PLAYER -> "Follow " + (activeJob.directGoal() == null ? "" : nonEmpty(activeJob.directGoal().targetPlayer(), "player"));
			case NAVIGATE_TO -> "Navigate to target";
			case MINE_BLOCKS -> "Mine blocks";
			case COLLECT_RESOURCE -> activeJob.taskSpec() == null ? "Collect resource" : "Collect " + activeJob.taskSpec().quantity() + " " + activeJob.taskSpec().resourceKind().name().toLowerCase();
			case CRAFT_RECIPE -> activeJob.craftRecipe() == null ? "Craft recipe" : "Craft " + activeJob.craftRecipe().quantity() + "x " + activeJob.craftRecipe().recipeId();
			case ASK_USER -> "Ask user";
			case IDLE -> "";
		};
	}

	private ai.moeru.airicraft.agent.tasks.LedgerStepKind activeStepKind() {
		return switch (activeJob.type()) {
			case FOLLOW_PLAYER, NAVIGATE_TO -> ai.moeru.airicraft.agent.tasks.LedgerStepKind.NAVIGATE_TO_POSITION;
			case MINE_BLOCKS -> ai.moeru.airicraft.agent.tasks.LedgerStepKind.MINE_BLOCKS;
			case COLLECT_RESOURCE -> ai.moeru.airicraft.agent.tasks.LedgerStepKind.COLLECT_RESOURCE;
			case CRAFT_RECIPE -> ai.moeru.airicraft.agent.tasks.LedgerStepKind.CRAFT_RECIPE;
			case ASK_USER -> ai.moeru.airicraft.agent.tasks.LedgerStepKind.ASK_USER;
			case IDLE -> null;
		};
	}

	private String activeStepId() {
		return activeJob.isIdle() ? null : activeJob.type().name().toLowerCase();
	}

	private ai.moeru.airicraft.agent.tasks.LedgerStepKind compatibilityActiveStepKind() {
		if (compatibilityLedger != null && compatibilityLedger.activeStepId() != null) {
			return compatibilityLedger.steps().stream()
				.filter(step -> Objects.equals(step.id(), compatibilityLedger.activeStepId()))
				.map(LedgerStep::kind)
				.findFirst()
				.orElse(activeStepKind());
		}
		return activeStepKind();
	}

	private StepExecutionResult lastStepResult() {
		if (activeJob.isIdle()) {
			return StepExecutionResult.idle();
		}
		StepExecutionStatus status = switch (activeJob.status()) {
			case IDLE, QUEUED -> StepExecutionStatus.IDLE;
			case RUNNING -> StepExecutionStatus.RUNNING;
			case BLOCKED -> StepExecutionStatus.WAITING;
			case COMPLETED -> StepExecutionStatus.COMPLETED;
			case FAILED -> StepExecutionStatus.FAILED;
			case CANCELLED -> StepExecutionStatus.CANCELLED;
		};
		return new StepExecutionResult(
			activeJob.type().name().toLowerCase(),
			status,
			activeJob.lastError(),
			Map.of(),
			Map.of("jobType", activeJob.type().name()),
			activeJob.updatedTick()
		);
	}

	private static TaskState toTaskState(ActiveJob job) {
		return switch (job.status()) {
			case IDLE -> TaskState.IDLE;
			case QUEUED -> TaskState.QUEUED;
			case RUNNING -> TaskState.RUNNING;
			case BLOCKED -> "session_gate".equals(job.blockedReason()) ? TaskState.PAUSED_BY_SESSION_GATE : TaskState.WAITING_FOR_PICKUP;
			case COMPLETED -> TaskState.COMPLETED;
			case FAILED -> TaskState.FAILED;
			case CANCELLED -> TaskState.CANCELLED;
		};
	}

	private static ActiveJob updated(
		ActiveJob job,
		ActiveJobStatus status,
		String blockedReason,
		String lastError,
		int collectedCount,
		long tick
	) {
		return new ActiveJob(
			job.jobId(),
			job.type(),
			status,
			job.directGoal(),
			job.taskSpec(),
			job.craftRecipe(),
			job.askPrompt(),
			job.waitUntilTick(),
			job.baselineResourceCount(),
			collectedCount,
			job.source(),
			blockedReason,
			lastError,
			tick
		);
	}

	private static String normalizeSource(String source) {
		return source == null || source.isBlank() ? "runtime" : source;
	}

	private static MissionSpec missionFromLedger(TaskLedger ledger) {
		return new MissionSpec(ledger.missionId(), ledger.missionType(), ledger.goalText());
	}

	private static String newJobId() {
		return "job-" + UUID.randomUUID();
	}

	private static String nonEmpty(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private static CollectResourceTaskDebugSnapshot collectResourceProbe(
		ActiveJob job,
		int currentResourceCount,
		boolean nearbyResourceTargetAvailable,
		TaskExecutionState primitiveExecutionState,
		String completionReason,
		long tick
	) {
		if (job == null || job.type() != ActiveJobType.COLLECT_RESOURCE || job.taskSpec() == null) {
			return CollectResourceTaskDebugSnapshot.empty();
		}
		int inventoryDelta = Math.max(0, currentResourceCount - job.baselineResourceCount());
		return new CollectResourceTaskDebugSnapshot(
			true,
			job.jobId(),
			job.taskSpec().resourceKind().name(),
			job.baselineResourceCount(),
			currentResourceCount,
			inventoryDelta,
			job.taskSpec().quantity(),
			job.collectedCount(),
			Math.max(0, job.taskSpec().quantity() - job.collectedCount()),
			nearbyResourceTargetAvailable,
			primitiveExecutionState == null ? null : primitiveExecutionState.name(),
			job.status().name(),
			job.blockedReason(),
			completionReason,
			tick
		);
	}
}
