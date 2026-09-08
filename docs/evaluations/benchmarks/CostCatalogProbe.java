import ai.moeru.airicraft.systemone.*;import ai.moeru.airicraft.systemone.voxel.*;import com.google.gson.*;import java.io.*;import java.util.*;import java.util.zip.*;
public class CostCatalogProbe {
 public static void main(String[]args)throws Exception {
  var gson=new Gson();var replay=new ProductionTape.Replay();var sf=ProductionTape.Replay.class.getDeclaredField("state");sf.setAccessible(true);
  var known=new HashMap<VoxelObservation.Pos,VoxelObservation.Seen>();StoneAcquisition.World world=null;ProductionKnowledge book=null;
  try(var r=new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(args[0]))))) {
   for(String line;(line=r.readLine())!=null;){var row=JsonParser.parseString(line).getAsJsonObject();replay.accept(row);
    if(row.get("type").getAsString().equals("production_begin"))book=gson.fromJson(row,ProductionTape.Header.class).knowledge();
    if(row.get("type").getAsString().equals("turn")){var turn=gson.fromJson(row,ProductionTape.Turn.class);world=StoneTape.reconstruct(turn.observation(),turn.tick(),known);if(turn.tick()>=3552)break;}
   }
  }
  var state=(TaskKernel.State<?>)sf.get(replay);var task=(ProductionDomain.Acquire)state.stack().stream().map(TaskKernel.Frame::task).filter(t->t instanceof ProductionDomain.Acquire a && a.item().equals("minecraft:wooden_pickaxe")).findFirst().orElseThrow();
  java.nio.file.Files.writeString(java.nio.file.Path.of(args[1]),gson.toJson(Map.of("knowledge",book,"task",task,"observation",StoneTape.observation(null,world,3552),"tick",3552)));
  var domain=new ProductionDomain(book);var contextMethod=ProductionDomain.class.getDeclaredMethod("supplyContext",StoneAcquisition.World.class,Map.class);contextMethod.setAccessible(true);
  var context=contextMethod.invoke(null,world,task.reserved());var cost=ProductionDomain.class.getDeclaredMethod("recipeCost",ProductionKnowledge.Recipe.class,context.getClass(),Set.class,int[].class);cost.setAccessible(true);
  var trail=new HashSet<>(task.ancestors());trail.add(task.item());System.out.println("inventory="+world.inventory());
  for(var recipe:book.recipes())if(recipe.output().equals(task.item())) {
   for(int limit:new int[]{256,2048}) {int[]budget={limit};System.out.println(recipe.cells().getFirst().item()+" limit="+limit+" cost="+cost.invoke(domain,recipe,context,trail,budget)+" remaining="+budget[0]);}
  }
 }
}
