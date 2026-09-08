package ai.moeru.airicraft.systemone.voxel;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionKnowledge.*;

class SearchAreasTest {
	private static final Pos ORIGIN = new Pos(-6,69,18), PREVIOUS = new Pos(-33,16,76), FEET = new Pos(-33,16,75);
	private static final SearchPrior PRIOR = new SearchPrior("minecraft:raw_iron",16,64,96,3,List.of("minecraft:stone"),List.of());
	private final UndergroundSearch search = new UndergroundSearch();

	@Test void anExhaustedAreaCanContinueFromAnObservedNewStanceWithItsReturnRouteIntact() {
		var task = arrived(List.of(ORIGIN));
		var next = assertInstanceOf(Keep.class,search.decide(view(task),world()));
		var continued = (UndergroundSearch.Task)next.continuation().orElseThrow();
		assertEquals(List.of(ORIGIN,FEET),continued.areas());
		assertEquals(FEET,continued.origin());
		assertEquals(0,continued.steps());
		assertEquals(List.of(ORIGIN,PREVIOUS,FEET),continued.route());
		var action = assertInstanceOf(Execute.class,search.decide(view(continued),world()));
		assertInstanceOf(VoxelCommand.Break.class,action.command());
		assertEquals(continued.areas(),((UndergroundSearch.Task)action.continuation()).areas());
	}

	@Test void anAreaAllowanceCannotBeResetByCirclingOrByFailedMovement() {
		var visited = arrived(List.of(ORIGIN,FEET.offset(5,0,0)));
		assertInstanceOf(Complete.class,search.decide(view(visited),world()));
		var exhausted = arrived(List.of(ORIGIN,ORIGIN.offset(80,0,0),ORIGIN.offset(160,0,0)));
		assertInstanceOf(Complete.class,search.decide(view(exhausted),world()));
		var pending = arrived(List.of(ORIGIN));
		var unreached = new UndergroundSearch.Task(pending.prior(),pending.targets(),pending.origin(),pending.position(),pending.direction(),pending.steps(),pending.rejected(),pending.ignoredTargets(),Optional.of(FEET.offset(0,0,1)),pending.lookedAt(),new VoxelCommand.Navigate(FEET.offset(0,0,1),12,200),pending.route(),pending.preparation(),pending.areas());
		var failed = new View<>(1,unreached,false,2,Optional.of(Outcome.failure("blocked")),Optional.<Outcome>empty());
		assertInstanceOf(Complete.class,search.decide(failed,world()));
		var known = new HashMap<>(world().known()); known.remove(FEET.offset(0,-1,0));
		var unsupported = new StoneAcquisition.World(world().eye(),FEET,world().inventory(),known);
		assertInstanceOf(Complete.class,search.decide(view(arrived(List.of(ORIGIN))),unsupported));
	}

	@Test void productionPreservesAnAreaTransitionAndCarriesItIntoLightingRepair() {
		var knowledge = new ProductionKnowledge("test",List.of(),List.of(new Harvest(PRIOR.item(),List.of("minecraft:iron_ore"),List.of("minecraft:stone_pickaxe"),Technique.EXPOSED)),
			List.of(),List.of(),List.of(PRIOR),new LightingPolicy.Parameters(7,10,8,80,4));
		var domain = new ProductionDomain(knowledge);
		var explore = new ProductionDomain.Explore(arrived(List.of(ORIGIN)),Map.of(),Set.of(PRIOR.item()));
		var keep = assertInstanceOf(Keep.class,domain.decide(new View<ProductionDomain.Task>(1,explore,false,2,Optional.of(Outcome.success("arrived")),Optional.empty()),world()));
		var continued = assertInstanceOf(ProductionDomain.Explore.class,keep.continuation().orElseThrow());
		assertEquals(List.of(ORIGIN,FEET),continued.search().areas());
		var dim = new HashMap<>(world().known());
		dim.replaceAll((p,s) -> new Seen(s.blockId(),s.empty(),s.identified(),s.fullSupport(),0,3,s.clearForBody()));
		var dark = new StoneAcquisition.World(world().eye(),FEET,Map.of("minecraft:stone_pickaxe",1),dim);
		var mission = new ProductionDomain.Mission(PRIOR.item(),1,0,0,new ProductionDomain.WorkingLight(LightingPolicy.State.begin(),continued.search().route()));
		var branch = List.of(new View<ProductionDomain.Task>(1,mission,false,3,Optional.empty(),Optional.empty()),new View<ProductionDomain.Task>(2,continued,false,3,Optional.empty(),Optional.empty()));
		var observed=domain.observe(branch,dark);
		var repair=domain.interrupt(List.of(new View<>(1,observed.getFirst(),false,3,Optional.empty(),Optional.empty()),branch.getLast()),dark).orElseThrow();
		var suspended = assertInstanceOf(ProductionDomain.ResumeWork.class,repair.continuation());
		assertEquals(continued,suspended.saved().task());
		assertEquals(continued.search().route(),suspended.returning().route());
	}

