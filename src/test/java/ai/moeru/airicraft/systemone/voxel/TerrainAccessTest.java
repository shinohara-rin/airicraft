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
	@Test void aFailedApproachToObservedUnminedOreCanPrepareAccessButAnUnknownTargetCannot() {
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
		var unknown = domain.decide(failed, world(known, start, Set.of()));
		assertFalse(unknown instanceof Child<?, ?> child && child.child() instanceof Access);
	}
	private static List<VoxelCommand> run(Map<Pos, Seen> known, Pos start, Pos goal) {
		var state = TerrainAccess.State.begin(start, goal, 0); Pos feet = start;
		var protectedFloor = new HashSet<Pos>(); protectedFloor.add(start.offset(0, -1, 0));
		var commands = new ArrayList<VoxelCommand>(); Optional<Outcome> feedback = Optional.empty();
		for (int tick = 1; tick < 50; tick++) {
			var world = world(known, feet, protectedFloor);
			var result = TerrainAccess.advance(state, world, feedback, tick, CLEARABLE, p -> true);
			if (result instanceof TerrainAccess.Arrived) { assertEquals(goal, feet); return commands; }
			var action = assertInstanceOf(TerrainAccess.Action.class, result); var command = action.command();
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
