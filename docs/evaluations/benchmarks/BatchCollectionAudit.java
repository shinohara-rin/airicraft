import ai.moeru.airicraft.systemone.TaskKernel;
import ai.moeru.airicraft.systemone.voxel.*;
import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Exact replay and lifecycle audit for displacement during one furnace batch. */
public class BatchCollectionAudit {
    public static void main(String[] args) throws Exception {
        var replay=new ProductionTape.Replay();var field=ProductionTape.Replay.class.getDeclaredField("state");field.setAccessible(true);
        var gson=new Gson();long task=-1,deadline=-1,requested=-1,resumed=-1,failedCollection=-1;int loads=0,collections=0;
        ProductionDomain.CollectBatch original=null;VoxelObservation.Pos startFeet=null,endFeet=null;
        var navigation=new ArrayList<Object>();
        try(var reader=new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(args[0]))))) {
            for(String line;(line=reader.readLine())!=null;) {
                var row=JsonParser.parseString(line).getAsJsonObject();replay.accept(row);
                if(!row.get("type").getAsString().equals("turn"))continue;
                var turn=gson.fromJson(row,ProductionTape.Turn.class);var state=(TaskKernel.State<?>)field.get(replay);
                for(var frame:state.stack()) if(frame.task() instanceof ProductionDomain.CollectBatch batch) {
                    if(original==null) { original=batch;task=frame.id();deadline=batch.deadline(); }
                    if(frame.id()!=task || !batch.recipe().equals(original.recipe()) || !batch.station().equals(original.station()) || batch.before()!=original.before() || batch.deadline()!=deadline)
                        throw new AssertionError("Collection replaced or restarted the existing batch");
                }
                for(var event:turn.events()) {
                    if(event.type().equals("task_suspended") && event.detail().equals("restore_smelting_access")) {
                        if(event.task()!=task)throw new AssertionError("Different task requested restoration");
                        requested=turn.tick();startFeet=turn.observation().feet();
                    }
                    if(event.type().equals("task_resumed") && event.detail().equals("SUCCEEDED:smelting_access_restored")) {
                        if(event.task()!=task || turn.tick()>=deadline)throw new AssertionError("Different or expired batch resumed");
                        resumed=turn.tick();endFeet=turn.observation().feet();
                    }
                }
                for(var reply:turn.feedback()) if(reply.outcome()!=null && reply.outcome().evidence().equals("furnace_not_observed"))failedCollection=turn.tick();
                for(var effect:turn.effects()) if(effect.startsWith("Start[")) {
                    if(effect.contains("command=StartSmelt["))loads++;
                    if(effect.contains("command=CollectSmelt[")) {
                        collections++;
                        if(original==null || !effect.contains("command="+new VoxelCommand.CollectSmelt(original.recipe(),original.station())))throw new AssertionError("Collected from a different batch");
                    }
                    if(effect.contains("command=Navigate[") && state.stack().stream().anyMatch(f->f.task() instanceof ProductionDomain.BatchAccess))
                        navigation.add(Map.of("tick",turn.tick(),"observedFeet",turn.observation().feet(),"effect",effect));
                }
            }
        }
        var outcome=replay.finish();
        if(outcome.kind()!=TaskKernel.ResultKind.SUCCEEDED || loads!=1 || collections<2 || requested<0 || failedCollection<0 || resumed<requested || navigation.isEmpty() || Objects.equals(startFeet,endFeet))
            throw new AssertionError("Missing successful batch-preserving access restoration");
        var result=new LinkedHashMap<String,Object>();
        result.put("exactReplayTurns",replay.turns());result.put("task",task);result.put("batch",original);result.put("originalDeadline",deadline);
        result.put("loads",loads);result.put("collectionAttempts",collections);result.put("failedCollectionTick",failedCollection);
        result.put("restorationRequestedTick",requested);result.put("sameBatchResumedTick",resumed);result.put("displacedFeet",startFeet);result.put("restoredFeet",endFeet);
        result.put("navigation",navigation);result.put("outcome",outcome);result.put("scope","Exact decision replay, recorded movement and same-batch collection; no independent visual or complete perception audit.");
        System.out.println(gson.toJson(result));
    }
}
