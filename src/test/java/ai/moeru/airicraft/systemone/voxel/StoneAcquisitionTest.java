package ai.moeru.airicraft.systemone.voxel;

import ai.moeru.airicraft.systemone.TaskKernel;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.StoneAcquisition.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelCommand.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import static org.junit.jupiter.api.Assertions.*;

class StoneAcquisitionTest {
	private static final Pos FEET = new Pos(0, 4, 0);
	private static final Pose EYE = new Pose(0.5, 5.62, 0.5, 0, 55);
	private static final Map<String, Integer> TOOL = Map.of("minecraft:wooden_pickaxe", 1);
	private final StoneAcquisition domain = new StoneAcquisition();

	@Test void geologicalPriorSelectsAnObservedSurfaceInsteadOfAnUnseenStoneCoordinate() {
		Pos surface = FEET.offset(1, -1, 0);
		var world = new World(EYE, FEET, TOOL, Map.of(surface, seen("minecraft:grass_block")));
		var result = domain.decide(ready(), world);
		assertEquals(new Break(surface, "minecraft:grass_block"), ((Execute<Task, VoxelCommand>) result).command());
	}

	@Test void anObservedTargetAfterMovementDoesNotTriggerARoutinePanorama() {
		Pos surface = FEET.offset(1, -1, 0);
		Task task = new Task(3, FEET, 0, 0, Set.of(), Optional.of(new Navigate(FEET, 24, 200)));
		var view = new View<>(1, task, false, 2, Optional.of(Outcome.success("stance_reached")), Optional.empty());
		var result = domain.decide(view, new World(EYE, FEET, TOOL, Map.of(surface, seen("minecraft:dirt"))));
		assertEquals(new Break(surface, "minecraft:dirt"), ((Execute<Task, VoxelCommand>) result).command());
	}

	@Test void surveysOnlyWhenNoObservedLocalTargetIsAvailable() {
		var view = new View<>(1, Task.begin(3, FEET), false, 1, Optional.<Outcome>empty(), Optional.<Outcome>empty());
		assertInstanceOf(Look.class, ((Execute<Task, VoxelCommand>) domain.decide(view, new World(EYE, FEET, TOOL, Map.of()))).command());
	}

	@Test void distantExposedStoneDoesNotOverrideAffordableLocalExcavation() {
		Pos surface = FEET.offset(1, -1, 0);
		var world = new World(EYE, FEET, TOOL, Map.of(surface, seen("minecraft:dirt"), FEET.offset(100, -1, 0), seen("minecraft:stone")));
		assertEquals(new Break(surface, "minecraft:dirt"), ((Execute<Task, VoxelCommand>) domain.decide(ready(), world)).command());
	}

	@Test void willNotBreakItsOwnFootingOrUnidentifiedDarkBlocks() {
		var world = new World(EYE, FEET, TOOL, Map.of(FEET.offset(0, -1, 0), seen("minecraft:stone"),
			FEET.offset(1, -1, 0), new Seen("unknown", false, false, 0, 1)));
		assertInstanceOf(Complete.class, domain.decide(ready(), world));
	}

	@Test void prefersDeepeningTheExcavationOverWideningItsSurface() {
		Pos lower = FEET.offset(1, -1, 0), higher = FEET.offset(1, 0, 0);
		var world = new World(EYE, FEET, TOOL, Map.of(lower, seen("minecraft:dirt"), higher, seen("minecraft:grass_block")));
		assertEquals(new Break(lower, "minecraft:dirt"), ((Execute<Task, VoxelCommand>) domain.decide(ready(), world)).command());
	}

	@Test void aSuccessfulBreakCanDescendIntoObservedSpaceWithKnownSupport() {
		Pos target = FEET.offset(1, -1, 0);
		Task task = new Task(3, FEET, 4, 0, Set.of(), Optional.of(new Break(target, "minecraft:dirt")));
		var view = new View<>(1, task, false, 2, Optional.of(Outcome.success("broken")), Optional.empty());
		var world = new World(EYE, FEET, TOOL, Map.of(target, seen("minecraft:air"), target.offset(0, 1, 0), seen("minecraft:air"),
			target.offset(0, -1, 0), seen("minecraft:dirt"), target.offset(0, 2, 0), seen("minecraft:air")));
		assertEquals(new Navigate(target, 24, 200), ((Execute<Task, VoxelCommand>) domain.decide(view, world)).command());
	}

