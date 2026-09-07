package ai.moeru.airicraft.systemone.voxel;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionDomain.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionKnowledge.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelCommand.*;
import ai.moeru.airicraft.systemone.TaskKernel;

class SurvivalRuntimeTest {
	private static final Pos FEET = new Pos(0, 1, 0);
	private static final ProductionKnowledge BOOK = new ProductionKnowledge("survival", List.of(), List.of(new Harvest("log", List.of("log"), List.of(), Technique.EXPOSED)));
	private static TaskKernel<Task, StoneAcquisition.World, VoxelCommand> kernel(ProductionKnowledge book) { return new TaskKernel<>(new ProductionDomain(book), new Limits(16, 16, 1000, 100)); }
	@Test void dangerReleasesMiningBeforeEscapeAndKeepsTheMission() {
		var kernel = kernel(BOOK); var safe = world(FEET, 0, 20, false, Map.of());
		var first = kernel.advance(kernel.begin("s", "r", new Mission("log", 1, 0, 0), 0), safe, List.of(), 1);
		var mining = (Start<VoxelCommand>) first.effects().getFirst(); assertInstanceOf(Break.class, mining.command());
		var danger = world(FEET, 0, 20, true, Map.of());
		var stopped = kernel.advance(first.state(), danger, List.of(), 2);
		assertEquals(List.of(new Stop<>(mining.token())), stopped.effects());
		var waiting = kernel.advance(stopped.state(), danger, List.of(new Released(new Token("s", "r", 999, 999))), 3);
		assertTrue(waiting.effects().isEmpty());
		var escaped = kernel.advance(waiting.state(), danger, List.of(new Released(mining.token())), 4);
		var move = (Start<VoxelCommand>) escaped.effects().getFirst();
		Pos refuge = assertInstanceOf(Navigate.class, move.command()).stance();
		assertNotEquals(mining.token().task(), move.token().task());
		assertEquals(1, escaped.state().stack().getFirst().id());
		var arrived = kernel.advance(escaped.state(), world(refuge, 0, 20, true, Map.of()), List.of(new Finished(move.token(), Outcome.success("arrived"))), 5);
		assertTrue(arrived.events().stream().anyMatch(e -> e.type().equals("task_resumed") && e.detail().equals("SUCCEEDED:survival_refuge_observed")));
		assertTrue(arrived.state().stack().getFirst().task() instanceof Mission mission && mission.item().equals("log"));
	}
	@Test void passiveSmeltingWaitResumesAtItsOriginalReadyTimeAfterEscape() {
		var book = new ProductionKnowledge("smelt", List.of(), List.of(), List.of(new Smelt("cook", "raw", "bar", 1, "furnace", 200)), List.of(new Fuel("fuel", 200)));
		var kernel = kernel(book);
		var first = kernel.advance(kernel.begin("s", "r", new Mission("bar", 1, 0, 0), 0), world(FEET, 0, 20, false, Map.of("raw", 1, "fuel", 1)), List.of(), 1);
		var cook = (Start<VoxelCommand>) first.effects().getFirst(); assertInstanceOf(StartSmelt.class, cook.command());
		var waiting = kernel.advance(first.state(), world(FEET, 0, 20, false, Map.of()), List.of(new Finished(cook.token(), Outcome.success("started"))), 2);
		assertInstanceOf(Sleeping.class, waiting.state().stack().getLast().phase());
		var repair = kernel.advance(waiting.state(), world(FEET, 0, 20, true, Map.of()), List.of(), 50);
		var move = (Start<VoxelCommand>) repair.effects().getFirst(); Pos refuge = ((Navigate) move.command()).stance();
		var resumed = kernel.advance(repair.state(), world(refuge, 0, 20, true, Map.of()), List.of(new Finished(move.token(), Outcome.success("arrived"))), 60);
		assertTrue(resumed.effects().isEmpty(), "escape must not cause premature furnace collection");
		assertEquals(202, ((Sleeping<?>) resumed.state().stack().getLast().phase()).wakeTick());
		var done = kernel.advance(resumed.state(), world(refuge, 0, 20, true, Map.of("bar", 1)), List.of(), 202);
		assertEquals(ResultKind.SUCCEEDED, done.state().outcome().orElseThrow().kind());
	}
	@Test void deathReleasesOldControlAndRespawnRebuildsDependenciesWithoutResettingBudgets() {
		var kernel = kernel(BOOK);
		var first = kernel.advance(kernel.begin("s", "r", new Mission("log", 1, 0, 0), 0), world(FEET, 0, 20, false, Map.of()), List.of(), 1);
		var old = (Start<VoxelCommand>) first.effects().getFirst();
		var dying = kernel.advance(first.state(), world(FEET, 0, 0, false, Map.of()), List.of(), 2);
		assertEquals(List.of(new Stop<>(old.token())), dying.effects());
		var dead = kernel.advance(dying.state(), world(FEET, 0, 0, false, Map.of()), List.of(new Released(old.token())), 3);
		assertEquals(1, dead.state().stack().size()); assertTrue(dead.effects().isEmpty());
		var respawned = kernel.advance(dead.state(), world(FEET, 1, 20, false, Map.of()), List.of(new Finished(old.token(), Outcome.success("late"))), 4);
		assertEquals(new Mission("log", 1, 1, 1), respawned.state().stack().getFirst().task());
		assertEquals(first.state().deadline(), respawned.state().deadline());
		assertTrue(((Start<?>) respawned.effects().getFirst()).token().attempt() > old.token().attempt());
		assertTrue(respawned.events().stream().anyMatch(e -> e.type().equals("feedback_ignored")));
		var domain = new ProductionDomain(BOOK);
		var limit = domain.reconsider(List.of(new View<Task>(1, new Mission("log", 1, 2, 2), false, 5, Optional.empty(), Optional.empty())), world(FEET, 3, 20, false, Map.of()));
		assertEquals(new Abandon("death_recovery_budget_exhausted"), limit.orElseThrow().continuation());
	}
	@Test void unobservedHazardsCannotInfluenceWorkEligibility() {
		var policy = new SurvivalPolicy(SurvivalPolicy.Parameters.minecraft());
		var safe = world(FEET, 0, 20, false, Map.of());
		assertFalse(policy.nearHazard(safe, FEET));
		assertTrue(policy.nearHazard(world(FEET, 0, 20, true, Map.of()), FEET));
		var hidden = new HashMap<>(safe.known()); hidden.put(new Pos(1, 1, 0), new Seen("minecraft:lava", false, false, false, 0, 1));
		assertFalse(policy.nearHazard(new StoneAcquisition.World(safe.eye(), safe.feet(), safe.inventory(), hidden), FEET));
	}
	private static StoneAcquisition.World world(Pos feet, long life, float health, boolean hazard, Map<String, Integer> inventory) {
		var known = new HashMap<Pos, Seen>();
		for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) for (int y = 0; y <= 3; y++) known.put(new Pos(x, y, z), new Seen(y == 0 ? "stone" : "minecraft:air", y != 0, true, y == 0, 15, 1));
		known.put(new Pos(0, 1, 2), new Seen("log", false, true, true, 15, 1));
		known.put(new Pos(2, 1, 2), new Seen("furnace", false, true, true, 15, 1));
		if (hazard) known.put(new Pos(1, 1, 0), new Seen("minecraft:lava", false, true, false, 15, 1));
		return new StoneAcquisition.World(new Pose(feet.x() + .5, feet.y() + 1.62, feet.z() + .5, 0, 0), feet, inventory, known, Set.of(), new SurvivalPolicy.Vitals(life, health, false, false));
	}
}
