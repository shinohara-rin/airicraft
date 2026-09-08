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

class ToolMaintenanceTest {
	private static final Pos FEET = new Pos(0, 1, 0);
	private static final SearchPrior PRIOR = new SearchPrior("ore", 0, 16, 20, List.of("stone"));
	private static final ProductionKnowledge BOOK = new ProductionKnowledge("test",
		List.of(new Recipe("pick", "pick", 1, 2, List.of(new Cell(0, "material")))),
		List.of(new Harvest("ore", List.of("ore_block"), List.of("pick", "other_pick"), Technique.EXPOSED)),
		List.of(), List.of(), List.of(PRIOR), new LightingPolicy.Parameters(7, 10, 8, 80, 4));

	@Test void missingToolReleasesThenRepairsAndReturnsToTheSameSearchWithCommitments() {
		var domain = new ProductionDomain(BOOK);
		var kernel = new TaskKernel<Task, StoneAcquisition.World, VoxelCommand>(domain, new Limits(16, 16, 1000, 50));
		var initialWorld = world(Map.of("pick", 1, "material", 3), FEET);
		var search = new Explore(UndergroundSearch.Task.begin(PRIOR, List.of("ore_block"), initialWorld, Set.of()),
			LightingPolicy.State.begin(), Map.of("material", 2), Set.of("ore"));
		var first = kernel.advance(kernel.begin("s", "r", search, 0), initialWorld, List.of(), 1);
		var original = start(first);
		assertInstanceOf(Navigate.class, original.command());
		var stopped = kernel.advance(first.state(), world(Map.of("material", 3), FEET), List.of(), 2);
		assertEquals(List.of(new Stop<VoxelCommand>(original.token())), stopped.effects());
		var repair = kernel.advance(stopped.state(), world(Map.of("material", 3), FEET), List.of(new Released(original.token())), 3);
		var craft = start(repair);
		assertInstanceOf(Craft.class, craft.command());
		var saved = (ResumeExplore) repair.state().stack().getFirst().task();
		assertEquals(((Navigate) original.command()).stance(), saved.saved().search().destination().orElseThrow());
		assertEquals(Map.of("material", 2), ((Acquire) repair.state().stack().getLast().task()).reserved());
		var returnStep = kernel.advance(repair.state(), world(Map.of("pick", 1, "material", 2), FEET.offset(2, 0, 0)),
			List.of(new Finished(craft.token(), Outcome.success("crafted"))), 4);
		assertEquals(new Navigate(FEET, 48, 400), start(returnStep).command());
		var resumed = kernel.advance(returnStep.state(), world(Map.of("pick", 1, "material", 2), FEET),
			List.of(new Finished(start(returnStep).token(), Outcome.success("arrived"))), 5);
		assertEquals(original.command(), start(resumed).command());
		assertEquals(original.token().task(), start(resumed).token().task());
		assertEquals(1, resumed.state().stack().size());
	}

	@Test void replacementReturnsForMissingMaterialsBeforeResumingItsRetainedSearch() {
		var domain=new ProductionDomain(BOOK); var feet=FEET.offset(2,0,0);
		var world=world(Map.of("material",2),feet);
		var route=List.of(FEET,FEET.offset(1,0,0),feet);
		var pending=feet.offset(0,0,1);
		var search=new UndergroundSearch.Task(PRIOR,List.of("ore_block"),FEET,feet,0,2,Map.of(),Set.of(),Optional.of(pending),Optional.empty(),null,route);
		var task=new Explore(search,LightingPolicy.State.begin(),Map.of("material",2),Set.of("ore"));
		var repair=assertInstanceOf(Child.class,domain.decide(view(task,Optional.empty()),world));
		var outward=assertInstanceOf(Resupply.class,repair.child());
		assertEquals(Map.of("material",2),outward.supply().reserved());
		assertEquals(List.of(feet,FEET.offset(1,0,0),FEET),outward.outward().route());
		var saved=assertInstanceOf(ResumeExplore.class,repair.continuation());
		assertEquals(search,saved.saved().search()); assertEquals(route,saved.returning().route());
		var travel=assertInstanceOf(Execute.class,domain.decide(view(outward,Optional.empty()),world));
		assertEquals(new Navigate(FEET,48,400),travel.command());
		var local=assertInstanceOf(Child.class,domain.decide(view(task,Optional.empty()),world(Map.of("material",3),feet)));
		assertInstanceOf(Acquire.class,local.child(),"unreserved local ingredients should avoid a supply trip");
	}

	@Test void failedReplacementTriesAnotherDeclaredToolThenTerminatesWithoutRecursiveRepair() {
		var domain = new ProductionDomain(BOOK);
		var world = world(Map.of(), FEET);
		var search = new Explore(UndergroundSearch.Task.begin(PRIOR, List.of("ore_block"), world, Set.of()),
			LightingPolicy.State.begin(), Map.of(), Set.of("ore"));
		var first = (Child<Task, VoxelCommand>) domain.decide(view(search, Optional.empty()), world);
		assertEquals("pick", ((Acquire) first.child()).item());
		var second = (Child<Task, VoxelCommand>) domain.decide(view(first.continuation(), Optional.of(Outcome.failure("unavailable"))), world);
		assertEquals("other_pick", ((Acquire) second.child()).item());
		var done = assertInstanceOf(Complete.class, domain.decide(view(second.continuation(), Optional.of(Outcome.failure("unavailable"))), world));
		assertEquals("search_tool_replacement_exhausted:ore", done.outcome().evidence());
		var cyclic = new Explore(search.search(), search.light(), Map.of(), Set.of("ore", "pick", "other_pick"));
		assertInstanceOf(Complete.class, domain.decide(view(cyclic, Optional.empty()), world));
	}

	private static View<Task> view(Task task, Optional<Outcome> child) { return new View<>(1, task, false, 1, Optional.empty(), child); }
	private static Start<VoxelCommand> start(Step<Task, VoxelCommand> step) { return (Start<VoxelCommand>) step.effects().getFirst(); }
	private static StoneAcquisition.World world(Map<String, Integer> inventory, Pos feet) {
		var known = new HashMap<Pos, Seen>();
		for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) for (int y = 0; y <= 3; y++) {
			known.put(new Pos(x, y, z), new Seen(y == 0 ? "stone" : "minecraft:air", y != 0, true, y == 0, 15, 1));
		}
		return new StoneAcquisition.World(new Pose(feet.x() + .5, feet.y() + 1.62, feet.z() + .5, 0, 45), feet, inventory, known);
	}
}
