package ai.moeru.airicraft.systemone.voxel;

import ai.moeru.airicraft.systemone.TaskKernel;
import org.junit.jupiter.api.Test;
import java.util.*;
import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionDomain.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionKnowledge.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelCommand.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import static org.junit.jupiter.api.Assertions.*;

class SmeltingProductionTest {
	private static final Smelt IRON = new Smelt("smelt_iron", "ore", "ingot", 1, "minecraft:furnace", 200);
	private static final Pos FURNACE = new Pos(1, 1, 0);
	private static ProductionKnowledge book(Smelt recipe, List<Fuel> fuels) { return new ProductionKnowledge("test", List.of(), List.of(), List.of(recipe), fuels); }

	@Test void collectionRestoresAccessToTheExistingBatchWithoutReloadingIngredients() {
		var kernel=new TaskKernel<Task,StoneAcquisition.World,VoxelCommand>(new ProductionDomain(book(IRON,List.of())),new Limits(16,16,1000,50));
		var batch=new CollectBatch(IRON,FURNACE,0,300,0);var world=world(Map.of());
		var first=kernel.advance(kernel.begin("s","r",batch,99),world,List.of(),100);
		var collect=assertInstanceOf(Start.class,first.effects().getFirst());
		var repair=kernel.advance(first.state(),world,List.of(new Finished(collect.token(),Outcome.failure("furnace_not_observed"))),101);
		assertTrue(repair.state().outcome().isEmpty());
		var movement=assertInstanceOf(Start.class,repair.effects().getFirst());
		var move=assertInstanceOf(Navigate.class,movement.command());assertNotEquals(world.feet(),move.stance());
		var retained=assertInstanceOf(CollectBatch.class,repair.state().stack().getFirst().task());
		assertEquals(300,retained.deadline());assertEquals(FURNACE,retained.station());assertEquals(IRON,retained.recipe());
		var feet=move.stance();var arrived=new StoneAcquisition.World(new Pose(feet.x()+.5,feet.y()+1.62,feet.z()+.5,0,0),feet,Map.of(),world.known());
		var retry=kernel.advance(repair.state(),arrived,List.of(new Finished(movement.token(),Outcome.success("arrived"))),102);
		var retried=assertInstanceOf(Start.class,retry.effects().getFirst());assertEquals(collect.command(),retried.command());
		assertEquals(300,assertInstanceOf(CollectBatch.class,retry.state().stack().getLast().task()).deadline());
		var inventory=new StoneAcquisition.World(arrived.eye(),arrived.feet(),Map.of("ingot",1),arrived.known());
		var done=kernel.advance(retry.state(),inventory,List.of(new Finished(retried.token(),Outcome.success("collected"))),103);
		assertEquals(ResultKind.SUCCEEDED,done.state().outcome().orElseThrow().kind());
	}
	@Test void collectionAccessExpiresAtTheOriginalDeadlineAndWaitsForRelease() {
		var kernel=new TaskKernel<Task,StoneAcquisition.World,VoxelCommand>(new ProductionDomain(book(IRON,List.of())),new Limits(16,16,1000,50));
		var world=world(Map.of());var batch=new CollectBatch(IRON,FURNACE,0,300,0);
		var first=kernel.advance(kernel.begin("s","r",batch,99),world,List.of(),100);
		var collect=assertInstanceOf(Start.class,first.effects().getFirst());
		var repair=kernel.advance(first.state(),world,List.of(new Finished(collect.token(),Outcome.failure("furnace_not_observed"))),101);
		var move=assertInstanceOf(Start.class,repair.effects().getFirst());
		var expired=kernel.advance(repair.state(),world,List.of(),300);
		assertEquals(List.of(new Stop<>(move.token())),expired.effects());assertTrue(expired.state().outcome().isEmpty());
		var done=kernel.advance(expired.state(),world,List.of(new Released(move.token())),301);
		assertTrue(done.effects().isEmpty());assertEquals(ResultKind.FAILED,done.state().outcome().orElseThrow().kind());
	}

