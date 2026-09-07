package ai.moeru.airicraft.systemone.voxel;

import ai.moeru.airicraft.systemone.TaskKernel;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionDomain.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionKnowledge.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelCommand.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;

class ProductionDomainTest {
	private static final Recipe PLANKS = recipe("planks", "planks", 4, 2, "log");
	private static final Recipe STICKS = recipe("sticks", "sticks", 4, 2, "planks", "planks");
	private static final Recipe TABLE = recipe("table", "minecraft:crafting_table", 1, 2, "planks", "planks", "planks", "planks");
	private static final Recipe PICK = recipe("pick", "pick", 1, 3, "planks", "planks", "planks", "sticks", "sticks");
	private static final ProductionKnowledge BOOK = new ProductionKnowledge("test", List.of(PLANKS, STICKS, TABLE, PICK), List.of());

	@Test void dependenciesAndWorkstationPreserveInputsUntilThePickIsCrafted() {
		var run = run(BOOK, Acquire.root("pick", 1), Map.of("log", 3), false);
		assertEquals(ResultKind.SUCCEEDED, run.outcome().kind());
		assertEquals(1, run.inventory().get("pick"));
		assertEquals(3, run.inventory().get("planks"));
		assertEquals(2, run.inventory().get("sticks"));
		assertEquals(1, run.commands().stream().filter(Place.class::isInstance).count());
		assertTrue(run.maxDepth() >= 3);
	}
	@Test void outputYieldAndReservedStockAreBothAccountedFor() {
		var goal = new Acquire("planks", 3, Map.of("planks", 2), Set.of(), Set.of(), "");
		var run = run(BOOK, goal, Map.of("planks", 2, "log", 1), false);
		assertEquals(ResultKind.SUCCEEDED, run.outcome().kind());
		assertEquals(6, run.inventory().get("planks"));
		assertEquals(1, run.commands().size());
	}
	@Test void aChildCannotConsumeItsParentsReservedIngredient() {
		var goal = new Acquire("sticks", 4, Map.of("planks", 2), Set.of(), Set.of(), "");
		var run = run(BOOK, goal, Map.of("planks", 2), false);
		assertEquals(ResultKind.FAILED, run.outcome().kind());
		assertTrue(run.commands().isEmpty());
		assertEquals(2, run.inventory().get("planks"));
	}
	@Test void recipeCyclesTerminateWithoutIssuingCommands() {
		var book = new ProductionKnowledge("cycle", List.of(recipe("a", "a", 1, 2, "b"), recipe("b", "b", 1, 2, "a")), List.of());
		var run = run(book, Acquire.root("a", 1), Map.of(), false);
		assertEquals(ResultKind.FAILED, run.outcome().kind());
		assertTrue(run.commands().isEmpty());
		assertTrue(run.events().stream().anyMatch(event -> event.detail().contains("dependency_cycle")));
	}
	@Test void failedRecipeSelectsAnAlternativeWithoutLosingTheGoal() {
		var book = new ProductionKnowledge("alternatives", List.of(recipe("a", "output", 1, 2, "a"), recipe("b", "output", 1, 2, "b")), List.of());
		var run = run(book, Acquire.root("output", 1), Map.of("a", 1, "b", 1), true);
		assertEquals(ResultKind.SUCCEEDED, run.outcome().kind());
		assertEquals(List.of("a", "b"), run.commands().stream().map(command -> ((Craft) command).recipe().id()).toList());
	}
	@Test void motorSuccessWithoutInventoryEvidenceDoesNotCompleteTheGoal() {
		var kernel = new TaskKernel<Task, StoneAcquisition.World, VoxelCommand>(new ProductionDomain(BOOK), new Limits(16, 16, 500, 100));
		var state = kernel.begin("session", "run", Acquire.root("planks", 3), 0);
		var world = world(Map.of("log", 1));
		var first = kernel.advance(state, world, List.of(), 1);
		var command = (Start<VoxelCommand>) first.effects().getFirst();
		var next = kernel.advance(first.state(), world, List.of(new Finished(command.token(), Outcome.success("click_accepted"))), 2);
		assertTrue(next.state().outcome().isEmpty());
	}
	@Test void ingredientAlternativesPreferAnObservedResourceOverAnUnseenSpecies() {
		var book = new ProductionKnowledge("woods", List.of(recipe("acacia", "planks", 4, 2, "acacia_log"), recipe("oak", "planks", 4, 2, "oak_log")),
			List.of(new Harvest("acacia_log", List.of("acacia_log"), List.of(), Technique.EXPOSED), new Harvest("oak_log", List.of("oak_log"), List.of(), Technique.EXPOSED)));
		var kernel = new TaskKernel<Task, StoneAcquisition.World, VoxelCommand>(new ProductionDomain(book), new Limits(16, 16, 500, 100));
		var state = kernel.begin("session", "run", Acquire.root("planks", 3), 0);
		var base = world(Map.of());
		var seen = new HashMap<>(base.known()); seen.put(new Pos(0, 1, 2), new Seen("oak_log", false, true, 15, 0));
		var step = kernel.advance(state, new StoneAcquisition.World(base.eye(), base.feet(), base.inventory(), seen), List.of(), 1);
		assertEquals("oak", ((Acquire) step.state().stack().getFirst().task()).method());
		assertEquals("oak_log", ((Break) ((Start<VoxelCommand>) step.effects().getFirst()).command()).expectedBlock());
	}
	@Test void rememberedOccludedStationRequiresReturningToAUsableStance() {
		var domain = new ProductionDomain(BOOK);
		var base = world(Map.of("planks", 3, "sticks", 2));
		var seen = new HashMap<>(base.known());
		seen.put(new Pos(1, 2, 0), new Seen("minecraft:stone", false, true, 15, 0));
		seen.put(new Pos(2, 1, 0), new Seen("minecraft:crafting_table", false, true, 15, 0));
		seen.put(new Pos(2, 2, 0), new Seen("minecraft:air", true, true, 15, 0));
		seen.put(new Pos(2, 0, 1), new Seen("minecraft:grass_block", false, true, 15, 0));
		seen.put(new Pos(2, 1, 1), new Seen("minecraft:air", true, true, 15, 0));
		seen.put(new Pos(2, 2, 1), new Seen("minecraft:air", true, true, 15, 0));
		var observation = new StoneAcquisition.World(base.eye(), base.feet(), base.inventory(), seen);
		var dependency = assertInstanceOf(Child.class, domain.decide(view(Acquire.root("pick", 1)), observation));
		var station = assertInstanceOf(Station.class, dependency.child());
		var action = assertInstanceOf(Execute.class, domain.decide(view(station), observation));
		assertEquals(new Pos(2, 1, 1), assertInstanceOf(Navigate.class, action.command()).stance());
	}
	@Test void placementAvoidsBodyOverlapAcrossBlockBoundaries() {
		var seen = new HashMap<Pos, Seen>();
		for (var support : List.of(new Pos(0, 0, 0), new Pos(-1, 0, 1))) {
			seen.put(support, new Seen("minecraft:grass_block", false, true, 15, 0));
			seen.put(support.offset(0, 1, 0), new Seen("minecraft:air", true, true, 15, 0));
			seen.put(support.offset(0, 2, 0), new Seen("minecraft:air", true, true, 15, 0));
		}
		var world = new StoneAcquisition.World(new Pose(.5, 2.62, 1.08, 0, 45), new Pos(0, 1, 1), Map.of("minecraft:crafting_table", 1), seen);
		var action = assertInstanceOf(Execute.class, new ProductionDomain(BOOK).decide(view(new Station("minecraft:crafting_table", Map.of(), Set.of(), 0, Set.of(), null)), world));
		assertEquals(new Pos(-1, 0, 1), assertInstanceOf(Place.class, action.command()).support());
	}
	@Test void stationPlacementLeavesEarlierReturnStepsClear() {
		var base = world(Map.of("minecraft:crafting_table", 1));
		var cells = new HashMap<>(base.known());
		var alternative = new Pos(2, 0, 0);
		cells.put(alternative, new Seen("minecraft:stone", false, true, 15, 1));
		cells.put(alternative.offset(0, 1, 0), new Seen("minecraft:air", true, true, 15, 1));
		cells.put(alternative.offset(0, 2, 0), new Seen("minecraft:air", true, true, 15, 1));
		var observation = new StoneAcquisition.World(base.eye(), base.feet(), base.inventory(), cells, Set.of(new Pos(1, 0, 0)));
		var action = (Execute<Task, VoxelCommand>) new ProductionDomain(BOOK).decide(view(new Station("minecraft:crafting_table", Map.of(), Set.of(), 0, Set.of(), null)), observation);
		assertEquals(new Place("minecraft:crafting_table", alternative, "minecraft:stone"), action.command());
	}
	@Test void stationPlacementApproachesHighGroundToReachTheTopFace() {
		var cells = new HashMap<Pos, Seen>();
		for (int x = 1; x <= 2; x++) {
			cells.put(new Pos(x, 2, 0), new Seen("minecraft:stone", false, true, 15, 1));
			cells.put(new Pos(x, 3, 0), new Seen("minecraft:air", true, true, 15, 1));
			cells.put(new Pos(x, 4, 0), new Seen("minecraft:air", true, true, 15, 1));
		}
		var observation = new StoneAcquisition.World(new Pose(.5, 2.62, .5, 0, 45), new Pos(0, 1, 0), Map.of("minecraft:crafting_table", 1), cells);
		var action = (Execute<Task, VoxelCommand>) new ProductionDomain(BOOK).decide(view(new Station("minecraft:crafting_table", Map.of(), Set.of(), 0, Set.of(), null)), observation);
		assertEquals(new Navigate(new Pos(1, 3, 0), 24, 200), action.command());
	}
	@Test void collectsMinedOreOnItsObservedFloorAboveThePlayersFeet() {
		var target = new Pos(3, 2, 0);
		var rule = new Harvest("raw_iron", List.of("ore"), List.of(), Technique.EXPOSED);
		var task = new Gather(rule, 1, new Pos(0, 1, 0), 0, Set.of(), Set.of(), new Break(target, "ore"));
		var base = world(Map.of()); var known = new HashMap<>(base.known());
		known.put(target, new Seen("minecraft:air", true, true, 15, 1));
		known.put(target.offset(0, 1, 0), new Seen("minecraft:air", true, true, 15, 1));
		known.put(target.offset(0, -1, 0), new Seen("minecraft:grass_block", false, true, 15, 1));
		var observation = new StoneAcquisition.World(base.eye(), base.feet(), base.inventory(), known);
		var view = new View<Task>(1, task, false, 2, Optional.of(Outcome.success("broken")), Optional.empty());
		var result = (Execute<Task, VoxelCommand>) new ProductionDomain(BOOK).decide(view, observation);
		assertEquals(new Navigate(target, 24, 200), result.command());
		assertEquals(Set.of(target), ((Gather) result.continuation()).drops());
	}
	private static View<Task> view(Task task) { return new View<>(1, task, false, 1, Optional.empty(), Optional.empty()); }

