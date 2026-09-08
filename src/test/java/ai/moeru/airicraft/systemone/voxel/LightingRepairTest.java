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
		Task parent = new Explore(search,Map.of(),Set.of("ore"));
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
