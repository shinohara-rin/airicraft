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
	@Test void aVanishedObservedOnlyTargetDoesNotTurnIntoAnUnrelatedSurvey() {
		var w=world(false);var task=new Gather(PROCESSED,1,w.feet(),0,Set.of(),Set.of(),null);
		var result=new ProductionDomain(BOOK).decide(new View<>(1,task,false,1,Optional.empty(),Optional.empty()),w);
		assertInstanceOf(Complete.class,result);
	}
}
