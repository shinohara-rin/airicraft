package ai.moeru.airicraft.systemone.voxel;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import ai.moeru.airicraft.systemone.TaskKernel;
import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionDomain.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionKnowledge.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelCommand.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;

class LightingRepairTest {
	private static final Pos FEET = new Pos(0, 1, 0);
	private static final SearchPrior PRIOR = new SearchPrior("ore", 0, 16, 20, List.of("minecraft:stone"));
	private static final Recipe TORCH = new Recipe("torch", "minecraft:torch", 4, 2, List.of(new Cell(0, "coal"), new Cell(2, "stick")));
	private static final ProductionKnowledge BOOK = new ProductionKnowledge("test", List.of(TORCH),
		List.of(new Harvest("ore", List.of("ore_block"), List.of("pick"), Technique.EXPOSED)), List.of(), List.of(), List.of(PRIOR), new LightingPolicy.Parameters(7, 10, 8, 80, 4));
	@Test void supplyAndPlacementSuspendThenResumeTheSameExplorationDestination() {
		var kernel = new TaskKernel<Task, StoneAcquisition.World, VoxelCommand>(new ProductionDomain(BOOK), new Limits(16, 16, 1000, 50));
		var stock = Map.of("pick", 1, "coal", 2, "stick", 2);
		var initial = kernel.advance(kernel.begin("s", "r", Acquire.root("ore", 1), 0), world(15, stock, FEET), List.of(), 1);
		var travel = start(initial); assertInstanceOf(Navigate.class, travel.command());
		var interruption = kernel.advance(initial.state(), world(4, stock, FEET), List.of(), 2);
		assertEquals(List.of(new Stop<VoxelCommand>(travel.token())), interruption.effects());
		assertEquals(2, interruption.state().stack().size());
		var supply = kernel.advance(interruption.state(), world(4, stock, FEET), List.of(new Released(travel.token())), 3);
		assertInstanceOf(Craft.class, start(supply).command());
		var retained = (ResumeExplore) supply.state().stack().get(1).task();
		assertEquals(((Navigate) travel.command()).stance(), retained.saved().search().destination().orElseThrow());
		var second = kernel.advance(supply.state(), world(4, Map.of("pick", 1, "coal", 1, "stick", 1, "minecraft:torch", 4), FEET), List.of(new Finished(start(supply).token(), Outcome.success("crafted"))), 4);
		assertInstanceOf(Craft.class, start(second).command());
		var placement = kernel.advance(second.state(), world(4, Map.of("pick", 1, "minecraft:torch", 8), FEET), List.of(new Finished(start(second).token(), Outcome.success("crafted"))), 5);
		assertEquals("minecraft:torch", assertInstanceOf(Place.class, start(placement).command()).item());
		var resumed = kernel.advance(placement.state(), world(10, Map.of("pick", 1, "minecraft:torch", 7), FEET), List.of(new Finished(start(placement).token(), Outcome.success("placed"))), 6);
		assertEquals(travel.command(), start(resumed).command());
		assertEquals(travel.token().task(), start(resumed).token().task());
		assertEquals(2, resumed.state().stack().size());
	}
	@Test void resupplyAwayFromTheCaveReturnsBeforeContinuingSearch() {
		var origin = world(15, Map.of("pick", 1), FEET);
		var search = UndergroundSearch.Task.begin(PRIOR, List.of("ore_block"), origin, Set.of());
		var saved = new Explore(search, LightingPolicy.State.begin(), Map.of(), Set.of("ore"));
		var resume = new ResumeExplore(saved, ReturnNavigation.State.begin(List.of(FEET)), new LightRepair(LightingPolicy.Repair.SUPPLY));
		var result = new ProductionDomain(BOOK).decide(new View<Task>(2, resume, false, 10, Optional.empty(), Optional.of(Outcome.success("supplied"))), world(15, Map.of("pick", 1, "minecraft:torch", 8), FEET.offset(2, 0, 0)));
		assertEquals(new Navigate(FEET, 48, 400), assertInstanceOf(Execute.class, result).command());
	}
	@Test void missingLightingInputsUseTheRememberedExitBeforeStartingResourceDiscovery() {
		var domain = new ProductionDomain(BOOK);
		var entrance = FEET.offset(0, 0, -2);
		var cave = world(4, Map.of("pick", 1), FEET);
		var search = new UndergroundSearch.Task(PRIOR, List.of("ore_block"), entrance, FEET, 0, 2,
			Map.of(), Set.of(), Optional.empty(), Optional.empty(), null, List.of(entrance, FEET));
		var explore = new Explore(search, LightingPolicy.State.begin(), Map.of(), Set.of("ore"));
		var repair = assertInstanceOf(Child.class, domain.decide(new View<Task>(2, explore, false, 10, Optional.empty(), Optional.empty()), cave));
		var outbound = assertInstanceOf(Execute.class, domain.decide(new View<Task>(3, (Task)repair.child(), false, 11, Optional.empty(), Optional.empty()), cave));
		assertEquals(new Navigate(entrance, 48, 400), outbound.command());
		var resumed = assertInstanceOf(ResumeExplore.class, repair.continuation());
		assertEquals(List.of(entrance, FEET), resumed.returning().route());
		assertEquals(search, resumed.saved().search());
		var atEntrance = domain.decide(new View<Task>(3, (Task)outbound.continuation(), false, 12, Optional.of(Outcome.success("arrived")), Optional.empty()), world(15, Map.of("pick", 1), entrance));
		assertInstanceOf(Child.class, atEntrance, "only at the entrance should ingredient acquisition begin");
	}
	@Test void firstDarkTickStartsOneAllowanceEvenWhileMotorFeedbackIsDelayed() {
		var kernel = new TaskKernel<Task, StoneAcquisition.World, VoxelCommand>(new ProductionDomain(BOOK), new Limits(16, 16, 1000, 50));
		var bright = world(15, Map.of("pick", 1), FEET);
		var task = new Explore(UndergroundSearch.Task.begin(PRIOR, List.of("ore_block"), bright, Set.of()),
			LightingPolicy.State.begin(), Map.of(), Set.of("ore", "minecraft:torch"));
		var first = kernel.advance(kernel.begin("s", "r", task, 0), bright, List.of(), 1);
		var moving = start(first); assertInstanceOf(Navigate.class, moving.command());
		var dark = world(4, Map.of("pick", 1), FEET);
		var state = first.state();
		for (int tick = 2; tick < 82; tick++) {
			var waiting = kernel.advance(state, dark, List.of(), tick);
			assertEquals(82, ProductionTape.lightingTrace(state, waiting.state(), dark).after().getFirst().allowance().expiresAt());
			assertTrue(waiting.effects().isEmpty()); state = waiting.state();
		}
		var expired = kernel.advance(state, dark, List.of(), 82);
		assertEquals(List.of(new Stop<VoxelCommand>(moving.token())), expired.effects(), "the pending movement cannot restart its darkness deadline every tick");
		assertEquals(first.state().nextAttempt(), expired.state().nextAttempt());
	}
	@Test void observedHealthLossRevokesDarkWorkBeforeItsTimeBudgetAndWaitsForRelease() {
		var kernel = new TaskKernel<Task, StoneAcquisition.World, VoxelCommand>(new ProductionDomain(BOOK), new Limits(16, 16, 1000, 50));
		var dim = world(4, Map.of("pick", 1), FEET);
		var task = new Explore(UndergroundSearch.Task.begin(PRIOR, List.of("ore_block"), dim, Set.of()),
			LightingPolicy.State.begin(), Map.of(), Set.of("ore", "minecraft:torch"));
		var first = kernel.advance(kernel.begin("s", "r", task, 0), dim, List.of(), 1);
		var moving = start(first);
		var injured = new StoneAcquisition.World(dim.eye(), dim.feet(), dim.inventory(), dim.known(), dim.footholds(), new SurvivalPolicy.Vitals(0, 18, false, false));
		var revoked = kernel.advance(first.state(), injured, List.of(), 2);
		assertEquals(List.of(new Stop<VoxelCommand>(moving.token())), revoked.effects());
		assertTrue(revoked.state().stack().getLast().phase() instanceof Releasing);
		assertEquals(first.state().nextAttempt(), revoked.state().nextAttempt());
		var stopped = kernel.advance(revoked.state(), injured, List.of(new Released(moving.token())), 3);
		assertTrue(stopped.effects().isEmpty(), "at the route origin, retreat requires no new movement");
		assertTrue(stopped.state().outcome().orElseThrow().evidence().contains("lighting_allowance_revoked:health_loss"));
	}
	@Test void aNarrowPassageUsesAnObservedWallAboveItsProtectedRoute() {
		var base = world(4, Map.of("minecraft:torch", 8), FEET);
		var known = new HashMap<>(base.known());
		var wall = new Pos(1, 3, 0);
		known.put(wall, new Seen("minecraft:stone", false, true, true, 4, 1));
		var route = new HashSet<Pos>();
		for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) route.add(new Pos(x, 0, z));
		var observed = new StoneAcquisition.World(base.eye(), base.feet(), base.inventory(), known, route);
		var domain = new ProductionDomain(BOOK);
		var task = new PlaceLight(Set.of(), Optional.empty());
		var result = domain.decide(new View<Task>(1, task, false, 1, Optional.empty(), Optional.empty()), observed);
		var command = assertInstanceOf(Place.class, assertInstanceOf(Execute.class, result).command());
		assertEquals(new Place("minecraft:torch", wall, "minecraft:stone", Face.WEST, "minecraft:wall_torch"), command);
		assertEquals(new Pos(0, 3, 0), command.destination());
		var unusedFloor = new StoneAcquisition.World(base.eye(), base.feet(), base.inventory(), known, Set.of(FEET.offset(0, -1, 0)));
		var preferred = domain.decide(new View<Task>(1, task, false, 1, Optional.empty(), Optional.empty()), unusedFloor);
		assertEquals(command, assertInstanceOf(Execute.class, preferred).command(), "an unused floor may still be the intended passage");
		known.put(wall, new Seen("unknown", false, false, false, 0, 2));
		var uncertain = new StoneAcquisition.World(base.eye(), base.feet(), base.inventory(), known, route);
		var search = new UndergroundSearch.Task(PRIOR,List.of("ore_block"),FEET,FEET,0,0,Map.of(),Set.of(),Optional.empty(),Optional.empty(),null,route.stream().map(p->p.offset(0,1,0)).toList());
		Task parent = new Explore(search,LightingPolicy.State.begin(),Map.of(),Set.of("ore"));
		var branch = List.of(new View<>(2,parent,false,2,Optional.<Outcome>empty(),Optional.<Outcome>empty()),new View<Task>(1,task,false,2,Optional.empty(),Optional.empty()));
		assertEquals(ResultKind.FAILED, assertInstanceOf(Complete.class, domain.decide(branch, uncertain)).outcome().kind());
	}
	private static Start<VoxelCommand> start(Step<Task, VoxelCommand> step) { return (Start<VoxelCommand>) step.effects().getFirst(); }
	@Test void twoBlockHighTunnelCanBeLitWithoutDestroyingItsWalkingRoute() {
		var base = world(4, Map.of("minecraft:torch", 8), FEET);
		var known = new HashMap<Pos, Seen>();
		var route = new HashSet<Pos>();
		for (int z = -2; z <= 3; z++) {
			route.add(new Pos(0, 0, z));
			for (int x = -1; x <= 1; x++) for (int y = 0; y <= 3; y++) {
				boolean air = x == 0 && y > 0 && y < 3;
				known.put(new Pos(x, y, z), new Seen(air ? "minecraft:air" : "minecraft:stone", air, true, !air, 4, 1));
			}
		}
		var observed = new StoneAcquisition.World(base.eye(), FEET, base.inventory(), known, route);
		var task = new PlaceLight(Set.of(), Optional.empty());
		var result = new ProductionDomain(BOOK).decide(new View<Task>(1, task, false, 1, Optional.empty(), Optional.empty()), observed);
		var place = assertInstanceOf(Place.class, assertInstanceOf(Execute.class, result).command());
		assertEquals("minecraft:wall_torch", place.expectedPlacedBlock());
		known.put(place.destination(), new Seen("minecraft:wall_torch", false, true, false, 14, 2, true));
		Pos standing = new Pos(place.destination().x(), 1, place.destination().z());
		assertTrue(StoneAcquisition.standable(known, standing));
		known.put(place.destination(), new Seen("minecraft:stone", false, true, true, 14, 3));
		assertFalse(StoneAcquisition.standable(known, standing));
	}
	private static StoneAcquisition.World world(int light, Map<String, Integer> inventory, Pos feet) {
		var known = new HashMap<Pos, Seen>();
		for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) for (int y = 0; y <= 3; y++) known.put(new Pos(x, y, z), new Seen(y == 0 ? "minecraft:stone" : "minecraft:air", y != 0, true, y == 0, light, 1));
		return new StoneAcquisition.World(new Pose(feet.x() + .5, feet.y() + 1.62, feet.z() + .5, 0, 45), feet, inventory, known);
	}
}
