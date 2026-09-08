import ai.moeru.airicraft.systemone.TaskKernel;
import ai.moeru.airicraft.systemone.voxel.*;
import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Replay verifies unsupported initial feet and supported repositioning before the sole furnace load. */
public class WorkstationFootingAudit {
 public static void main(String[] args)throws Exception {
  var replay=new ProductionTape.Replay();var gson=new Gson();var known=new HashMap<VoxelObservation.Pos,VoxelObservation.Seen>();
  VoxelObservation.Pos initial=null,loaded=null;VoxelObservation.Seen initialFloor=null,loadedFloor=null;
  long firstTick=-1,loadTick=-1;int loads=0,movesBeforeLoad=0;
  try(var reader=new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(args[0]))))) {
   for(String line;(line=reader.readLine())!=null;) {
    var row=JsonParser.parseString(line).getAsJsonObject();replay.accept(row);
    if(!row.get("type").getAsString().equals("turn"))continue;
    var turn=gson.fromJson(row,ProductionTape.Turn.class);var world=StoneTape.reconstruct(turn.observation(),turn.tick(),known);
    if(world==null)continue;
    if(initial==null) {
     initial=world.feet();firstTick=turn.tick();initialFloor=known.get(initial.offset(0,-1,0));
     if(StoneAcquisition.standable(known,initial))throw new AssertionError("Initial stance was already supported");
    }
    for(var effect:turn.effects()) if(effect.startsWith("Start[")) {
     if(loads==0 && effect.contains("command=Navigate["))movesBeforeLoad++;
     if(effect.contains("command=StartSmelt[")) {
      if(!StoneAcquisition.standable(known,world.feet()))throw new AssertionError("Started furnace from unsupported feet");
      loads++;loadTick=turn.tick();loaded=world.feet();loadedFloor=known.get(loaded.offset(0,-1,0));
     }
    }
   }
  }
  var outcome=replay.finish();
  if(outcome.kind()!=TaskKernel.ResultKind.SUCCEEDED || loads!=1 || movesBeforeLoad<1 || Objects.equals(initial,loaded))throw new AssertionError("Missing supported movement before the single load");
  if(args.length>1 && args[1].equals("initial-air") && (initialFloor==null || !initialFloor.empty()))throw new AssertionError("Initial air floor was not observed");
  var result=new LinkedHashMap<String,Object>();result.put("exactReplayTurns",replay.turns());result.put("initialTick",firstTick);result.put("initialFeet",initial);result.put("initialFloor",initialFloor);
  result.put("loadTick",loadTick);result.put("loadedFeet",loaded);result.put("loadedFloor",loadedFloor);result.put("loads",loads);result.put("navigationStartsBeforeLoad",movesBeforeLoad);
  result.put("outcome",outcome);result.put("scope","Recorded standing support and movement before loading; no physical velocity or full perception audit.");System.out.println(gson.toJson(result));
 }
}
