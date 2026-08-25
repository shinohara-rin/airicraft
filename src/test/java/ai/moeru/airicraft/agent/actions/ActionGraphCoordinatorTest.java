package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.agent.tasks.TaskExecutionState;
import ai.moeru.airicraft.agent.tasks.TaskTerminalEvent;
import ai.moeru.airicraft.agent.tasks.SmeltingRecipeKnowledge;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionGraphCoordinatorTest {
	private static final String WORLD = "world-a";
	private static final String DIMENSION = "minecraft:overworld";
	private static final String ACTOR = "bot";

	@Test
	void identicalGoalIsIdempotentAndDifferentForegroundGoalIsBusy() {
		ActionGraphCoordinator coordinator = new ActionGraphCoordinator(step ->
			ActionGraphPrimitiveDispatchResult.accepted("task", Map.of()));
		ActionGoal firstGoal = ActionGoal.resourceCollection("WOOD_LOGS", 1);

		ActionGraphStartResult first = coordinator.submit(firstGoal, Map.of(), context(100), 100);
		ActionGraphStartResult identical = coordinator.submit(firstGoal, Map.of(), context(101), 101);
		ActionGraphStartResult different = coordinator.submit(ActionGoal.resourceCollection("COBBLESTONE", 1), Map.of(), context(102), 102);

		assertEquals(ActionGraphAdmission.STARTED, first.admission());
		assertEquals(ActionGraphAdmission.EXISTING, identical.admission());
		assertEquals(first.execution().execution().executionId(), identical.execution().execution().executionId());
		assertEquals(ActionGraphAdmission.BUSY, different.admission());
		assertEquals("foreground_busy", different.failureCode());
	}

	@Test
	void terminalFailureEventCarriesCapabilityReasonExactlyOnce() throws Exception {
		ActionGraphCoordinator coordinator = new ActionGraphCoordinator(step -> {
			throw new AssertionError("unknown acquisition must not dispatch");
		});
		ActionGraphStartResult started = coordinator.submit(
			ActionGoal.inventoryItem("minecraft:seagrass", 20),
			Map.of(),
			context(100),
			100
		);

		for (int attempt = 0; attempt < 100 && coordinator.inspect(started.execution().execution().executionId()).residency() != ActionGraphResidency.TERMINAL; attempt++) {
			coordinator.tick(input(101 + attempt, Map.of(), Map.of(), null, List.of()), true);
			Thread.sleep(2L);
		}

		List<ActionGraphCoordinatorEvent> terminal = coordinator.drainEvents().stream()
			.filter(event -> "action_graph.goal_terminal".equals(event.type()))
			.toList();
		assertEquals(1, terminal.size());
		assertEquals(started.execution().execution().executionId(), terminal.getFirst().executionId());
		assertEquals("FAILED", terminal.getFirst().payload().get("state"));
		assertEquals("no_route", terminal.getFirst().payload().get("failureCode"));

		coordinator.tick(input(102, Map.of(), Map.of(), null, List.of()), true);
		assertTrue(coordinator.drainEvents().stream()
			.noneMatch(event -> "action_graph.goal_terminal".equals(event.type())));
	}

	@Test
	void terminalFailureEventIdentifiesFailedCharcoalPrerequisite() throws Exception {
		ActionGraphCoordinator coordinator = new ActionGraphCoordinator(step ->
			ActionGraphPrimitiveDispatchResult.failed(
				ai.moeru.airicraft.agent.tasks.TaskFailureCode.MISSING_FACT,
				"insufficient_illumination reason=loaded_target_unilluminated torchCount=0",
				Map.of("failureReason", "insufficient_illumination")
			)
		);
		Map<String, Integer> inventory = Map.of(
			"minecraft:oak_log", 2,
			"minecraft:oak_planks", 8,
			"minecraft:stick", 2,
			"minecraft:crafting_table", 1,
			"minecraft:wooden_pickaxe", 1
		);
		SmeltingRecipeKnowledge charcoal = new SmeltingRecipeKnowledge(
			"inferred:minecraft_oak_log_to_minecraft_charcoal",
			"minecraft:oak_log",
			"minecraft:charcoal",
			1,
			64,
			200,
			"minecraft:furnace",
			1
		);
		ActionGraphStartResult started = coordinator.submit(
			ActionGoal.inventoryItem("minecraft:charcoal", 1), inventory, context(100), 100
		);

		for (int attempt = 0; attempt < 200 && coordinator.inspect(started.execution().execution().executionId()).residency() != ActionGraphResidency.TERMINAL; attempt++) {
			coordinator.tick(input(101 + attempt, inventory, Map.of(), null, List.of(), List.of(charcoal)), true);
			Thread.sleep(2L);
		}

		ActionGraphCoordinatorEvent terminal = coordinator.drainEvents().stream()
			.filter(event -> "action_graph.goal_terminal".equals(event.type()))
			.findFirst()
			.orElseThrow();
		assertEquals("mine_block", terminal.payload().get("failedPrimitive"));
		assertEquals("minecraft:cobblestone", terminal.payload().get("failedTarget"));
		assertTrue(((Map<?, ?>) terminal.payload().get("failedArgs")).containsValue("minecraft:cobblestone"));
		coordinator.shutdown();
	}

	@Test
	void smeltingWatchReleasesTheForegroundLaneAndCanBeCancelled() throws Exception {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionGraphCoordinator coordinator = new ActionGraphCoordinator(dispatcher);
		Map<String, Integer> inventory = Map.of(
			"minecraft:raw_iron", 3,
			"minecraft:furnace", 1,
			"minecraft:coal", 1,
			"minecraft:stone_pickaxe", 1
		);
		ActionGraphStartResult smelting = coordinator.submit(
			ActionGoal.inventoryItem("minecraft:iron_ingot", 3), inventory, context(100), 100
		);
		TaskTerminalEvent terminal = null;
		ActionGraphExecutionView smeltingView = smelting.execution();

		for (int attempt = 0; attempt < 150 && smeltingView.residency() != ActionGraphResidency.SUSPENDED; attempt++) {
			long tick = 101L + attempt;
			coordinator.tick(input(tick, inventory, Map.of(), terminal, List.of(smeltingProcess(false, tick))), true);
			terminal = null;
			smeltingView = coordinator.inspect(smelting.execution().execution().executionId());
			if (smeltingView.execution().state() == ActionGraphExecutionState.WAITING_PRIMITIVE) {
				terminal = new TaskTerminalEvent(
					smeltingView.execution().activeTaskId(),
					null,
					TaskExecutionState.COMPLETED,
					"started",
					null
				);
			}
			Thread.sleep(2L);
		}

		assertEquals(ActionGraphResidency.SUSPENDED, smeltingView.residency(), smeltingView.execution().toString());
		assertEquals(ActionGraphExecutionState.WATCHING, smeltingView.execution().state());
		ActionGraphStartResult foreground = coordinator.submit(
			ActionGoal.resourceCollection("WOOD_LOGS", 1), Map.of(), context(300), 300
		);
		assertEquals(ActionGraphAdmission.STARTED, foreground.admission());

		ActionGraphExecutionView cancelled = coordinator.cancel(
			smelting.execution().execution().executionId(), "user_cancelled", 301
		);
		assertEquals(ActionGraphResidency.TERMINAL, cancelled.residency());
		assertEquals(ActionGraphExecutionState.CANCELLED, cancelled.execution().state());
		assertTrue(coordinator.drainEvents().stream().anyMatch(event ->
			"action_graph.goal_cancelled".equals(event.type())
				&& smelting.execution().execution().executionId().equals(event.executionId())
		));
		coordinator.shutdown();
	}

	private static ActionGraphExecutionInput input(
		long tick,
		Map<String, Integer> inventory,
		Map<String, Integer> resources,
		TaskTerminalEvent terminal,
		List<ActionFact> facts
	) {
		return input(tick, inventory, resources, terminal, facts, ActionGraphRecipeFixtures.survivalSmelts());
	}

	private static ActionGraphExecutionInput input(
		long tick,
		Map<String, Integer> inventory,
		Map<String, Integer> resources,
		TaskTerminalEvent terminal,
		List<ActionFact> facts,
		List<SmeltingRecipeKnowledge> smelts
	) {
		return new ActionGraphExecutionInput(
			context(tick), inventory, resources, true, true, terminal,
			List.of(), ActionGraphRecipeFixtures.survivalCrafts(), List.of(), smelts, facts,
			new ActionGraphAgentPosition(WORLD, DIMENSION, 0, 64, 0), Map.of(), BlockAcquisitionTestFixtures.survival(),
			NearbyBlockAvailability.unknown()
		);
	}

	private static ActionResolverContext context(long tick) {
		return new ActionResolverContext(WORLD, ACTOR, DIMENSION, tick);
	}

	private static ActionFact smeltingProcess(boolean ready, long tick) {
		return new ActionFact(
			ActionFactIdentity.smeltingProcess(
				WORLD,
				ACTOR,
				"smelt-process-test",
				"inferred:minecraft_raw_iron_to_minecraft_iron_ingot",
				"minecraft:iron_ingot"
			),
			Map.of("ready", ready ? 1 : 0, "expectedOutputCount", 3),
			ActionFactProvenance.OBSERVED,
			tick,
			tick + 20
		);
	}

	private static final class RecordingDispatcher implements ActionGraphPrimitiveDispatcher {
		private final List<ActionPlanStep> steps = new ArrayList<>();

		@Override
		public ActionGraphPrimitiveDispatchResult dispatch(ActionPlanStep step) {
			steps.add(step);
			return ActionGraphPrimitiveDispatchResult.accepted("task-" + steps.size(), Map.of());
		}
	}
}
