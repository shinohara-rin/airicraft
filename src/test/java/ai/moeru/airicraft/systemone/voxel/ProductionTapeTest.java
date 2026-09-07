package ai.moeru.airicraft.systemone.voxel;

import ai.moeru.airicraft.systemone.TaskKernel;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionDomain.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;

class ProductionTapeTest {
	private static final Gson GSON = new Gson();
	@Test void replaysTheRecordedCatalogAndProductionDecisions() {
		var replay = new ProductionTape.Replay();
		rows().forEach(replay::accept);
		assertEquals(ResultKind.SUCCEEDED, replay.finish().kind());
		assertEquals(2, replay.turns());
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
		return List.of(json(ProductionTape.header(state, book)), json(StoneTape.turn(1, null, before, List.of(), null, first)),
			json(StoneTape.turn(2, before, after, feedback, null, second)), json(Map.of("type", "end", "rows", 3)));
	}
	private static JsonObject json(Object row) { return GSON.fromJson(GSON.toJson(row), JsonObject.class); }
}
