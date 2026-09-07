package ai.moeru.airicraft.systemone.voxel;

import ai.moeru.airicraft.systemone.TaskKernel;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.StoneAcquisition.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelCommand.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import static org.junit.jupiter.api.Assertions.*;

class StoneTapeTest {
	private static final Gson GSON = new Gson();

	@Test void serializedLiveShapedInputsReproduceDecisionsIncludingFeedbackAndRelease() {
		var replay = new StoneTape.Replay();
		recording().forEach(replay::accept);
		assertEquals(Outcome.success("cobblestone_inventory_observed"), replay.finish());
		assertEquals(4, replay.turns());
	}
	@Test void missingTurnAndMissingFooterCannotPassAsACompleteReplay() {
		var rows = recording();
		var missingTurn = new StoneTape.Replay();
		missingTurn.accept(rows.get(0)); missingTurn.accept(rows.get(1));
		assertThrows(IllegalArgumentException.class, () -> missingTurn.accept(rows.get(3)));
		var noFooter = new StoneTape.Replay();
		rows.subList(0, rows.size() - 1).forEach(noFooter::accept);
		assertThrows(IllegalArgumentException.class, noFooter::finish);
	}
	@Test void alteredDecisionsAndUnsupportedVersionsAreRejected() {
		var rows = recording();
		var replay = new StoneTape.Replay(); replay.accept(rows.getFirst());
		rows.get(1).getAsJsonArray("effects").add("unrecorded action");
		assertThrows(IllegalArgumentException.class, () -> replay.accept(rows.get(1)));
		rows.getFirst().addProperty("version", 999);
		assertThrows(IllegalArgumentException.class, () -> new StoneTape.Replay().accept(rows.getFirst()));
	}
	@Test void missingTicksAndFalseFooterCountsAreRejected() {
		var rows = recording();
		var replay = new StoneTape.Replay(); replay.accept(rows.getFirst());
		rows.get(1).addProperty("tick", 99);
		assertThrows(IllegalArgumentException.class, () -> replay.accept(rows.get(1)));
		var complete = recording(); var reader = new StoneTape.Replay();
		complete.subList(0, complete.size() - 1).forEach(reader::accept);
		complete.getLast().addProperty("rows", 1);
		assertThrows(IllegalArgumentException.class, () -> reader.accept(complete.getLast()));
	}

	private static List<JsonObject> recording() {
		var kernel = new TaskKernel<>(new StoneAcquisition(), StoneTape.LIMITS);
		Pos feet = new Pos(0, 4, 0);
		var state = kernel.begin("session", "run", Task.begin(1, feet), 0);
		List<JsonObject> rows = new ArrayList<>(); rows.add(json(StoneTape.header(state)));
		World previous = null;
		for (int tick = 1; tick <= 4; tick++) {
			Map<String, Integer> inventory = tick < 3 ? Map.of("minecraft:wooden_pickaxe", 1) : Map.of("minecraft:cobblestone", 1);
			World world = new World(new Pose(0.5, 5.62, 0.5, tick * 90, 55), feet, inventory,
				tick == 1 ? Map.of(new Pos(100, 3, 0), new Seen("minecraft:stone", false, true, true, 15, tick)) : Map.of());
			List<Feedback> feedback = switch (tick) {
				case 2 -> List.of(new Finished(new Token("session", "run", 1, 1), Outcome.failure("obstruction")));
				case 4 -> List.of(new Released(new Token("session", "run", 1, 2)));
				default -> List.of();
			};
			var step = kernel.advance(state, world, feedback, tick);
			rows.add(json(StoneTape.turn(tick, previous, world, feedback, null, step)));
			previous = world; state = step.state();
		}
		rows.add(json(Map.of("type", "end", "rows", rows.size())));
		return rows;
	}
	private static JsonObject json(Object row) { return GSON.fromJson(GSON.toJson(row), JsonObject.class); }
}
