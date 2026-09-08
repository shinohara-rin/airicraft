package ai.moeru.airicraft.systemone.voxel;

import java.util.*;
import java.io.*;
import java.util.zip.GZIPInputStream;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelCommand.*;
import ai.moeru.airicraft.systemone.voxel.UndergroundSearch.Task;
import ai.moeru.airicraft.systemone.voxel.StoneAcquisition.World;
import ai.moeru.airicraft.systemone.voxel.ProductionKnowledge.SearchPrior;

class SearchBacktrackingTest {
	private final UndergroundSearch search = new UndergroundSearch();
	@Test void recordedUnsupportedRidgeRetreatsToObservedFooting() throws IOException {
		try (var reader = new InputStreamReader(new GZIPInputStream(Objects.requireNonNull(getClass().getResourceAsStream("/systemone/search-dead-end.json.gz"))))) {
			var json = JsonParser.parseReader(reader).getAsJsonObject(); var gson = new Gson();
			// Historical decision-only fixture lacks attachment/properties; no navigation reconstruction is claimed.
			for (var cell : json.getAsJsonObject("observation").getAsJsonArray("changed")) {
				var seen = cell.getAsJsonObject().getAsJsonObject("seen");
				seen.add("attachment",new Gson().toJsonTree(Attachment.none()));
				seen.add("properties",new JsonObject());
			}
			var o = gson.fromJson(json.get("observation"), StoneTape.Observation.class);
			var known = new HashMap<Pos,Seen>(); o.changed().forEach(c -> known.put(c.pos(), c.seen()));
			var world = new World(o.eye(),o.feet(),o.inventory(),known,o.footholds(),o.vitals(),o.drops());
			var route = List.of(gson.fromJson(json.get("route"),Pos[].class));
			var rejected = new HashMap<Pos,UndergroundSearch.Rejection>();
			for (var p : gson.fromJson(json.get("rejected"),Pos[].class)) rejected.put(p,UndergroundSearch.rejection(world,p,UndergroundSearch.RejectionReason.UNRESOLVED_OBSERVATION));
			var task = new Task(gson.fromJson(json.get("prior"),SearchPrior.class),List.of("minecraft:iron_ore","minecraft:deepslate_iron_ore"),route.getFirst(),world.feet(),0,json.get("steps").getAsInt(),rejected,Set.of(),Optional.of(new Pos(-6,95,19)),Optional.of(new Pos(-6,94,19)),new Look(-70.18811f,63.371956f),route);
			assertEquals(25,task.steps());
			assertEquals(0,world.inventory().getOrDefault("minecraft:dirt",0));
			var decision = assertInstanceOf(Execute.class,search.decide(view(task,1383),world));
			assertEquals(new Navigate(new Pos(-7,95,18),12,200),decision.command());
			assertEquals(task.route(),((Task)decision.continuation()).route(),"return anchors and visited branches remain retained");
		}
	}

	@Test void retreatsThroughADeadEndThenExploresAnUnvisitedBranchWithoutCycling() {
		Pos origin=new Pos(0,4,0), junction=origin.offset(0,0,1), dead=junction.offset(0,0,1), branch=junction.offset(1,0,0);
		var known = terrain(List.of(origin,junction,dead,branch));
		var prior = new SearchPrior("ore",4,16,20,List.of());
		Task task = new Task(prior,List.of("ore"),origin,dead,0,2,Map.of(),Set.of(),Optional.empty(),Optional.empty(),null,List.of(origin,junction,dead));
		Pos feet=dead; var movements=new ArrayList<Pos>(); boolean finished=false;
		for (int tick=1;tick<100;tick++) {
			var decision=search.decide(view(task,tick),world(feet,known));
			if (decision instanceof Complete<?,?> complete) { assertEquals(ResultKind.FAILED,complete.outcome().kind()); finished=true; break; }
			var action=assertInstanceOf(Execute.class,decision); task=(Task)action.continuation();
			if (action.command() instanceof Navigate move) { feet=move.stance(); movements.add(feet); }
			else assertInstanceOf(Look.class,action.command());
		}
		assertTrue(finished,"an exhausted search must terminate within its existing budget");
		assertEquals(List.of(junction,branch,junction,origin),movements);
		assertEquals(6,task.steps(),"both forward and return movement consume search steps");
		assertTrue(task.route().containsAll(List.of(origin,junction,dead,branch)));
	}

	@Test void missingOrHazardousReturnSupportDoesNotAuthorizeBacktracking() {
		Pos origin=new Pos(0,4,0), dead=origin.offset(0,0,1);
		var task = new Task(new SearchPrior("ore",4,16,20,List.of()),List.of("ore"),origin,dead,0,1,Map.of(),Set.of(),Optional.empty(),Optional.empty(),null,List.of(origin,dead));
		for (boolean unknown : List.of(true,false)) {
			var known=terrain(List.of(origin,dead)); if (unknown) known.remove(origin.offset(0,-1,0));
			var world=world(dead,known); var current=task;
			for (int tick=1;tick<20;tick++) {
				var result=search.decide(view(current,tick),world,Map.of(),p -> unknown || !p.equals(origin));
				if (result instanceof Complete<?,?>) break;
				var action=assertInstanceOf(Execute.class,result); assertInstanceOf(Look.class,action.command()); current=(Task)action.continuation();
				assertTrue(tick<19,"must exhaust unavailable alternatives");
			}
		}
	}

	private static View<Task> view(Task task,long tick) { return new View<>(1,task,false,tick,Optional.of(Outcome.success("completed")),Optional.empty()); }
	private static World world(Pos feet,Map<Pos,Seen> known) { return new World(new Pose(feet.x()+.5,feet.y()+1.62,feet.z()+.5,0,30),feet,Map.of(),known); }
	private static Map<Pos,Seen> terrain(List<Pos> stances) {
		var known=new HashMap<Pos,Seen>();
		for (int x=-2;x<=3;x++) for(int z=-2;z<=4;z++) for(int y=2;y<=6;y++) known.put(new Pos(x,y,z),new Seen("minecraft:air",true,true,false,15,1));
		for (Pos p:stances) known.put(p.offset(0,-1,0),new Seen("minecraft:stone",false,true,true,15,1));
		return known;
	}
}
