package ai.moeru.airicraft.systemone.voxel;

import ai.moeru.airicraft.systemone.TaskKernel;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionDomain.*;
import ai.moeru.airicraft.systemone.voxel.StoneAcquisition.World;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;

/** Production missions record their complete immutable recipe/prior catalog once at the boundary. */
public final class ProductionTape {
	public static final String METHOD_VERSION = "reactive-production-v29";
	private static final Gson GSON = new Gson();
	public record Header(String type, int version, String methodVersion, ProductionKnowledge knowledge,
		String session, String run, String item, int count, long tick, Limits limits) {}
	public static Header header(State<Task> state, ProductionKnowledge knowledge) {
		var goal = (Acquire) state.stack().getFirst().task();
		return new Header("production_begin", 1, METHOD_VERSION, knowledge, state.session(), state.run(), goal.item(), goal.count(), state.lastTick(), StoneTape.LIMITS);
	}
	public static final class Replay {
		private TaskKernel<Task, World, VoxelCommand> kernel;
		private State<Task> state;
		private final Map<Pos, Seen> known = new HashMap<>();
		private long rows, sequence;
		private boolean ended;
		public void accept(JsonObject row) {
			if (ended) throw new IllegalArgumentException("Data after recording footer");
			switch (row.get("type").getAsString()) {
				case "production_begin" -> {
					if (rows != 0) throw new IllegalArgumentException("Repeated recording header");
					if (row.get("version").getAsInt() != 1 || !METHOD_VERSION.equals(row.get("methodVersion").getAsString())) throw new IllegalArgumentException("Unsupported production version");
					var header = GSON.fromJson(row, Header.class);
					kernel = new TaskKernel<>(new ProductionDomain(header.knowledge()), header.limits());
					state = kernel.begin(header.session(), header.run(), Acquire.root(header.item(), header.count()), header.tick());
				}
				case "turn" -> {
					if (state == null) throw new IllegalArgumentException("Missing production header");
					var turn = GSON.fromJson(row, StoneTape.Turn.class);
					if (turn.sequence() != ++sequence || turn.tick() != state.lastTick() + 1) throw new IllegalArgumentException("Missing or reordered production turn " + sequence);
					World world = null;
					if (turn.observation() != null) {
						var observation = turn.observation();
						observation.removed().forEach(known::remove); observation.changed().forEach(cell -> known.put(cell.pos(), cell.seen()));
						world = new World(observation.eye(), observation.feet(), observation.inventory(), known, observation.footholds());
					}
					var step = kernel.advance(state, world, turn.feedback().stream().map(StoneTape.Reply::decode).toList(), turn.tick(), Optional.ofNullable(turn.cancellation()));
					if (!turn.effects().equals(step.effects().stream().map(Object::toString).toList()) || !turn.events().equals(step.events())
						|| !turn.outcome().equals(step.state().outcome().map(Outcome::toString).orElse(""))) throw new IllegalArgumentException("Production decision mismatch at " + turn.tick());
					state = step.state();
				}
				case "end" -> {
					if (row.get("rows").getAsLong() != rows || state == null || state.outcome().isEmpty()) throw new IllegalArgumentException("Incomplete production recording");
					ended = true; return;
				}
				default -> throw new IllegalArgumentException("Incomplete or unknown recording row");
			}
			rows++;
		}
		public Outcome finish() { if (!ended) throw new IllegalArgumentException("Missing recording footer"); return state.outcome().orElseThrow(); }
		public long turns() { return sequence; }
	}
	public static void main(String[] args) throws Exception {
		if (args.length != 1) throw new IllegalArgumentException("Expected one recording path");
		var production = new Replay(); var stone = new StoneTape.Replay(); Boolean legacy = null;
		try (var reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(Path.of(args[0]))), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				var row = GSON.fromJson(line, JsonObject.class);
				if (legacy == null) legacy = row.get("type").getAsString().equals("begin");
				if (legacy) stone.accept(row); else production.accept(row);
			}
		}
		System.out.println("Replay verified: " + (Boolean.TRUE.equals(legacy) ? stone.turns() : production.turns()) + " turns; "
			+ (Boolean.TRUE.equals(legacy) ? stone.finish() : production.finish()));
	}
}
