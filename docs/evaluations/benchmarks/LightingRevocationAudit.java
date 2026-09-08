import ai.moeru.airicraft.systemone.TaskKernel;
import ai.moeru.airicraft.systemone.voxel.*;
import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Exact replay plus observed injury, revoked work, release acknowledgement, and withdrawal. */
public class LightingRevocationAudit {
    public static void main(String[] args) throws Exception {
        var replay = new ProductionTape.Replay();
        var field = ProductionTape.Replay.class.getDeclaredField("state");
        field.setAccessible(true);
        var gson = new Gson();
        TaskKernel.Token stopped = null;
        long injuryTick = -1, releaseTick = -1, deadline = -1;
        float healthBefore = 0, healthAfter = 0;
        boolean withdrawal = false, reasonRecorded = false;
        try (var reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(args[0]))))) {
            for (String line; (line = reader.readLine()) != null;) {
                var row = JsonParser.parseString(line).getAsJsonObject();
                var before = (TaskKernel.State<?>) field.get(replay);
                replay.accept(row);
                if (!row.get("type").getAsString().equals("turn")) continue;
                var turn = gson.fromJson(row, ProductionTape.Turn.class);
                if (stopped != null) for (var reply : turn.feedback()) {
                    if (reply.token().equals(stopped) && Set.of("released", "finished").contains(reply.kind())) releaseTick = turn.tick();
                }
                if (injuryTick < 0 && before != null && !before.stack().isEmpty() && turn.observation() != null) {
                    var leaf = before.stack().getLast();
                    if (leaf.task() instanceof ProductionDomain.Explore explore
                        && leaf.phase() instanceof TaskKernel.Acting<?> acting && explore.light().allowance().isPresent()) {
                        var allowance = explore.light().allowance().orElseThrow();
                        var vitals = turn.observation().vitals();
                        if (vitals.life() == allowance.life() && vitals.health() < allowance.observedHealth()) {
                            if (turn.tick() >= allowance.expiresAt() || turn.lighting().policyLight() >= 7)
                                throw new AssertionError("Injury must occur during an unexpired dim-work allowance");
                            stopped = acting.token(); injuryTick = turn.tick(); deadline = allowance.expiresAt();
                            healthBefore = allowance.observedHealth(); healthAfter = vitals.health();
                            if (!turn.effects().equals(List.of(new TaskKernel.Stop<>(stopped).toString())))
                                throw new AssertionError("Injury did not stop exactly the active command");
                        }
                    }
                }
                if (injuryTick >= 0) {
                    if (releaseTick < 0 && turn.effects().stream().anyMatch(e -> e.startsWith("Start[")))
                        throw new AssertionError("New motor work started before release acknowledgement");
                    if (turn.effects().stream().anyMatch(e -> e.startsWith("Start[") && e.contains("expectedBlock=minecraft:coal_ore")))
                        throw new AssertionError("Coal mining continued after this fixture's withdrawal");
                    for (var event : turn.events()) {
                        if (event.type().equals("task_suspended") && event.detail().equals("lighting_retreat")) withdrawal = true;
                        if (event.type().equals("task_ended") && event.detail().contains("lighting_allowance_revoked:health_loss")) reasonRecorded = true;
                    }
                }
            }
        }
        var outcome = replay.finish();
        if (injuryTick < 0 || releaseTick < injuryTick || !withdrawal || !reasonRecorded || outcome.kind() != TaskKernel.ResultKind.FAILED)
            throw new AssertionError("Missing complete injury/release/withdrawal evidence");
        System.out.println(gson.toJson(Map.of("exactReplayTurns", replay.turns(), "observedInjuryTick", injuryTick,
            "healthBefore", healthBefore, "healthAfter", healthAfter, "originalAllowanceDeadline", deadline,
            "releaseAcknowledgedTick", releaseTick, "withdrawalRecorded", withdrawal, "revocationReasonRecorded", reasonRecorded,
            "outcome", outcome, "scope", "Injected active Explore injury only; no all-task lighting or full perception audit claim.")));
    }
}
