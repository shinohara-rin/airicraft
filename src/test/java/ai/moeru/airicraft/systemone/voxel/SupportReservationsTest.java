package ai.moeru.airicraft.systemone.voxel;

import java.util.*;
import java.io.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionDomain.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionKnowledge.*;

class SupportReservationsTest {
	@Test void anUnvisitedLeafPerchDoesNotReserveTheResourceConnectingItToUsedTerrain() {
		var world=recorded("tree-unused-perch");var target=new Pos(0,200,20);var perch=new Pos(0,202,21);
		assertFalse(world.footholds().contains(perch.offset(0,-1,0)));
		assertTrue(world.footholds().contains(target));
		var rule=new Harvest("minecraft:acacia_log",List.of("minecraft:acacia_log"),List.of(),Technique.EXPOSED);
		var domain=new ProductionDomain(new ProductionKnowledge("test",List.of(),List.of(rule)));
		Task gather=new Gather(rule,1,world.feet(),0,Set.of(),Set.of(),null);
		var view=new View<>(2,gather,false,639,Optional.<Outcome>empty(),Optional.<Outcome>empty());
		var action=assertInstanceOf(Execute.class,domain.decide(view,world));
		assertEquals(new VoxelCommand.Break(target,"minecraft:acacia_log"),action.command());
		Task returning=new Retreat(ReturnNavigation.State.begin(List.of(perch)));
		var branch=List.of(new View<>(1,returning,false,639,Optional.<Outcome>empty(),Optional.<Outcome>empty()),view);
		assertTrue(SupportReservations.protect(world,List.of(perch)).footholds().contains(target),"a promised return to the perch reserves its connector");
		assertFalse(domain.decide(branch,world) instanceof Execute<?,?> next && next.command().equals(action.command()));
		var visited=new HashSet<>(world.footholds());visited.add(perch.offset(0,-1,0));
		var used=new StoneAcquisition.World(world.eye(),world.feet(),world.inventory(),world.known(),visited,world.vitals(),world.drops());
		assertTrue(SupportReservations.protect(used,List.of()).footholds().contains(target),"an actually used branch still needs a walking bypass");
	}
	@Test void aHarvestSearchOriginDoesNotReserveAResourceAfterWalkingOffIt() {
		var world = recorded("tree-origin"); var origin = new Pos(0,201,20); var target = origin.offset(0,-1,0);
		var rule = new Harvest("minecraft:acacia_log",List.of("minecraft:acacia_log"),List.of(),Technique.EXPOSED);
		Task gather = new Gather(rule,1,origin,0,Set.of(),Set.of(),null);
		var domain = new ProductionDomain(new ProductionKnowledge("test",List.of(),List.of(rule)));
		var view = new View<>(2,gather,false,570,Optional.<Outcome>empty(),Optional.<Outcome>empty());
		var action = assertInstanceOf(Execute.class,domain.decide(view,world));
		assertEquals(new VoxelCommand.Break(target,"minecraft:acacia_log"),action.command());
		Task returning = new Retreat(new ReturnNavigation.State(List.of(origin),0,0,Set.of(),Optional.empty()));
		var branch = List.of(new View<>(1,returning,false,570,Optional.<Outcome>empty(),Optional.<Outcome>empty()),view);
		var retained = domain.decide(branch,world);
		assertFalse(retained instanceof Execute<?,?> next && next.command() instanceof VoxelCommand.Break broken && broken.target().equals(target),"an actual return continuation still reserves its waypoint");
	}