	@Test void collectionCannotReplaceItsLostFurnaceWithAnotherObservedStation() {
		var domain=new ProductionDomain(book(IRON,List.of()));var base=world(Map.of());var known=new HashMap<>(base.known());
		known.put(FURNACE,new Seen("minecraft:air",true,true,false,15,101));
		known.put(new Pos(0,1,1),new Seen("minecraft:furnace",false,true,true,15,101));
		var changed=new StoneAcquisition.World(base.eye(),base.feet(),base.inventory(),known);
		var task=new BatchAccess(IRON.station(),FURNACE,300,Set.of(base.feet()),Optional.empty());
		var done=assertInstanceOf(Complete.class,domain.decide(new View<Task>(1,task,false,101,Optional.empty(),Optional.empty()),changed));
		assertEquals("batch_station_lost",done.outcome().evidence());
	}
	@Test void collectionTerrainPreparationCannotExtendTheBatchDeadline() {
		var domain=new ProductionDomain(book(IRON,List.of()));var base=world(Map.of());var stance=new Pos(-1,1,0);
		var task=new BatchAccess(IRON.station(),FURNACE,300,Set.of(base.feet()),Optional.of(stance));
		var failed=new View<Task>(1,task,false,250,Optional.of(Outcome.failure("observed_route_unavailable")),Optional.empty());
		var child=assertInstanceOf(Child.class,domain.decide(failed,base));
		assertEquals(300,assertInstanceOf(Access.class,child.child()).state().deadline());
		assertEquals(task,assertInstanceOf(AfterAccess.class,child.continuation()).saved());
	}

	@Test void rejectedFurnaceReachRepositionsBeforeRetryingWithoutDiscardingTheRecipe() {
		var kernel=new TaskKernel<Task,StoneAcquisition.World,VoxelCommand>(new ProductionDomain(book(IRON,List.of(new Fuel("coal",1600)))),new Limits(16,16,1000,50));
		var world=world(Map.of("ore",1,"coal",1));
		var first=kernel.advance(kernel.begin("s","r",Acquire.root("ingot",1),0),world,List.of(),1);
		var load=assertInstanceOf(Start.class,first.effects().getFirst());
		var repair=kernel.advance(first.state(),world,List.of(new Finished(load.token(),Outcome.failure("furnace_not_observed"))),2);
		assertTrue(repair.state().outcome().isEmpty());
		var movement=assertInstanceOf(Start.class,repair.effects().getFirst());
		var move=assertInstanceOf(Navigate.class,movement.command());
		assertNotEquals(world.feet(),move.stance());
		var feet=move.stance();var arrived=new StoneAcquisition.World(new Pose(feet.x()+.5,feet.y()+1.62,feet.z()+.5,0,0),feet,world.inventory(),world.known());
		var retry=kernel.advance(repair.state(),arrived,List.of(new Finished(movement.token(),Outcome.success("arrived"))),3);
		var retried=assertInstanceOf(Start.class,retry.effects().getFirst());
		assertEquals(load.command(),retried.command());
		assertEquals(1,world.inventory().get("ore"));
		assertTrue(retry.state().outcome().isEmpty());
	}
	@Test void repeatedFurnaceReachFailuresHaveABoundedNumberOfDistinctAttempts() {
		var kernel=new TaskKernel<Task,StoneAcquisition.World,VoxelCommand>(new ProductionDomain(book(IRON,List.of(new Fuel("coal",1600)))),new Limits(16,16,1000,50));
		var world=world(Map.of("ore",1,"coal",1));var state=kernel.begin("s","r",Acquire.root("ingot",1),0);
		List<Feedback> feedback=List.of();var attempted=new HashSet<Pos>();int loads=0;
		for(int tick=1;tick<50 && state.outcome().isEmpty();tick++) {
			var next=kernel.advance(state,world,feedback,tick);state=next.state();feedback=List.of();
			for(var effect:next.effects()) if(effect instanceof Start<VoxelCommand> start) {
				if(start.command() instanceof StartSmelt) {
					loads++;assertTrue(attempted.add(world.feet()),"failed reach must not retry the same stance");
					feedback=List.of(new Finished(start.token(),Outcome.failure("furnace_not_observed")));
				} else {
					var feet=assertInstanceOf(Navigate.class,start.command()).stance();
					world=new StoneAcquisition.World(new Pose(feet.x()+.5,feet.y()+1.62,feet.z()+.5,0,0),feet,world.inventory(),world.known());
					feedback=List.of(new Finished(start.token(),Outcome.success("arrived")));
				}
			}
		}
		assertEquals(4,loads);assertEquals(ResultKind.FAILED,state.outcome().orElseThrow().kind());
		assertEquals(Map.of("ore",1,"coal",1),world.inventory());
	}

