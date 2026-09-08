import ai.moeru.airicraft.systemone.TaskKernel;
import ai.moeru.airicraft.systemone.voxel.*;
import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Exact replay of coal-based lighting supply interrupting a partially stocked stone goal. */
public class LightingBootstrapAudit {
 public static void main(String[] args)throws Exception {
  var replay=new ProductionTape.Replay();var field=ProductionTape.Replay.class.getDeclaredField("state");field.setAccessible(true);
  var gson=new Gson();long requested=-1,resumed=-1,task=-1;String activity="";int coalBreaks=0,smeltStarts=0;boolean coalChoice=false;
  Map<String,Integer> stock=Map.of();
  try(var reader=new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(args[0]))))) {
   for(String line;(line=reader.readLine())!=null;) {
    var row=JsonParser.parseString(line).getAsJsonObject();replay.accept(row);
    if(!row.get("type").getAsString().equals("turn"))continue;
    var turn=gson.fromJson(row,ProductionTape.Turn.class);var state=(TaskKernel.State<?>)field.get(replay);
    for(var event:turn.events()) {
     if(event.detail().contains("dependency_cycle:minecraft:cobblestone"))throw new AssertionError("Lighting entered the suspended cobblestone cycle");
     if(event.type().equals("task_suspended") && event.detail().equals("lighting_supply") && requested<0) {
      requested=turn.tick();task=event.task();stock=turn.observation().inventory();
      int stone=stock.getOrDefault("minecraft:cobblestone",0);
      if(stone<=0 || stone>=8 || stock.getOrDefault("minecraft:furnace",0)>0 || stock.getOrDefault("minecraft:torch",0)>0)
       throw new AssertionError("Missing partial cobblestone/no-furnace bootstrap state");
      var frame=state.stack().stream().filter(f->f.id()==event.task()).findFirst().orElseThrow();
      if(!(frame.task() instanceof ProductionDomain.ResumeWork repair))throw new AssertionError("Missing retained work");
      activity=repair.saved().task().getClass().getSimpleName();
     }
    }
    if(requested>=0)for(var frame:state.stack()) {
     if(frame.task() instanceof ProductionDomain.Acquire acquire && acquire.item().equals("minecraft:coal") && acquire.ancestors().contains("minecraft:cobblestone"))coalChoice=true;
     if(frame.id()==task && frame.task().getClass().getSimpleName().equals(activity) && resumed<0) {
      if(turn.lighting().policyLight()<10)throw new AssertionError("Resumed work before restoring light");
      resumed=turn.tick();
     }
    }
    for(var effect:turn.effects())if(effect.startsWith("Start[")) {
     if(effect.contains("command=StartSmelt["))smeltStarts++;
     if(requested>=0 && resumed<0 && effect.contains("command=Break[") && effect.contains("expectedBlock=minecraft:coal_ore"))coalBreaks++;
    }
   }
  }
  var outcome=replay.finish();
  if(outcome.kind()!=TaskKernel.ResultKind.SUCCEEDED || requested<0 || resumed<=requested || !coalChoice || coalBreaks<1 || smeltStarts!=0)
   throw new AssertionError("Missing coal supply and same-task lighting resumption");
  System.out.println(gson.toJson(Map.of("exactReplayTurns",replay.turns(),"task",task,"activity",activity,"requestedTick",requested,"resumedTick",resumed,
   "inventoryAtRepair",stock,"coalBreaksDuringRepair",coalBreaks,"smeltStarts",smeltStarts,"outcome",outcome,
   "scope","Exact replay, observed inventory and lighting lifecycle; no complete perception or independent physical-action audit.")));
 }
}
