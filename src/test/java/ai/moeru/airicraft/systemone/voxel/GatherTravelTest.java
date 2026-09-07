package ai.moeru.airicraft.systemone.voxel;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionDomain.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionKnowledge.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelCommand.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;

class GatherTravelTest {
	private final Harvest rule = new Harvest("log", List.of("log"), List.of(), Technique.EXPOSED);
	private final ProductionDomain domain = new ProductionDomain(new ProductionKnowledge("test", List.of(), List.of(rule)));
	@Test void aBudgetedLegRetainsItsDestinationOnlyWhileItMakesObservedProgress() {
		var initial = new Gather(rule, 1, new Pos(0,1,0), 0, Set.of(), Set.of(), null);
		var first = assertInstanceOf(Execute.class, domain.decide(new View<Task>(3, initial, false, 1, Optional.empty(), Optional.empty()), world(0, true)));
		var destination = assertInstanceOf(Navigate.class, first.command());
		var continued = assertInstanceOf(Execute.class, domain.decide(failed((Task) first.continuation(), "navigation_budget_exhausted"), world(20, true)));
		assertEquals(destination, continued.command());
		assertEquals(Optional.of(new Pos(0,1,20)), ((Gather) continued.continuation()).commandOrigin());
		for (int position : new int[]{20, 10}) {
			var stopped = domain.decide(failed((Task) continued.continuation(), "navigation_budget_exhausted"), world(position, true));
			assertFalse(stopped instanceof Execute<?,?> action && action.command().equals(destination), "a stalled or regressing leg must not be retried unchanged");
		}
	}
	@Test void progressDoesNotOverrideAnUnavailableRouteOrAResourceThatDisappeared() {
		var initial = new Gather(rule, 1, new Pos(0,1,0), 0, Set.of(), Set.of(), null);
		var first = assertInstanceOf(Execute.class, domain.decide(new View<Task>(3, initial, false, 1, Optional.empty(), Optional.empty()), world(0, true)));
		for (boolean resource : new boolean[]{true, false}) {
			var decision = domain.decide(failed((Task) first.continuation(), resource ? "observed_route_unavailable" : "navigation_budget_exhausted"), world(20, resource));
			assertFalse(decision instanceof Execute<?,?> action && action.command().equals(first.command()));
		}
	}
	private View<Task> failed(Task task, String reason) { return new View<>(3, task, false, 2, Optional.of(Outcome.failure(reason)), Optional.empty()); }
	private StoneAcquisition.World world(int z, boolean resource) {
		var known = new HashMap<Pos,Seen>();
		for (int offset=0; offset<=60; offset++) {
			known.put(new Pos(0,0,offset), new Seen("stone",false,true,true,15,1));
			for(int y=1;y<=3;y++) known.put(new Pos(0,y,offset), new Seen("air",true,true,false,15,1));
		}
		if (resource) known.put(new Pos(0,1,60), new Seen("log",false,true,true,15,1));
		return new StoneAcquisition.World(new Pose(.5,2.62,z+.5,0,0), new Pos(0,1,z), Map.of(), known);
	}
}
