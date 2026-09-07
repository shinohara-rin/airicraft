package ai.moeru.airicraft.systemone.voxel;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelCommand.*;
import ai.moeru.airicraft.systemone.voxel.ProductionKnowledge.SearchPrior;
import ai.moeru.airicraft.systemone.voxel.ProductionKnowledge.SupportMaterial;
import ai.moeru.airicraft.systemone.voxel.UndergroundSearch.Task;

class UndergroundSearchTest {
	private static final Pos FEET = new Pos(0, 4, 0);
	private static final SearchPrior PRIOR = new SearchPrior("ore", 1, 16, 20, List.of("minecraft:stone", "minecraft:dirt"));
	private final UndergroundSearch search = new UndergroundSearch();
	@Test void aPriorOpensAnObservedSurfaceInsteadOfSelectingBuriedOre() {
		var world = world(Map.of(FEET.offset(0, 0, 1), seen("minecraft:dirt")));
		var action = assertInstanceOf(Execute.class, search.decide(view(Task.begin(PRIOR, List.of("iron"), world, Set.of())), world));
		assertEquals(new Break(FEET.offset(0, 0, 1), "minecraft:dirt"), action.command());
	}
	@Test void anObservedCaveFloorCompetesWithFurtherExcavation() {
		var floor = FEET.offset(0, -1, 1);
		var world = world(Map.of(floor, seen("minecraft:stone")));
		var action = assertInstanceOf(Execute.class, search.decide(view(Task.begin(PRIOR, List.of("iron"), world, Set.of())), world));
		assertEquals(new Navigate(FEET.offset(0, 0, 1), 12, 200), action.command());
	}
	@Test void unknownSupportMustBeObservedBeforeDescent() {
		var world = world(Map.of());
		var action = assertInstanceOf(Execute.class, search.decide(view(Task.begin(PRIOR, List.of("iron"), world, Set.of())), world));
		assertInstanceOf(Look.class, action.command());
	}
	@Test void observedSupportDoesNotDependOnTheExcavationMaterialList() {
		for (String id : List.of("minecraft:andesite", "minecraft:deepslate", "another_game:floor")) {
			var floor = FEET.offset(0, -1, 1);
			var world = world(Map.of(floor, new Seen(id, false, true, true, 15, 1)));
			var action = assertInstanceOf(Execute.class, search.decide(view(Task.begin(PRIOR, List.of("iron"), world, Set.of())), world));
			assertEquals(new Navigate(FEET.offset(0, 0, 1), 12, 200), action.command());
			assertFalse(StoneAcquisition.supportsStanding(new Seen(id, false, true, false, 15, 1)));
			assertFalse(StoneAcquisition.supportsStanding(new Seen(id, false, false, true, 0, 1)));
		}
	}
	@Test void cheapFloorBehindTheSearchDoesNotDisplaceForwardExcavation() {
		var surface = FEET.offset(0, 0, 1);
		var world = world(Map.of(surface, seen("minecraft:stone"), FEET.offset(0, -1, -1), seen("minecraft:stone")));
		var action = assertInstanceOf(Execute.class, search.decide(view(Task.begin(PRIOR, List.of("iron"), world, Set.of())), world));
		assertEquals(new Break(surface, "minecraft:stone"), action.command());
	}
	@Test void discoveredOreFinishesSearchEvenWhileMovementOwnsTheMotor() {
		var world = world(Map.of(FEET.offset(1, 0, 0), seen("iron")));
		var task = Task.begin(PRIOR, List.of("iron"), world, Set.of());
		var result = search.decide(new View<>(1, task, true, 1, Optional.empty(), Optional.empty()), world);
		assertEquals(ResultKind.SUCCEEDED, assertInstanceOf(Complete.class, result).outcome().kind());
	}
	@Test void anInterruptedDestinationIsRevalidatedInsteadOfBlindlyResumed() {
		var blocked = FEET.offset(0, -1, 1);
		var world = world(Map.of(blocked, new Seen("lava", false, true, false, 15, 1), FEET.offset(-1, -1, 0), seen("minecraft:dirt")));
		var task = new Task(PRIOR, List.of("iron"), FEET, FEET, 0, 3, Set.of(), Set.of(), Optional.of(blocked), Optional.empty(), new Navigate(blocked, 12, 200));
		var action = assertInstanceOf(Execute.class, search.decide(view(task), world));
		assertEquals(new Navigate(FEET.offset(-1, 0, 0), 12, 200), action.command());
		assertEquals(0, ((Task) action.continuation()).direction(), "local sidesteps preserve the exploration heading");
	}
	@Test void reachingANewPositionRetiresLocalFailuresWithoutResettingTheStepBudget() {
		var world = world(Map.of(FEET.offset(0, 0, 1), seen("minecraft:dirt")));
		var rejected = new HashSet<Pos>();
		for (int x = 20; x < 36; x++) rejected.add(new Pos(x, 4, 0));
		var task = new Task(PRIOR, List.of("iron"), FEET, FEET.offset(0, 0, -1), 0, 5, rejected, Set.of(), Optional.of(FEET), Optional.empty(), new Navigate(FEET, 12, 200));
		var step = assertInstanceOf(Execute.class, search.decide(new View<>(1, task, false, 6, Optional.of(Outcome.success("arrived")), Optional.empty()), world));
		assertEquals(new Break(FEET.offset(0, 0, 1), "minecraft:dirt"), step.command());
		assertEquals(6, ((Task) step.continuation()).steps());
		assertTrue(((Task) step.continuation()).rejected().isEmpty());
		var exhausted = new Task(PRIOR, List.of("iron"), FEET, task.position(), 0, PRIOR.maxSteps() - 1, rejected, Set.of(), Optional.of(FEET), Optional.empty(), task.last());
		assertEquals(ResultKind.FAILED, assertInstanceOf(Complete.class, search.decide(view(exhausted), world)).outcome().kind());
	}
	@Test void partialFailedMovementRecordsActualPositionAndConsumesSearchBudget() {
		var world = world(Map.of(FEET.offset(0, 0, 1), seen("minecraft:dirt")));
		var origin = FEET.offset(0, 0, -1);
		var unreached = FEET.offset(1, 0, 0);
		var task = new Task(PRIOR, List.of("iron"), origin, origin, 0, 5, Set.of(), Set.of(), Optional.of(unreached), Optional.empty(), new Navigate(unreached, 12, 200));
		var step = assertInstanceOf(Execute.class, search.decide(new View<>(1, task, false, 6, Optional.of(Outcome.failure("blocked")), Optional.empty()), world));
		var next = (Task) step.continuation();
		assertEquals(6, next.steps());
		assertEquals(List.of(origin, FEET), next.route());
		assertFalse(next.route().contains(unreached));
		assertTrue(next.rejected().contains(unreached));
		var unchanged = (Task) assertInstanceOf(Execute.class, search.decide(view(next), world)).continuation();
		assertEquals(next.steps(), unchanged.steps());
		assertEquals(next.route(), unchanged.route());
	}
	@Test void aVisibleSideSupportAllowsOneExplicitFootholdBeforeNavigation() {
		var floor = FEET.offset(0, -2, 1);
		var support = floor.offset(1, 0, 0);
		var base = world(Map.of(floor, seen("minecraft:air"), support, seen("minecraft:stone")));
		var world = new StoneAcquisition.World(base.eye(), base.feet(), Map.of("building_item", 2), base.known());
		var prior = new SearchPrior(PRIOR.item(), PRIOR.preferredY(), PRIOR.radius(), PRIOR.maxSteps(), PRIOR.excavatable(),
			List.of(new SupportMaterial("building_item", "placed_support")));
		var task = Task.begin(prior, List.of("iron"), world, Set.of());
		var placement = assertInstanceOf(Execute.class, search.decide(view(task), world));
		assertEquals(new Place("building_item", support, "minecraft:stone", Face.WEST, "placed_support"), placement.command());
		var known = new HashMap<>(world.known()); known.put(floor, new Seen("placed_support", false, true, true, 15, 2));
		var placedWorld = new StoneAcquisition.World(world.eye(), world.feet(), Map.of("building_item", 1), known);
		var placed = (Task) placement.continuation();
		var move = assertInstanceOf(Execute.class, search.decide(new View<>(1, placed, false, 2, Optional.of(Outcome.success("placed")), Optional.empty()), placedWorld));
		assertEquals(new Navigate(floor.offset(0, 1, 0), 12, 200), move.command());
		var reserved = search.decide(view(task), world, Map.of("building_item", 2));
		assertFalse(reserved instanceof Execute<?, ?> action && action.command() instanceof Place);
		known.remove(floor);
		var unknown = search.decide(view(task), new StoneAcquisition.World(world.eye(), world.feet(), world.inventory(), known));
		assertFalse(unknown instanceof Execute<?, ?> action && action.command() instanceof Place);
		known.put(floor, seen("minecraft:air"));
		known.put(support.offset(0, 1, 0), seen("minecraft:stone"));
		var flat = assertInstanceOf(Execute.class, search.decide(view(task), new StoneAcquisition.World(world.eye(), world.feet(), world.inventory(), known)));
		assertEquals(floor.offset(0, 1, 0), assertInstanceOf(Place.class, flat.command()).destination(), "a fillable gap at the cave floor competes before creating a lower step");
	}
	@Test void anUnusableRetainedStepDoesNotPermitRevisitingOtherFootholds() {
		var visitedFloor = FEET.offset(-1, -1, 0);
		var base = world(Map.of(visitedFloor, seen("minecraft:stone")));
		var world = new StoneAcquisition.World(base.eye(), base.feet(), base.inventory(), base.known(), Set.of(visitedFloor));
		var forward = FEET.offset(0, 0, 1);
		var task = new Task(PRIOR, List.of("iron"), FEET, FEET, 0, 3, Set.of(), Set.of(), Optional.of(forward), Optional.of(forward.offset(0, -1, 0)), null);
		var action = search.decide(view(task), world);
		assertFalse(action instanceof Execute<?, ?> execute && execute.command() instanceof Navigate move && move.stance().equals(visitedFloor.offset(0, 1, 0)));
	}
	private static View<Task> view(Task task) { return new View<>(1, task, false, 1, Optional.empty(), Optional.empty()); }
	private static Seen seen(String id) { return new Seen(id, id.equals("minecraft:air"), true, !id.equals("minecraft:air"), 15, 1); }
	private static StoneAcquisition.World world(Map<Pos, Seen> blocks) {
		var known = new HashMap<Pos, Seen>();
		for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = 3; y <= 6; y++) known.put(new Pos(x, y, z), seen("minecraft:air"));
		known.putAll(blocks);
		return new StoneAcquisition.World(new Pose(.5, 5.62, .5, 0, 45), FEET, Map.of("minecraft:stone_pickaxe", 1), known);
	}
}