	@Test void furnaceWaitReleasesTheMotorAndCollectionRequiresObservedInventory() {
		var kernel = new TaskKernel<Task, StoneAcquisition.World, VoxelCommand>(new ProductionDomain(book(IRON, List.of(new Fuel("coal", 1600)))), new Limits(16, 16, 1000, 50));
		var start = kernel.advance(kernel.begin("s", "r", Acquire.root("ingot", 1), 0), world(Map.of("ore", 1, "coal", 1)), List.of(), 1);
		var load = (Start<VoxelCommand>) start.effects().getFirst();
		assertInstanceOf(StartSmelt.class, load.command());
		var waiting = kernel.advance(start.state(), world(Map.of()), List.of(new Finished(load.token(), Outcome.success("input_loaded"))), 2);
		assertTrue(waiting.effects().isEmpty());
		assertInstanceOf(Sleeping.class, waiting.state().stack().getLast().phase());
		var asleep = kernel.advance(waiting.state(), world(Map.of()), List.of(), 201);
		assertTrue(asleep.effects().isEmpty());
		assertTrue(asleep.state().outcome().isEmpty());
		var collect = kernel.advance(asleep.state(), world(Map.of()), List.of(), 202);
		var pickup = (Start<VoxelCommand>) collect.effects().getFirst();
		assertInstanceOf(CollectSmelt.class, pickup.command());
		var noInventory = kernel.advance(collect.state(), world(Map.of()), List.of(new Finished(pickup.token(), Outcome.success("accepted"))), 203);
		assertTrue(noInventory.state().outcome().isEmpty());
		var actual = kernel.advance(collect.state(), world(Map.of("ingot", 1)), List.of(new Finished(pickup.token(), Outcome.success("collected"))), 203);
		assertEquals(ResultKind.SUCCEEDED, actual.state().outcome().orElseThrow().kind());
	}

