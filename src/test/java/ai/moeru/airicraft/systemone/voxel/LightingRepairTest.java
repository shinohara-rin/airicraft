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
		var resume = new ResumeExplore(saved, FEET, LightingPolicy.Repair.SUPPLY, Set.of(), Optional.empty());
		var result = new ProductionDomain(BOOK).decide(new View<Task>(2, resume, false, 10, Optional.empty(), Optional.of(Outcome.success("supplied"))), world(15, Map.of("pick", 1, "minecraft:torch", 8), FEET.offset(2, 0, 0)));
		assertEquals(new Navigate(FEET, 48, 400), assertInstanceOf(Execute.class, result).command());
	}
	private static Start<VoxelCommand> start(Step<Task, VoxelCommand> step) { return (Start<VoxelCommand>) step.effects().getFirst(); }
	private static StoneAcquisition.World world(int light, Map<String, Integer> inventory, Pos feet) {
		var known = new HashMap<Pos, Seen>();
		for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) for (int y = 0; y <= 3; y++) known.put(new Pos(x, y, z), new Seen(y == 0 ? "minecraft:stone" : "minecraft:air", y != 0, true, light, 1));
		return new StoneAcquisition.World(new Pose(feet.x() + .5, feet.y() + 1.62, feet.z() + .5, 0, 45), feet, inventory, known);
	}
}
