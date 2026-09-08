import ai.moeru.airicraft.systemone.TaskKernel;
import ai.moeru.airicraft.systemone.voxel.*;
import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Checks completed cobblestone accounting before a dim-light repair; later goal success is separate. */
public class GoalSettlementAudit {
 public static void main(String[] args)throws Exception {
  var gson=new Gson();var replay=new ProductionTape.Replay();var field=ProductionTape.Replay.class.getDeclaredField("state");field.setAccessible(true);
  long settled=-1,parent=-1,settledTick=-1,repairTick=-1;int stock=-1,count=-1,light=-1;LightingPolicy.Parameters parameters=null;
  try(var reader=new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(args[0]))))) {
   for(String line;(line=reader.readLine())!=null;) {
    var row=JsonParser.parseString(line).getAsJsonObject();var before=(TaskKernel.State<?>)field.get(replay);replay.accept(row);
    if(row.get("type").getAsString().equals("production_begin"))parameters=gson.fromJson(row,ProductionTape.Header.class).knowledge().lighting();
    if(!row.get("type").getAsString().equals("turn"))continue;
    var turn=gson.fromJson(row,ProductionTape.Turn.class);var after=(TaskKernel.State<?>)field.get(replay);
    for(var event:turn.events()) {
     if(settled<0 && event.type().equals("task_ended") && event.detail().startsWith("SUCCEEDED:inventory_observed:minecraft:cobblestone:") && turn.lighting().policyLight()<parameters.enterBelow()) {
      var frames=before.stack();int index=-1;for(int i=0;i<frames.size();i++)if(frames.get(i).id()==event.task())index=i;
      if(index<=0 || !(frames.get(index).task() instanceof ProductionDomain.Acquire goal))throw new AssertionError("Missing completed acquisition frame");
      stock=turn.observation().inventory().getOrDefault(goal.item(),0)-goal.reserved().getOrDefault(goal.item(),0);count=goal.count();
      if(stock<count)throw new AssertionError("Unfulfilled prerequisite was retired");
      if(turn.effects().stream().anyMatch(e->e.startsWith("Start["))) {
       if(!(frames.getLast().phase() instanceof TaskKernel.Releasing<?> releasing)
         || turn.feedback().stream().noneMatch(f->f.token().equals(releasing.token()) && Set.of("released","finished").contains(f.kind())))
        throw new AssertionError("New action before the completed goal's motor release");
       long retainedParent=frames.get(index-1).id();
       if(turn.events().stream().noneMatch(e->e.task()==retainedParent && e.type().equals("task_suspended") && e.detail().equals("lighting_supply")))
        throw new AssertionError("Parent acted before maintenance after release");
      }
      settled=event.task();parent=frames.get(index-1).id();settledTick=turn.tick();light=turn.lighting().policyLight();
      long removed=settled;if(after.stack().stream().anyMatch(f->f.id()==removed))throw new AssertionError("Completed frame still active");
     }
     if(settled>=0 && repairTick<0 && event.type().equals("task_suspended") && event.detail().equals("lighting_supply")) {
      if(event.task()!=parent || turn.tick()<settledTick)throw new AssertionError("Maintenance did not belong to the retained parent after settlement");
      var supply=after.stack().stream().map(TaskKernel.Frame::task).filter(t->t instanceof ProductionDomain.Acquire a && a.item().equals("minecraft:torch")).map(t->(ProductionDomain.Acquire)t).findFirst().orElseThrow();
      if(supply.ancestors().contains("minecraft:cobblestone"))throw new AssertionError("Repair inherited the retired prerequisite");
      repairTick=turn.tick();
     }
    }
   }
  }
  var outcome=replay.finish();if(settled<0 || repairTick<settledTick)throw new AssertionError("Missing dim-light completed-goal settlement before repair");
  System.out.println(gson.toJson(Map.of("exactReplayTurns",replay.turns(),"settledTask",settled,"parentTask",parent,"settledTick",settledTick,"repairTick",repairTick,
   "availableCobblestone",stock,"requiredCobblestone",count,"lightAtSettlement",light,"finalOutcome",outcome,
   "scope","Completed dependency and subsequent maintenance ordering only; final mission outcome is reported separately.")));
 }
}
