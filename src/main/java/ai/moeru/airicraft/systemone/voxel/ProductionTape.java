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
import java.util.List;
import java.util.ArrayList;
import java.util.zip.GZIPInputStream;
import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionDomain.*;
import ai.moeru.airicraft.systemone.voxel.StoneAcquisition.World;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;

/** Production missions record their complete immutable recipe/prior catalog once at the boundary. */
public final class ProductionTape {
	public static final int FORMAT_VERSION = 3;
	public static final String METHOD_VERSION = "reactive-production-v77";
	private static final Gson GSON = new Gson();
	public record Header(String type, int version, String methodVersion, ProductionKnowledge knowledge,
		String session, String run, String item, int count, long tick, Limits limits, boolean mission, long life) {}
	public static Header header(State<Task> state, ProductionKnowledge knowledge) {
		Task root = state.stack().getFirst().task();
		boolean mission = root instanceof Mission;
		var goal = mission ? Acquire.root(((Mission) root).item(), ((Mission) root).count()) : (Acquire) root;
		return new Header("production_begin", FORMAT_VERSION, METHOD_VERSION, knowledge, state.session(), state.run(), goal.item(), goal.count(), state.lastTick(), StoneTape.LIMITS, mission, mission ? ((Mission) root).life() : 0);
	}
	/** Factual policy state, including suspended allowances; presence does not imply permission to act. */
	public record LightingFrame(long task, String phase, boolean leaf, List<String> continuation,
		boolean maintaining, List<LightingPolicy.Repair> failed, LightingPolicy.Allowance allowance, String activity, String activityPhase, boolean repairing) {}
	public record LightingTrace(Integer policyLight, List<LightingFrame> before, List<LightingFrame> after) {}
	public record Turn(String type, long sequence, long tick, StoneTape.Observation observation, List<StoneTape.Reply> feedback,
		String cancellation, List<String> effects, List<Event> events, String outcome, LightingTrace lighting, List<SearchChange> search) {}
	public static Turn turn(long sequence, World previous, World current, List<Feedback> feedback, String cancellation,
		State<Task> before, Step<Task, VoxelCommand> step) {
		var row = StoneTape.turn(sequence, previous, current, feedback, cancellation, step);
		return new Turn(row.type(), row.sequence(), row.tick(), row.observation(), row.feedback(), row.cancellation(),
			row.effects(), row.events(), row.outcome(), lightingTrace(before, step.state(), current), searchChanges(before, step.state()));
	}
	public record RejectionCell(Pos pos, UndergroundSearch.Geometry geometry) {}
	public record SearchChange(long task, String change, Pos candidate, UndergroundSearch.RejectionReason reason, List<RejectionCell> observed) {}
	static List<SearchChange> searchChanges(State<Task> before, State<Task> after) {
		var old = searchRejections(before); var current = searchRejections(after);
		var ids = new java.util.TreeSet<>(old.keySet()); ids.addAll(current.keySet());
		var order = java.util.Comparator.comparingInt(Pos::x).thenComparingInt(Pos::y).thenComparingInt(Pos::z);
		var changes = new ArrayList<SearchChange>();
		for (long id : ids) {
			var previous = old.getOrDefault(id, Map.of()); var next = current.getOrDefault(id, Map.of());
			var positions = new java.util.TreeSet<>(order); positions.addAll(previous.keySet()); positions.addAll(next.keySet());
			for (Pos pos : positions) {
				var a = previous.get(pos); var b = next.get(pos);
				if (java.util.Objects.equals(a,b)) continue;
				var evidence = b == null ? a : b;
				changes.add(new SearchChange(id,b == null ? "removed" : "rejected",pos,evidence.reason(),
					evidence.observed().entrySet().stream().sorted(Map.Entry.comparingByKey(order)).map(e->new RejectionCell(e.getKey(),e.getValue())).toList()));
			}
		}
		return List.copyOf(changes);
	}
	private static Map<Long,Map<Pos,UndergroundSearch.Rejection>> searchRejections(State<Task> state) {
		var result = new HashMap<Long,Map<Pos,UndergroundSearch.Rejection>>();
		for (var frame : state.stack()) {
			Task task = frame.task();
			while (task instanceof AfterEscape || task instanceof AfterAccess || task instanceof ResumeExplore || task instanceof ResumeWork || task instanceof Restored)
				task = task instanceof AfterEscape after ? after.saved() : task instanceof AfterAccess after ? after.saved() : task instanceof ResumeWork repair ? repair.saved().task() : task instanceof Restored restored ? restored.saved().task() : ((ResumeExplore)task).saved();
			if (task instanceof Explore explore) result.put(frame.id(),explore.search().rejected());
		}
		return result;
	}

