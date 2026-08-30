# Resource Task Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a first-class high-level resource task layer so the planner and wrapper can request wood collection while Baritone continues to execute only primitive goals.

**Architecture:** Introduce `TaskSpec`/`TaskRuntime` as a semantic layer above `GoalDirector` and `WorldTaskExecutor`, extend planner intent parsing to submit/cancel tasks, and expose the same task surface through bridge and wrapper commands. `CollectResourceTaskHandler` decomposes `WOOD_LOGS` into nearby candidate selection, primitive navigate/mine steps, and inventory-delta-based completion.

**Tech Stack:** Java 21, Fabric client mod, Gson JSON bridge, Picocli wrapper CLI, JUnit 5, existing Baritone integration.

---

## File Structure

### New files

- `src/client/java/ai/moeru/airicraft/agent/tasks/TaskType.java`
  - task family enum
- `src/client/java/ai/moeru/airicraft/agent/tasks/TaskState.java`
  - task lifecycle enum
- `src/client/java/ai/moeru/airicraft/agent/tasks/TaskResourceKind.java`
  - supported semantic resource families
- `src/client/java/ai/moeru/airicraft/agent/tasks/TaskSpec.java`
  - immutable high-level task payload
- `src/client/java/ai/moeru/airicraft/agent/tasks/TaskSnapshot.java`
  - externally visible task status and progress
- `src/client/java/ai/moeru/airicraft/agent/tasks/TaskProgressSnapshot.java`
  - collected/remaining counts
- `src/client/java/ai/moeru/airicraft/agent/tasks/TaskStep.java`
  - current decomposition step enum
- `src/client/java/ai/moeru/airicraft/agent/tasks/TaskOwnership.java`
  - distinguishes direct goals from task-owned goals
- `src/client/java/ai/moeru/airicraft/agent/tasks/CollectResourceTaskHandler.java`
  - v1 semantic task decomposition
- `src/client/java/ai/moeru/airicraft/agent/tasks/InventoryResourceCounter.java`
  - counts accepted inventory items for a resource kind
- `src/client/java/ai/moeru/airicraft/agent/tasks/NearbyTreeLocator.java`
  - finds nearby viable log targets
- `src/client/java/ai/moeru/airicraft/agent/tasks/TaskRuntime.java`
  - owns active task, progress, retries, and generated primitive goal
- `src/test/java/ai/moeru/airicraft/agent/tasks/TaskRuntimeTest.java`
  - task state machine tests
- `src/test/java/ai/moeru/airicraft/agent/tasks/CollectResourceTaskHandlerTest.java`
  - decomposition tests
- `src/test/java/ai/moeru/airicraft/agent/tasks/InventoryResourceCounterTest.java`
  - resource counting tests
- `src/test/java/ai/moeru/airicraft/agent/tasks/NearbyTreeLocatorTest.java`
  - nearby candidate resolution tests

### Modified files

- `src/client/java/ai/moeru/airicraft/agent/llm/PlannerIntent.java`
  - add `taskSpec`
- `src/client/java/ai/moeru/airicraft/agent/llm/OpenAiCompatibleLlmBackend.java`
  - parse `submit_task` / `cancel_task`
- `src/client/java/ai/moeru/airicraft/agent/llm/PlannerPromptPolicy.java`
  - document the expanded planner contract
- `src/client/java/ai/moeru/airicraft/agent/dialogue/DialogueIntent.java`
  - carry task payloads into runtime
- `src/client/java/ai/moeru/airicraft/agent/dialogue/DialogueIntentType.java`
  - add task actions
- `src/client/java/ai/moeru/airicraft/agent/dialogue/DialogueRuntime.java`
  - map planner task intents into dialogue output
- `src/client/java/ai/moeru/airicraft/agent/AgentRuntimeSnapshot.java`
  - expose `task`
- `src/client/java/ai/moeru/airicraft/agent/EmbodiedAgentRuntime.java`
  - coordinate task runtime, direct goals, events, and live verification hooks
- `src/client/java/ai/moeru/airicraft/agent/behavior/BehaviorTreeRuntime.java`
  - reflect task-level subtree and progress
- `src/client/java/ai/moeru/airicraft/ModBridgeServer.java`
  - add `/v1/agent/tasks`
- `wrapper/src/main/java/ai/moeru/airicraft/wrapper/HttpBridgeTransport.java`
  - transport methods for task endpoints
- `wrapper/src/main/java/ai/moeru/airicraft/wrapper/AiricraftCliMain.java`
  - `agent tasks` commands
