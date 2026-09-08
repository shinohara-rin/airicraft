package ai.moeru.airicraft.systemone.voxel;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import java.util.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import static org.junit.jupiter.api.Assertions.*;

class ObservationDeltaTest {
	private static final Gson GSON = new Gson();
	private static final Pos A = new Pos(0, 4, 1), B = new Pos(1, 4, 1);
	private static Seen seen(long tick) { return new Seen("stone", false, true, true, 12, tick, false, new Attachment(Set.of(VoxelCommand.Face.NORTH),false)); }
	private static StoneAcquisition.World world(Map<Pos,Seen> known) {
		return new StoneAcquisition.World(new Pose(0.5,5.62,0.5,0,0), new Pos(0,4,0), Map.of(), known);
	}
	@Test void refreshRoundTripPreservesEveryCellAndAllTimestampsAcrossRemovalAndNullGaps() {
		var worlds = Arrays.asList(world(Map.of(A,seen(1),B,seen(1))), world(Map.of(A,seen(2),B,seen(1))),
			world(Map.of(A,seen(2))), null, world(Map.of(B,seen(5))));
		var known = new HashMap<Pos,Seen>(); StoneAcquisition.World previous = null;
		for (int i=0;i<worlds.size();i++) {
			var current=worlds.get(i); var delta=StoneTape.observation(previous,current,i+1);
			if (i==1) { assertEquals(List.of(A),delta.refreshed()); assertTrue(delta.changed().isEmpty()); }
			var decoded=GSON.fromJson(GSON.toJson(delta),StoneTape.Observation.class);
			var reconstructed=StoneTape.reconstruct(decoded,i+1,known);
			assertEquals(current,reconstructed);
			if(current==null) assertTrue(known.isEmpty());
			previous=current;
		}
	}
	@Test void changedIdentityGeometryLightingAndNonCurrentTimestampsRemainFullCells() {
		var before=world(Map.of(A,seen(1)));
		for (Seen changed : List.of(new Seen("dirt",false,true,true,12,2,false),
			new Seen("stone",true,true,true,12,2,false),new Seen("stone",false,false,true,12,2,false),
			new Seen("stone",false,true,false,12,2,false),new Seen("stone",false,true,true,11,2,false),
			new Seen("stone",false,true,true,12,2,true),seen(3))) {
			var after=world(Map.of(A,changed)); var delta=StoneTape.observation(before,after,2);
			assertTrue(delta.refreshed().isEmpty()); assertEquals(List.of(new StoneTape.Cell(A,changed)),delta.changed());
			assertEquals(after,StoneTape.reconstruct(delta,2,new HashMap<>(before.known())));
		}
	}
	@Test void malformedRefreshesAreRejectedBeforeReconstructionMutatesMemory() {
		var before=world(Map.of(A,seen(1))); var delta=StoneTape.observation(before,world(Map.of(A,seen(2))),2);
		for (int corruption=0;corruption<5;corruption++) {
			var json=GSON.toJsonTree(delta).getAsJsonObject();
			switch(corruption) {
				case 0 -> json.getAsJsonArray("refreshed").add(GSON.toJsonTree(A));
				case 1 -> json.getAsJsonArray("refreshed").add(GSON.toJsonTree(B));
				case 2 -> json.getAsJsonArray("changed").add(GSON.toJsonTree(new StoneTape.Cell(A,seen(2))));
				case 3 -> json.getAsJsonArray("removed").add(GSON.toJsonTree(A));
				case 4 -> { json.getAsJsonArray("refreshed").remove(0); json.getAsJsonArray("changed").add(GSON.toJsonTree(new StoneTape.Cell(B,seen(2)))); json.getAsJsonArray("removed").add(GSON.toJsonTree(B)); }
			}
			var known=new HashMap<>(before.known());
			assertThrows(IllegalArgumentException.class,()->StoneTape.reconstruct(GSON.fromJson(json,StoneTape.Observation.class),2,known));
			assertEquals(before.known(),known);
		}
	}
	@Test void currentWireFormatRequiresEveryDeltaList() {
		var observation=GSON.toJsonTree(StoneTape.observation(null,world(Map.of()),1)).getAsJsonObject();
		for(String field:List.of("changed","removed","refreshed")) {
			var bad=observation.deepCopy(); bad.remove(field);
			var row=new com.google.gson.JsonObject();row.add("observation",bad);
			assertThrows(IllegalArgumentException.class,()->StoneTape.validateObservation(row));
		}
	}
}
