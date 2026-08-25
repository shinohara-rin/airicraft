package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.agent.tasks.CraftingOpportunity;
import ai.moeru.airicraft.agent.tasks.SmeltingRecipeKnowledge;
import ai.moeru.airicraft.agent.tasks.TaskExecutionState;
import ai.moeru.airicraft.agent.tasks.TaskFailureCode;
import ai.moeru.airicraft.agent.tasks.TaskTerminalEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionGraphExecutionRuntimeTest {
	private static final ActionResolverContext CONTEXT = new ActionResolverContext(
		"world-a",
		"bot",
		"minecraft:overworld",
		100
	);

	@Test
	void dedicatedResolutionExecutorNeverBlocksForegroundTick() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		CountDownLatch workerOccupied = new CountDownLatch(1);
		CountDownLatch releaseWorker = new CountDownLatch(1);
		try {
			executor.submit(() -> {
				workerOccupied.countDown();
				try {
					releaseWorker.await();
				}
				catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
			});
			assertTrue(workerOccupied.await(1, TimeUnit.SECONDS));

			RecordingDispatcher dispatcher = new RecordingDispatcher();
			ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(dispatcher, executor);
			runtime.submit(ActionGoal.inventoryItem("minecraft:bread", 1), Map.of("minecraft:wheat", 3), CONTEXT, 100);

			ActionGraphExecutionSnapshot resolving = assertTimeoutPreemptively(
				Duration.ofMillis(200),
				() -> runtime.tick(input(Map.of("minecraft:wheat", 3), null, 101))
			);

			assertEquals(ActionGraphExecutionState.RESOLVING, resolving.state());
			assertTrue(dispatcher.dispatchedSteps.isEmpty());
			assertTrace(resolving.trace(), "resolution_scheduled");

			releaseWorker.countDown();
			ActionGraphExecutionSnapshot completedResolution = resolving;
			for (int attempt = 0; attempt < 100 && completedResolution.state() == ActionGraphExecutionState.RESOLVING; attempt++) {
				Thread.sleep(5L);
				completedResolution = runtime.tick(input(Map.of("minecraft:wheat", 3), null, 102 + attempt));
			}
			assertEquals(ActionGraphExecutionState.WAITING_PRIMITIVE, completedResolution.state());
			assertEquals(1, dispatcher.dispatchedSteps.size());
		}
		finally {
			releaseWorker.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void verbosePayloadCapsTraceAndRecoveryHistory() {
		List<ActionTraceEvent> trace = new ArrayList<>();
		for (int index = 0; index < 45; index++) {
			trace.add(new ActionTraceEvent("event_" + index, "action", "alternative", "step", Map.of()));
		}
		List<Map<String, Object>> recovery = new ArrayList<>();
		for (int index = 0; index < 12; index++) {
			recovery.add(Map.of("index", index));
		}
		ActionGraphExecutionSnapshot snapshot = new ActionGraphExecutionSnapshot(
			true,
			"action-graph-test",
			ActionGraphExecutionState.FAILED,
			ActionGoal.inventoryItem("minecraft:iron_pickaxe", 1),
			ActionRoute.empty(),
			0,
			null,
			0,
			0,
			0,
			"",
			"stale-task-id",
			"no_route",
			"no route",
			Map.of(),
			trace,
			recovery,
			Map.of(),
			Map.of(),
			Map.of()
		);

		Map<String, Object> payload = snapshot.toPayload(true);

		assertEquals(45, payload.get("traceEventCount"));
		assertEquals(5, payload.get("traceOmitted"));
		assertEquals(40, list(payload.get("trace")).size());
		assertEquals("event_5", map(list(payload.get("trace")).getFirst()).get("eventType"));
		assertEquals(2, payload.get("recoveryHistoryOmitted"));
		assertEquals(10, list(payload.get("recoveryHistory")).size());
		assertEquals(false, payload.get("activePrimitive"));
	}

	@Test
	void resourceGoalDispatchesProviderAndCompletesAfterObservedResourceFact() {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(dispatcher);
		runtime.submit(ActionGoal.resourceCollection("WOOD_LOGS", 3), Map.of(), CONTEXT, 100);

		ActionGraphExecutionSnapshot dispatched = runtime.tick(inputWithResources(Map.of("WOOD_LOGS", 1), null, 101));

		assertEquals(ActionGraphExecutionState.WAITING_PRIMITIVE, dispatched.state());
		assertEquals(1, dispatcher.dispatchedSteps.size());
		ActionPlanStep step = dispatcher.dispatchedSteps.getFirst();
		assertEquals("resource_provider", step.actionId());
		assertEquals("collect_resource", step.targetId());
		assertEquals("WOOD_LOGS", step.args().get("resourceKind"));
		assertEquals(2, step.args().get("quantity"));

		ActionGraphExecutionSnapshot completed = runtime.tick(inputWithResources(
			Map.of("WOOD_LOGS", 3),
			new TaskTerminalEvent("task-1", null, TaskExecutionState.COMPLETED, "collected", null),
			102
		));

		assertEquals(ActionGraphExecutionState.SUCCEEDED, completed.state());
		assertTrace(completed.trace(), "execution_succeeded");
	}

	@Test
	void dispatchesFirstPrimitiveAndCompletesAfterObservedGoalFact() {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(dispatcher);
		runtime.submit(ActionGoal.inventoryItem("minecraft:bread", 1), Map.of("minecraft:wheat", 3), CONTEXT, 100);

		ActionGraphExecutionSnapshot dispatched = runtime.tick(input(Map.of("minecraft:wheat", 3), null, 101));

		assertEquals(ActionGraphExecutionState.WAITING_PRIMITIVE, dispatched.state());
		assertEquals(1, dispatcher.dispatchedSteps.size());
		assertEquals("craft_item", dispatcher.dispatchedSteps.getFirst().targetId());
		assertEquals("task-1", dispatched.activeTaskId());
		assertEquals(1, dispatched.stepAttempt());

		ActionGraphExecutionSnapshot completed = runtime.tick(input(
			Map.of("minecraft:wheat", 3, "minecraft:bread", 1),
			new TaskTerminalEvent("task-1", null, TaskExecutionState.COMPLETED, "crafted", null),
			102
		));

		assertEquals(ActionGraphExecutionState.SUCCEEDED, completed.state());
		assertEquals(1, completed.cursor());
		assertEquals("", completed.failureCode());
		assertTrace(completed.trace(), "primitive_terminal");
		assertTrace(completed.trace(), "execution_succeeded");
	}

	@Test
	void reflexPausePreservesActionGraphIdentityAndResumesSamePrimitive() {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(dispatcher);
		ActionGraphExecutionSnapshot started = runtime.submit(
			ActionGoal.inventoryItem("minecraft:bread", 1),
			Map.of("minecraft:wheat", 3),
			CONTEXT,
			100
		);
		ActionGraphExecutionSnapshot dispatched = runtime.tick(input(Map.of("minecraft:wheat", 3), null, 101));

		ActionGraphExecutionSnapshot paused = runtime.pauseForReflex(102);

		assertEquals(started.executionId(), paused.executionId());
		assertEquals(dispatched.activeTaskId(), paused.activeTaskId());
		assertEquals(ActionGraphExecutionState.BLOCKED, paused.state());
		assertEquals("reflex", paused.failureCode());
		assertTrace(paused.trace(), "reflex_pause");

		ActionGraphExecutionSnapshot stillPaused = runtime.tick(new ActionGraphExecutionInput(
			new ActionResolverContext("world-a", "bot", "minecraft:overworld", 103),
			Map.of("minecraft:wheat", 3),
			Map.of(),
			true,
			false,
			null,
			List.of(), List.of(), List.of(), List.of(), List.of(), null, Map.of(),
			BlockAcquisitionIndex.empty(), NearbyBlockAvailability.unknown()
		));
		assertEquals(ActionGraphExecutionState.BLOCKED, stillPaused.state());

		ActionGraphExecutionSnapshot resumed = runtime.tick(new ActionGraphExecutionInput(
			new ActionResolverContext("world-a", "bot", "minecraft:overworld", 104),
			Map.of("minecraft:wheat", 3),
			Map.of(),
			true,
			true,
			null,
			List.of(), List.of(), List.of(), List.of(), List.of(), null, Map.of(),
			BlockAcquisitionIndex.empty(), NearbyBlockAvailability.unknown()
		));
		assertEquals(started.executionId(), resumed.executionId());
		assertEquals(dispatched.activeTaskId(), resumed.activeTaskId());
		assertEquals(ActionGraphExecutionState.WAITING_PRIMITIVE, resumed.state());
	}

	@Test
	void zeroStepRouteSucceedsOnlyAfterGoalFactIsObserved() {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(dispatcher);
		runtime.submit(ActionGoal.inventoryItem("minecraft:bread", 1), Map.of(), CONTEXT, 100);

		ActionGraphExecutionSnapshot noRoute = runtime.tick(input(Map.of(), null, 101));

		assertEquals(ActionGraphExecutionState.FAILED, noRoute.state());
		assertEquals("no_route", noRoute.failureCode());

		runtime.submit(ActionGoal.inventoryItem("minecraft:bread", 1), Map.of(), CONTEXT, 102);
		ActionGraphExecutionSnapshot satisfied = runtime.tick(input(Map.of("minecraft:bread", 1), null, 103));

		assertEquals(ActionGraphExecutionState.SUCCEEDED, satisfied.state());
		assertTrue(dispatcher.dispatchedSteps.isEmpty());
		assertTrace(satisfied.trace(), "step_skipped");
	}

	@Test
	void availableCraftFactsEnableGenericRecipeRoute() {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(dispatcher);
		runtime.submit(ActionGoal.inventoryItem("minecraft:bread", 1), Map.of("minecraft:wheat", 3), CONTEXT, 100);

		ActionGraphExecutionSnapshot dispatched = runtime.tick(input(
			Map.of("minecraft:wheat", 3),
			null,
			101,
			List.of(new CraftingOpportunity(
				"wheat_wheat_wheat_to_bread",
				"minecraft:bread",
				1,
				List.of("minecraft:wheat", "minecraft:wheat", "minecraft:wheat")
			))
		));

		assertEquals(ActionGraphExecutionState.WAITING_PRIMITIVE, dispatched.state());
		assertEquals(1, dispatcher.dispatchedSteps.size());
		ActionPlanStep craft = dispatcher.dispatchedSteps.getFirst();
		assertEquals("recipe_provider", craft.actionId());
		assertEquals("craft_item", craft.targetId());
		assertEquals("wheat_wheat_wheat_to_bread", craft.args().get("recipeId"));
		assertEquals(1, craft.args().get("quantity"));
	}

	@Test
	void knownCraftFactsEnablePlanningBeforeRecipeIsExecutable() {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(dispatcher);
		runtime.submit(ActionGoal.inventoryItem("minecraft:stick", 4), Map.of("minecraft:oak_planks", 2), CONTEXT, 100);

		ActionGraphExecutionSnapshot dispatched = runtime.tick(inputWithKnownCrafts(
			Map.of("minecraft:oak_planks", 2),
			null,
			101,
			List.of(new CraftingOpportunity(
				"oak_planks_x2_to_stick",
				"minecraft:stick",
				4,
				List.of("minecraft:oak_planks", "minecraft:oak_planks")
			))
		));

		assertEquals(ActionGraphExecutionState.WAITING_PRIMITIVE, dispatched.state());
		assertEquals(1, dispatcher.dispatchedSteps.size());
		ActionPlanStep craft = dispatcher.dispatchedSteps.getFirst();
		assertEquals("recipe_provider", craft.actionId());
		assertEquals("craft_item", craft.targetId());
		assertEquals("oak_planks_x2_to_stick", craft.args().get("recipeId"));
		assertEquals(4, craft.args().get("quantity"));
		assertTrace(dispatched.trace(), "fact_observed");
	}

	@Test
	void broadIronPickaxeGoalStartsThroughInferredSurvivalKnowledge() {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(dispatcher);
		runtime.submit(ActionGoal.inventoryItem("minecraft:iron_pickaxe", 1), Map.of("minecraft:birch_planks", 3), CONTEXT, 100);

		ActionGraphExecutionSnapshot dispatched = runtime.tick(inputWithKnownCraftsAndSmelts(
			Map.of("minecraft:birch_planks", 3),
			null,
			101,
			ActionGraphRecipeFixtures.survivalCrafts(),
			ActionGraphRecipeFixtures.survivalSmelts()
		));

		assertEquals(ActionGraphExecutionState.WAITING_PRIMITIVE, dispatched.state());
		assertEquals(1, dispatcher.dispatchedSteps.size());
		ActionPlanStep first = dispatcher.dispatchedSteps.getFirst();
		assertEquals(ActionStepKind.PRIMITIVE, first.kind());
		assertTrue(dispatched.route().steps().stream().anyMatch(step ->
			"smelt_item".equals(step.targetId())
				&& "inferred:minecraft_raw_iron_to_minecraft_iron_ingot".equals(step.args().get("optionId"))
		), () -> dispatched.route().toString());
		assertTrue(dispatched.route().steps().stream().anyMatch(step ->
			"craft_item".equals(step.targetId())
				&& "minecraft:iron_pickaxe".equals(step.args().get("itemId"))
		), () -> dispatched.route().toString());
		assertTrue(dispatched.trace().stream().anyMatch(event ->
			"route_selected".equals(event.eventType())
				&& "smelting_provider".equals(event.actionId())
				&& event.alternativeId().startsWith("inferred:")
			), () -> dispatched.trace().toString());
	}

	@Test
	void genericKnownSmeltRecipeEnablesNonIronRoute() {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(dispatcher);
		runtime.submit(
			ActionGoal.inventoryItem("minecraft:glass", 1),
			Map.of("minecraft:sand", 1, "minecraft:furnace", 1, "minecraft:coal", 1),
			CONTEXT,
			100
		);
		SmeltingRecipeKnowledge glass = new SmeltingRecipeKnowledge(
			"inferred:minecraft_sand_to_minecraft_glass",
			"minecraft:sand",
			"minecraft:glass",
			1,
			64,
			200,
			"minecraft:furnace",
			1
		);

		ActionGraphExecutionSnapshot dispatched = runtime.tick(inputWithKnownCraftsAndSmelts(
			Map.of("minecraft:sand", 1, "minecraft:furnace", 1, "minecraft:coal", 1),
			null,
			101,
			List.of(),
			List.of(glass)
		));

		assertEquals(ActionGraphExecutionState.WAITING_PRIMITIVE, dispatched.state());
		assertEquals(1, dispatcher.dispatchedSteps.size());
		ActionPlanStep smelt = dispatched.route().steps().stream()
			.filter(step -> "smelt_item".equals(step.targetId()))
			.findFirst()
			.orElseThrow(() -> new AssertionError(dispatched.route().toString()));
		assertEquals("smelt_item", smelt.targetId());
		assertEquals("minecraft:sand", smelt.args().get("inputItemId"));
		assertEquals("minecraft:glass", smelt.args().get("itemId"));
		assertEquals(glass.optionId(), smelt.args().get("optionId"));
	}

	@Test
	void smeltStartSuspendsUntilExactProcessIsReadyThenCollectsIt() {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(dispatcher);
		runtime.submit(
			ActionGoal.inventoryItem("minecraft:iron_ingot", 3),
			Map.of("minecraft:raw_iron", 3, "minecraft:furnace", 1, "minecraft:coal", 1, "minecraft:stone_pickaxe", 1),
			CONTEXT,
			100
		);

		ActionGraphExecutionSnapshot smeltDispatched = runtime.tick(input(
			Map.of("minecraft:raw_iron", 3, "minecraft:furnace", 1, "minecraft:coal", 1, "minecraft:stone_pickaxe", 1),
			null,
			101,
			List.of(),
			List.of(ironIngotSmeltRecipe(101))
		));

		assertEquals(ActionGraphExecutionState.WAITING_PRIMITIVE, smeltDispatched.state());
		assertEquals(1, dispatcher.dispatchedSteps.size());
		assertEquals("smelt_item", dispatcher.dispatchedSteps.getFirst().targetId());

		ActionGraphExecutionSnapshot observing = runtime.tick(input(
			Map.of("minecraft:raw_iron", 3, "minecraft:furnace", 1, "minecraft:coal", 1, "minecraft:stone_pickaxe", 1),
			new TaskTerminalEvent("task-1", null, TaskExecutionState.COMPLETED, "started", null),
			102,
			List.of(),
			List.of(ironIngotSmeltRecipe(102))
		));

		assertEquals(ActionGraphExecutionState.OBSERVING, observing.state());

		ActionGraphExecutionSnapshot suspended = runtime.tick(input(
			Map.of("minecraft:raw_iron", 3, "minecraft:furnace", 1, "minecraft:coal", 1, "minecraft:stone_pickaxe", 1),
			null,
			122,
			List.of(),
			List.of(ironIngotSmeltRecipe(122), smeltingProcess(false, 122))
		));

		assertEquals(ActionGraphExecutionState.WATCHING, suspended.state());
		assertEquals(1, dispatcher.dispatchedSteps.size());
		assertEquals("wait_for_smelted_item", suspended.pendingWatch());
		ActionGraphWatchSnapshot watch = runtime.pendingWatches().getFirst();
		assertEquals("smelt-process-test", watch.spec().condition().queryKeys().get("processId"));
		assertEquals("smelt-process-test", watch.spec().sourceFactIdentity().keys().get("processId"));
		assertEquals(10, watch.spec().anchor().x());

		ActionGraphExecutionSnapshot fulfilled = runtime.tickPassive(input(
			Map.of("minecraft:raw_iron", 3, "minecraft:furnace", 1, "minecraft:coal", 1, "minecraft:stone_pickaxe", 1),
			null,
			200,
			List.of(),
			List.of(ironIngotSmeltRecipe(200), smeltingProcess(true, 200))
		));
		assertEquals(ActionGraphExecutionState.OBSERVING, fulfilled.state());

		ActionGraphExecutionSnapshot collectDispatched = runtime.tick(input(
			Map.of("minecraft:raw_iron", 3, "minecraft:furnace", 1, "minecraft:coal", 1, "minecraft:stone_pickaxe", 1),
			null,
			201,
			List.of(),
			List.of(ironIngotSmeltRecipe(201), smeltingProcess(true, 201))
		));
		assertEquals(ActionGraphExecutionState.WAITING_PRIMITIVE, collectDispatched.state());
		assertEquals(2, dispatcher.dispatchedSteps.size());
		ActionPlanStep collect = dispatcher.dispatchedSteps.get(1);
		assertEquals("collect_smelted_item", collect.targetId());
		assertEquals("smelt-process-test", collect.args().get("processId"));
		assertEquals(1, countTrace(collectDispatched.trace(), "route_started"));
	}

	@Test
	void nestedCraftingRouteDispatchesIngredientCraftFirst() {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(dispatcher);
		runtime.submit(ActionGoal.inventoryItem("minecraft:stick", 4), Map.of("minecraft:oak_log", 1), CONTEXT, 100);

		ActionGraphExecutionSnapshot dispatched = runtime.tick(input(
			Map.of("minecraft:oak_log", 1),
			null,
			101,
			List.of(
				new CraftingOpportunity(
					"oak_log_to_oak_planks",
					"minecraft:oak_planks",
					4,
					List.of("minecraft:oak_log")
				),
				new CraftingOpportunity(
					"oak_planks_x2_to_stick",
					"minecraft:stick",
					4,
					List.of("minecraft:oak_planks", "minecraft:oak_planks")
				)
			)
		));

		assertEquals(ActionGraphExecutionState.WAITING_PRIMITIVE, dispatched.state());
		assertEquals(1, dispatcher.dispatchedSteps.size());
		ActionPlanStep firstCraft = dispatcher.dispatchedSteps.getFirst();
		assertEquals("craft_item", firstCraft.targetId());
		assertEquals("minecraft:oak_planks", firstCraft.args().get("itemId"));
		assertEquals("oak_log_to_oak_planks", firstCraft.args().get("recipeId"));
	}

	@Test
	void genericWoodCollectionReplansToObservedLogVariantRecipe() {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(dispatcher);
		List<CraftingOpportunity> survivalCrafts = ActionGraphRecipeFixtures.survivalCrafts();
		runtime.submit(ActionGoal.inventoryItem("minecraft:crafting_table", 1), Map.of(), CONTEXT, 100);

		ActionGraphExecutionSnapshot collecting = runtime.tick(input(Map.of(), null, 101, survivalCrafts));

		assertEquals(ActionGraphExecutionState.WAITING_PRIMITIVE, collecting.state());
		assertEquals(1, dispatcher.dispatchedSteps.size());
		assertEquals("collect_resource", dispatcher.dispatchedSteps.getFirst().targetId());

		ActionGraphExecutionSnapshot observing = runtime.tick(input(
			Map.of("minecraft:birch_log", 1),
			new TaskTerminalEvent("task-1", null, TaskExecutionState.COMPLETED, "collected", null),
			102,
			survivalCrafts
		));

		assertEquals(ActionGraphExecutionState.OBSERVING, observing.state());

		ActionGraphExecutionSnapshot replanned = runtime.tick(input(Map.of("minecraft:birch_log", 1), null, 123, survivalCrafts));

		assertEquals(ActionGraphExecutionState.WAITING_PRIMITIVE, replanned.state());
		assertEquals(2, dispatcher.dispatchedSteps.size());
		ActionPlanStep craftPlanks = dispatcher.dispatchedSteps.get(1);
		assertEquals("craft_item", craftPlanks.targetId());
		assertEquals("minecraft:birch_planks", craftPlanks.args().get("itemId"));
		assertEquals("birch_log_to_birch_planks", craftPlanks.args().get("recipeId"));
	}

	@Test
	void repeatedObservedFactsDoNotSpamTrace() {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(dispatcher);
		runtime.submit(ActionGoal.inventoryItem("minecraft:stick", 4), Map.of("minecraft:oak_log", 1), CONTEXT, 100);
		List<CraftingOpportunity> crafts = List.of(
			new CraftingOpportunity(
				"oak_log_to_oak_planks",
				"minecraft:oak_planks",
				4,
				List.of("minecraft:oak_log")
			),
			new CraftingOpportunity(
				"oak_planks_x2_to_stick",
				"minecraft:stick",
				4,
				List.of("minecraft:oak_planks", "minecraft:oak_planks")
			)
		);

		ActionGraphExecutionSnapshot first = runtime.tick(input(Map.of("minecraft:oak_log", 1), null, 101, crafts));
		long firstFactEvents = countTrace(first.trace(), "fact_observed");
		ActionGraphExecutionSnapshot repeated = runtime.tick(input(Map.of("minecraft:oak_log", 1), null, 102, crafts));
		ActionGraphExecutionSnapshot changed = runtime.tick(input(Map.of("minecraft:oak_log", 0), null, 103, crafts));

		assertEquals(firstFactEvents, countTrace(repeated.trace(), "fact_observed"));
		assertEquals(firstFactEvents + 1, countTrace(changed.trace(), "fact_observed"));
	}

	@Test
	void transientPrimitiveFailureRetriesThenReplansWithoutDispatchChurn() {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(dispatcher);
		runtime.submit(ActionGoal.inventoryItem("minecraft:bread", 1), Map.of("minecraft:wheat", 3), CONTEXT, 100);

		runtime.tick(input(Map.of("minecraft:wheat", 3), null, 101));
		runtime.tick(input(Map.of("minecraft:wheat", 3), failed("task-1", "path_timeout"), 102));
		runtime.tick(input(Map.of("minecraft:wheat", 3), null, 103));
		runtime.tick(input(Map.of("minecraft:wheat", 3), failed("task-2", "path_timeout"), 104));
		runtime.tick(input(Map.of("minecraft:wheat", 3), null, 105));
		ActionGraphExecutionSnapshot replanned = runtime.tick(input(Map.of("minecraft:wheat", 3), failed("task-3", "path_timeout"), 106));
		ActionGraphExecutionSnapshot stable = runtime.tick(input(Map.of("minecraft:wheat", 3), null, 107));

		assertEquals(ActionGraphExecutionState.WAITING_PRIMITIVE, replanned.state());
		assertEquals(4, dispatcher.dispatchedSteps.size());
		assertEquals(3, stable.recoveryHistory().size());
		assertEquals(4, dispatcher.dispatchedSteps.size());
		assertTrace(replanned.trace(), "recovery_selected");
		assertTrace(replanned.trace(), "route_replanned");
	}

	@Test
	void unknownFailureDetailDoesNotTriggerTextBasedRecovery() {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(dispatcher);
		runtime.submit(ActionGoal.inventoryItem("minecraft:bread", 1), Map.of("minecraft:wheat", 3), CONTEXT, 100);

		ActionGraphExecutionSnapshot dispatched = runtime.tick(input(Map.of("minecraft:wheat", 3), null, 101));
		ActionGraphExecutionSnapshot failed = runtime.tick(input(
			Map.of("minecraft:wheat", 3),
			new TaskTerminalEvent(dispatched.activeTaskId(), null, TaskExecutionState.FAILED, "path target busy timeout detail", null),
			102
		));

		assertEquals(ActionGraphExecutionState.FAILED, failed.state());
		assertEquals("failed", failed.failureCode());
		assertEquals(1, dispatcher.dispatchedSteps.size());
		assertEquals("path target busy timeout detail", failed.message());
		assertEquals("path target busy timeout detail", map(failed.recoveryHistory().getFirst()).get("message"));
	}

	@Test
	void typedFailureCodeControlsRecoveryWhenDetailContainsPolicyWords() {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(dispatcher);
		runtime.submit(ActionGoal.inventoryItem("minecraft:bread", 1), Map.of("minecraft:wheat", 3), CONTEXT, 100);

		ActionGraphExecutionSnapshot dispatched = runtime.tick(input(Map.of("minecraft:wheat", 3), null, 101));
		ActionGraphExecutionSnapshot recovered = runtime.tick(input(
			Map.of("minecraft:wheat", 3),
			failed(dispatched.activeTaskId(), TaskFailureCode.MISSING_FACT, "path timeout target detail"),
			102
		));

		assertEquals(ActionGraphExecutionState.WAITING_PRIMITIVE, recovered.state());
		assertEquals(2, dispatcher.dispatchedSteps.size());
		assertEquals("missing_fact", map(recovered.recoveryHistory().getFirst()).get("failureCode"));
		assertEquals("missing_fact", map(recovered.recoveryHistory().getFirst()).get("typedFailureCode"));
	}

	@Test
	void typedDispatchFailureReachesGraphRecovery() {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		dispatcher.nextResult = ActionGraphPrimitiveDispatchResult.failed(
			TaskFailureCode.MISSING_FACT,
			"path target detail",
			Map.of()
		);
		ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(dispatcher);
		runtime.submit(ActionGoal.inventoryItem("minecraft:bread", 1), Map.of("minecraft:wheat", 3), CONTEXT, 100);

		ActionGraphExecutionSnapshot recovered = runtime.tick(input(Map.of("minecraft:wheat", 3), null, 101));

		assertEquals(ActionGraphExecutionState.WAITING_PRIMITIVE, recovered.state());
		assertEquals(2, dispatcher.dispatchedSteps.size());
		assertEquals("missing_fact", map(recovered.recoveryHistory().getFirst()).get("failureCode"));
	}

	@Test
	void missingCraftRecipeFailureReplansWithoutBlockingRecipeAlternative() {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(dispatcher);
		runtime.submit(ActionGoal.inventoryItem("minecraft:bread", 1), Map.of("minecraft:wheat", 3), CONTEXT, 100);
		List<CraftingOpportunity> crafts = List.of(new CraftingOpportunity(
			"wheat_x3_to_bread",
			"minecraft:bread",
			1,
			List.of("minecraft:wheat", "minecraft:wheat", "minecraft:wheat")
		));

		ActionGraphExecutionSnapshot dispatched = runtime.tick(input(Map.of("minecraft:wheat", 3), null, 101, crafts));
		ActionGraphExecutionSnapshot retried = runtime.tick(input(
			Map.of("minecraft:wheat", 3),
			failed(dispatched.activeTaskId(), "recipe_not_found"),
			102,
			crafts
		));

		assertEquals(ActionGraphExecutionState.WAITING_PRIMITIVE, retried.state());
		assertEquals(2, dispatcher.dispatchedSteps.size());
		assertEquals("wheat_x3_to_bread", dispatcher.dispatchedSteps.get(1).args().get("recipeId"));
		assertTrue(retried.failureCode().isBlank(), () -> retried.toString());
	}

	@Test
	void observedInventorySnapshotClearsConsumedItemsBeforeReplan() {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(dispatcher);
		runtime.submit(
			ActionGoal.inventoryItem("minecraft:wooden_pickaxe", 1),
			Map.of("minecraft:birch_planks", 7, "minecraft:stick", 2),
			CONTEXT,
			100
		);

		ActionGraphExecutionSnapshot pickaxeDispatched = runtime.tick(inputWithKnownCrafts(
			Map.of("minecraft:birch_planks", 7, "minecraft:stick", 2),
			null,
			101,
			ActionGraphRecipeFixtures.survivalCrafts()
		));
		ActionPlanStep pickaxeStep = dispatcher.dispatchedSteps.getFirst();
		assertEquals("minecraft:wooden_pickaxe", pickaxeStep.args().get("itemId"));

		ActionGraphExecutionSnapshot failed = runtime.tick(inputWithKnownCrafts(
			Map.of("minecraft:stick", 2),
			new TaskTerminalEvent(pickaxeDispatched.activeTaskId(), null, TaskExecutionState.FAILED, "recipe_not_found", null, TaskFailureCode.MISSING_FACT),
			102,
			ActionGraphRecipeFixtures.survivalCrafts()
		));
		ActionGraphExecutionSnapshot replanned = runtime.tick(inputWithKnownCrafts(
			Map.of("minecraft:stick", 2),
			null,
			122,
			ActionGraphRecipeFixtures.survivalCrafts()
		));

		assertEquals(ActionGraphExecutionState.WAITING_PRIMITIVE, failed.state(), () -> failed.toString());
		assertEquals(ActionGraphExecutionState.WAITING_PRIMITIVE, replanned.state(), () -> replanned.toString());
		assertEquals(2, dispatcher.dispatchedSteps.size());
		assertEquals("collect_resource", dispatcher.dispatchedSteps.get(1).targetId());
	}

	@Test
	void busyTerminalFailureWaitsBeforeRetryingPrimitive() {
		assertTerminalFailureWaitsBeforeRetry("crafting_busy");
	}

	@Test
	void occupiedGridTerminalFailureWaitsBeforeRetryingPrimitive() {
		assertTerminalFailureWaitsBeforeRetry("crafting_grid_occupied");
	}

	@Test
	void repeatedBusyTerminalFailuresExhaustRetryBudget() {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(dispatcher);
		runtime.submit(ActionGoal.inventoryItem("minecraft:bread", 1), Map.of("minecraft:wheat", 3), CONTEXT, 100);
		ActionGraphExecutionSnapshot snapshot = runtime.tick(input(Map.of("minecraft:wheat", 3), null, 101));
		long tick = 101;

		for (int failureCount = 0; failureCount < 40 && !terminal(snapshot.state()); failureCount++) {
			String taskId = snapshot.activeTaskId();
			assertFalse(taskId.isBlank(), snapshot.toString());
			snapshot = runtime.tick(input(Map.of("minecraft:wheat", 3), failed(taskId, "crafting_busy"), ++tick));
			if (!terminal(snapshot.state()) && snapshot.activeTaskId().isBlank()) {
				tick += 20;
				snapshot = runtime.tick(input(Map.of("minecraft:wheat", 3), null, tick));
			}
		}

		assertEquals(ActionGraphExecutionState.FAILED, snapshot.state(), snapshot.toString());
		assertEquals("budget_exceeded", snapshot.failureCode());
		assertTrue(dispatcher.dispatchedSteps.size() < 40);
	}

	private static boolean terminal(ActionGraphExecutionState state) {
		return state == ActionGraphExecutionState.SUCCEEDED
			|| state == ActionGraphExecutionState.FAILED
			|| state == ActionGraphExecutionState.CANCELLED;
	}

	private static void assertTerminalFailureWaitsBeforeRetry(String failureMessage) {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(dispatcher);
		runtime.submit(ActionGoal.inventoryItem("minecraft:bread", 1), Map.of("minecraft:wheat", 3), CONTEXT, 100);

		ActionGraphExecutionSnapshot dispatched = runtime.tick(input(Map.of("minecraft:wheat", 3), null, 101));
		assertEquals(ActionGraphExecutionState.WAITING_PRIMITIVE, dispatched.state(), () -> dispatched.toString());
		assertFalse(dispatched.activeTaskId().isBlank(), () -> dispatched.toString());
		ActionGraphExecutionSnapshot waiting = runtime.tick(input(Map.of("minecraft:wheat", 3), failed(dispatched.activeTaskId(), failureMessage), 102));
		ActionGraphExecutionSnapshot stillWaiting = runtime.tick(input(Map.of("minecraft:wheat", 3), null, 103));

		assertEquals(ActionGraphExecutionState.OBSERVING, waiting.state());
		assertEquals(ActionGraphExecutionState.OBSERVING, stillWaiting.state());
		assertEquals(1, dispatcher.dispatchedSteps.size());
		ActionGraphExecutionSnapshot retried = runtime.tick(input(Map.of("minecraft:wheat", 3), null, 122));
		assertEquals(ActionGraphExecutionState.WAITING_PRIMITIVE, retried.state());
		assertEquals(2, dispatcher.dispatchedSteps.size());
		assertTrace(waiting.trace(), "recovery_selected");
	}

	private static ActionGraphExecutionInput input(Map<String, Integer> observedInventory, TaskTerminalEvent terminalEvent, long tick) {
		return input(observedInventory, terminalEvent, tick, ActionGraphRecipeFixtures.survivalCrafts());
	}

	private static ActionGraphExecutionInput input(
		Map<String, Integer> observedInventory,
		TaskTerminalEvent terminalEvent,
		long tick,
		List<CraftingOpportunity> availableCrafts
	) {
		return input(observedInventory, terminalEvent, tick, availableCrafts, List.of());
	}

	private static ActionGraphExecutionInput input(
		Map<String, Integer> observedInventory,
		TaskTerminalEvent terminalEvent,
		long tick,
		List<CraftingOpportunity> availableCrafts,
		List<ActionFact> observedFacts
	) {
		return new ActionGraphExecutionInput(
			new ActionResolverContext(CONTEXT.worldId(), CONTEXT.actorId(), CONTEXT.dimension(), tick),
			observedInventory,
			Map.of(),
			true,
			true,
			terminalEvent,
			availableCrafts,
			List.of(),
			List.of(),
			List.of(),
			observedFacts,
			null,
			Map.of(),
			BlockAcquisitionTestFixtures.survival(),
			NearbyBlockAvailability.unknown()
		);
	}

	private static ActionGraphExecutionInput inputWithResources(
		Map<String, Integer> observedResources,
		TaskTerminalEvent terminalEvent,
		long tick
	) {
		return new ActionGraphExecutionInput(
			new ActionResolverContext(CONTEXT.worldId(), CONTEXT.actorId(), CONTEXT.dimension(), tick),
			Map.of(),
			observedResources,
			true,
			true,
			terminalEvent,
			List.of(),
			List.of(),
			List.of(),
			List.of(),
			List.of(),
			null,
			Map.of(),
			BlockAcquisitionTestFixtures.survival(),
			NearbyBlockAvailability.unknown()
		);
	}

	private static ActionGraphExecutionInput inputWithKnownCrafts(
		Map<String, Integer> observedInventory,
		TaskTerminalEvent terminalEvent,
		long tick,
		List<CraftingOpportunity> knownCrafts
	) {
		return new ActionGraphExecutionInput(
			new ActionResolverContext(CONTEXT.worldId(), CONTEXT.actorId(), CONTEXT.dimension(), tick),
			observedInventory,
			Map.of(),
			true,
			true,
			terminalEvent,
			List.of(),
			knownCrafts,
			List.of(),
			List.of(),
			List.of(),
			null,
			Map.of(),
			BlockAcquisitionTestFixtures.survival(),
			NearbyBlockAvailability.unknown()
		);
	}

	private static ActionGraphExecutionInput inputWithKnownCraftsAndSmelts(
		Map<String, Integer> observedInventory,
		TaskTerminalEvent terminalEvent,
		long tick,
		List<CraftingOpportunity> knownCrafts,
		List<SmeltingRecipeKnowledge> knownSmelts
	) {
		return new ActionGraphExecutionInput(
			new ActionResolverContext(CONTEXT.worldId(), CONTEXT.actorId(), CONTEXT.dimension(), tick),
			observedInventory,
			Map.of(),
			true,
			true,
			terminalEvent,
			List.of(),
			knownCrafts,
			List.of(),
			knownSmelts,
			List.of(),
			null,
			Map.of(),
			BlockAcquisitionTestFixtures.survival(),
			NearbyBlockAvailability.unknown()
		);
	}

	private static ActionFact ironIngotSmeltRecipe(long tick) {
		return new ActionFact(
			ActionFactIdentity.smeltRecipe(CONTEXT.worldId(), CONTEXT.actorId(), "smelt:minecraft_raw_iron_to_minecraft_iron_ingot:test"),
			Map.of(
				"inputItemId", "minecraft:raw_iron",
				"outputItemId", "minecraft:iron_ingot",
				"outputCount", 1,
				"maxInputQuantity", 3,
				"cookTimeTicks", 200
			),
			ActionFactProvenance.OBSERVED,
			tick,
			ActionFact.NEVER_STALE
		);
	}

	private static ActionFact smeltingProcess(boolean ready, long tick) {
		return new ActionFact(
			ActionFactIdentity.smeltingProcess(
				CONTEXT.worldId(),
				CONTEXT.actorId(),
				"smelt-process-test",
				"smelt:minecraft_raw_iron_to_minecraft_iron_ingot:test",
				"minecraft:iron_ingot"
			),
			Map.of(
				"ready", ready ? 1 : 0,
				"expectedOutputCount", 3,
				"origin", Map.of("x", 10, "y", 64, "z", 20)
			),
			ActionFactProvenance.OBSERVED,
			tick,
			tick + 20
		);
	}

	private static TaskTerminalEvent failed(String taskId, String message) {
		return failed(taskId, TaskFailureCode.fromLegacyDetail(message), message);
	}

	private static TaskTerminalEvent failed(String taskId, TaskFailureCode failureCode, String message) {
		return new TaskTerminalEvent(taskId, null, TaskExecutionState.FAILED, message, null, failureCode);
	}

	private static void assertTrace(List<ActionTraceEvent> trace, String eventType) {
		assertTrue(trace.stream().anyMatch(event -> eventType.equals(event.eventType())), () -> trace.toString());
	}

	private static long countTrace(List<ActionTraceEvent> trace, String eventType) {
		return trace.stream().filter(event -> eventType.equals(event.eventType())).count();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> list(Object value) {
		return (List<Object>) value;
	}

	private static final class RecordingDispatcher implements ActionGraphPrimitiveDispatcher {
		private final List<ActionPlanStep> dispatchedSteps = new ArrayList<>();
		private ActionGraphPrimitiveDispatchResult nextResult;

		@Override
		public ActionGraphPrimitiveDispatchResult dispatch(ActionPlanStep step) {
			dispatchedSteps.add(step);
			if (nextResult != null) {
				ActionGraphPrimitiveDispatchResult result = nextResult;
				nextResult = null;
				return result;
			}
			LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
			payload.put("targetId", step.targetId());
			if ("smelt_item".equals(step.targetId())) {
				payload.put("processId", "smelt-process-test");
			}
			return ActionGraphPrimitiveDispatchResult.accepted(
				"task-" + dispatchedSteps.size(),
				payload
			);
		}
	}
}
