package ai.moeru.airicraft.systemone.voxel;

import ai.moeru.airicraft.systemone.TaskKernel;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.StoneAcquisition.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelCommand.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;

/** Versioned wire values for reproducing actual decision inputs, including negative motor feedback. */
public final class StoneTape {
	public static final int FORMAT_VERSION = 3;
	public static final String METHOD_VERSION = "local-stone-v14";
	public static final Limits LIMITS = new Limits(16, 16, 72000, 2000);
	private static final Gson GSON = new Gson();
	private StoneTape() {}

	public record Header(String type, int version, String methodVersion, String session, String run,
		int count, Pos origin, long tick, Limits limits) {}
	public record Cell(Pos pos, Seen seen) {}
	public record Observation(Pose eye, Pos feet, Map<String, Integer> inventory, List<Cell> changed, List<Pos> removed, java.util.Set<Pos> footholds, SurvivalPolicy.Vitals vitals, List<ItemObservation.Drop> drops, List<Pos> refreshed) {
		public Observation { inventory = Map.copyOf(inventory); changed = List.copyOf(changed); removed = List.copyOf(removed); footholds = java.util.Set.copyOf(footholds); drops = List.copyOf(drops); refreshed = refreshed == null ? List.of() : List.copyOf(refreshed); }
		public Observation(Pose eye, Pos feet, Map<String, Integer> inventory, List<Cell> changed, List<Pos> removed, java.util.Set<Pos> footholds, SurvivalPolicy.Vitals vitals) { this(eye,feet,inventory,changed,removed,footholds,vitals,List.of(),List.of()); }
	}
	public record Reply(Token token, String kind, Outcome outcome) {
		Feedback decode() {
			return switch (kind) {
				case "finished" -> new Finished(token, java.util.Objects.requireNonNull(outcome));
				case "released" -> new Released(token);
				default -> throw new IllegalArgumentException("Unknown reply kind " + kind);
			};
		}
	}
	public record Turn(String type, long sequence, long tick, Observation observation, List<Reply> feedback,
		String cancellation, List<String> effects, List<Event> events, String outcome) {
		public Turn { feedback = List.copyOf(feedback); effects = List.copyOf(effects); events = List.copyOf(events); }
	}

	public static Header header(State<Task> state) {
		Task task = state.stack().getFirst().task();
		return new Header("begin", FORMAT_VERSION, METHOD_VERSION, state.session(), state.run(), task.count(), task.origin(), state.lastTick(), LIMITS);
	}
	public static Turn turn(long sequence, World previous, World current, List<Feedback> feedback, String cancellation, Step<?, VoxelCommand> step) {
		Observation observation = observation(previous, current, step.state().lastTick());
		return new Turn("turn", sequence, step.state().lastTick(), observation,
			feedback.stream().map(reply -> reply instanceof Finished finished ? new Reply(reply.token(), "finished", finished.outcome())
				: new Reply(reply.token(), "released", null)).toList(), cancellation,
			step.effects().stream().map(Object::toString).toList(), step.events(), step.state().outcome().map(Outcome::toString).orElse(""));
	}

