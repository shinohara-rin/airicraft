import ai.moeru.airicraft.systemone.TaskKernel;
import ai.moeru.airicraft.systemone.voxel.*;
import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Exact replay of mission lighting supply, observed outward/return positions, and resumed exploration. */
public class MissionSupplyAudit {
    public static void main(String[] args) throws Exception {
        var replay=new ProductionTape.Replay();var field=ProductionTape.Replay.class.getDeclaredField("state");field.setAccessible(true);
        var gson=new Gson();long task=-1,resumedAt=-1;Object destination=null;
        var outward=new ArrayList<Object>();var returning=new ArrayList<Object>();
        try(var reader=new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(args[0]))))) {
            for(String line;(line=reader.readLine())!=null;) {
                var row=JsonParser.parseString(line).getAsJsonObject();replay.accept(row);
                if(!row.get("type").getAsString().equals("turn"))continue;
                var turn=gson.fromJson(row,ProductionTape.Turn.class);var state=(TaskKernel.State<?>)field.get(replay);
                for(var frame:state.stack())if(task<0 && frame.task() instanceof ProductionDomain.ResumeWork repair
                    && repair.repair()==LightingPolicy.Repair.SUPPLY && repair.saved().task() instanceof ProductionDomain.Explore explore) {
                    task=frame.id();destination=explore.search().destination().orElse(explore.search().position());
                }
                if(task<0||state.stack().isEmpty())continue;
                var leaf=state.stack().getLast();
                if(turn.effects().stream().anyMatch(e->e.startsWith("Start[")&&e.contains("command=Navigate["))) {
                    if(leaf.task() instanceof ProductionDomain.Resupply supply)outward.add(Map.of("tick",turn.tick(),"observedFeet",turn.observation().feet(),"target",supply.outward().last().orElseThrow()));
                    if(leaf.id()==task && leaf.task() instanceof ProductionDomain.ResumeWork repair && repair.repaired().filter(o->o.kind()==TaskKernel.ResultKind.SUCCEEDED).isPresent())
                        returning.add(Map.of("tick",turn.tick(),"observedFeet",turn.observation().feet(),"target",repair.returning().last().orElseThrow()));
                }
                if(leaf.id()==task && leaf.task() instanceof ProductionDomain.Explore && !returning.isEmpty() && resumedAt<0)resumedAt=turn.tick();
            }
        }
        var outcome=replay.finish();
        if(outcome.kind()!=TaskKernel.ResultKind.SUCCEEDED||outward.isEmpty()||returning.isEmpty()||resumedAt<0)throw new AssertionError("Missing successful supply trip, return and same-task exploration");
        System.out.println(gson.toJson(Map.of("exactReplayTurns",replay.turns(),"task",task,"interruptedDestination",destination,
            "outward",outward,"returning",returning,"sameTaskResumedAt",resumedAt,"outcome",outcome,
            "scope","Issued navigation, observed feet and exact lifecycle replay; no independent physics or hidden-read audit.")));
    }
}
