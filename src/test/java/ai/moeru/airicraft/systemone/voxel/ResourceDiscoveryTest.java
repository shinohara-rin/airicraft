package ai.moeru.airicraft.systemone.voxel;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionDomain.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionKnowledge.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelCommand.*;

class ResourceDiscoveryTest {
	private static final Harvest RULE = new Harvest("raw",List.of("resource"),List.of(),Technique.EXPOSED,new Discovery(List.of("indicator"),1));
	private static final Pos FEET = new Pos(0,1,0), CLUE = new Pos(0,2,2), RESOURCE = new Pos(0,2,3);
	private static ProductionKnowledge book(List<Recipe> recipes,List<Harvest> harvests) {
		return new ProductionKnowledge("synthetic-indicators",recipes,harvests,List.of(),List.of(),List.of(),List.of("indicator"),new LightingPolicy.Parameters(7,10,8,80,4),SurvivalPolicy.Parameters.minecraft());
	}
	private static StoneAcquisition.World observe(Map<Pos,Sample> scene, long tick) {
		var eye = new Pose(.5,2.62,.5,0,0);
		return new StoneAcquisition.World(eye,FEET,Map.of(),VoxelObservation.observe(p -> scene.getOrDefault(p,new Sample("air",true,false,15)),eye,new Lens(16,100,80,2,2),tick));
	}
	private static Map<Pos,Sample> scene() {
		var scene = new HashMap<Pos,Sample>();
		for (int x=-1;x<=1;x++) for (int y=1;y<=3;y++) scene.put(new Pos(x,y,2),new Sample("indicator",false,true,15));
		scene.put(RESOURCE,new Sample("resource",false,true,15));
		return scene;
	}
	private static View<Task> view(Task task,long tick,Optional<Outcome> receipt) { return new View<>(1,task,false,tick,receipt,Optional.empty()); }

	@Test void inspectionRevealsAResourceWithoutTreatingTheIndicatorAsInventory() {
		var scene = scene(); var observed = observe(scene,1);
		assertFalse(observed.known().containsKey(RESOURCE),"the indicator occludes the actual resource");
		var task = new Gather(RULE,1,FEET,0,Set.of(),Set.of(),null);
		var domain = new ProductionDomain(book(List.of(),List.of(RULE)));
		var first = assertInstanceOf(Execute.class,domain.decide(view(task,1,Optional.empty()),observed));
		assertEquals(new Break(CLUE,"indicator"),first.command());
		var continuation = (Gather)first.continuation();
		assertEquals(1,continuation.discoveryClears());
		scene.remove(CLUE); var exposed = observe(scene,2);
		assertEquals("resource",exposed.known().get(RESOURCE).blockId());
		var second = assertInstanceOf(Execute.class,domain.decide(view(continuation,2,Optional.of(Outcome.success("cleared"))),exposed));
		assertEquals(new Break(RESOURCE,"resource"),second.command());
		assertTrue(((Gather)second.continuation()).drops().isEmpty(),"clearing an indicator is not evidence of a resource drop");
		assertEquals(1,((Gather)second.continuation()).discoveryClears());
	}

	@Test void anUnproductiveInspectionExhaustsItsOwnBudget() {
		var scene = scene(); scene.remove(RESOURCE);
		var domain = new ProductionDomain(book(List.of(),List.of(RULE)));
		var first = (Execute<Task,VoxelCommand>)domain.decide(view(new Gather(RULE,1,FEET,0,Set.of(),Set.of(),null),1,Optional.empty()),observe(scene,1));
		scene.remove(CLUE);
		var second = assertInstanceOf(Execute.class,domain.decide(view(first.continuation(),2,Optional.of(Outcome.success("cleared"))),observe(scene,2)));
		assertInstanceOf(Look.class,second.command(),"remaining indicators cannot bypass the clearance budget");
		assertEquals(1,((Gather)second.continuation()).discoveryClears());
	}

	@Test void observedIndicatorsRankBelowKnownResourcesAndAboveBlindSpeciesSearch() {
		var other = new Harvest("other",List.of("other_resource"),List.of(),Technique.EXPOSED);
		var a = new Recipe("a","output",1,2,List.of(new Cell(0,"other")));
		var b = new Recipe("b","output",1,2,List.of(new Cell(0,"raw")));
		var domain = new ProductionDomain(book(List.of(a,b),List.of(other,RULE)));
		var observed = observe(scene(),1);
		var indicated = assertInstanceOf(Child.class,domain.decide(view(Acquire.root("output",1),1,Optional.empty()),observed));
		assertEquals("b",((Acquire)indicated.continuation()).method());
		var known = new HashMap<>(observed.known()); known.put(new Pos(10,1,0),new Seen("other_resource",false,true,true,15,2));
		var actual = new StoneAcquisition.World(observed.eye(),FEET,Map.of(),known);
		var preferred = assertInstanceOf(Child.class,domain.decide(view(Acquire.root("output",1),2,Optional.empty()),actual));
		assertEquals("a",((Acquire)preferred.continuation()).method());
	}
}