	/** Only current-tick timestamp changes can omit the otherwise identical cell value. */
	public static Observation observation(World previous, World current, long tick) {
		if (current == null) return null;
		Map<Pos, Seen> before = previous == null ? Map.of() : previous.known();
		var changed = new java.util.ArrayList<Cell>();
		var refreshed = new java.util.ArrayList<Pos>();
		current.known().forEach((pos, seen) -> {
			Seen old = before.get(pos);
			if (seen.equals(old)) return;
			if (old != null && seen.tick() == tick && atTick(old, tick).equals(seen)) refreshed.add(pos);
			else changed.add(new Cell(pos, seen));
		});
		return new Observation(current.eye(), current.feet(), current.inventory(), changed,
			before.keySet().stream().filter(pos -> !current.known().containsKey(pos)).toList(),
			current.footholds(), current.vitals(), current.drops(), refreshed);
	}
	private static Seen atTick(Seen seen, long tick) {
		return new Seen(seen.blockId(), seen.empty(), seen.identified(), seen.fullSupport(), seen.light(), tick, seen.clearForBody(), seen.attachment());
	}
	/** Wire validation is separate from legacy standalone observation fixtures. */
	static void validateObservation(JsonObject row) {
		var observation = row.get("observation");
		if (observation == null || observation.isJsonNull()) return;
		var object = observation.getAsJsonObject();
		for (String field : List.of("changed", "removed", "refreshed"))
			if (!object.has(field) || !object.get(field).isJsonArray()) throw new IllegalArgumentException("Missing observation delta " + field);
	}
	public static World reconstruct(Observation observation, long tick, Map<Pos, Seen> known) {
		if (observation == null) { known.clear(); return null; }
		var touched = new java.util.HashSet<Pos>();
		for (Cell cell : observation.changed())
			if (cell.pos() == null || cell.seen() == null || !touched.add(cell.pos())) throw new IllegalArgumentException("Invalid or repeated changed cell");
		for (Pos pos : observation.removed())
			if (pos == null || !touched.add(pos)) throw new IllegalArgumentException("Conflicting removed cell");
		for (Pos pos : observation.refreshed())
			if (pos == null || !touched.add(pos) || !known.containsKey(pos)) throw new IllegalArgumentException("Unknown or conflicting refreshed cell");
		observation.removed().forEach(known::remove);
		observation.changed().forEach(cell -> known.put(cell.pos(), cell.seen()));
		observation.refreshed().forEach(pos -> known.put(pos, atTick(known.get(pos), tick)));
		return new World(observation.eye(), observation.feet(), observation.inventory(), known, observation.footholds(), observation.vitals(), observation.drops());
	}

	/** Streaming replay checks complete input coverage and compares effects, events, and terminal outcome. */
	public static final class Replay {
		private TaskKernel<Task, World, VoxelCommand> kernel;
		private State<Task> state;
		private final Map<Pos, Seen> known = new HashMap<>();
		private long rows;
		private long sequence;
		private boolean ended;

		public void accept(JsonObject row) {
			if (ended) throw new IllegalArgumentException("Data after recording footer");
			String type = row.get("type").getAsString();
			if (type.equals("begin")) {
				if (state != null || rows != 0) throw new IllegalArgumentException("Repeated recording header");
				Header header = GSON.fromJson(row, Header.class);
				if (header.version() != FORMAT_VERSION || !METHOD_VERSION.equals(header.methodVersion())) throw new IllegalArgumentException("Unsupported recording version");
				kernel = new TaskKernel<>(new StoneAcquisition(), header.limits());
				state = kernel.begin(header.session(), header.run(), Task.begin(header.count(), header.origin()), header.tick());
			}
			else if (type.equals("turn")) {
				if (state == null) throw new IllegalArgumentException("Missing recording header");
				validateObservation(row);
				Turn turn = GSON.fromJson(row, Turn.class);
				if (turn.sequence() != ++sequence) throw new IllegalArgumentException("Missing or reordered turn at " + sequence);
				if (turn.tick() != state.lastTick() + 1) throw new IllegalArgumentException("Missing decision tick at " + turn.tick());
				World world = reconstruct(turn.observation(), turn.tick(), known);
				var replay = kernel.advance(state, world, turn.feedback().stream().map(Reply::decode).toList(), turn.tick(), Optional.ofNullable(turn.cancellation()));
				if (!turn.effects().equals(replay.effects().stream().map(Object::toString).toList()) || !turn.events().equals(replay.events())
					|| !turn.outcome().equals(replay.state().outcome().map(Outcome::toString).orElse(""))) {
					throw new IllegalArgumentException("Decision mismatch at tick " + turn.tick());
				}
				state = replay.state();
			}
			else if (type.equals("end")) {
				if (row.get("rows").getAsLong() != rows || state == null || state.outcome().isEmpty()) {
					throw new IllegalArgumentException("Incomplete recording or missing terminal outcome");
				}
				ended = true;
				return;
			}
			else throw new IllegalArgumentException("Incomplete or unknown recording row: " + type);
			rows++;
		}
		public Outcome finish() {
			if (!ended) throw new IllegalArgumentException("Missing recording footer");
			return state.outcome().orElseThrow();
		}
		public long turns() { return sequence; }
	}

	public static void main(String[] args) throws IOException {
		if (args.length != 1) throw new IllegalArgumentException("Expected one system-one-decisions.jsonl.gz path");
		Replay replay = new Replay();
		try (var reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(Path.of(args[0]))), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) replay.accept(GSON.fromJson(line, JsonObject.class));
		}
		System.out.println("Replay verified: " + replay.turns() + " turns; " + replay.finish());
	}
}
