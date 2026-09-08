import ai.moeru.airicraft.systemone.TaskKernel;
import ai.moeru.airicraft.systemone.voxel.*;
import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Verifies a live gathering or passive furnace-wait lighting interruption through exact replay. */
public class MissionLightingAudit {
    public static void main(String[] args) throws Exception {
        var replay=new ProductionTape.Replay(); var field=ProductionTape.Replay.class.getDeclaredField("state");field.setAccessible(true);
        var gson=new Gson(); long frameId=-1, interrupted=-1, released=-1, resumed=-1, cookingDeadline=-1;
        String activity=""; TaskKernel.Token oldToken=null; boolean repaired=false;
        try(var reader=new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(args[0]))))) {
            for(String line;(line=reader.readLine())!=null;) {
                var row=JsonParser.parseString(line).getAsJsonObject(); var before=(TaskKernel.State<?>)field.get(replay); replay.accept(row);
                if(!row.get("type").getAsString().equals("turn"))continue;
                var turn=gson.fromJson(row,ProductionTape.Turn.class); var after=(TaskKernel.State<?>)field.get(replay);
                if(frameId<0) for(var frame:after.stack()) if(frame.task() instanceof ProductionDomain.ResumeWork repair) {
                    var saved=repair.saved().task();
                    if(!(saved instanceof ProductionDomain.Gather || saved instanceof ProductionDomain.CollectBatch))continue;
                    var previous=before.stack().stream().filter(v->v.id()==frame.id()).findFirst().orElseThrow();
                    frameId=frame.id();interrupted=turn.tick();activity=saved.getClass().getSimpleName();
                    if(turn.lighting().policyLight()>=7)throw new AssertionError("Repair was not requested in dim light");
                    if(saved instanceof ProductionDomain.CollectBatch cooking) {
                        if(!(previous.phase() instanceof TaskKernel.Sleeping<?>))throw new AssertionError("Furnace work was not passively waiting");
                        if(turn.effects().stream().anyMatch(e->e.startsWith("Stop[")))throw new AssertionError("Passive wait invented motor release");
                        cookingDeadline=cooking.deadline();
                    } else {
                        if(!(previous.phase() instanceof TaskKernel.Acting<?> acting))throw new AssertionError("Gather was not acting");
                        oldToken=acting.token();
                        if(!turn.effects().equals(List.of(new TaskKernel.Stop<>(oldToken).toString())))throw new AssertionError("Gather was not stopped before repair");
                    }
                }
                if(frameId<0)continue;
                for(var reply:turn.feedback())if(reply.token().equals(oldToken)&&Set.of("finished","released").contains(reply.kind()))released=turn.tick();
                if(oldToken!=null&&released<0&&turn.effects().stream().anyMatch(e->e.startsWith("Start[")))throw new AssertionError("Repair acted before release");
                repaired|=turn.events().stream().anyMatch(e->e.type().equals("task_resumed")&&e.detail().equals("SUCCEEDED:working_light_observed"));
                for(var frame:after.stack())if(frame.id()==frameId && frame.task().getClass().getSimpleName().equals(activity) && repaired) {
                    if(turn.lighting().policyLight()<10)throw new AssertionError("Resumed before working light recovered");
                    if(frame.task() instanceof ProductionDomain.CollectBatch cooking && cooking.deadline()!=cookingDeadline)throw new AssertionError("Cooking deadline was restarted");
                    if(resumed<0)resumed=turn.tick();
                }
            }
        }
        var outcome=replay.finish();
        if(outcome.kind()!=TaskKernel.ResultKind.SUCCEEDED||frameId<0||!repaired||resumed<interrupted||oldToken!=null&&released<interrupted)
            throw new AssertionError("Missing successful same-frame lighting repair and resumption");
        System.out.println(gson.toJson(Map.of("exactReplayTurns",replay.turns(),"activity",activity,"task",frameId,"interruptedTick",interrupted,
            "releaseTick",released,"resumedTick",resumed,"originalCookingDeadline",cookingDeadline,"outcome",outcome,
            "scope","Observed light and exact lifecycle replay. Does not audit hidden reads or independently prove every physical action.")));
    }
}