- `src/test/java/ai/moeru/airicraft/agent/dialogue/DialogueRuntimeTest.java`
  - planner task intent wiring
- `src/test/java/ai/moeru/airicraft/agent/llm/OpenAiCompatibleLlmBackendTest.java`
  - parser coverage
- `src/test/java/ai/moeru/airicraft/agent/EmbodiedAgentRuntimeTest.java`
  - task/direct-goal ownership and event flow
- `wrapper/src/test/java/ai/moeru/airicraft/wrapper/AiricraftCliMainTest.java`
  - wrapper command coverage
- `wrapper/src/test/java/ai/moeru/airicraft/wrapper/HttpBridgeTransportTest.java`
  - task endpoint transport coverage

### Existing files to read before editing

- `docs/superpowers/specs/2026-04-07-resource-task-runtime-design.md`
- `src/client/java/ai/moeru/airicraft/agent/goals/GoalDirector.java`
- `src/client/java/ai/moeru/airicraft/agent/tasks/BaritoneTaskExecutor.java`
- `src/client/java/ai/moeru/airicraft/agent/tasks/WorldTaskExecutor.java`

### Task 1: Planner Contract For Task Submission

**Files:**
- Modify: `src/client/java/ai/moeru/airicraft/agent/llm/PlannerIntent.java`
- Modify: `src/client/java/ai/moeru/airicraft/agent/dialogue/DialogueIntent.java`
- Modify: `src/client/java/ai/moeru/airicraft/agent/dialogue/DialogueIntentType.java`
- Modify: `src/client/java/ai/moeru/airicraft/agent/llm/OpenAiCompatibleLlmBackend.java`
- Modify: `src/client/java/ai/moeru/airicraft/agent/llm/PlannerPromptPolicy.java`
- Modify: `src/client/java/ai/moeru/airicraft/agent/dialogue/DialogueRuntime.java`
- Test: `src/test/java/ai/moeru/airicraft/agent/llm/OpenAiCompatibleLlmBackendTest.java`
- Test: `src/test/java/ai/moeru/airicraft/agent/dialogue/DialogueRuntimeTest.java`

- [ ] **Step 1: Write failing parser tests for `submit_task` and `cancel_task`**

```java
@Test
void parsesSubmitTaskIntentWithCollectResourceSpec() {
	String json = """
		{
		  "replyText": "On it.",
		  "intent": {
		    "type": "submit_task",
		    "taskSpec": {
		      "type": "COLLECT_RESOURCE",
		      "resourceKind": "WOOD_LOGS",
		      "quantity": 16
		    }
		  },
		  "toolRequest": null
		}
		""";

	PlannerResponse response = OpenAiCompatibleLlmBackend.parsePlannerResponse(json);

	assertEquals("submit_task", response.intent().type());
	assertNotNull(response.intent().taskSpec());
	assertEquals(TaskType.COLLECT_RESOURCE, response.intent().taskSpec().type());
	assertEquals(TaskResourceKind.WOOD_LOGS, response.intent().taskSpec().resourceKind());
	assertEquals(16, response.intent().taskSpec().quantity());
}

@Test
void parsesCancelTaskIntentWithoutGoalFields() {
	String json = """
		{
		  "replyText": "",
		  "intent": {
		    "type": "cancel_task",
		    "taskSpec": null
		  },
		  "toolRequest": null
		}
		""";

	PlannerResponse response = OpenAiCompatibleLlmBackend.parsePlannerResponse(json);

	assertEquals("cancel_task", response.intent().type());
	assertNull(response.intent().goalType());
	assertNull(response.intent().taskSpec());
}
```

- [ ] **Step 2: Run parser tests to verify RED**

Run: `source .envrc && ./gradlew test -x wrapper:test --tests ai.moeru.airicraft.agent.llm.OpenAiCompatibleLlmBackendTest`

Expected: FAIL because `taskSpec` and task intent wiring do not exist yet.

- [ ] **Step 3: Write failing dialogue mapping tests**

```java
@Test
void mapsSubmitTaskPlannerIntentIntoDialogueIntent() {
	PlannerOrchestrator orchestrator = orchestratorReturning(new PlannerResponse(
		"On it.",
		new PlannerIntent(
			"submit_task",
			null,
			null,
			null,
			null,
			new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 16)
		),
		null
	));
	DialogueRuntime runtime = new DialogueRuntime(orchestrator, 8, CLOCK);

	runtime.onPlayerChat("rin", "get wood", 20L, SESSION, null, Optional.empty(), EVENT_BUFFER);
	DialogueResponse response = runtime.poll(20L, EVENT_BUFFER);

	assertEquals(DialogueIntentType.SUBMIT_TASK, response.intent().type());
	assertEquals(TaskType.COLLECT_RESOURCE, response.intent().taskSpec().type());
}
```

