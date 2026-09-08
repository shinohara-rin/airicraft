import ai.moeru.airicraft.systemone.TaskKernel;
import ai.moeru.airicraft.systemone.voxel.*;
import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Audits the shared restoration window, or its injury revocation, using exact decision replay. */
public class MissionAllowanceAudit {
    public static void main(String[] args) throws Exception {
        boolean injury=args.length>1 && args[1].equals("injury");
        var replay=new ProductionTape.Replay(); var field=ProductionTape.Replay.class.getDeclaredField("state");field.setAccessible(true);
        var gson=new Gson(); var activities=new TreeMap<String,Integer>();var episodes=new HashSet<String>();
        TaskKernel.Token stopped=null;long injuryTick=-1,releaseTick=-1,deadline=-1;int startsAfterInjury=0;
        try(var reader=new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(args[0]))))) {
            for(String line;(line=reader.readLine())!=null;) {
                var row=JsonParser.parseString(line).getAsJsonObject();var before=(TaskKernel.State<?>)field.get(replay);replay.accept(row);
                if(!row.get("type").getAsString().equals("turn"))continue;
                var turn=gson.fromJson(row,ProductionTape.Turn.class);
                if(before!=null&&!before.stack().isEmpty()&&before.stack().getFirst().task() instanceof ProductionDomain.Mission mission&&turn.observation()!=null) {
                    var allowance=mission.light().policy().allowance();
                    if(allowance.isPresent()&&turn.observation().vitals().health()<allowance.get().observedHealth()&&injuryTick<0) {
                        if(!(before.stack().getLast().phase() instanceof TaskKernel.Acting<?> acting))throw new AssertionError("Injury did not interrupt an active command");
                        stopped=acting.token();injuryTick=turn.tick();deadline=allowance.get().expiresAt();
                        if(injuryTick>=deadline||!turn.effects().equals(List.of(new TaskKernel.Stop<>(stopped).toString())))throw new AssertionError("Injury did not revoke work before expiry");
                    }
                }
                if(stopped!=null) {
                    for(var reply:turn.feedback())if(reply.token().equals(stopped)&&Set.of("released","finished").contains(reply.kind()))releaseTick=turn.tick();
                    startsAfterInjury+=turn.effects().stream().filter(e->e.startsWith("Start[")).count();
                }
                for(var frame:turn.lighting().after())if(frame.allowance()!=null&&frame.continuation().contains("mission")) {
                    var allowance=frame.allowance();episodes.add(allowance.origin()+":"+allowance.expiresAt()+":"+allowance.purpose());
                    if(frame.activityPhase().equals("Acting")&&!frame.repairing()) {
                        if(turn.tick()>=allowance.expiresAt()||allowance.validity()!=LightingPolicy.Validity.ACTIVE)throw new AssertionError("This fixture kept acting after allowance expiry/revocation");
                        if(allowance.purpose()!=LightingPolicy.Purpose.LIGHT_RESTORATION)throw new AssertionError("Expected the explicit light-restoration window");
                        if(turn.lighting().policyLight()<7)activities.merge(frame.activity(),1,Integer::sum);
                    }
                }
            }
        }
        var outcome=replay.finish();
        if(injury) {
            if(injuryTick<0||releaseTick<injuryTick||startsAfterInjury!=0||outcome.kind()!=TaskKernel.ResultKind.FAILED||!outcome.evidence().contains("health_loss"))
                throw new AssertionError("Missing prompt injury termination without further work");
        } else if(outcome.kind()!=TaskKernel.ResultKind.SUCCEEDED||episodes.size()!=1||activities.size()<2||injuryTick>=0)throw new AssertionError("Missing successful cross-task allowance evidence");
        System.out.println(gson.toJson(Map.of("exactReplayTurns",replay.turns(),"outcome",outcome,"distinctEpisodes",episodes.size(),"actingDimSamplesByTask",activities,
            "observedInjuryTick",injuryTick,"releaseAcknowledgedTick",releaseTick,"originalAllowanceDeadline",deadline,"startsAfterInjury",startsAfterInjury,
            "scope","Recorded mission policy and lifecycle for these fixtures only; no full perception audit or physical-exposure certification.")));
    }
}
