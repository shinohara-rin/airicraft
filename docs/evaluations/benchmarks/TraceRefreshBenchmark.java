import ai.moeru.airicraft.systemone.voxel.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import com.google.gson.*;
import java.io.*;import java.util.*;import java.util.zip.*;
public class TraceRefreshBenchmark {
 record CompactObservation(Pose eye,Pos feet,Map<String,Integer> inventory,List<StoneTape.Cell> changed,List<Pos> refreshed,List<Pos> removed,Set<Pos> footholds,SurvivalPolicy.Vitals vitals,List<ItemObservation.Drop> drops) {}
 record CompactTurn(String type,long sequence,long tick,CompactObservation observation,List<StoneTape.Reply> feedback,String cancellation,List<String> effects,List<ai.moeru.airicraft.systemone.TaskKernel.Event> events,String outcome,ProductionTape.LightingTrace lighting,List<ProductionTape.SearchChange> search) {}
 static boolean same(Seen a,Seen b) {return a!=null&&a.blockId().equals(b.blockId())&&a.empty()==b.empty()&&a.identified()==b.identified()&&a.fullSupport()==b.fullSupport()&&a.light()==b.light()&&a.clearForBody()==b.clearForBody();}
 static Seen refresh(Seen a,long tick) {return new Seen(a.blockId(),a.empty(),a.identified(),a.fullSupport(),a.light(),tick,a.clearForBody());}
 record Measurement(long nanos,long rawBytes,long gzipBytes) {}
 static Measurement measure(List<Object> rows,Gson gson)throws Exception {
  long start=System.nanoTime(),raw=0;var bytes=new ByteArrayOutputStream();
  try(var out=new GZIPOutputStream(bytes)) {for(var row:rows) {var encoded=(gson.toJson(row)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);raw+=encoded.length;out.write(encoded);}}
  return new Measurement(System.nanoTime()-start,raw,bytes.size());
 }
 public static void main(String[] args)throws Exception {
  var gson=new Gson();var plain=new ArrayList<Object>();var compact=new ArrayList<Object>();var known=new HashMap<Pos,Seen>();var reconstructed=new HashMap<Pos,Seen>();long cells=0,refreshes=0,turns=0;
  try(var r=new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(args[0]))))) {
   for(String line;(line=r.readLine())!=null;) {
    var json=JsonParser.parseString(line).getAsJsonObject();String type=json.get("type").getAsString();
    if(type.equals("production_begin")) {var header=gson.fromJson(json,ProductionTape.Header.class);plain.add(header);compact.add(header);}
    else if(type.equals("turn")) {
     var t=gson.fromJson(json,ProductionTape.Turn.class);plain.add(t);turns++;
     CompactObservation o=null;
     if(t.observation()!=null) {
      var original=t.observation();var changed=new ArrayList<StoneTape.Cell>();var refreshed=new ArrayList<Pos>();
      for(var c:original.changed()) {cells++;if(c.seen().tick()==t.tick()&&same(known.get(c.pos()),c.seen())) {refreshed.add(c.pos());refreshes++;}else changed.add(c);known.put(c.pos(),c.seen());}
      original.removed().forEach(known::remove);
      original.removed().forEach(reconstructed::remove);
      for(var c:changed)reconstructed.put(c.pos(),c.seen());
      for(var p:refreshed)reconstructed.put(p,refresh(Objects.requireNonNull(reconstructed.get(p)),t.tick()));
      if(!known.equals(reconstructed))throw new AssertionError("Observation mismatch at "+t.tick());
      o=new CompactObservation(original.eye(),original.feet(),original.inventory(),changed,refreshed,original.removed(),original.footholds(),original.vitals(),original.drops());
     } else {known.clear();reconstructed.clear();}
     compact.add(new CompactTurn(t.type(),t.sequence(),t.tick(),o,t.feedback(),t.cancellation(),t.effects(),t.events(),t.outcome(),t.lighting(),t.search()));
    }
   }
  }
  var a=new Gson();var b=new Gson();for(int i=0;i<3;i++){measure(plain,a);measure(compact,b);}
  var baseline=new ArrayList<Measurement>();var proposed=new ArrayList<Measurement>();
  for(int i=0;i<10;i++){if(i%2==0){baseline.add(measure(plain,a));proposed.add(measure(compact,b));}else{proposed.add(measure(compact,b));baseline.add(measure(plain,a));}}
  System.out.println(gson.toJson(Map.of("turns",turns,"changedCells",cells,"timestampRefreshes",refreshes,"allReconstructedObservationsEqual",true,"baseline",baseline,"compactPrototype",proposed,"scope","Warm single-process Gson serialization plus gzip in memory; no disk, queue scheduling, or live throughput claim.")));
 }
}
