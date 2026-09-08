import ai.moeru.airicraft.systemone.TaskKernel;
import ai.moeru.airicraft.systemone.voxel.*;
import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Exact replay plus physical observation and ownership checks for the transient-light fixture. */
public class LightRegionAudit {
    public static void main(String[] args) throws Exception {
        var replay=new ProductionTape.Replay();var field=ProductionTape.Replay.class.getDeclaredField("state");field.setAccessible(true);
        var gson=new Gson();long task=-1,requested=-1,stopped=-1,released=-1,resumed=-1;int startZ=0,minZ=Integer.MAX_VALUE;
        boolean retreatStarted=false;TaskKernel.Token retreatToken=null;
        var known=new HashMap<VoxelObservation.Pos,VoxelObservation.Seen>();
        try(var reader=new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(args[0]))))) {
            for(String line;(line=reader.readLine())!=null;) {
                var row=JsonParser.parseString(line).getAsJsonObject();var before=(TaskKernel.State<?>)field.get(replay);replay.accept(row);
                if(!row.get("type").getAsString().equals("turn"))continue;
                var turn=gson.fromJson(row,ProductionTape.Turn.class);var after=(TaskKernel.State<?>)field.get(replay);
                var world=StoneTape.reconstruct(turn.observation(),turn.tick(),known);
                if(task<0)for(var frame:after.stack())if(frame.task() instanceof ProductionDomain.RegainLight recovery) {
                    var previous=before.stack().stream().filter(v->v.id()==frame.id()).findFirst().orElseThrow();
                    Object expected=previous.task() instanceof ProductionDomain.Restored restored ? restored.saved().task() : previous.task();
                    if(!expected.equals(recovery.saved().task()))throw new AssertionError("Interrupted task was replaced: "+previous.task().getClass().getSimpleName()+" -> "+recovery.saved().task().getClass().getSimpleName());
                    if(turn.effects().stream().anyMatch(e->e.startsWith("Start[")))throw new AssertionError("Prohibited work reached the motor");
                    task=frame.id();requested=turn.tick();startZ=world.feet().z();
                }
                if(task<0)continue;
                if(resumed<0)minZ=Math.min(minZ,world.feet().z());
                for(var frame:after.stack())if(frame.id()==task&&frame.task() instanceof ProductionDomain.RegainLight&&frame.phase() instanceof TaskKernel.Acting<?> acting) {
                    retreatToken=acting.token();retreatStarted=true;
                }
                if(retreatToken!=null) {
                    for(var effect:turn.effects())if(effect.equals(new TaskKernel.Stop<>(retreatToken).toString()))stopped=turn.tick();
                    for(var reply:turn.feedback())if(reply.token().equals(retreatToken)&&reply.kind().equals("released"))released=turn.tick();
                }
                if(turn.events().stream().anyMatch(e->e.type().equals("task_revised")&&e.detail().equals("working_light_regained"))) {
                    if(turn.lighting().policyLight()<10)throw new AssertionError("Resumed from stale light");
                    resumed=turn.tick();
                    long expectedTask=task;
                    if(turn.events().stream().noneMatch(e->e.type().equals("task_revised")&&e.detail().equals("working_light_regained")&&e.task()==expectedTask))throw new AssertionError("Resumed a different task");
                }
            }
        }
        var outcome=replay.finish();
        if(outcome.kind()!=TaskKernel.ResultKind.SUCCEEDED||task<0||!retreatStarted||minZ>=startZ||stopped<requested||released<stopped||resumed<released)
            throw new AssertionError("Missing successful observed retreat, release and light recovery");
        System.out.println(gson.toJson(Map.of("exactReplayTurns",replay.turns(),"task",task,"requestedTick",requested,"retreatStopTick",stopped,
            "releaseTick",released,"resumedTick",resumed,"retreatStartZ",startZ,"minimumRetreatZ",minZ,"outcome",outcome,
            "scope","Recorded task preservation, observed motion/light and release ordering. No complete perception audit.")));
    }
}