	@Test void charcoalInputCannotAlsoBeSpentAsFuel() {
		var recipe = new Smelt("charcoal", "log", "charcoal", 1, "minecraft:furnace", 200);
		var domain = new ProductionDomain(book(recipe, List.of(new Fuel("log", 300))));
		var task = new SmeltBatch(recipe, Map.of(), Set.of("charcoal"), Set.of(), "");
		var dependency = assertInstanceOf(Child.class, domain.decide(view(task), world(Map.of("log", 1))));
		var acquire = assertInstanceOf(Acquire.class, dependency.child());
		assertEquals("log", acquire.item());
		assertEquals(1, acquire.reserved().get("log"));
		var action = assertInstanceOf(Execute.class, domain.decide(view(task), world(Map.of("log", 2))));
		assertEquals(new StartSmelt(recipe, FURNACE, "log", 1), action.command());
	}
	@Test void rawInputsCoverTheRemainingOutputQuantityBeforeReturningToTheFurnace() {
		var domain = new ProductionDomain(book(IRON, List.of(new Fuel("coal", 1600))));
		var goal = new Acquire("ingot", 3, Map.of("ingot", 1, "ore", 1), Set.of(), Set.of(), "smelt_iron");
		var child = assertInstanceOf(Child.class, domain.decide(view(goal), world(Map.of("ingot", 2, "ore", 1))));
		var supply = assertInstanceOf(Acquire.class, child.child());
		assertEquals("ore", supply.item());
		assertEquals(2, supply.count());
		assertEquals(1, supply.reserved().get("ore"));
		assertEquals(2, supply.reserved().get("ingot"));
		var stocked = assertInstanceOf(Child.class, domain.decide(view(goal), world(Map.of("ingot", 2, "ore", 3))));
		assertInstanceOf(SmeltBatch.class, stocked.child());
	}
	@Test void fuelAcquisitionCannotBurnTheInputsForLaterBatches() {
		var charcoal = new Smelt("charcoal", "log", "charcoal", 1, "minecraft:furnace", 200);
		var domain = new ProductionDomain(book(charcoal, List.of(new Fuel("log", 300))));
		var inventory = world(Map.of("log", 2));
		var batch = assertInstanceOf(Child.class, domain.decide(view(Acquire.root("charcoal", 2)), inventory));
		var fuel = assertInstanceOf(Child.class, domain.decide(view((Task) batch.child()), inventory));
		var supply = assertInstanceOf(Acquire.class, fuel.child());
		assertEquals("log", supply.item());
		assertEquals(1, supply.count());
		assertEquals(2, supply.reserved().get("log"));
	}

	@Test void failedFuelSupplySelectsAnAvailableAlternative() {
		var domain = new ProductionDomain(book(IRON, List.of(new Fuel("coal", 1600), new Fuel("plank", 300))));
		var task = new SmeltBatch(IRON, Map.of(), Set.of("ingot"), Set.of(), "coal");
		var view = new View<Task>(1, task, false, 2, Optional.empty(), Optional.of(Outcome.failure("no_coal")));
		var action = assertInstanceOf(Execute.class, domain.decide(view, world(Map.of("ore", 1, "plank", 1))));
		assertEquals(new StartSmelt(IRON, FURNACE, "plank", 1), action.command());
	}
	@Test void distantRememberedFuelSourcePrecedesAnUnobservedSpeciesUntilItsMethodFails() {
		var recipes = List.of(new Recipe("acacia", "acacia_planks", 4, 2, List.of(new Cell(0, "acacia_log"))),
			new Recipe("birch", "birch_planks", 4, 2, List.of(new Cell(0, "birch_log"))));
		var harvests = List.of(new Harvest("acacia_log", List.of("acacia_log"), List.of(), Technique.EXPOSED),
			new Harvest("birch_log", List.of("birch_log"), List.of(), Technique.EXPOSED));
		var domain = new ProductionDomain(new ProductionKnowledge("fuel_sources", recipes, harvests, List.of(IRON),
			List.of(new Fuel("acacia_planks", 300), new Fuel("birch_planks", 300))));
		var base = world(Map.of("ore", 1)); var known = new HashMap<>(base.known());
		known.put(new Pos(40, 40, 0), new Seen("birch_log", false, true, true, 15, 1));
		var observed = new StoneAcquisition.World(base.eye(), base.feet(), base.inventory(), known);
		var task = new SmeltBatch(IRON, Map.of(), Set.of("ingot"), Set.of(), "");
		var first = (Child<Task, VoxelCommand>) domain.decide(view(task), observed);
		assertEquals("birch_planks", ((Acquire) first.child()).item());
		assertEquals(1, ((Acquire) first.child()).reserved().get("ore"));
		var failed = new View<Task>(1, first.continuation(), false, 2, Optional.empty(), Optional.of(Outcome.failure("route_exhausted")));
		var alternative = assertInstanceOf(Child.class, domain.decide(failed, observed));
		assertEquals("acacia_planks", ((Acquire) alternative.child()).item());
		known.put(new Pos(40, 40, 0), new Seen("unknown", false, false, true, 0, 2));
		var unknown = new StoneAcquisition.World(base.eye(), base.feet(), base.inventory(), known);
		assertEquals("acacia_planks", ((Acquire) assertInstanceOf(Child.class, domain.decide(view(task), unknown)).child()).item());
	}