- [ ] **Step 4: Run dialogue tests to verify RED**

Run: `source .envrc && ./gradlew test -x wrapper:test --tests ai.moeru.airicraft.agent.dialogue.DialogueRuntimeTest`

Expected: FAIL because `DialogueIntentType.SUBMIT_TASK` and `taskSpec` support are missing.

- [ ] **Step 5: Implement minimal task intent parsing and mapping**

```java
public record PlannerIntent(
	String type,
	GoalType goalType,
	String targetPlayer,
	GoalPosition position,
	GoalMineSpec mineSpec,
	TaskSpec taskSpec
) {
}

public enum DialogueIntentType {
	SET_GOAL,
	CLEAR_GOAL,
	SUBMIT_TASK,
	CANCEL_TASK,
	REPLY_ONLY,
	ASK_CLARIFICATION,
	ACKNOWLEDGE_FAILURE,
	NONE
}
```

```java
private static TaskSpec parseTaskSpec(JsonObject object, String fieldName) {
	Optional<JsonObject> taskObject = getObject(object, fieldName);
	if (taskObject.isEmpty()) {
		return null;
	}
	TaskType type = TaskType.valueOf(getString(taskObject.get(), "type").orElseThrow());
	TaskResourceKind resourceKind = TaskResourceKind.valueOf(getString(taskObject.get(), "resourceKind").orElseThrow());
	int quantity = getInt(taskObject.get(), "quantity").orElseThrow();
	return new TaskSpec(type, resourceKind, quantity);
}
```

- [ ] **Step 6: Update the planner prompt contract**

```java
"intent": {
  "type": "submit_task" | "cancel_task" | "set_goal" | "clear_goal" | "reply_only" | "ask_clarification" | "acknowledge_failure" | "none",
  "goalType": "FOLLOW_PLAYER" | "NAVIGATE_TO" | "MINE_BLOCKS" | null,
  "taskSpec": {
    "type": "COLLECT_RESOURCE",
    "resourceKind": "WOOD_LOGS",
    "quantity": 16
  } | null
}
```

- [ ] **Step 7: Run the parser and dialogue tests to verify GREEN**

Run: `source .envrc && ./gradlew test -x wrapper:test --tests ai.moeru.airicraft.agent.llm.OpenAiCompatibleLlmBackendTest --tests ai.moeru.airicraft.agent.dialogue.DialogueRuntimeTest`

Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add src/client/java/ai/moeru/airicraft/agent/llm/PlannerIntent.java \
  src/client/java/ai/moeru/airicraft/agent/dialogue/DialogueIntent.java \
  src/client/java/ai/moeru/airicraft/agent/dialogue/DialogueIntentType.java \
  src/client/java/ai/moeru/airicraft/agent/llm/OpenAiCompatibleLlmBackend.java \
  src/client/java/ai/moeru/airicraft/agent/llm/PlannerPromptPolicy.java \
  src/client/java/ai/moeru/airicraft/agent/dialogue/DialogueRuntime.java \
  src/test/java/ai/moeru/airicraft/agent/llm/OpenAiCompatibleLlmBackendTest.java \
  src/test/java/ai/moeru/airicraft/agent/dialogue/DialogueRuntimeTest.java