	@Test void pathCompletionAloneDoesNotSatisfyTheInventoryGoal() {
		var world = new World(EYE, FEET, Map.of("minecraft:cobblestone", 3), Map.of());
		assertEquals(new Complete<Task, VoxelCommand>(Outcome.success("cobblestone_inventory_observed")), domain.decide(ready(), world));
		assertNotEquals(new Complete<Task, VoxelCommand>(Outcome.success("cobblestone_inventory_observed")),
			domain.decide(ready(), new World(EYE, FEET, TOOL, Map.of())));
	}
	@Test void descentProtectsEarlierFootingSoTheReturnRouteIsNotMinedAway() {
		var origin = new Pos(0, 64, 0); var feet = new Pos(1, 63, 0); var forward = new Pos(2, 62, 0);
		var task = new Task(3, origin, 0, 0, Set.of(), Optional.of(new Navigate(feet, 24, 200)));
		var view = new View<>(1, task, false, 2, Optional.of(Outcome.success("stance_reached")), Optional.<Outcome>empty());
		var observation = new World(new Pose(1.5, 64.62, .5, 0, 45), feet, TOOL,
			Map.of(origin.offset(0, -1, 0), seen("minecraft:stone"), forward, seen("minecraft:dirt")));
		var result = (Execute<Task, VoxelCommand>) domain.decide(view, observation);
		assertEquals(new Break(forward, "minecraft:dirt"), result.command());
		assertEquals(java.util.List.of(origin, feet), result.continuation().route());
		var failed = new View<>(1, result.continuation(), false, 3, Optional.of(Outcome.failure("target_changed")), Optional.<Outcome>empty());
		var next = domain.decide(failed, observation);
		assertFalse(next instanceof Execute<Task, VoxelCommand> execute && execute.command() instanceof Break broken && broken.target().equals(origin.offset(0, -1, 0)));
	}

	@Test void clearsTheNextStepBeforeWalkingOntoItsFloor() {
		Pos head = FEET.offset(1, 0, 0), nextStep = head.offset(0, -1, 0);
		var task = new Task(3, FEET, 0, 0, Set.of(), Optional.of(new Break(head, "minecraft:dirt")));
		var view = new View<>(1, task, false, 2, Optional.of(Outcome.success("broken")), Optional.<Outcome>empty());
		var world = new World(EYE, FEET, TOOL, Map.of(head, seen("minecraft:air"), head.offset(0, 1, 0), seen("minecraft:air"), nextStep, seen("minecraft:dirt")));
		assertEquals(new Break(nextStep, "minecraft:dirt"), ((Execute<Task, VoxelCommand>) domain.decide(view, world)).command());
	}

	@Test void clearsOverheadEntryAndRetainsTheIntendedLowerStep() {
		Pos target = FEET.offset(1, -1, 0), ceiling = target.offset(0, 2, 0);
		Task task = new Task(3, FEET, 4, 0, Set.of(), Optional.of(new Break(target, "minecraft:dirt")));
		var view = new View<>(1, task, false, 2, Optional.of(Outcome.success("broken")), Optional.<Outcome>empty());
		var cells = new java.util.HashMap<Pos, Seen>(Map.of(target, seen("minecraft:air"), target.offset(0, 1, 0), seen("minecraft:air"),
			target.offset(0, -1, 0), seen("minecraft:stone"), ceiling, seen("minecraft:dirt")));
		var preparation = (Execute<Task, VoxelCommand>) domain.decide(view, new World(EYE, FEET, TOOL, cells));
		assertEquals(new Break(ceiling, "minecraft:dirt"), preparation.command());
		cells.put(ceiling, seen("minecraft:air"));
		var resume = new View<>(1, preparation.continuation(), false, 3, Optional.of(Outcome.success("broken")), Optional.<Outcome>empty());
		assertEquals(new Navigate(target, 24, 200), ((Execute<Task, VoxelCommand>) domain.decide(resume, new World(EYE, FEET, TOOL, cells))).command());
	}

	private static View<Task> ready() {
		return new View<>(1, new Task(3, FEET, 4, 0, Set.of(), Optional.empty()), false, 1, Optional.empty(), Optional.empty());
	}
	private static Seen seen(String id) { return new Seen(id, id.equals("minecraft:air"), true, 15, 1); }
}
