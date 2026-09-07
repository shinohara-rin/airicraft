package ai.moeru.airicraft.systemone.voxel;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelCommand.*;
import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionKnowledge.*;

class EdgePlacementTest {
	@Test void eachEdgeTargetExposesTheFaceWithinTheCrouchedSupportEnvelope() {
		Pos support = new Pos(3, 4, 5);
		for (Face face : List.of(Face.NORTH,Face.SOUTH,Face.WEST,Face.EAST)) {
			var place = new Place("block", support,"stone",face,"block"); var target = EdgePlacement.edge(place);
			assertTrue((target.x()-support.x()-.5)*face.x+(target.z()-support.z()-.5)*face.z > .5);
			assertTrue(EdgePlacement.withinSupportEnvelope(place,target.x(),target.z()));
			assertFalse(EdgePlacement.withinSupportEnvelope(place,target.x()+face.x*.3,target.z()+face.z*.3));
		}
		assertThrows(IllegalArgumentException.class,()->new EdgePlace(new Place("block",support,"stone")));
	}
	@Test void edgePlacementRequiresAnObservedGapAndConfirmedStandingSupport() {
		Pos feet = new Pos(0,4,0), floor = feet.offset(0,-1,0), gap = floor.offset(0,0,1);
		var known = new HashMap<Pos,Seen>();
		for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++) for(int y=2;y<=6;y++) known.put(new Pos(x,y,z),new Seen("air",true,true,false,15,1));
		known.put(floor,new Seen("stone",false,true,true,15,1));
		var prior = new SearchPrior("ore",0,16,20,List.of("stone"),List.of(new SupportMaterial("block","block")));
		var world = new StoneAcquisition.World(new Pose(.5,5.62,.5,0,45),feet,Map.of("block",4),known,Set.of(floor));
		var search = new UndergroundSearch(); var task = UndergroundSearch.Task.begin(prior,List.of("ore_block"),world,Set.of());
		var view = new View<>(1,task,false,1,Optional.<Outcome>empty(),Optional.<Outcome>empty());
		var action = assertInstanceOf(Execute.class,search.decide(view,world));
		var edge = assertInstanceOf(EdgePlace.class,action.command());
		assertEquals(gap,edge.placement().destination());
		assertEquals(floor,edge.placement().support());
		assertFalse(ObservedReach.visibleFace(known,world.eye(),floor,Face.SOUTH,4.3));
		var reserved = search.decide(view,world,Map.of("block",4));
		assertFalse(reserved instanceof Execute<?,?> e && e.command() instanceof EdgePlace);
		known.remove(gap);
		var unknown = new StoneAcquisition.World(world.eye(),feet,world.inventory(),known,Set.of(floor));
		var decision = search.decide(view,unknown);
		assertFalse(decision instanceof Execute<?,?> e && e.command() instanceof EdgePlace ep && ep.placement().destination().equals(gap));
	}
}
