package ai.moeru.airicraft.systemone.voxel;

import ai.moeru.airicraft.systemone.TaskKernel;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionDomain.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;

class ProductionTapeTest {
	private static final Gson GSON = new Gson();
	@Test void unavailableObservationPreservesAReceiptAndReplayResumesFromTheFreshSnapshot() {
		var book = new ProductionKnowledge("availability",List.of(),List.of(new ProductionKnowledge.Harvest("ore_item",List.of("ore_block"),List.of(),ProductionKnowledge.Technique.EXPOSED)));
		var kernel = new TaskKernel<Task,StoneAcquisition.World,VoxelCommand>(new ProductionDomain(book),StoneTape.LIMITS);
		var initial = kernel.begin("session","run",Acquire.root("ore_item",1),0);
		var base = ItemPickupTest.world(Map.of(),List.of());
		var a=base.feet().offset(0,0,1); var b=base.feet().offset(1,0,1);
		var known=new java.util.HashMap<>(base.known());
		for(var pos:List.of(a,b)) known.put(pos,new Seen("ore_block",false,true,true,15,1));
		var observed=new StoneAcquisition.World(base.eye(),base.feet(),base.inventory(),known);
		var first=kernel.advance(initial,observed,List.of(),1);
		var start=assertInstanceOf(Start.class,first.effects().getFirst());
		assertEquals(a,assertInstanceOf(VoxelCommand.Break.class,start.command()).target());
		var receipt=new Finished(start.token(),Outcome.failure("observed_target_changed"));
		var missing=kernel.advance(first.state(),null,List.of(receipt),2);
		assertTrue(missing.effects().isEmpty());
		assertEquals(Optional.of(receipt.outcome()),missing.state().stack().getLast().commandResult());
		var waiting=kernel.advance(missing.state(),null,List.of(),3);
		assertEquals(missing.state().stack(),waiting.state().stack(),"missing terrain must not consume the task or its feedback");
		var fresh=new java.util.HashMap<>(base.known()); fresh.remove(a); fresh.remove(b);
		var restored=new StoneAcquisition.World(base.eye(),base.feet(),base.inventory(),fresh);
		var resumed=kernel.advance(waiting.state(),restored,List.of(),4);
		assertFalse(resumed.effects().stream().anyMatch(e->e instanceof Start<?> s && s.command() instanceof VoxelCommand.Break));
		var ended=kernel.advance(resumed.state(),restored,List.of(),5,Optional.of("test_finished"));
		var released=kernel.advance(ended.state(),restored,ended.effects().stream().filter(e->e instanceof Stop<?>).map(e->(Feedback)new Released(((Stop<?>)e).token())).toList(),6,Optional.of("test_finished"));
		var replay=new ProductionTape.Replay();
		List.of(json(ProductionTape.header(initial,book)),json(ProductionTape.turn(1,null,observed,List.of(),null,initial,first)),
			json(ProductionTape.turn(2,observed,null,List.of(receipt),null,first.state(),missing)),
			json(ProductionTape.turn(3,null,null,List.of(),null,missing.state(),waiting)),
			json(ProductionTape.turn(4,null,restored,List.of(),null,waiting.state(),resumed)),
			json(ProductionTape.turn(5,restored,restored,List.of(),"test_finished",resumed.state(),ended)),
			json(ProductionTape.turn(6,restored,restored,ended.effects().stream().filter(e->e instanceof Stop<?>).map(e->(Feedback)new Released(((Stop<?>)e).token())).toList(),"test_finished",ended.state(),released)),
			json(Map.of("type","end","rows",7))).forEach(replay::accept);
		assertEquals(ResultKind.CANCELLED,replay.finish().kind());
	}
	@Test void replaysTheRecordedCatalogAndProductionDecisions() {
		var replay = new ProductionTape.Replay();
		rows().forEach(replay::accept);
		assertEquals(ResultKind.SUCCEEDED, replay.finish().kind());
		assertEquals(2, replay.turns());
	}
	@Test void observedItemsReplayAndDecisionRelevantItemTamperingIsRejected() {
		var book=new ProductionKnowledge("pickup",List.of(),List.of());
		var kernel=new TaskKernel<Task,StoneAcquisition.World,VoxelCommand>(new ProductionDomain(book),StoneTape.LIMITS);
		var state=kernel.begin("session","run",Acquire.root("log",1),0);
		var before=ItemPickupTest.world(Map.of(),List.of(ItemPickupTest.drop(.5,0)));
		var first=kernel.advance(state,before,List.of(),1);
		var start=assertInstanceOf(Start.class,first.effects().getFirst());
		assertInstanceOf(VoxelCommand.Navigate.class,start.command());
		var after=ItemPickupTest.world(Map.of("log",1),List.of());
		List<Feedback> feedback=List.of(new Finished(start.token(),Outcome.success("arrived")));
		var second=kernel.advance(first.state(),after,feedback,2);
		var header=json(ProductionTape.header(state,book));
		var turn=json(ProductionTape.turn(1,null,before,List.of(),null,state,first));
		var replay=new ProductionTape.Replay();replay.accept(header);replay.accept(turn);
		replay.accept(json(ProductionTape.turn(2,before,after,feedback,null,first.state(),second)));
		replay.accept(json(Map.of("type","end","rows",3)));
		assertEquals(ResultKind.SUCCEEDED,replay.finish().kind());
		for(boolean remove:List.of(false,true)) {
			var corrupt=turn.deepCopy();var observation=corrupt.getAsJsonObject("observation");
			if(remove) observation.remove("drops");
			else observation.getAsJsonArray("drops").get(0).getAsJsonObject().addProperty("item","unrelated");
			var rejected=new ProductionTape.Replay();rejected.accept(header);
			assertThrows(RuntimeException.class,()->rejected.accept(corrupt));
		}
	}
	@Test void changedKnowledgeAndMissingInputsAreRejected() {
		var rows = rows();
		var corrupt = rows.getFirst().deepCopy();
		corrupt.getAsJsonObject("knowledge").getAsJsonArray("recipes").get(0).getAsJsonObject().addProperty("yield", 2);
		var replay = new ProductionTape.Replay(); replay.accept(corrupt);
		assertThrows(IllegalArgumentException.class, () -> replay.accept(rows.get(1)));
		var missing = new ProductionTape.Replay(); missing.accept(rows.getFirst());
		assertThrows(IllegalArgumentException.class, () -> missing.accept(rows.get(2)));
		assertThrows(IllegalArgumentException.class, missing::finish);
	}
	@Test void lightingEvidenceMustBePresentAndMatchTheReplayedPolicyState() {
		for (boolean remove : List.of(true, false)) {
			var rows = rows(); var turn = rows.get(1).deepCopy();
			if (remove) turn.remove("lighting");
			else turn.getAsJsonObject("lighting").addProperty("policyLight", 15);
			var replay = new ProductionTape.Replay(); replay.accept(rows.getFirst());
			assertThrows(IllegalArgumentException.class, () -> replay.accept(turn));
		}
	}
	@Test void suspendedAllowanceRetainsItsDeadlineAndIsDistinguishedFromActiveExploration() {
		var origin = new Pos(0,1,0);
		var world = new StoneAcquisition.World(new Pose(.5,2.62,.5,0,0), origin, Map.of(), Map.of());
		var prior = new ProductionKnowledge.SearchPrior("ore",2,4,20,List.of("stone"));
		var light = new LightingPolicy.State(true, Set.of(LightingPolicy.Repair.PLACEMENT, LightingPolicy.Repair.SUPPLY), Optional.of(new LightingPolicy.Allowance(origin,80)));
		var explore = new Explore(UndergroundSearch.Task.begin(prior,List.of("ore"),world,Set.of()),light,Map.of(),Set.of());
		var kernel = new TaskKernel<Task,StoneAcquisition.World,VoxelCommand>(new ProductionDomain(new ProductionKnowledge("test",List.of(),List.of())),StoneTape.LIMITS);
		var before = kernel.begin("session","run",explore,0);
		Task saved = new AfterEscape(new AfterAccess(new ResumeExplore(explore,ReturnNavigation.State.begin(List.of(origin)),new LightRepair(LightingPolicy.Repair.SUPPLY))),Optional.empty(),Optional.empty());
		var after = new State<Task>("session","run",List.of(
			new Frame<>(1,saved,new WaitingChild<>(2,"repair"),Optional.empty(),Optional.empty()),
			new Frame<>(2,Acquire.root("torch",8),new Ready<>(),Optional.empty(),Optional.empty())),3,1,72000,20,Optional.empty());
		var trace = ProductionTape.lightingTrace(before,after,world);
		assertTrue(trace.before().getFirst().leaf());
		var suspended = trace.after().getFirst();
		assertFalse(suspended.leaf()); assertEquals("WaitingChild",suspended.phase());
		assertEquals(List.of("survival_repair","access_repair","resupply_return","explore"),suspended.continuation());
		assertEquals(80,suspended.allowance().expiresAt());
		assertEquals(List.of(LightingPolicy.Repair.SUPPLY,LightingPolicy.Repair.PLACEMENT),suspended.failed());
	}
	@Test void missingOrTamperedSearchEvidenceCannotReplay() {
		for(boolean remove : List.of(false,true)) {
			var rows=rows();var turn=rows.get(1).deepCopy();
			if(remove)turn.remove("search");else turn.getAsJsonArray("search").add("invented rejection");
			var replay=new ProductionTape.Replay();replay.accept(rows.getFirst());
			assertThrows(IllegalArgumentException.class,()->replay.accept(turn));
		}
	}
	@Test void searchEvidenceChangesAreSparseAndSurviveSuspension() {
		var feet=new Pos(0,1,0);var target=new Pos(0,1,1);
		var world=new StoneAcquisition.World(new Pose(.5,2.62,.5,0,0),feet,Map.of(),Map.of());
		var prior=new ProductionKnowledge.SearchPrior("ore",1,4,20,List.of("stone"));
		var empty=new Explore(UndergroundSearch.Task.begin(prior,List.of("ore"),world,Set.of()),LightingPolicy.State.begin(),Map.of(),Set.of());
		var evidence=UndergroundSearch.rejection(world,target,UndergroundSearch.RejectionReason.UNRESOLVED_OBSERVATION);
		var rejected=new Explore(new UndergroundSearch.Task(prior,List.of("ore"),feet,feet,0,0,Map.of(target,evidence),Set.of(),Optional.empty(),Optional.empty(),null),empty.light(),Map.of(),Set.of());
		var kernel=new TaskKernel<Task,StoneAcquisition.World,VoxelCommand>((view,observation)->new Keep<>(),StoneTape.LIMITS);
		var before=kernel.begin("s","r",empty,0);var after=kernel.begin("s","r",rejected,0);
		var changes=ProductionTape.searchChanges(before,after);
		assertEquals(1,changes.size());assertEquals("rejected",changes.getFirst().change());
		assertEquals(target,changes.getFirst().candidate());assertEquals(evidence.reason(),changes.getFirst().reason());
		assertTrue(changes.getFirst().observed().stream().anyMatch(c->c.pos().equals(target.offset(0,-1,0))&&!c.geometry().identified()));
		assertTrue(ProductionTape.searchChanges(after,kernel.begin("s","r",new AfterAccess(rejected),0)).isEmpty());
		assertEquals("removed",ProductionTape.searchChanges(after,before).getFirst().change());
	}

