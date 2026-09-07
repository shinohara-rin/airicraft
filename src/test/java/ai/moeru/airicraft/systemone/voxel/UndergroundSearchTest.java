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
	@Test void anObservedTorchIsTraversedWithoutBeingExcavated() {
		var next = FEET.offset(0, 0, 1);
		var world = world(Map.of(next.offset(0, -1, 0), seen("minecraft:stone"),
			next, new Seen("minecraft:wall_torch", false, true, false, 14, 1, true)));
		var action = assertInstanceOf(Execute.class, search.decide(view(Task.begin(PRIOR, List.of("iron"), world, Set.of())), world));
		assertEquals(new Navigate(next, 12, 200), action.command());
	}
	@Test void unknownSupportMustBeObservedBeforeDescent() {
		var world = world(Map.of());
		var action = assertInstanceOf(Execute.class, search.decide(view(Task.begin(PRIOR, List.of("iron"), world, Set.of())), world));
		assertInstanceOf(Look.class, action.command());
	}
	@Test void newlyObservedCaveFloorDisplacesAProposedDownwardExcavation() {
		var floor = FEET.offset(0, -1, 1);
		var unseen = world(Map.of());
		var proposed = assertInstanceOf(Execute.class, search.decide(view(Task.begin(PRIOR, List.of("iron"), unseen, Set.of())), unseen));
		assertInstanceOf(Look.class, proposed.command());
		var task = (Task) proposed.continuation();
		assertEquals(Optional.of(floor), task.destination());
		var observed = world(Map.of(floor, seen("minecraft:stone")));
		var next = assertInstanceOf(Execute.class, search.decide(new View<>(1, task, false, 2, Optional.of(Outcome.success("looked")), Optional.empty()), observed));
		assertEquals(new Navigate(floor.offset(0, 1, 0), 12, 200), next.command());
	}
	@Test void clearingHeadroomDoesNotAbandonTheDownwardStepEvenAcrossALook() {
		Pos upper = FEET.offset(0, 0, 1), lower = upper.offset(0, -1, 0);
		var initial = world(Map.of(upper, seen("minecraft:dirt")));
		var opening = assertInstanceOf(Execute.class, search.decide(view(Task.begin(PRIOR, List.of("iron"), initial, Set.of())), initial));
		assertEquals(new Break(upper, "minecraft:dirt"), opening.command());
		var opened = (Task) opening.continuation();
		assertEquals(Optional.of(lower), opened.destination());
		var unknownFloor = world(Map.of());
		var looking = assertInstanceOf(Execute.class, search.decide(new View<>(1, opened, false, 2, Optional.of(Outcome.success("broken")), Optional.empty()), unknownFloor));
		assertInstanceOf(Look.class, looking.command());
		var observedFloor = world(Map.of(lower, seen("minecraft:stone"), lower.offset(0, -1, 0), seen("minecraft:stone")));
		assertTrue(StoneAcquisition.standable(observedFloor.known(), upper), "the intermediate flat position is now walkable");
		var downward = assertInstanceOf(Execute.class, search.decide(new View<>(1, (Task) looking.continuation(), false, 3, Optional.of(Outcome.success("looked")), Optional.empty()), observedFloor));
		assertEquals(new Break(lower, "minecraft:stone"), downward.command());
		var completedStep = world(Map.of(lower, seen("minecraft:air"), lower.offset(0, -1, 0), seen("minecraft:stone")));
		var move = assertInstanceOf(Execute.class, search.decide(new View<>(1, (Task) downward.continuation(), false, 4, Optional.of(Outcome.success("broken")), Optional.empty()), completedStep));
		assertEquals(new Navigate(lower, 12, 200), move.command());
		var unsafe = search.decide(new View<>(1, (Task) looking.continuation(), false, 3, Optional.of(Outcome.success("looked")), Optional.empty()), observedFloor, Map.of(), p -> !p.equals(lower));
		assertFalse(unsafe instanceof Execute<?, ?> action && action.command().equals(new Break(lower, "minecraft:stone")), "commitment never overrides a newly observed hazard");
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
		var task = new Task(PRIOR, List.of("iron"), FEET, FEET, 0, 3, Map.of(), Set.of(), Optional.of(blocked), Optional.empty(), new Navigate(blocked, 12, 200));
		var action = assertInstanceOf(Execute.class, search.decide(view(task), world));
		assertEquals(new Navigate(FEET.offset(-1, 0, 0), 12, 200), action.command());
		assertEquals(0, ((Task) action.continuation()).direction(), "local sidesteps preserve the exploration heading");
	}
	@Test void reachingANewPositionRetiresLocalFailuresWithoutResettingTheStepBudget() {
		var world = world(Map.of(FEET.offset(0, 0, 1), seen("minecraft:dirt")));
		var rejected = new HashMap<Pos, UndergroundSearch.Rejection>();
		for (int x = 20; x < 36; x++) rejected.put(new Pos(x,4,0), UndergroundSearch.rejection(world,new Pos(x,4,0),UndergroundSearch.RejectionReason.COMMAND_FAILED));
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
		var task = new Task(PRIOR, List.of("iron"), origin, origin, 0, 5, Map.of(), Set.of(), Optional.of(unreached), Optional.empty(), new Navigate(unreached, 12, 200));
		var step = assertInstanceOf(Execute.class, search.decide(new View<>(1, task, false, 6, Optional.of(Outcome.failure("blocked")), Optional.empty()), world));
		var next = (Task) step.continuation();
		assertEquals(6, next.steps());
		assertEquals(List.of(origin, FEET), next.route());
		assertFalse(next.route().contains(unreached));
		assertTrue(next.rejected().containsKey(unreached));
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
		var task = new Task(PRIOR, List.of("iron"), FEET, FEET, 0, 3, Map.of(), Set.of(), Optional.of(forward), Optional.of(forward.offset(0, -1, 0)), null,List.of(visitedFloor.offset(0,1,0),FEET));
		var action = search.decide(view(task), world);
		assertFalse(action instanceof Execute<?, ?> execute && execute.command() instanceof Navigate move && move.stance().equals(visitedFloor.offset(0, 1, 0)));
	}
	@Test void headingIsAPreferenceAtTheSearchRegionBoundary() {
		Pos origin = new Pos(-6,69,18), feet = new Pos(-17,16,81), inward = feet.offset(0,0,-1);
		var prior = new SearchPrior("ore",16,64,96,List.of("minecraft:stone"));
		var known = new HashMap<Pos,Seen>();
		for (int x=-18;x<=-16;x++) for (int z=80;z<=82;z++) for (int y=15;y<=18;y++) known.put(new Pos(x,y,z),seen(y==15 ? "minecraft:stone" : "minecraft:air"));
		known.put(inward,seen("minecraft:stone")); known.put(inward.offset(0,1,0),seen("minecraft:stone"));
		var world = new StoneAcquisition.World(new Pose(-16.5,17.62,81.5,0,0),feet,Map.of("minecraft:stone_pickaxe",1),known,Set.of(feet.offset(1,-1,0),feet.offset(0,-1,0)));
		var task = new Task(prior,List.of("iron"),origin,feet,0,73,Map.of(),Set.of(),Optional.empty(),Optional.empty(),null,List.of(origin,feet.offset(1,0,0),feet));
		var action = assertInstanceOf(Execute.class,search.decide(view(task),world));
		var target = assertInstanceOf(Break.class,action.command()).target();
		assertEquals(inward.x(),target.x()); assertEquals(inward.z(),target.z());
		assertEquals(73,((Task)action.continuation()).steps()); assertEquals(origin,((Task)action.continuation()).origin());
	}
	@Test void clearsVisibleLitterThenWaitsForAnObservationOfItsHiddenSupport() {
		Pos next = FEET.offset(0,0,1), floor = next.offset(0,-1,0);
		var known = new HashMap<>(world(Map.of()).known()); known.remove(floor);
		known.put(next,new Seen("minecraft:leaf_litter",false,true,false,15,1,true));
		var prior = new SearchPrior("ore",4,16,20,List.of("minecraft:stone","minecraft:leaf_litter"));
		var world = new StoneAcquisition.World(EYE_FOR_LITTER,FEET,Map.of("minecraft:stone_pickaxe",1),known);
		var task = Task.begin(prior,List.of("iron"),world,Set.of());
		var clear = assertInstanceOf(Execute.class,search.decide(view(task),world,Map.of(),p->p.x()==0&&p.z()>=0));
		assertEquals(new Break(next,"minecraft:leaf_litter"),clear.command());
		known.put(next,seen("minecraft:air"));
		world = new StoneAcquisition.World(world.eye(),FEET,world.inventory(),known);
		var inspect = assertInstanceOf(Execute.class,search.decide(new View<>(1,(Task)clear.continuation(),false,2,Optional.of(Outcome.success("broken")),Optional.empty()),world,Map.of(),p->p.x()==0&&p.z()>=0));
		assertInstanceOf(Look.class,inspect.command(),"removing the plant does not prove what was underneath it");
		known.put(floor,seen("minecraft:stone"));
		world = new StoneAcquisition.World(world.eye(),FEET,world.inventory(),known);
		var enter = assertInstanceOf(Execute.class,search.decide(new View<>(1,(Task)inspect.continuation(),false,3,Optional.of(Outcome.success("looked")),Optional.empty()),world,Map.of(),p->p.x()==0&&p.z()>=0));
		assertEquals(next,assertInstanceOf(Navigate.class,enter.command()).stance());
	}
	@Test void newSupportEvidenceReopensARejectedCandidateButRepeatedObservationsDoNot() {
		var next = FEET.offset(0,0,1); var side = FEET.offset(-1,0,0);
		var known = new HashMap<>(world(Map.of(side,seen("minecraft:stone"),FEET.offset(1,0,0),seen("minecraft:bedrock"),FEET.offset(0,0,-1),seen("minecraft:bedrock"))).known());
		var base = world(Map.of());
		var prior = new SearchPrior("ore",FEET.y(),16,20,List.of("minecraft:stone"));
		var unknown = new StoneAcquisition.World(base.eye(),FEET,base.inventory(),known);
		var look = assertInstanceOf(Execute.class,search.decide(view(Task.begin(prior,List.of("iron"),unknown,Set.of())),unknown));
		assertInstanceOf(Look.class,look.command());
		var alternative = assertInstanceOf(Execute.class,search.decide(new View<>(1,(Task)look.continuation(),false,2,Optional.of(Outcome.success("looked")),Optional.empty()),unknown));
		assertInstanceOf(Break.class,alternative.command());
		var rejected = (Task)alternative.continuation();
		var retry = new View<>(1,rejected,false,3,Optional.of(Outcome.failure("target_changed")),Optional.<Outcome>empty());
		known.replaceAll((p,v)->new Seen(v.blockId(),v.empty(),v.identified(),v.fullSupport(),14,3,v.clearForBody()));
		var unchanged = new StoneAcquisition.World(base.eye(),FEET,base.inventory(),known);
		assertInstanceOf(Complete.class,search.decide(retry,unchanged), "timestamps and light updates are not new geometry");
		known.put(next.offset(0,-1,0),seen("minecraft:stone"));
		var observed = new StoneAcquisition.World(base.eye(),FEET,base.inventory(),known);
		var reopened = assertInstanceOf(Execute.class,search.decide(retry,observed));
		assertEquals(new Navigate(next,12,200),reopened.command());
		var failed = search.decide(new View<>(1,(Task)reopened.continuation(),false,4,Optional.of(Outcome.failure("blocked")),Optional.empty()),observed);
		assertFalse(failed instanceof Execute<?,?> e && e.command().equals(reopened.command()), "unchanged failed navigation stays excluded");
	}

	private static final Pose EYE_FOR_LITTER = new Pose(.5,5.62,.5,0,45);
	private static View<Task> view(Task task) { return new View<>(1, task, false, 1, Optional.empty(), Optional.empty()); }
	private static Seen seen(String id) { return new Seen(id, id.equals("minecraft:air"), true, !id.equals("minecraft:air"), 15, 1); }
	private static StoneAcquisition.World world(Map<Pos, Seen> blocks) {
		var known = new HashMap<Pos, Seen>();
		for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = 3; y <= 6; y++) known.put(new Pos(x, y, z), seen("minecraft:air"));
		known.putAll(blocks);
		return new StoneAcquisition.World(new Pose(.5, 5.62, .5, 0, 45), FEET, Map.of("minecraft:stone_pickaxe", 1), known);
	}
}