git commit -m "feat: add planner task intent contract"
```

### Task 2: Task Domain Model And Runtime Skeleton

**Files:**
- Create: `src/client/java/ai/moeru/airicraft/agent/tasks/TaskType.java`
- Create: `src/client/java/ai/moeru/airicraft/agent/tasks/TaskState.java`
- Create: `src/client/java/ai/moeru/airicraft/agent/tasks/TaskResourceKind.java`
- Create: `src/client/java/ai/moeru/airicraft/agent/tasks/TaskSpec.java`
- Create: `src/client/java/ai/moeru/airicraft/agent/tasks/TaskSnapshot.java`
- Create: `src/client/java/ai/moeru/airicraft/agent/tasks/TaskProgressSnapshot.java`
- Create: `src/client/java/ai/moeru/airicraft/agent/tasks/TaskStep.java`
- Create: `src/client/java/ai/moeru/airicraft/agent/tasks/TaskOwnership.java`
- Create: `src/client/java/ai/moeru/airicraft/agent/tasks/TaskRuntime.java`
- Modify: `src/client/java/ai/moeru/airicraft/agent/AgentRuntimeSnapshot.java`
- Modify: `src/client/java/ai/moeru/airicraft/agent/EmbodiedAgentRuntime.java`
- Test: `src/test/java/ai/moeru/airicraft/agent/tasks/TaskRuntimeTest.java`
- Test: `src/test/java/ai/moeru/airicraft/agent/EmbodiedAgentRuntimeTest.java`

- [ ] **Step 1: Write failing task runtime state machine tests**

```java
@Test
void submitTaskCreatesQueuedSnapshot() {
	TaskRuntime runtime = new TaskRuntime(HANDLER, INVENTORY_COUNTER);

	runtime.submit(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 8), 100L, "rin");

	TaskSnapshot snapshot = runtime.snapshot();
	assertEquals(TaskState.QUEUED, snapshot.state());
	assertEquals(TaskType.COLLECT_RESOURCE, snapshot.spec().type());
	assertEquals(0, snapshot.progress().collected());
}

@Test
void cancelTaskTransitionsToCancelledAndClearsOwnedGoal() {
	TaskRuntime runtime = new TaskRuntime(HANDLER, INVENTORY_COUNTER);
	runtime.submit(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 8), 100L, "rin");

	runtime.cancel(140L, "user_cancelled");

	assertEquals(TaskState.CANCELLED, runtime.snapshot().state());
	assertTrue(runtime.currentGoal().isEmpty());
}
```

- [ ] **Step 2: Run task runtime tests to verify RED**

Run: `source .envrc && ./gradlew test -x wrapper:test --tests ai.moeru.airicraft.agent.tasks.TaskRuntimeTest`

Expected: FAIL because the task domain model does not exist yet.

- [ ] **Step 3: Write failing runtime ownership tests**

```java
@Test
void directGoalSubmissionCancelsActiveTaskFirst() {
	EmbodiedAgentRuntime runtime = runtimeWithTaskSupport();
	runtime.injectDialogueResponseForTests(new DialogueResponse(
		"",
		new DialogueIntent(DialogueIntentType.SUBMIT_TASK, null, null, null, null, new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 4)),
		20L
	));
	runtime.tick(client(), 20L);

	runtime.injectDialogueResponseForTests(new DialogueResponse(
		"",
		new DialogueIntent(DialogueIntentType.SET_GOAL, GoalType.NAVIGATE_TO, null, POSITION, null, null),
		30L
	));
	runtime.tick(client(), 30L);

	assertEquals(TaskState.CANCELLED, runtime.snapshot().task().state());
	assertEquals(GoalType.NAVIGATE_TO, runtime.activeGoal().orElseThrow().type());
}
```

- [ ] **Step 4: Run runtime ownership tests to verify RED**

Run: `source .envrc && ./gradlew test -x wrapper:test --tests ai.moeru.airicraft.agent.EmbodiedAgentRuntimeTest`

Expected: FAIL because the runtime does not yet coordinate tasks and direct goals.

- [ ] **Step 5: Implement the task domain model and runtime skeleton**

```java
public record TaskSpec(
	TaskType type,
	TaskResourceKind resourceKind,
	int quantity
) {
	public TaskSpec {
		if (quantity <= 0) {
			throw new IllegalArgumentException("quantity must be positive");
		}
	}
}
```

```java
public final class TaskRuntime {
	private TaskSnapshot snapshot = TaskSnapshot.idle();
	private GoalSnapshot currentGoal;

	public void submit(TaskSpec spec, long tick, String source) {
		snapshot = TaskSnapshot.queued(spec, source, tick);
		currentGoal = null;
	}

	public void cancel(long tick, String reason) {
		if (snapshot.state() == TaskState.IDLE) {
			return;
		}
		snapshot = snapshot.withTerminalState(TaskState.CANCELLED, reason, tick);
		currentGoal = null;
	}
}
```

- [ ] **Step 6: Thread task snapshot through `EmbodiedAgentRuntime`**

```java
private final TaskRuntime taskRuntime;
private TaskSnapshot taskSnapshot = TaskSnapshot.idle();