	static LightingTrace lightingTrace(State<Task> before, State<Task> after, World world) {
		return new LightingTrace(world == null ? null : ProductionDomain.light(world), lighting(before), lighting(after));
	}
	static List<LightingFrame> lighting(State<Task> state) {
		var result = new ArrayList<LightingFrame>();
		for (int i = 0; i < state.stack().size(); i++) {
			var frame = state.stack().get(i); Task task = frame.task(); var path = new ArrayList<String>();
			while (true) {
				if (task instanceof AfterEscape saved) { path.add("survival_repair"); task = saved.saved(); }
				else if (task instanceof AfterAccess saved) { path.add("access_repair"); task = saved.saved(); }
				else if (task instanceof ResumeExplore saved) { path.add("resupply_return"); task = saved.saved(); }
				else if (task instanceof ResumeWork saved) { path.add("lighting_repair"); task = saved.saved().task(); }
				else if (task instanceof Restored saved) { path.add("restored"); task = saved.saved().task(); }
				else break;
			}
			if (task instanceof Mission mission) {
				path.add("mission"); var light = mission.light().policy();
				result.add(new LightingFrame(frame.id(), frame.phase().getClass().getSimpleName(), i == state.stack().size() - 1,
					List.copyOf(path), light.maintaining(), light.failed().stream().sorted().toList(), light.allowance().orElse(null),
					state.stack().getLast().task().getClass().getSimpleName(),state.stack().getLast().phase().getClass().getSimpleName(),
					state.stack().stream().anyMatch(v -> ProductionDomain.unwrap(v.task()) instanceof ResumeWork)));
			}
		}
		return List.copyOf(result);
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
					if (row.get("version").getAsInt() != FORMAT_VERSION || !METHOD_VERSION.equals(row.get("methodVersion").getAsString())) throw new IllegalArgumentException("Unsupported production version");
					var header = GSON.fromJson(row, Header.class);
					kernel = new TaskKernel<>(new ProductionDomain(header.knowledge()), header.limits());
					state = kernel.begin(header.session(), header.run(), header.mission() ? new Mission(header.item(), header.count(), header.life(), 0) : Acquire.root(header.item(), header.count()), header.tick());
				}
				case "turn" -> {
					if (state == null) throw new IllegalArgumentException("Missing production header");
					StoneTape.validateObservation(row);
					var turn = GSON.fromJson(row, StoneTape.Turn.class);
					if (turn.sequence() != ++sequence || turn.tick() != state.lastTick() + 1) throw new IllegalArgumentException("Missing or reordered production turn " + sequence);
					World world = StoneTape.reconstruct(turn.observation(), turn.tick(), known);
					var step = kernel.advance(state, world, turn.feedback().stream().map(StoneTape.Reply::decode).toList(), turn.tick(), Optional.ofNullable(turn.cancellation()));
					if (!GSON.toJsonTree(lightingTrace(state, step.state(), world)).equals(row.get("lighting"))) throw new IllegalArgumentException("Production lighting state mismatch at " + turn.tick());
					if (!GSON.toJsonTree(searchChanges(state, step.state())).equals(row.get("search"))) throw new IllegalArgumentException("Production search evidence mismatch at " + turn.tick());
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
