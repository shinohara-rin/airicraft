package ai.moeru.airicraft.systemone.voxel;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import ai.moeru.airicraft.systemone.TaskKernel;
import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionKnowledge.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionDomain.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;

class HarvestSearchPolicyTest {
	private static final Harvest PROCESSED = new Harvest("processed", List.of("processed_block"), List.of(), Technique.EXPOSED, Discovery.observedOnly());
	private static final Harvest RAW = new Harvest("raw", List.of("raw_block"), List.of(), Technique.EXPOSED, new Discovery(List.of("foliage"),4));
	private static final ProductionKnowledge BOOK = new ProductionKnowledge("search-priors",List.of(
		new Recipe("a-processed","product",4,2,List.of(new Cell(0,"processed"))),
		new Recipe("z-raw","product",4,2,List.of(new Cell(0,"raw")))),List.of(PROCESSED,RAW));
	private static StoneAcquisition.World world(boolean processed) {
		var base=ItemPickupTest.world(Map.of(),List.of());var known=new HashMap<>(base.known());
		if(processed)known.put(base.feet().offset(0,0,1),new Seen("processed_block",false,true,true,15,1));
		return new StoneAcquisition.World(base.eye(),base.feet(),base.inventory(),known);
	}
	private static Step<Task,VoxelCommand> start(String item, StoneAcquisition.World world) {
		var kernel=new TaskKernel<Task,StoneAcquisition.World,VoxelCommand>(new ProductionDomain(BOOK),StoneTape.LIMITS);
		return kernel.advance(kernel.begin("s","r",Acquire.root(item,1),0),world,List.of(),1);
	}
	@Test void missingRecordedSearchModeCannotSilentlyEnableSurveying() {
		var gson=new com.google.gson.Gson();var json=gson.toJsonTree(PROCESSED.discovery()).getAsJsonObject();
		json.remove("searchMode");
		assertThrows(RuntimeException.class,()->gson.fromJson(json,Discovery.class));
	}

	@Test void absentObservedOnlyResourceDoesNotStartASurvey() {
		var step=start("processed",world(false));
		assertTrue(step.effects().isEmpty());assertEquals(ResultKind.FAILED,step.state().outcome().orElseThrow().kind());
	}
	@Test void actualObservedProcessedResourceStillAuthorizesHarvesting() {
		var step=start("processed",world(true));
		var command=assertInstanceOf(Start.class,step.effects().getFirst());
		assertEquals("processed_block",assertInstanceOf(VoxelCommand.Break.class,command.command()).expectedBlock());
	}
	@Test void recipeRankingDoesNotPretendAnUnseenProcessedSourceIsDiscoverable() {
		var step=start("product",world(false));
		assertEquals("z-raw",assertInstanceOf(Acquire.class,step.state().stack().getFirst().task()).method());
		assertInstanceOf(VoxelCommand.Look.class,assertInstanceOf(Start.class,step.effects().getFirst()).command());
	}
	@Test void anExplicitGeologicalSearchRemainsAnAvailableRecipeInput() {
		var ore = new Harvest("ore", List.of("ore_block"), List.of("pick"), Technique.EXPOSED, Discovery.observedOnly());
		var book = new ProductionKnowledge("geological-input", List.of(
			new Recipe("a-surface", "product", 1, 2, List.of(new Cell(0,"raw"), new Cell(1,"raw"))),
			new Recipe("z-geological", "product", 1, 2, List.of(new Cell(0,"ore")))),
			List.of(ore, RAW), List.of(), List.of(),
			List.of(new SearchPrior("ore", 0, 16, 16, List.of("stone"))),
			new LightingPolicy.Parameters(7,10,8,80,4));
		var kernel = new TaskKernel<Task,StoneAcquisition.World,VoxelCommand>(new ProductionDomain(book),StoneTape.LIMITS);
		var base = world(false);
		var observed = new StoneAcquisition.World(base.eye(),base.feet(),Map.of("pick",1),base.known());
		var step = kernel.advance(kernel.begin("s","r",Acquire.root("product",1),0),observed,List.of(),1);
		assertEquals("z-geological", assertInstanceOf(Acquire.class,step.state().stack().getFirst().task()).method());
		assertTrue(step.state().stack().stream().anyMatch(frame -> frame.task() instanceof Explore));
		assertFalse(step.state().stack().stream().anyMatch(frame -> frame.task() instanceof Gather),
			"a location prior authorizes exploration; it does not identify an ore block");
		var known = new HashMap<>(observed.known());
		known.put(observed.feet().offset(0,0,1),new Seen("raw_block",false,true,true,15,1));
		var exposed = new StoneAcquisition.World(observed.eye(),observed.feet(),observed.inventory(),known);
		var withEvidence = kernel.advance(kernel.begin("s","r",Acquire.root("product",1),0),exposed,List.of(),1);
		assertEquals("a-surface",assertInstanceOf(Acquire.class,withEvidence.state().stack().getFirst().task()).method(),
			"an observed source takes precedence over a geological prior");
	}
	@Test void aVanishedObservedOnlyTargetDoesNotTurnIntoAnUnrelatedSurvey() {
		var w=world(false);var task=new Gather(PROCESSED,1,w.feet(),0,Set.of(),Set.of(),null);
		var result=new ProductionDomain(BOOK).decide(new View<>(1,task,false,1,Optional.empty(),Optional.empty()),w);
		assertInstanceOf(Complete.class,result);
	}
}