public AgentRuntimeSnapshot snapshot() {
	return new AgentRuntimeSnapshot(
		initialized,
		tickCount,
		sessionSnapshot,
		activeGoal(),
		taskExecutionSnapshot,
		taskSnapshot,
		dialogueRuntime.snapshot(),
		behaviorTreeRuntime.snapshot(),
		verificationRuntime.snapshot()
	);
}
```

- [ ] **Step 7: Run task runtime and embodied runtime tests to verify GREEN**

Run: `source .envrc && ./gradlew test -x wrapper:test --tests ai.moeru.airicraft.agent.tasks.TaskRuntimeTest --tests ai.moeru.airicraft.agent.EmbodiedAgentRuntimeTest`

Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add src/client/java/ai/moeru/airicraft/agent/tasks/TaskType.java \
  src/client/java/ai/moeru/airicraft/agent/tasks/TaskState.java \
  src/client/java/ai/moeru/airicraft/agent/tasks/TaskResourceKind.java \
  src/client/java/ai/moeru/airicraft/agent/tasks/TaskSpec.java \
  src/client/java/ai/moeru/airicraft/agent/tasks/TaskSnapshot.java \
  src/client/java/ai/moeru/airicraft/agent/tasks/TaskProgressSnapshot.java \
  src/client/java/ai/moeru/airicraft/agent/tasks/TaskStep.java \
  src/client/java/ai/moeru/airicraft/agent/tasks/TaskOwnership.java \
  src/client/java/ai/moeru/airicraft/agent/tasks/TaskRuntime.java \
  src/client/java/ai/moeru/airicraft/agent/AgentRuntimeSnapshot.java \
  src/client/java/ai/moeru/airicraft/agent/EmbodiedAgentRuntime.java \
  src/test/java/ai/moeru/airicraft/agent/tasks/TaskRuntimeTest.java \
  src/test/java/ai/moeru/airicraft/agent/EmbodiedAgentRuntimeTest.java
git commit -m "feat: add task runtime skeleton"
```

### Task 3: Wood Collection Decomposition And Semantic Progress

**Files:**
- Create: `src/client/java/ai/moeru/airicraft/agent/tasks/CollectResourceTaskHandler.java`
- Create: `src/client/java/ai/moeru/airicraft/agent/tasks/InventoryResourceCounter.java`
- Create: `src/client/java/ai/moeru/airicraft/agent/tasks/NearbyTreeLocator.java`
- Modify: `src/client/java/ai/moeru/airicraft/agent/tasks/TaskRuntime.java`
- Modify: `src/client/java/ai/moeru/airicraft/agent/EmbodiedAgentRuntime.java`
- Modify: `src/client/java/ai/moeru/airicraft/agent/behavior/BehaviorTreeRuntime.java`
- Test: `src/test/java/ai/moeru/airicraft/agent/tasks/CollectResourceTaskHandlerTest.java`
- Test: `src/test/java/ai/moeru/airicraft/agent/tasks/InventoryResourceCounterTest.java`
- Test: `src/test/java/ai/moeru/airicraft/agent/tasks/NearbyTreeLocatorTest.java`
- Test: `src/test/java/ai/moeru/airicraft/agent/tasks/TaskRuntimeTest.java`

- [ ] **Step 1: Write failing resource counting and locator tests**

```java
@Test
void countsAcceptedWoodLogsAcrossSpecies() {
	PlayerInventory inventory = inventoryWith(
		stack("minecraft:oak_log", 3),
		stack("minecraft:birch_log", 5),
		stack("minecraft:cobblestone", 12)
	);

	assertEquals(8, new InventoryResourceCounter().count(inventory, TaskResourceKind.WOOD_LOGS));
}

@Test
void findsNearestReachableLogCandidate() {
	World world = worldWithLogAt(new BlockPos(4, 64, 2));
	NearbyTreeLocator locator = new NearbyTreeLocator(8);

	Optional<BlockPos> result = locator.findNearest(playerAt(0, 64, 0), world, TaskResourceKind.WOOD_LOGS);

	assertEquals(new BlockPos(4, 64, 2), result.orElseThrow());
}
```

- [ ] **Step 2: Run resource helper tests to verify RED**

Run: `source .envrc && ./gradlew test -x wrapper:test --tests ai.moeru.airicraft.agent.tasks.InventoryResourceCounterTest --tests ai.moeru.airicraft.agent.tasks.NearbyTreeLocatorTest`

Expected: FAIL because helper classes do not exist yet.

- [ ] **Step 3: Write failing task progression tests**

