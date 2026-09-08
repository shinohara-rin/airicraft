import ai.moeru.airicraft.systemone.voxel.*;
import ai.moeru.airicraft.systemone.TaskKernel;
import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Replay-backed inspection of actual outward and return commands for a tool repair. */
public class ToolResupplyAudit {
 public static void main(String[] args)throws Exception {
  var replay=new ProductionTape.Replay();var field=ProductionTape.Replay.class.getDeclaredField("state");field.setAccessible(true);
  var gson=new Gson();var outward=new ArrayList<Object>();var returning=new ArrayList<Object>();
  long repairedFrame=-1; boolean resumed=false;Object interruptedDestination=null;
  try(var reader=new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(args[0]))))) {
   for(String line;(line=reader.readLine())!=null;) {
    var row=JsonParser.parseString(line).getAsJsonObject(); replay.accept(row);
    if(!row.get("type").getAsString().equals("turn"))continue;
    var turn=gson.fromJson(row,ProductionTape.Turn.class); var state=(TaskKernel.State<?>)field.get(replay);
    for(var frame:state.stack()) if(frame.task() instanceof ProductionDomain.ResumeExplore resume && resume.repair() instanceof ProductionDomain.ToolRepair) {
     if(repairedFrame<0) {repairedFrame=frame.id();interruptedDestination=resume.saved().search().destination().orElseThrow();}
    }
    if(state.stack().isEmpty())continue;
    var leaf=state.stack().getLast();
    if(turn.effects().stream().anyMatch(e->e.startsWith("Start[")&&e.contains("command=Navigate["))) {
     if(leaf.task() instanceof ProductionDomain.Resupply supply)
      outward.add(Map.of("tick",turn.tick(),"feet",turn.observation().feet(),"target",supply.outward().last().orElseThrow()));
     if(leaf.id()==repairedFrame && leaf.task() instanceof ProductionDomain.ResumeExplore resume)
      returning.add(Map.of("tick",turn.tick(),"feet",turn.observation().feet(),"target",resume.returning().last().orElseThrow()));
     if(leaf.id()==repairedFrame && leaf.task() instanceof ProductionDomain.Explore) resumed=true;
    }
   }
  }
  if(replay.finish().kind()!=TaskKernel.ResultKind.SUCCEEDED || outward.isEmpty() || returning.isEmpty() || !resumed)
   throw new AssertionError("Missing successful outward/return/resumption evidence");
  System.out.println(gson.toJson(Map.of("exactReplayTurns",replay.turns(),"repairFrame",repairedFrame,
   "interruptedDestination",interruptedDestination,"outwardCommands",outward,"returnCommands",returning,"sameFrameResumedExploration",resumed,
   "outcome",replay.finish().toString(),"scope","Exact decision replay plus issued navigation and observed feet. Does not independently validate world visibility or all physical path segments.")));
 }
}
