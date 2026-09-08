package ai.moeru.airicraft.systemone.voxel;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelCommand.*;
import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionDomain.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionKnowledge.*;

class TerrainAccessTest {
	private static final Set<String> CLEARABLE = Set.of("stone");
	@Test void aChangedCorridorIsPreparedWithoutBreakingItsFloor() {
		var known = solid(); Pos start = new Pos(0, 4, 0), goal = new Pos(0, 4, 4);
		for (int z = 0; z <= 4; z++) for (int y = 4; y <= 5; y++) known.put(new Pos(0, y, z), air());
		known.put(new Pos(0, 4, 2), stone()); known.put(new Pos(0, 5, 2), stone());
		var result = run(known, start, goal);
		assertEquals(2, result.stream().filter(Break.class::isInstance).count());
		assertTrue(result.stream().filter(Break.class::isInstance).map(Break.class::cast).allMatch(b -> b.target().z() == 2 && b.target().y() >= 4));
	}
	@Test void aTwoBlockPickupDescentGetsAnAlternateStairRatherThanALargerFallLimit() {
		var known = solid(); Pos start = new Pos(0, 4, 0), goal = new Pos(0, 2, 1);
		known.put(start, air()); known.put(start.offset(0, 1, 0), air());
		for (int y = 2; y <= 5; y++) known.put(new Pos(0, y, 1), air());
		var result = run(known, start, goal);
		assertTrue(result.stream().anyMatch(Break.class::isInstance));
		assertTrue(result.stream().filter(Navigate.class::isInstance).map(Navigate.class::cast).anyMatch(m -> m.stance().x() != 0));
	}
	@Test void unknownSpaceCanOnlyRequestObservationAndUnchangedLooksAreBounded() {
		Pos start = new Pos(0, 4, 0), goal = new Pos(0, 4, 1);
		var known = new HashMap<Pos, Seen>();
		known.put(start, air()); known.put(start.offset(0, 1, 0), air()); known.put(start.offset(0, -1, 0), stone());
		var state = TerrainAccess.State.begin(start, goal, 0); var world = world(known, start, Set.of());
		int commands = 0;
		for (int tick = 1; tick <= 60; tick++) {
			var decision = TerrainAccess.advance(state, world, commands == 0 ? Optional.empty() : Optional.of(Outcome.success("looked")), tick, CLEARABLE, p -> true);
			if (decision instanceof TerrainAccess.Unavailable) return;
			var action = assertInstanceOf(TerrainAccess.Action.class, decision);
			assertInstanceOf(Look.class, action.command()); state = action.state(); commands++;
		}
		fail("an unchanged observation must exhaust local preparation");
	}
	@Test void newHazardsAndProtectedFootingInvalidateAPlannedBreak() {
		var known = solid(); Pos start = new Pos(0, 4, 0), goal = new Pos(0, 4, 1);
		known.put(start, air()); known.put(start.offset(0, 1, 0), air());
		var state = TerrainAccess.State.begin(start, goal, 0);
		var world = world(known, start, Set.of());
		assertInstanceOf(TerrainAccess.Unavailable.class, TerrainAccess.advance(state, world, Optional.empty(), 1, CLEARABLE, p -> !p.equals(goal)));
		var protectedWorld = world(known, start, Set.of(goal, goal.offset(0, 1, 0)));
		assertInstanceOf(TerrainAccess.Unavailable.class, TerrainAccess.advance(state, protectedWorld, Optional.empty(), 1, CLEARABLE, p -> true));
	}
	@Test void pickupAndReturnUseTheSameChildAndDoNotRecursivelyRetryItsFailure() {
		Pos start = new Pos(0, 4, 0), goal = new Pos(0, 2, 1);
		var harvest = new Harvest("ore", List.of("ore_block"), List.of(), Technique.EXPOSED);
		var prior = new SearchPrior("ore", 2, 16, 20, List.of("stone"));
		var domain = new ProductionDomain(new ProductionKnowledge("test", List.of(), List.of(harvest), List.of(), List.of(), List.of(prior), new LightingPolicy.Parameters(7,10,8,80,4)));
		var world = world(solid(), start, Set.of());
		var gather = new Gather(harvest, 1, start, 0, Set.of(), Set.of(goal), new Navigate(goal, 24, 200), Set.of(goal));
		var pickup = assertInstanceOf(Child.class, domain.decide(new View<Task>(3, gather, false, 1, Optional.of(Outcome.failure("observed_route_unavailable")), Optional.empty()), world));
		assertInstanceOf(Access.class, pickup.child());
		var failed = domain.decide(new View<Task>(3, (Task) pickup.continuation(), false, 2, Optional.empty(), Optional.of(Outcome.failure("no_path"))), world);
		assertFalse(failed instanceof Child<?, ?> child && child.child() instanceof Access);
		var route = new ReturnNavigation.State(List.of(start, goal), 0, 1, Set.of(), Optional.of(goal));
		var retreat = new Retreat(route);
		var repair = assertInstanceOf(Child.class, domain.decide(new View<Task>(4, retreat, false, 1, Optional.of(Outcome.failure("observed_route_unavailable")), Optional.empty()), world));
		assertInstanceOf(Access.class, repair.child());
		assertEquals(ProductionDomain.retainedCells(List.of(retreat)), ProductionDomain.retainedCells(List.of((Task) repair.continuation())));
	}
	@Test void accessRepairRequiresAnObservedResourceOrSurveyStance() {
		Pos start = new Pos(0, 4, 0), goal = new Pos(0, 2, 2), ore = new Pos(0, 2, 5);
		var harvest = new Harvest("ore", List.of("ore_block"), List.of(), Technique.EXPOSED);
		var prior = new SearchPrior("ore", 2, 16, 20, List.of("stone"));
		var domain = new ProductionDomain(new ProductionKnowledge("test", List.of(), List.of(harvest), List.of(), List.of(), List.of(prior), new LightingPolicy.Parameters(7,10,8,80,4)));
		var known = solid();
		for (int x = -1; x <= 1; x++) for (int z = 1; z <= 6; z++) for (int y = 2; y <= 5; y++) known.put(new Pos(x,y,z), air());
		known.put(start, air()); known.put(start.offset(0,1,0), air());
		known.put(ore, new Seen("ore_block", false, true, true, 15, 1));
		var gather = new Gather(harvest, 1, start, 0, Set.of(), Set.of(goal), new Navigate(goal, 24, 200));
		var failed = new View<Task>(3, gather, false, 1, Optional.of(Outcome.failure("observed_route_unavailable")), Optional.empty());
		var repair = assertInstanceOf(Child.class, domain.decide(failed, world(known, start, Set.of())));
		assertEquals(goal, assertInstanceOf(Access.class, repair.child()).state().goal());
		known.put(ore, new Seen("unknown", false, false, true, 0, 2));
		var survey = assertInstanceOf(Child.class,domain.decide(failed, world(known, start, Set.of())));
		assertEquals(goal,assertInstanceOf(Access.class,survey.child()).state().goal(),"the observed stance remains a valid survey destination without a known resource");
		known.remove(goal.offset(0,-1,0));
		var unknown = domain.decide(failed, world(known, start, Set.of()));
		assertFalse(unknown instanceof Child<?, ?> child && child.child() instanceof Access);
	}
	@Test void aFailedExplorationStepRetainsItsPurposeAndPreparesADifferentApproach() {
		Pos start = new Pos(0, 4, 0), goal = new Pos(0, 3, 1);
		var harvest = new Harvest("ore", List.of("ore_block"), List.of(), Technique.EXPOSED);
		var prior = new SearchPrior("ore", 2, 16, 20, List.of("stone"));
		var domain = new ProductionDomain(new ProductionKnowledge("test", List.of(), List.of(harvest), List.of(), List.of(), List.of(prior), new LightingPolicy.Parameters(7,10,8,80,4)));
		var known = solid(); known.put(start, air()); known.put(start.offset(0,1,0), air());
		for (int y = 3; y <= 5; y++) known.put(new Pos(0,y,1), air());
		var observed = world(known, start, Set.of(start.offset(0,-1,0)));
		var search = new UndergroundSearch.Task(prior, harvest.blocks(), start, start, 0, 2, Map.of(), Set.of(), Optional.of(goal), Optional.empty(), new Navigate(goal,12,200));
		var explore = new Explore(search, Map.of(), Set.of("ore"));
		var failed = new View<Task>(3, explore, false, 1, Optional.of(Outcome.failure("observed_route_unavailable")), Optional.empty());
		var repair = assertInstanceOf(Child.class, domain.decide(failed, observed));
		var access = assertInstanceOf(Access.class, repair.child());
		assertEquals(goal, access.state().goal());
		assertEquals(ProductionDomain.retainedCells(List.of(explore)), ProductionDomain.retainedCells(List.of((Task) repair.continuation())));
		var next = assertInstanceOf(Execute.class, domain.decide(new View<Task>(4, access, false, 2, Optional.empty(), Optional.empty()), observed));
		assertFalse(next.command() instanceof Navigate move && move.stance().equals(goal), "do not repeat the just-failed direct movement");
		var exhausted = domain.decide(new View<Task>(3, (Task) repair.continuation(), false, 3, Optional.empty(), Optional.of(Outcome.failure("no_access"))), observed);
		assertFalse(exhausted instanceof Child<?, ?> child && child.child() instanceof Access, "failed preparation returns to bounded search alternatives");
	}
	@Test void aFailedWorkstationApproachUsesLocalPreparationBeforeAbandoningItsStance() {
		Pos start = new Pos(0,4,0), goal = new Pos(0,2,2), furnace = new Pos(0,2,5);
		var known = solid();
		for (int z=0;z<=6;z++) for (int y=2;y<=5;y++) known.put(new Pos(0,y,z),air());
		known.put(furnace,new Seen("furnace",false,true,true,15,1));
		var observed = world(known,start,Set.of());
		var task = new Station("furnace",Map.of("input",1),Set.of("ingot"),0,Set.of(),new Navigate(goal,24,200));
		var domain = new ProductionDomain(new ProductionKnowledge("test",List.of(),List.of()));
		var failed = new View<Task>(3,task,false,1,Optional.of(Outcome.failure("observed_route_unavailable")),Optional.empty());
		var repair = assertInstanceOf(Child.class,domain.decide(failed,observed));
		assertEquals(goal,assertInstanceOf(Access.class,repair.child()).state().goal());
		assertEquals(task,assertInstanceOf(AfterAccess.class,repair.continuation()).saved());
		var exhausted = domain.decide(new View<Task>(3,(Task)repair.continuation(),false,2,Optional.empty(),Optional.of(Outcome.failure("no_access"))),observed);
		assertFalse(exhausted instanceof Child<?,?> child && child.child() instanceof Access);
		known.put(furnace,new Seen("unknown",false,false,true,0,2));
		var unknown = domain.decide(failed,world(known,start,Set.of()));
		assertFalse(unknown instanceof Child<?,?> child && child.child() instanceof Access);
	}
	@Test void surfaceHarvestAndPickupCanRepairAccessWithoutAnUndergroundSearchPrior() {
		Pos start = new Pos(0,4,0), goal = new Pos(0,2,2), log = new Pos(0,2,5);
		var harvest = new Harvest("log",List.of("log_block"),List.of(),Technique.EXPOSED);
		var knowledge = new ProductionKnowledge("surface",List.of(),List.of(harvest),List.of(),List.of(),List.of(),List.of("stone"),
			new LightingPolicy.Parameters(7,10,8,80,4),SurvivalPolicy.Parameters.minecraft());
		var domain = new ProductionDomain(knowledge); var known = solid();
		for (int z=0;z<=6;z++) for (int y=2;y<=5;y++) known.put(new Pos(0,y,z),air());
		known.put(log,new Seen("log_block",false,true,true,15,1));
		var gather = new Gather(harvest,1,start,0,Set.of(),Set.of(goal),new Navigate(goal,24,200));
		var failed = new View<Task>(3,gather,false,1,Optional.of(Outcome.failure("observed_route_unavailable")),Optional.empty());
		var child = assertInstanceOf(Child.class,domain.decide(failed,world(known,start,Set.of())));
		assertEquals(Set.of("stone"),assertInstanceOf(Access.class,child.child()).clearable());
		known.put(log,air());
		Pos pickup = new Pos(0,2,4);
		var collecting = new Gather(harvest,1,start,0,Set.of(),Set.of(pickup),new Navigate(pickup,24,200),Set.of(log));
		var pickupFailed = new View<Task>(3,collecting,false,2,failed.commandResult(),Optional.empty());
		var pickupChild = assertInstanceOf(Child.class,domain.decide(pickupFailed,world(known,start,Set.of())));
		assertEquals(pickup,assertInstanceOf(Access.class,pickupChild.child()).state().goal());
	}
	@Test void localStoneExplorationUsesTheSharedAccessRepairAndPreservesItsSearch() {
		Pos start = new Pos(0,4,0), goal = new Pos(0,2,2);
		var known = solid();
		for (int z=0;z<=4;z++) for (int y=2;y<=5;y++) known.put(new Pos(0,y,z),air());
		known.put(goal.offset(0,-1,0),stone());
		var state = new StoneAcquisition.Task(3,start,0,0,Set.of(),Optional.of(new Navigate(goal,24,200)));
		var domain = new ProductionDomain(new ProductionKnowledge("test",List.of(),List.of(),List.of(),List.of(),List.of(),List.of("stone"),
			new LightingPolicy.Parameters(7,10,8,80,4),SurvivalPolicy.Parameters.minecraft()));
		var task = new Excavate(state);
		var observed = new StoneAcquisition.World(new Pose(.5,5.62,.5,0,0),start,Map.of("minecraft:wooden_pickaxe",1),known,Set.of());
		var child = assertInstanceOf(Child.class,domain.decide(new View<Task>(2,task,false,1,Optional.of(Outcome.failure("observed_route_unavailable")),Optional.empty()),observed));
		assertEquals(goal,assertInstanceOf(Access.class,child.child()).state().goal());
		assertEquals(task,assertInstanceOf(AfterAccess.class,child.continuation()).saved());
		var failed = domain.decide(new View<Task>(2,(Task)child.continuation(),false,2,Optional.empty(),Optional.of(Outcome.failure("no_access"))),observed);
		assertFalse(failed instanceof Child<?,?> c && c.child() instanceof Access,"a failed repair must return to the stone task's bounded alternatives");
	}
	@Test void aFailedDirectMoveCanStillClearAnObservedObstructionOnThatEdge() {
		Pos start = new Pos(0, 4, 0), goal = new Pos(0, 4, 1);
		var known = solid(); known.put(start, air()); known.put(start.offset(0,1,0), air());
		known.put(goal, air());
		var state = TerrainAccess.State.afterFailedNavigation(start, goal, 0);
		var action = assertInstanceOf(TerrainAccess.Action.class, TerrainAccess.advance(state, world(known, start, Set.of()), Optional.empty(), 1, CLEARABLE, p -> true));
		assertEquals(new Break(goal.offset(0,1,0), "stone"), action.command());
		known.put(goal.offset(0,1,0), air());
		var repaired = assertInstanceOf(TerrainAccess.Action.class, TerrainAccess.advance(action.state(), world(known, start, Set.of()), Optional.of(Outcome.success("broken")), 2, CLEARABLE, p -> true));
		assertEquals(goal, assertInstanceOf(Navigate.class, repaired.command()).stance());
	}
	@Test void aDistantDestinationUsesLocalPreparationWithoutResettingTheWorkBudget() {
		Pos start = new Pos(0,4,0), goal = new Pos(0,4,12);
		var known = new HashMap<Pos,Seen>();
		for (int z=0; z<=12; z++) {
			known.put(new Pos(0,3,z), stone()); known.put(new Pos(0,4,z), air()); known.put(new Pos(0,5,z), air());
		}
		known.put(new Pos(0,5,3), stone());
		var commands = run(known, start, goal);
		assertTrue(commands.contains(new Break(new Pos(0,5,3), "stone")));
		assertEquals(12, commands.stream().filter(Navigate.class::isInstance).count());
		var expired = TerrainAccess.advance(TerrainAccess.State.begin(start, goal, 0), world(known, new Pos(0,4,6), Set.of()), Optional.empty(), 1000, CLEARABLE, p -> true);
		assertEquals(new TerrainAccess.Unavailable("access_budget_exhausted"), expired);
	}
	@Test void aDistantUphillGoalDoesNotSwitchFrontiersAfterEverySuccessfulStep() {
		// Reduced from v48 original ticks 15140-15354: two cheap approaches to an
		// uphill frontier. Moving one block makes the other frontier enter the horizon.
		var known = new HashMap<Pos,Seen>();
		for (int x=0; x<=10; x++) {
			int floor = x == 0 || x == 10 ? 4 : 3;
			known.put(new Pos(x,floor,0),stone());
			for (int y=floor+1;y<=6;y++) known.put(new Pos(x,y,0),air());
		}
		Pos feet = new Pos(5,4,0), goal = new Pos(5,53,37);
		var state = TerrainAccess.State.begin(feet,goal,0);
		for (int tick=1;tick<=5;tick++) {
			var action = assertInstanceOf(TerrainAccess.Action.class,TerrainAccess.advance(state,world(known,feet,Set.of()),
				tick == 1 ? Optional.empty() : Optional.of(Outcome.success("stance_reached")),tick,CLEARABLE,p->true));
			feet = assertInstanceOf(Navigate.class,action.command()).stance(); state = action.state();
			assertEquals(goal,state.goal()); assertEquals(1000,state.deadline()); assertEquals(tick,state.work());
		}
		assertEquals(5,feet.y(),"reach the selected uphill frontier instead of alternating between two floor blocks");
	}
	@Test void aRetainedRouteRechecksNewHazardsAndRejectsFailedEdges() {
		var known = new HashMap<Pos,Seen>();
		for (int z=0;z<=5;z++) {
			known.put(new Pos(0,3,z),stone()); known.put(new Pos(0,4,z),air()); known.put(new Pos(0,5,z),air());
		}
		Pos start = new Pos(0,4,0), goal = new Pos(0,4,5);
		var first = assertInstanceOf(TerrainAccess.Action.class,TerrainAccess.advance(TerrainAccess.State.begin(start,goal,0),
			world(known,start,Set.of()),Optional.empty(),1,CLEARABLE,p->true));
		Pos arrived = assertInstanceOf(Navigate.class,first.command()).stance();
		Pos dangerous = arrived.offset(0,0,1);
		var changed = TerrainAccess.advance(first.state(),world(known,arrived,Set.of()),Optional.of(Outcome.success("stance_reached")),2,
			CLEARABLE,p->!p.equals(dangerous));
		assertFalse(changed instanceof TerrainAccess.Action a && a.command() instanceof Navigate n && n.stance().equals(dangerous));
		var failed = TerrainAccess.advance(first.state(),world(known,start,Set.of()),Optional.of(Outcome.failure("observed_route_unavailable")),2,CLEARABLE,p->true);
		assertFalse(failed instanceof TerrainAccess.Action a && a.command().equals(first.command()),"failed route edges cannot be repeated unchanged");
	}
	@Test void inspectingHiddenSupportCanClearOnlyAnAuthorizedObservedObstruction() {
		Pos start = new Pos(0,4,0), next = new Pos(0,4,1), floor = next.offset(0,-1,0);
		var known = new HashMap<Pos,Seen>();
		known.put(start,air()); known.put(start.offset(0,1,0),air()); known.put(start.offset(0,-1,0),stone());
		known.put(next.offset(0,1,0),air()); known.put(next,new Seen("litter",false,true,false,15,1,true));
		var world = world(known,start,Set.of());
		var action = assertInstanceOf(TerrainAccess.Action.class,TerrainAccess.advance(TerrainAccess.State.begin(start,next,0),world,Optional.empty(),1,Set.of("litter"),p->true));
		assertEquals(new Break(next,"litter"),action.command());
		assertInstanceOf(Look.class,TerrainAccess.inspect(world,floor,Set.of(),p->true));
		assertInstanceOf(Look.class,TerrainAccess.inspect(world,floor,Set.of("litter"),p->!p.equals(next)));
		assertInstanceOf(Look.class,TerrainAccess.inspect(world(known,start,Set.of(next)),floor,Set.of("litter"),p->true));
		known.put(next,new Seen("unknown",false,false,false,0,2));
		assertInstanceOf(Look.class,TerrainAccess.inspect(world(known,start,Set.of()),floor,Set.of("unknown"),p->true));
	}
	@Test void aDescentOvershootRecoversItsLandingWithoutLosingTheRemainingStaircase() {
		Pos start = new Pos(0,4,0), landing = new Pos(0,3,1), next = new Pos(1,2,1), goal = new Pos(2,1,1);
		var known = new HashMap<Pos,Seen>();
		for (int y=3;y<=5;y++) known.put(new Pos(0,y,1),air());
		known.put(landing.offset(0,-1,0),stone());
		Pos overshoot = new Pos(0,3,2);
		var move = new Navigate(landing,12,100);
		var tail = List.of(new TerrainAccess.Edge(landing,next),new TerrainAccess.Edge(next,goal));
		var route = new ArrayList<TerrainAccess.Edge>();route.add(new TerrainAccess.Edge(start,landing));route.addAll(tail);
		var state = new TerrainAccess.State(start,goal,1000,4,Set.of(),route,move,Optional.empty());
		var world = new StoneAcquisition.World(new Pose(.5,4.62,2.21,0,0),overshoot,Map.of(),known,Set.of(landing.offset(0,-1,0)));
		var failed = Optional.of(Outcome.failure("observed_route_unavailable"));
		var recovery = assertInstanceOf(TerrainAccess.Action.class,TerrainAccess.advance(state,world,failed,30,CLEARABLE,p->true));
		assertEquals(move,recovery.command()); assertEquals(1000,recovery.state().deadline());assertEquals(5,recovery.state().work());
		assertEquals(tail,recovery.state().route().subList(1,recovery.state().route().size()));
		var unchanged = TerrainAccess.advance(recovery.state(),world,failed,31,CLEARABLE,p->true);
		assertFalse(unchanged instanceof TerrainAccess.Action action && action.command().equals(move),"an unchanged failure cannot repeat the landing recovery");
		var hazardous = TerrainAccess.advance(state,world,failed,30,CLEARABLE,p->!p.equals(landing));
		assertFalse(hazardous instanceof TerrainAccess.Action action && action.command().equals(move),"new hazards invalidate recovery");
	}
	@Test void unknownFootingEndsTheProposalUntilItsSupportIsObserved() {
		Pos start = new Pos(0,4,0), next = new Pos(0,3,1), goal = new Pos(0,1,4);
		var known = new HashMap<Pos,Seen>();
		known.put(start,air());known.put(start.offset(0,1,0),air());known.put(start.offset(0,-1,0),stone());
		known.put(next,stone());known.put(next.offset(0,1,0),air());known.put(next.offset(0,2,0),air());
		var state = TerrainAccess.State.begin(start,goal,0);
		var first = assertInstanceOf(TerrainAccess.Action.class,TerrainAccess.advance(state,world(known,start,Set.of()),Optional.empty(),1,CLEARABLE,p->true));
		assertEquals(new Break(next,"stone"),first.command());
		assertEquals(List.of(new TerrainAccess.Edge(start,next)),first.state().route(),"no later step can rely on hypothetical support");
		known.put(next,air());
		var inspect = assertInstanceOf(TerrainAccess.Action.class,TerrainAccess.advance(first.state(),world(known,start,Set.of()),Optional.of(Outcome.success("broken")),2,CLEARABLE,p->true));
		assertInstanceOf(Look.class,inspect.command(),"clearing a surface does not prove support below it");
		known.put(next.offset(0,-1,0),stone());
		var enter = assertInstanceOf(TerrainAccess.Action.class,TerrainAccess.advance(inspect.state(),world(known,start,Set.of()),Optional.of(Outcome.success("observed")),3,CLEARABLE,p->true));
		assertEquals(next,assertInstanceOf(Navigate.class,enter.command()).stance());
	}
	@Test void anObservedTrunkCanProvideOneBlockStepsWithoutDestroyingReturnFooting() {
		var known = solid();
		known.replaceAll((p,seen) -> p.y() <= 0 ? stone() : air());
		for (int x=0;x<=1;x++) for (int z=0;z<=1;z++) for (int y=1;y<=5;y++)
			known.put(new Pos(x,y,z),new Seen("log",false,true,true,15,1));
		Pos start = new Pos(0,6,0), goal = new Pos(2,1,0);
		assertInstanceOf(TerrainAccess.Unavailable.class,TerrainAccess.advance(TerrainAccess.State.begin(start,goal,0),
			world(known,start,Set.of(start.offset(0,-1,0))),Optional.empty(),1,CLEARABLE,p->true));
		var commands = run(known,start,goal,Set.of("stone","log"));
		assertTrue(commands.stream().filter(Break.class::isInstance).map(Break.class::cast).anyMatch(b -> b.expectedBlock().equals("log")));
	}
	private static List<VoxelCommand> run(Map<Pos, Seen> known, Pos start, Pos goal) {
		return run(known,start,goal,CLEARABLE);
	}
	private static List<VoxelCommand> run(Map<Pos, Seen> known, Pos start, Pos goal, Set<String> clearable) {
		var state = TerrainAccess.State.begin(start, goal, 0); Pos feet = start;
		var protectedFloor = new HashSet<Pos>(); protectedFloor.add(start.offset(0, -1, 0));
		var commands = new ArrayList<VoxelCommand>(); Optional<Outcome> feedback = Optional.empty();
		for (int tick = 1; tick < 50; tick++) {
			var world = world(known, feet, protectedFloor);
			var result = TerrainAccess.advance(state, world, feedback, tick, clearable, p -> true);
			if (result instanceof TerrainAccess.Arrived) { assertEquals(goal, feet); return commands; }
			var action = assertInstanceOf(TerrainAccess.Action.class, result, "feet=" + feet + " commands=" + commands + " result=" + result); var command = action.command();
			if (command instanceof Break broken) {
				assertFalse(protectedFloor.contains(broken.target()));
				assertTrue(ObservedReach.visible(known, world.eye(), broken.target(), 4.3));
				assertEquals(broken.expectedBlock(), known.get(broken.target()).blockId()); known.put(broken.target(), air());
			} else if (command instanceof Navigate move) {
				assertTrue(Math.abs(feet.y() - move.stance().y()) <= 1);
				assertTrue(StoneAcquisition.standable(known, move.stance())); feet = move.stance(); protectedFloor.add(feet.offset(0, -1, 0));
			} else fail("fully observed test geometry should not require another look: " + command);
			commands.add(command); state = action.state(); feedback = Optional.of(Outcome.success("effect_observed"));
		}
		throw new AssertionError("access did not finish within its work bound");
	}
	private static HashMap<Pos, Seen> solid() {
		var result = new HashMap<Pos, Seen>();
		for (int x=-6;x<=6;x++) for(int z=-6;z<=6;z++) for(int y=-2;y<=10;y++) result.put(new Pos(x,y,z),stone());
		return result;
	}
	private static Seen stone() { return new Seen("stone",false,true,true,15,1); }
	private static Seen air() { return new Seen("air",true,true,false,15,1); }
	private static StoneAcquisition.World world(Map<Pos,Seen> known, Pos feet, Set<Pos> protectedFloor) {
		return new StoneAcquisition.World(new Pose(feet.x()+.5,feet.y()+1.62,feet.z()+.5,0,0),feet,Map.of(),known,protectedFloor);
	}
}