	private static List<JsonObject> rows() {
		var recipe = new ProductionKnowledge.Recipe("planks", "planks", 4, 2, List.of(new ProductionKnowledge.Cell(0, "log")));
		var book = new ProductionKnowledge("fixture", List.of(recipe), List.of());
		var kernel = new TaskKernel<Task, StoneAcquisition.World, VoxelCommand>(new ProductionDomain(book), StoneTape.LIMITS);
		var state = kernel.begin("session", "run", Acquire.root("planks", 3), 0);
		var before = new StoneAcquisition.World(new Pose(0, 0, 0, 0, 0), new Pos(0, 0, 0), Map.of("log", 1), Map.of());
		var first = kernel.advance(state, before, List.of(), 1);
		var command = (Start<VoxelCommand>) first.effects().getFirst();
		var after = new StoneAcquisition.World(before.eye(), before.feet(), Map.of("planks", 4), Map.of());
		List<Feedback> feedback = List.of(new Finished(command.token(), Outcome.success("inventory_changed")));
		var second = kernel.advance(first.state(), after, feedback, 2);
		return List.of(json(ProductionTape.header(state, book)), json(ProductionTape.turn(1, null, before, List.of(), null, state, first)),
			json(ProductionTape.turn(2, before, after, feedback, null, first.state(), second)), json(Map.of("type", "end", "rows", 3)));
	}
	private static JsonObject json(Object row) { return GSON.fromJson(GSON.toJson(row), JsonObject.class); }
}
