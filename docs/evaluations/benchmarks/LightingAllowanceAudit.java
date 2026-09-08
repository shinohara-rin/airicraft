import ai.moeru.airicraft.systemone.TaskKernel;
import ai.moeru.airicraft.systemone.voxel.*;
import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Checks real reduced-light excavation using exact replay, feedback, and changed observations. */
public class LightingAllowanceAudit {
    private record Excavation(VoxelCommand.Break command, LightingPolicy.Allowance allowance, long startedTick) {}
    public static void main(String[] args) throws Exception {
        var replay = new ProductionTape.Replay();
        var stateField = ProductionTape.Replay.class.getDeclaredField("state");
        stateField.setAccessible(true);
        var gson = new Gson();
        var known = new HashMap<VoxelObservation.Pos,VoxelObservation.Seen>();
        var starts = new LinkedHashMap<TaskKernel.Token,Excavation>();
        var evidence = new ArrayList<Object>();
        var allowances = new HashSet<LightingPolicy.Allowance>();
        LightingPolicy policy = null;
        long activeDimTicks = 0;
        int confirmedBreaks = 0;
        try (var reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(args[0]))))) {
            for (String line; (line = reader.readLine()) != null;) {
                var row = JsonParser.parseString(line).getAsJsonObject();
                replay.accept(row);
                if (row.get("type").getAsString().equals("production_begin")) {
                    var header = gson.fromJson(row,ProductionTape.Header.class);
                    policy = new LightingPolicy(header.knowledge().lighting());
                }
                if (!row.get("type").getAsString().equals("turn")) continue;
                var turn = gson.fromJson(row,ProductionTape.Turn.class);
                var world = StoneTape.reconstruct(turn.observation(),turn.tick(),known);
                var state = (TaskKernel.State<?>) stateField.get(replay);
                for (var reply : turn.feedback()) {
                    if (!Set.of("finished", "released").contains(reply.kind())) continue;
                    var work = starts.remove(reply.token());
                    if (work == null) continue;
                    boolean commandSucceeded = reply.kind().equals("finished") && reply.outcome().kind() == TaskKernel.ResultKind.SUCCEEDED;
                    boolean searchObservedSurface = reply.kind().equals("released") && turn.events().stream().anyMatch(e ->
                        e.task() == reply.token().task() && e.type().equals("task_ended") && e.detail().startsWith("SUCCEEDED:resource_surface_observed:"));
                    if (!commandSucceeded && !searchObservedSurface) continue;
                    var now = world.known().get(work.command().target());
                    if (turn.tick() > work.allowance().expiresAt()) throw new AssertionError("Break completed beyond allowance deadline");
                    if (now == null || !now.empty() || now.tick() < work.startedTick()) throw new AssertionError("Break completion lacks an observed cleared cell");
                    confirmedBreaks++;
                    evidence.add(Map.of("finishedTick",turn.tick(),"target",work.command().target(),"observedEmptyAt",now.tick(),
                        "completionEvidence",commandSucceeded ? "command_finished" : "surface_observed_and_command_released"));
                }
                if (state.stack().isEmpty()) continue;
                var leaf = state.stack().getLast();
                if (!(leaf.task() instanceof ProductionDomain.Explore explore)
                    || !(leaf.phase() instanceof TaskKernel.Acting<?> acting)
                    || explore.light().allowance().isEmpty()) continue;
                var allowance = explore.light().allowance().orElseThrow();
                if (world == null || turn.lighting().policyLight() == null) throw new AssertionError("Missing active light observation");
                if (!policy.permits(allowance,world.feet(),turn.tick())) throw new AssertionError("Active exploration outside allowance");
                if (turn.lighting().policyLight() >= policy.parameters().enterBelow()) continue;
                activeDimTicks++;
                allowances.add(allowance);
                if (explore.search().last() instanceof VoxelCommand.Break broken
                    && turn.effects().contains(new TaskKernel.Start<>(acting.token(),broken).toString())) {
                    if (!policy.permits(allowance,broken.target(),turn.tick())) throw new AssertionError("Break target outside allowance");
                    starts.put(acting.token(),new Excavation(broken,allowance,turn.tick()));
                    evidence.add(Map.of("startedTick",turn.tick(),"policyLight",turn.lighting().policyLight(),
                        "allowance",allowance,"target",broken.target()));
                }
            }
        }
        var outcome = replay.finish();
        if (outcome.kind() != TaskKernel.ResultKind.SUCCEEDED || activeDimTicks == 0 || confirmedBreaks == 0)
            throw new AssertionError("Missing successful reduced-light excavation evidence");
        System.out.println(gson.toJson(Map.of("exactReplayTurns",replay.turns(),"outcome",outcome,
            "observedDimExploreTicks",activeDimTicks,"distinctAllowances",allowances.size(),"confirmedBreaks",confirmedBreaks,"evidence",evidence,
            "scope","Active Explore work only. Does not establish maintenance on other tasks or audit sensor/motor hidden reads.")));
    }
}