	@Test void anObservedBypassReleasesOldFootingButNotContactWaypointsOrTheOnlyConnector() {
		var world = recorded("canopy");
		var entrance = new Pos(0,200,15);
		var reserved = SupportReservations.protect(world, List.of(entrance)).footholds();
		assertTrue(reserved.contains(new Pos(-1,197,14)), "partial current contact");
		assertTrue(reserved.contains(entrance.offset(0,-1,0)), "return waypoint");
		assertTrue(reserved.contains(new Pos(0,198,14)), "only connector back to the canopy");
		assertFalse(reserved.contains(new Pos(-1,199,15)), "old top-surface visit has a walking bypass");
		var pinned = SupportReservations.protect(world, List.of(entrance,new Pos(-1,200,15))).footholds();
		assertTrue(pinned.contains(new Pos(-1,199,15)), "a saved return waypoint must override the bypass");
	}
	@Test void recordedCanopyAccessCanPrepareADescentWhileTheParentEntranceStaysReserved() {
		var world = recorded("canopy"); var entrance = new Pos(0,200,15);
		var domain = new ProductionDomain(new ProductionKnowledge("test",List.of(),List.of()));
		Task parent = new Excavate(StoneAcquisition.Task.begin(3,entrance));
		Task access = new Access(TerrainAccess.State.begin(world.feet(),new Pos(-3,195,14),259),Set.of("minecraft:oak_leaves","minecraft:dirt"));
		var branch = List.of(new View<>(1,parent,false,259,Optional.<Outcome>empty(),Optional.<Outcome>empty()),new View<>(2,access,false,259,Optional.<Outcome>empty(),Optional.<Outcome>empty()));
		var action = assertInstanceOf(Execute.class,domain.decide(branch,world));
		assertEquals(new VoxelCommand.Navigate(new Pos(-1,198,14),12,100),action.command());
	}
	@Test void newUndergroundSearchDoesNotTreatBootstrapFootprintsAsItsOwnCompletedExploration() {
		var world = recorded("original-search"); var origin = new Pos(0,67,7);
		var prior = new SearchPrior("ore",16,64,96,List.of("minecraft:grass_block","minecraft:dirt","minecraft:stone"));
		var domain = new ProductionDomain(new ProductionKnowledge("test",List.of(),List.of(new Harvest("ore",List.of("minecraft:iron_ore"),List.of("minecraft:stone_pickaxe"),Technique.EXPOSED)),List.of(),List.of(),List.of(prior),new LightingPolicy.Parameters(7,10,8,80,4)));
		var search = new UndergroundSearch.Task(prior,List.of("minecraft:iron_ore"),origin,origin,2,0,Map.of(),Set.of(),Optional.of(world.feet()),Optional.empty(),new VoxelCommand.Navigate(world.feet(),12,200),List.of(origin));
		Task task = new Explore(search,Map.of(),Set.of("ore"));
		var result = domain.decide(new View<>(1,task,false,1036,Optional.of(Outcome.success("stance_reached")),Optional.empty()),world);
		var action = assertInstanceOf(Execute.class,result);
		assertInstanceOf(VoxelCommand.Break.class,action.command());
		assertNotEquals(origin.offset(0,-1,0),((VoxelCommand.Break)action.command()).target());
	}
	@Test void destroyingTheBypassRestoresProtectionBeforeAnotherBlockCanBeCleared() {
		var known = new HashMap<Pos,Seen>(); var supports = new HashSet<Pos>();
		for(int x=0;x<=2;x++) for(int z=0;z<=2;z++) if(x!=1||z!=1) {
			var p=new Pos(x,0,z);supports.add(p);known.put(p,new Seen("stone",false,true,true,15,1));
			known.put(p.offset(0,1,0),new Seen("air",true,true,false,15,1));known.put(p.offset(0,2,0),new Seen("air",true,true,false,15,1));
		}
		var feet = new Pos(0,1,0); var first = new Pos(1,0,0); var second = new Pos(1,0,2);
		var world = new StoneAcquisition.World(new Pose(.5,2.62,.5,0,0),feet,Map.of(),known,supports);
		assertFalse(SupportReservations.protect(world,List.of()).footholds().contains(second));
		known.put(first,new Seen("air",true,true,false,15,2));
		world = new StoneAcquisition.World(world.eye(),feet,Map.of(),known,supports);
		assertTrue(SupportReservations.protect(world,List.of()).footholds().contains(second));
		known.remove(new Pos(0,2,1));
		world = new StoneAcquisition.World(world.eye(),feet,Map.of(),known,supports);
		assertTrue(SupportReservations.protect(world,List.of()).footholds().contains(new Pos(0,0,1)), "unknown clearance is not a proven bypass");
	}
	@Test void changedConnectivityCancelsAnOwnedBreakAndWaitsForReleaseBeforeChoosingAgain() {
		var known = new HashMap<Pos,Seen>(); var supports = new HashSet<Pos>();
		for(int x=0;x<=2;x++) for(int z=0;z<=2;z++) if(x!=1||z!=1) {
			var p=new Pos(x,0,z);supports.add(p);known.put(p,new Seen("stone",false,true,true,15,1));
			known.put(p.offset(0,1,0),new Seen("air",true,true,false,15,1));known.put(p.offset(0,2,0),new Seen("air",true,true,false,15,1));
		}
		var feet = new Pos(0,1,0);
		var world = new StoneAcquisition.World(new Pose(.5,2.62,.5,0,45),feet,Map.of(),known,supports);
		var harvest = new Harvest("rock",List.of("stone"),List.of(),Technique.EXPOSED);
		var domain = new ProductionDomain(new ProductionKnowledge("test",List.of(),List.of(harvest)));
		var kernel = new ai.moeru.airicraft.systemone.TaskKernel<Task,StoneAcquisition.World,VoxelCommand>(domain,new Limits(16,16,100,20));
		var first = kernel.advance(kernel.begin("s","r",new Gather(harvest,1,feet,0,Set.of(),Set.of(),null),0),world,List.of(),1);
		var started = assertInstanceOf(Start.class,first.effects().getFirst());
		var target = assertInstanceOf(VoxelCommand.Break.class,started.command()).target();
		Pos other = new Pos(2-target.x(),0,2-target.z());
		known.put(other,new Seen("air",true,true,false,15,2));
		var changed = new StoneAcquisition.World(world.eye(),feet,Map.of(),known,supports);
		var stopped = kernel.advance(first.state(),changed,List.of(),2);
		assertEquals(List.of(new Stop<>(started.token())),stopped.effects());
		var waiting = kernel.advance(stopped.state(),changed,List.of(),3);
		assertTrue(waiting.effects().isEmpty());
		var resumed = kernel.advance(waiting.state(),changed,List.of(new Released(started.token())),4);
		assertTrue(resumed.events().stream().anyMatch(e->e.type().equals("task_revised")&&e.detail().equals("support_reservation_changed")));
		assertFalse(resumed.effects().stream().anyMatch(e->e instanceof Start<?> start&&start.command() instanceof VoxelCommand.Break broken&&broken.target().equals(target)));
	}
	private static StoneAcquisition.World recorded(String name) {
		try(var reader = new InputStreamReader(new java.util.zip.GZIPInputStream(Objects.requireNonNull(SupportReservationsTest.class.getResourceAsStream("/systemone/"+name+".json.gz"))))) {
			var object = JsonParser.parseReader(reader).getAsJsonObject();
			// Historical terrain fixture predates attachment sensing; it grants no fixture support.
			for (var cell : object.getAsJsonObject("observation").getAsJsonArray("changed")) cell.getAsJsonObject().getAsJsonObject("seen").add("attachment",new com.google.gson.Gson().toJsonTree(Attachment.none()));
			var o = new Gson().fromJson(object.get("observation"),StoneTape.Observation.class);
			var known = new HashMap<Pos,Seen>();o.changed().forEach(c->known.put(c.pos(),c.seen()));
			return new StoneAcquisition.World(o.eye(),o.feet(),o.inventory(),known,o.footholds(),o.vitals());
		} catch(IOException failure) {throw new UncheckedIOException(failure);}
	}
}
