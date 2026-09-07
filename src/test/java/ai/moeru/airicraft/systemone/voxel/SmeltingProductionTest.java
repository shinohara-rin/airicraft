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

	@Test void failedFuelSupplySelectsAnAvailableAlternative() {
		var domain = new ProductionDomain(book(IRON, List.of(new Fuel("coal", 1600), new Fuel("plank", 300))));
		var task = new SmeltBatch(IRON, Map.of(), Set.of("ingot"), Set.of(), "coal");
		var view = new View<Task>(1, task, false, 2, Optional.empty(), Optional.of(Outcome.failure("no_coal")));
		var action = assertInstanceOf(Execute.class, domain.decide(view, world(Map.of("ore", 1, "plank", 1))));
		assertEquals(new StartSmelt(IRON, FURNACE, "plank", 1), action.command());
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