```java
@Test
void collectResourceTaskEmitsNavigateThenMineAndCompletesOnInventoryDelta() {
	FakeTaskWorld world = FakeTaskWorld.withNearbyLog(new BlockPos(2, 64, 1), "minecraft:oak_log");
	TaskRuntime runtime = runtimeFor(world, inventoryCount(0));

	runtime.submit(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 4), 10L, "rin");
	runtime.tick(world.session(), Optional.empty(), TaskExecutionSnapshot.idle(), 11L);
	assertEquals(GoalType.NAVIGATE_TO, runtime.currentGoal().orElseThrow().type());

	runtime.tick(world.session(), runtime.currentGoal(), TaskExecutionSnapshot.completed(runtime.currentGoal().orElseThrow()), 12L);
	assertEquals(GoalType.MINE_BLOCKS, runtime.currentGoal().orElseThrow().type());

	world.setInventoryCount(TaskResourceKind.WOOD_LOGS, 4);
	runtime.tick(world.session(), runtime.currentGoal(), TaskExecutionSnapshot.completed(runtime.currentGoal().orElseThrow()), 13L);

	assertEquals(TaskState.COMPLETED, runtime.snapshot().state());
	assertEquals(4, runtime.snapshot().progress().collected());
}
```

- [ ] **Step 4: Run task progression test to verify RED**

Run: `source .envrc && ./gradlew test -x wrapper:test --tests ai.moeru.airicraft.agent.tasks.TaskRuntimeTest`

Expected: FAIL because the runtime does not yet decompose collection tasks.

- [ ] **Step 5: Implement minimal helpers and collection handler**

```java
public final class InventoryResourceCounter {
	public int count(PlayerInventory inventory, TaskResourceKind resourceKind) {
		return switch (resourceKind) {
			case WOOD_LOGS -> countMatching(inventory, Set.of(
				"minecraft:oak_log",
				"minecraft:birch_log",
				"minecraft:spruce_log",
				"minecraft:jungle_log",
				"minecraft:acacia_log",
				"minecraft:dark_oak_log",
				"minecraft:mangrove_log",
				"minecraft:cherry_log"
			));
		};
	}
}
```

```java
public GoalSnapshot nextGoal(TaskSnapshot snapshot, TaskWorldView worldView, long tick) {
	Optional<BlockPos> candidate = treeLocator.findNearest(worldView.player(), worldView.world(), snapshot.spec().resourceKind());
	if (candidate.isEmpty()) {
		throw new TaskStepFailure("no_tree_candidate");
	}
	return new GoalSnapshot(
		GoalType.NAVIGATE_TO,
		null,
		new GoalPosition(candidate.get().getX(), candidate.get().getY(), candidate.get().getZ(), false),
		null,
		tick,
		"task_runtime"
	);
}
```

- [ ] **Step 6: Implement task runtime progression and event emission**

```java
if (snapshot.state() == TaskState.QUEUED) {
	currentGoal = handler.start(spec, worldView, tick);
	snapshot = snapshot.withRunningStep(TaskStep.NAVIGATE_TO_TARGET, currentGoal, baselineCount);
	return;
}
if (taskExecution.state() == TaskExecutionState.COMPLETED && currentGoal.type() == GoalType.NAVIGATE_TO) {
	currentGoal = handler.beginMining(snapshot, worldView, tick);
	snapshot = snapshot.withRunningStep(TaskStep.MINE_TARGET, currentGoal, currentCollected);
	return;
}
if (inventoryCount >= snapshot.spec().quantity()) {
	snapshot = snapshot.withTerminalState(TaskState.COMPLETED, null, tick);
	currentGoal = null;
}
```

- [ ] **Step 7: Run helper and task runtime tests to verify GREEN**

Run: `source .envrc && ./gradlew test -x wrapper:test --tests ai.moeru.airicraft.agent.tasks.InventoryResourceCounterTest --tests ai.moeru.airicraft.agent.tasks.NearbyTreeLocatorTest --tests ai.moeru.airicraft.agent.tasks.CollectResourceTaskHandlerTest --tests ai.moeru.airicraft.agent.tasks.TaskRuntimeTest`

Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add src/client/java/ai/moeru/airicraft/agent/tasks/CollectResourceTaskHandler.java \
  src/client/java/ai/moeru/airicraft/agent/tasks/InventoryResourceCounter.java \
  src/client/java/ai/moeru/airicraft/agent/tasks/NearbyTreeLocator.java \
  src/client/java/ai/moeru/airicraft/agent/tasks/TaskRuntime.java \
  src/client/java/ai/moeru/airicraft/agent/EmbodiedAgentRuntime.java \
  src/client/java/ai/moeru/airicraft/agent/behavior/BehaviorTreeRuntime.java \
  src/test/java/ai/moeru/airicraft/agent/tasks/CollectResourceTaskHandlerTest.java \
  src/test/java/ai/moeru/airicraft/agent/tasks/InventoryResourceCounterTest.java \
  src/test/java/ai/moeru/airicraft/agent/tasks/NearbyTreeLocatorTest.java \
  src/test/java/ai/moeru/airicraft/agent/tasks/TaskRuntimeTest.java
