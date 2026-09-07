package ai.moeru.airicraft.systemone.voxel;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelCommand.*;
import ai.moeru.airicraft.systemone.voxel.ProductionKnowledge.SearchPrior;
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
		var world = world(Map.of(blocked, seen("lava"), FEET.offset(-1, -1, 0), seen("minecraft:dirt")));
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
	private static View<Task> view(Task task) { return new View<>(1, task, false, 1, Optional.empty(), Optional.empty()); }
	private static Seen seen(String id) { return new Seen(id, id.equals("minecraft:air"), true, 15, 1); }
	private static StoneAcquisition.World world(Map<Pos, Seen> blocks) {
		var known = new HashMap<Pos, Seen>();
		for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = 3; y <= 6; y++) known.put(new Pos(x, y, z), seen("minecraft:air"));
		known.putAll(blocks);
		return new StoneAcquisition.World(new Pose(.5, 5.62, .5, 0, 45), FEET, Map.of("minecraft:stone_pickaxe", 1), known);
	}
}
