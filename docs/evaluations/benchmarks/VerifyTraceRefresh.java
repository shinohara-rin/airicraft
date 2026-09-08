import ai.moeru.airicraft.systemone.voxel.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Offline evidence check, not an agent input or supported migration command. Preserves incomplete footers. */
public class VerifyTraceRefresh {
 public static void main(String[] args) throws Exception {
  var gson=new Gson(); var known=new HashMap<Pos,Seen>(); var rebuilt=new HashMap<Pos,Seen>();
  StoneAcquisition.World previous=null; long turns=0, changed=0, refreshes=0, beforeBytes=0, afterBytes=0;
  try(var reader=new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(Path.of(args[0]))),StandardCharsets.UTF_8));
      var writer=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(Path.of(args[1]),StandardOpenOption.CREATE_NEW)),StandardCharsets.UTF_8))) {
   for(String line;(line=reader.readLine())!=null;) {
    beforeBytes+=(line+"\n").getBytes(StandardCharsets.UTF_8).length;
    var row=JsonParser.parseString(line).getAsJsonObject(); String type=row.get("type").getAsString();
    if(type.equals("production_begin")) {
     if(row.get("version").getAsInt()!=2) throw new IllegalArgumentException("Expected source production format 2");
     row.addProperty("version",ProductionTape.FORMAT_VERSION);
    } else if(type.equals("turn")) {
     var old=gson.fromJson(row,ProductionTape.Turn.class); turns++;
     // Historical format has full changed cells; reconstruct independently of the new decoder.
     StoneAcquisition.World current=null;
     if(old.observation()==null) known.clear();
     else {
      var o=old.observation();o.removed().forEach(known::remove);o.changed().forEach(c->known.put(c.pos(),c.seen()));
      current=new StoneAcquisition.World(o.eye(),o.feet(),o.inventory(),known,o.footholds(),o.vitals(),o.drops());
     }
     var delta=StoneTape.observation(previous,current,old.tick());
     // Include wire serialization/deserialization in the equality check.
     var wire=gson.toJsonTree(delta); var decoded=gson.fromJson(wire,StoneTape.Observation.class);
     if(!Objects.equals(current,StoneTape.reconstruct(decoded,old.tick(),rebuilt))) throw new AssertionError("Input mismatch at "+old.tick());
     if(delta!=null) {changed+=delta.changed().size();refreshes+=delta.refreshed().size();}
     row.add("observation",wire); previous=current;
    }
    String output=gson.toJson(row)+"\n";afterBytes+=output.getBytes(StandardCharsets.UTF_8).length;writer.write(output);
   }
  }
  System.out.println(gson.toJson(Map.of("turns",turns,"fullChangedCells",changed,"refreshedCells",refreshes,
   "allInputsEqual",true,"sourceRawBytes",beforeBytes,"compactRawBytes",afterBytes,
   "sourceGzipBytes",Files.size(Path.of(args[0])),"compactGzipBytes",Files.size(Path.of(args[1])),
   "scope","Offline exact input reconstruction. Decisions and footer preserved, including any incompleteness. No live throughput claim.")));
 }
}