	@Test void resumptionCanUseObservedArrivalAfterARepairConsumedTheMovementReceipt() {
		var task = arrived(List.of(ORIGIN));
		var resumed = new View<>(1,task,false,2,Optional.<Outcome>empty(),Optional.<Outcome>empty());
		var next = assertInstanceOf(Keep.class,search.decide(resumed,world()));
		assertEquals(List.of(ORIGIN,FEET),((UndergroundSearch.Task)next.continuation().orElseThrow()).areas());
	}

	@Test void radiusExhaustionBeforeTheStepLimitAdvancesOnlyWithinTheFiniteAreaAllowance() {
		var origin = new Pos(0,69,0); var feet = new Pos(0,16,64); var previous = feet.offset(0,0,-1);
		var known = new HashMap<Pos,Seen>();
		for (Pos stance : List.of(feet,previous)) {
			known.put(stance,new Seen("minecraft:air",true,true,false,15,1));
			known.put(stance.offset(0,1,0),new Seen("minecraft:air",true,true,false,15,1));
			known.put(stance.offset(0,-1,0),new Seen("minecraft:stone",false,true,true,15,1));
		}
		for (int y=0;y<=1;y++) known.put(feet.offset(0,y,1),new Seen("minecraft:stone",false,true,true,15,1));
		var world = new StoneAcquisition.World(new Pose(.5,17.62,64.5,0,0),feet,Map.of(),known);
		for (var areas : List.of(List.of(origin),List.of(origin,origin.offset(128,0,0),origin.offset(256,0,0)))) {
			var task = new UndergroundSearch.Task(PRIOR,List.of("minecraft:iron_ore"),origin,previous,0,67,Map.of(),Set.of(),Optional.of(feet),Optional.empty(),new VoxelCommand.Navigate(feet,12,200),List.of(origin,previous),UndergroundSearch.Preparation.EXCAVATING,areas);
			var decision = search.decide(view(task),world);
			if (areas.size()==3) {
				var retreat = assertInstanceOf(Execute.class,decision);
				assertEquals(new VoxelCommand.Navigate(previous,12,200),retreat.command());
				var saved = (UndergroundSearch.Task)retreat.continuation();
				assertEquals(areas,saved.areas()); assertEquals(68,saved.steps(),"backtracking does not renew an exhausted area allowance");
				continue;
			}
			var next = (UndergroundSearch.Task)assertInstanceOf(Keep.class,decision).continuation().orElseThrow();
			assertEquals(List.of(origin,feet),next.areas());
			assertEquals(List.of(origin,previous,feet),next.route());
			assertEquals(0,next.steps());
			assertInstanceOf(Execute.class,search.decide(view(next),world));
			var inspected = new UndergroundSearch.Task(task.prior(),task.targets(),task.origin(),feet,task.direction(),68,Map.of(),Set.of(),Optional.of(feet.offset(1,0,0)),Optional.of(feet.offset(1,-1,0)),new VoxelCommand.Look(90,30),List.of(origin,previous,feet),task.preparation(),areas);
			assertInstanceOf(Keep.class,search.decide(view(inspected),world), "a survey at the boundary must not erase observed arrival");
		}
	}

	private static UndergroundSearch.Task arrived(List<Pos> areas) {
		return new UndergroundSearch.Task(PRIOR,List.of("minecraft:iron_ore"),ORIGIN,PREVIOUS,0,95,Map.of(),Set.of(),Optional.of(FEET),Optional.empty(),
			new VoxelCommand.Navigate(FEET,12,200),List.of(ORIGIN,PREVIOUS),UndergroundSearch.Preparation.OBSERVING,areas);
	}
	private static View<UndergroundSearch.Task> view(UndergroundSearch.Task task) { return new View<>(1,task,false,2,Optional.of(Outcome.success("arrived")),Optional.empty()); }
	private static StoneAcquisition.World world() {
		var known = new HashMap<Pos,Seen>();
		for (Pos stance : List.of(FEET,PREVIOUS)) for (int y=0;y<=1;y++) known.put(stance.offset(0,y,0),new Seen("minecraft:air",true,true,false,15,1));
		for (Pos pos : List.of(FEET.offset(0,-1,0),PREVIOUS.offset(0,-1,0),FEET.offset(-1,0,0),FEET.offset(-1,1,0))) known.put(pos,new Seen("minecraft:stone",false,true,true,15,1));
		return new StoneAcquisition.World(new Pose(FEET.x()+.5,FEET.y()+1.62,FEET.z()+.5,0,30),FEET,Map.of("minecraft:stone_pickaxe",1,"minecraft:torch",8),known,Set.of(FEET.offset(0,-1,0),PREVIOUS.offset(0,-1,0)));
	}
}