git commit -m "feat: add wood collection task runtime"
```

### Task 4: Bridge, Wrapper, And Task Instrumentation

**Files:**
- Modify: `src/client/java/ai/moeru/airicraft/ModBridgeServer.java`
- Modify: `wrapper/src/main/java/ai/moeru/airicraft/wrapper/HttpBridgeTransport.java`
- Modify: `wrapper/src/main/java/ai/moeru/airicraft/wrapper/AiricraftCliMain.java`
- Modify: `src/client/java/ai/moeru/airicraft/agent/AgentRuntimeSnapshot.java`
- Modify: `src/client/java/ai/moeru/airicraft/agent/EmbodiedAgentRuntime.java`
- Test: `wrapper/src/test/java/ai/moeru/airicraft/wrapper/HttpBridgeTransportTest.java`
- Test: `wrapper/src/test/java/ai/moeru/airicraft/wrapper/AiricraftCliMainTest.java`
- Test: `src/test/java/ai/moeru/airicraft/agent/EmbodiedAgentRuntimeTest.java`

- [ ] **Step 1: Write failing wrapper transport and CLI tests**

```java
@Test
void getAgentTasksCallsTaskSnapshotEndpoint() {
	FakeBridgeTransport transport = new FakeBridgeTransport(Map.of("state", "RUNNING"));

	Map<String, Object> payload = transport.getAgentTasks();

	assertEquals("/v1/agent/tasks", transport.lastPath());
	assertEquals("RUNNING", payload.get("state"));
}

@Test
void taskSubmitCommandPrintsDeterministicPayload() {
	int exitCode = runCli(
		"agent", "tasks", "submit",
		"--type", "collect-resource",
		"--resource", "wood-logs",
		"--quantity", "16"
	);

	assertEquals(0, exitCode);
	assertOutputContains("command: agent tasks submit");
	assertOutputContains("type: COLLECT_RESOURCE");
	assertOutputContains("resourceKind: WOOD_LOGS");
}
```

- [ ] **Step 2: Run wrapper tests to verify RED**

Run: `source .envrc && ./gradlew wrapper:test --tests ai.moeru.airicraft.wrapper.HttpBridgeTransportTest --tests ai.moeru.airicraft.wrapper.AiricraftCliMainTest`

Expected: FAIL because task endpoints and CLI commands do not exist yet.

- [ ] **Step 3: Write failing mod bridge/runtime snapshot tests**

```java
@Test
void taskSnapshotIsExposedInAgentRuntimeSnapshot() {
	EmbodiedAgentRuntime runtime = runtimeWithTaskSupport();
	runtime.submitTaskForTests(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 4));
	runtime.tick(client(), 25L);

	assertEquals(TaskState.QUEUED, runtime.snapshot().task().state());
}
```

- [ ] **Step 4: Run bridge/runtime tests to verify RED**

Run: `source .envrc && ./gradlew test -x wrapper:test --tests ai.moeru.airicraft.agent.EmbodiedAgentRuntimeTest`

Expected: FAIL because the mod-side snapshot and task bridge handlers are incomplete.

- [ ] **Step 5: Implement bridge endpoints and wrapper commands**

```java
httpServer.createContext("/v1/agent/tasks", this::handleAgentTasks);
```

```java
@Command(name = "tasks", mixinStandardHelpOptions = true, description = "Inspect and control high-level agent tasks.")
private static final class AgentTasksCommand extends BaseCommand {
	@Override
	Map<String, Object> runCommand() {
		return transport().getAgentTasks();
	}
}
```

```java
@Command(name = "submit", mixinStandardHelpOptions = true, description = "Submit a high-level task.")
private static final class AgentTasksSubmitCommand extends BaseCommand {
	@Option(names = "--type", required = true)
	private String type;
	@Option(names = "--resource", required = true)
	private String resource;
	@Option(names = "--quantity", required = true)
	private int quantity;