	private static Run run(ProductionKnowledge book, Acquire goal, Map<String, Integer> initial, boolean failFirst) {
		var kernel = new TaskKernel<Task, StoneAcquisition.World, VoxelCommand>(new ProductionDomain(book), new Limits(16, 16, 500, 100));
		var state = kernel.begin("session", "run", goal, 0);
		var inventory = new HashMap<>(initial);
		var known = new HashMap<>(world(initial).known());
		var commands = new ArrayList<VoxelCommand>(); var events = new ArrayList<Event>();
		List<Feedback> feedback = List.of(); int maxDepth = 1;
		for (int tick = 1; tick <= 100 && state.outcome().isEmpty(); tick++) {
			var base = world(inventory);
			var step = kernel.advance(state, new StoneAcquisition.World(base.eye(), base.feet(), inventory, known), feedback, tick);
			state = step.state(); events.addAll(step.events()); maxDepth = Math.max(maxDepth, state.stack().size()); feedback = List.of();
			for (var effect : step.effects()) {
				if (effect instanceof Stop<VoxelCommand> stop) { feedback = List.of(new Released(stop.token())); continue; }
				var start = (Start<VoxelCommand>) effect; commands.add(start.command());
				if (failFirst && commands.size() == 1) { feedback = List.of(new Finished(start.token(), Outcome.failure("injected_recipe_failure"))); continue; }
				if (start.command() instanceof Craft craft) {
					craft.recipe().ingredients().forEach((item, count) -> { assertTrue(inventory.getOrDefault(item, 0) >= count, "Missing " + item); inventory.merge(item, -count, Integer::sum); });
					inventory.merge(craft.recipe().output(), craft.recipe().yield(), Integer::sum);
				}
				else if (start.command() instanceof Place place) {
					assertTrue(inventory.getOrDefault(place.item(), 0) > 0); inventory.merge(place.item(), -1, Integer::sum);
					known.put(place.support().offset(0, 1, 0), new Seen(place.item(), false, true, 15, tick));
				}
				else fail("Unexpected acquisition action: " + start.command());
				feedback = List.of(new Finished(start.token(), Outcome.success("effect_applied")));
			}
		}
		assertTrue(state.outcome().isPresent(), "Production did not terminate");
		return new Run(state.outcome().orElseThrow(), inventory, commands, events, maxDepth);
	}
	private static StoneAcquisition.World world(Map<String, Integer> inventory) {
		return new StoneAcquisition.World(new Pose(.5, 2.62, .5, 0, 45), new Pos(0, 1, 0), inventory,
			Map.of(new Pos(0, 2, 0), new Seen("minecraft:air", true, true, 15, 0), new Pos(1, 0, 0), new Seen("minecraft:grass_block", false, true, 15, 0), new Pos(1, 1, 0), new Seen("minecraft:air", true, true, 15, 0), new Pos(1, 2, 0), new Seen("minecraft:air", true, true, 15, 0)));
	}
	private static Recipe recipe(String id, String output, int yield, int width, String... inputs) {
		var cells = new ArrayList<Cell>(); for (int i = 0; i < inputs.length; i++) cells.add(new Cell(i, inputs[i]));
		return new Recipe(id, output, yield, width, cells);
	}
	private record Run(Outcome outcome, Map<String, Integer> inventory, List<VoxelCommand> commands, List<Event> events, int maxDepth) {}
}