	@Test void collectionFailureWaitsThenTerminatesWithinTheDeclaredBudget() {
		var domain = new ProductionDomain(book(IRON, List.of()));
		var task = new CollectBatch(IRON, FURNACE, 0, 300, 1);
		var failed = new View<Task>(1, task, false, 210, Optional.of(Outcome.failure("not_ready")), Optional.empty());
		var waiting = assertInstanceOf(Sleep.class, domain.decide(failed, world(Map.of())));
		assertEquals(230, waiting.wakeTick());
		var expired = new View<Task>(1, task, false, 300, Optional.empty(), Optional.empty());
		assertEquals(ResultKind.FAILED, assertInstanceOf(Complete.class, domain.decide(expired, world(Map.of()))).outcome().kind());
	}

	@Test void aFuelShortageCannotSilentlyStartAnUnfundedSmelt() {
		var domain = new ProductionDomain(book(IRON, List.of()));
		var result = domain.decide(view(new SmeltBatch(IRON, Map.of(), Set.of("ingot"), Set.of(), "")), world(Map.of("ore", 1)));
		assertEquals("smelting_fuel_alternatives_exhausted", assertInstanceOf(Complete.class, result).outcome().evidence());
		assertEquals(2, new Fuel("stick", 100).quantity(200));
		assertEquals(3, new Fuel("stick", 100).quantity(201));
	}
	@Test void toolPrerequisitesFollowDeclaredProgressionAndTryTheNextAfterFailure() {
		var book = new ProductionKnowledge("tools", List.of(), List.of(new Harvest("ore", List.of("ore_block"), List.of("stone_pick", "diamond_pick"), Technique.EXPOSED)));
		var domain = new ProductionDomain(book);
		var first = (Child<Task, VoxelCommand>) domain.decide(view(Acquire.root("ore", 1)), world(Map.of()));
		assertEquals("stone_pick", ((Acquire) first.child()).item());
		var failed = new View<Task>(1, first.continuation(), false, 2, Optional.empty(), Optional.of(Outcome.failure("stone_unavailable")));
		var alternative = (Child<Task, VoxelCommand>) domain.decide(failed, world(Map.of()));
		assertEquals("diamond_pick", ((Acquire) alternative.child()).item());
	}

	@Test void anUnavailableInputCannotBecomeCheapByIncreasingRecipeYield() {
		var recipes = List.of(new Recipe("impossible_bulk", "compressed", Integer.MAX_VALUE, 2, List.of(new Cell(0, "missing"))),
			new Recipe("bad", "product", 1, 2, List.of(new Cell(0, "compressed"))), new Recipe("good", "product", 1, 2, List.of(new Cell(0, "wood"))));
		var book = new ProductionKnowledge("cost", recipes, List.of(new Harvest("wood", List.of("wood"), List.of(), Technique.EXPOSED)));
		var dependency = (Child<Task, VoxelCommand>) new ProductionDomain(book).decide(view(Acquire.root("product", 1)), world(Map.of()));
		assertEquals("wood", ((Acquire) dependency.child()).item());
	}
	private static View<Task> view(Task task) { return new View<>(1, task, false, 1, Optional.empty(), Optional.empty()); }
	private static StoneAcquisition.World world(Map<String, Integer> inventory) {
		var known = new HashMap<Pos, Seen>();
		for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = 0; y <= 3; y++) {
			known.put(new Pos(x, y, z), new Seen(y == 0 ? "minecraft:stone" : "minecraft:air", y != 0, true, y == 0, 15, 0));
		}
		known.put(FURNACE, new Seen("minecraft:furnace", false, true, true, 15, 0));
		return new StoneAcquisition.World(new Pose(.5, 2.62, .5, 0, 45), new Pos(0, 1, 0), inventory, known);
	}
}