	@Override
	Map<String, Object> runCommand() {
		return transport().submitAgentTask(Map.of(
			"type", normalizeTaskType(type),
			"resourceKind", normalizeResource(resource),
			"quantity", quantity
		));
	}
}
```

- [ ] **Step 6: Run wrapper and mod-side tests to verify GREEN**

Run: `source .envrc && ./gradlew test wrapper:test --tests ai.moeru.airicraft.agent.EmbodiedAgentRuntimeTest --tests ai.moeru.airicraft.wrapper.HttpBridgeTransportTest --tests ai.moeru.airicraft.wrapper.AiricraftCliMainTest`

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/client/java/ai/moeru/airicraft/ModBridgeServer.java \
  src/client/java/ai/moeru/airicraft/agent/AgentRuntimeSnapshot.java \
  src/client/java/ai/moeru/airicraft/agent/EmbodiedAgentRuntime.java \
  wrapper/src/main/java/ai/moeru/airicraft/wrapper/HttpBridgeTransport.java \
  wrapper/src/main/java/ai/moeru/airicraft/wrapper/AiricraftCliMain.java \
  wrapper/src/test/java/ai/moeru/airicraft/wrapper/HttpBridgeTransportTest.java \
  wrapper/src/test/java/ai/moeru/airicraft/wrapper/AiricraftCliMainTest.java \
  src/test/java/ai/moeru/airicraft/agent/EmbodiedAgentRuntimeTest.java
git commit -m "feat: expose agent task debug surface"
```

### Task 5: Live Verification And End-To-End Task Scenario

Historical note: live end-to-end verification now belongs to the reusable evaluator under `scenarios/` and `scripts/run-evaluation-scenarios`; the retired in-process verification DSL has been removed.

**Files:**
- Add: `scenarios/<scenario-id>/scenario.yml`
- Modify: `src/client/java/ai/moeru/airicraft/agent/EmbodiedAgentRuntime.java`
- Modify: `docs/superpowers/plans/2026-04-06-baritone-planner-integration.md`

- [ ] **Step 1: Add a dedicated controlled task verification entry point**

```java
// Express the prompt, budget, outcome checks, and evidence settings in scenario.yml.
```

- [ ] **Step 2: Start the client and verify bridge/wrapper availability**

Run: `source .envrc && ./gradlew runClient`

Expected: client starts, bridge state file is written, `airicraft status` reports `available: true`

- [ ] **Step 3: Submit a wood collection task through the wrapper and observe progress**

Run: `source .envrc && wrapper/build/install/airicraft/bin/airicraft agent tasks submit --type collect-resource --resource wood-logs --quantity 4 --verbose`

Expected:
- `status: ok`
- `command: agent tasks submit`
- task snapshot shows `state: QUEUED` or `state: RUNNING`

- [ ] **Step 4: Verify primitive execution and completion checkpoints**

Run: `source .envrc && wrapper/build/install/airicraft/bin/airicraft agent status --verbose`

Expected:
- `task.state` transitions through `RUNNING`
- `taskExecution.processName` shows a real Baritone process
- `task.progress.collected` increases
- `task.state` reaches `COMPLETED`

- [ ] **Step 5: Verify planner coherence after task completion**

Run: `source .envrc && wrapper/build/install/airicraft/bin/airicraft agent events recent --verbose`

Expected:
- `task.submitted`
- `task.step_started`
- `task.progress`
- `task.completed`
- internal planner follow-up does not degrade the runtime

- [ ] **Step 6: Update the Baritone integration plan checklist with the live result**

```markdown
- [x] Task runtime supports wrapper-submitted wood collection tasks end to end.
- [x] Live task verification completed in a controlled world.
```

- [ ] **Step 7: Commit**

```bash
git add scenarios/<scenario-id>/scenario.yml \
  src/client/java/ai/moeru/airicraft/agent/EmbodiedAgentRuntime.java \
  docs/superpowers/plans/2026-04-06-baritone-planner-integration.md
git commit -m "test: verify wood collection task live"
```

## Self-Review

- Spec coverage: planner contract, task runtime, collection decomposition, instrumentation, bridge/wrapper surface, and live verification each have a dedicated task.
- Placeholder scan: there are no `TBD` or “implement later” placeholders; each task names exact files, tests, commands, and minimal code direction.
- Type consistency: the plan consistently uses `TaskSpec`, `TaskType.COLLECT_RESOURCE`, `TaskResourceKind.WOOD_LOGS`, `TaskSnapshot`, and `DialogueIntentType.SUBMIT_TASK` across all tasks.
