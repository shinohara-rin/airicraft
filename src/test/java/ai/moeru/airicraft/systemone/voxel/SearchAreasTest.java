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
		var failed = new View<>(1,arrived(List.of(ORIGIN)),false,2,Optional.of(Outcome.failure("blocked")),Optional.<Outcome>empty());
		assertInstanceOf(Complete.class,search.decide(failed,world()));
		var known = new HashMap<>(world().known()); known.remove(FEET.offset(0,-1,0));
		var unsupported = new StoneAcquisition.World(world().eye(),FEET,world().inventory(),known);
		assertInstanceOf(Complete.class,search.decide(view(arrived(List.of(ORIGIN))),unsupported));
	}

	@Test void productionPreservesAnAreaTransitionAndCarriesItIntoLightingRepair() {
		var knowledge = new ProductionKnowledge("test",List.of(),List.of(new Harvest(PRIOR.item(),List.of("minecraft:iron_ore"),List.of("minecraft:stone_pickaxe"),Technique.EXPOSED)),
			List.of(),List.of(),List.of(PRIOR),new LightingPolicy.Parameters(7,10,8,80,4));
		var domain = new ProductionDomain(knowledge);
		var explore = new ProductionDomain.Explore(arrived(List.of(ORIGIN)),LightingPolicy.State.begin(),Map.of(),Set.of(PRIOR.item()));
		var keep = assertInstanceOf(Keep.class,domain.decide(new View<ProductionDomain.Task>(1,explore,false,2,Optional.of(Outcome.success("arrived")),Optional.empty()),world()));
		var continued = assertInstanceOf(ProductionDomain.Explore.class,keep.continuation().orElseThrow());
		assertEquals(List.of(ORIGIN,FEET),continued.search().areas());
		var dim = new HashMap<>(world().known());
		dim.replaceAll((p,s) -> new Seen(s.blockId(),s.empty(),s.identified(),s.fullSupport(),0,3,s.clearForBody()));
		var dark = new StoneAcquisition.World(world().eye(),FEET,Map.of("minecraft:stone_pickaxe",1),dim);
		var repair = assertInstanceOf(Child.class,domain.decide(new View<ProductionDomain.Task>(1,continued,false,3,Optional.empty(),Optional.empty()),dark));
		var suspended = assertInstanceOf(ProductionDomain.ResumeExplore.class,repair.continuation());
		assertEquals(continued.search(),suspended.saved().search());
		assertEquals(continued.search().route(),suspended.returning().route());
	}

	private static UndergroundSearch.Task arrived(List<Pos> areas) {
		return new UndergroundSearch.Task(PRIOR,List.of("minecraft:iron_ore"),ORIGIN,PREVIOUS,0,95,Set.of(),Set.of(),Optional.of(FEET),Optional.empty(),
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
