import ai.moeru.airicraft.systemone.*;import ai.moeru.airicraft.systemone.voxel.*;import com.google.gson.*;import java.nio.file.*;import java.util.*;
public class CostSnapshotChoice {
 public static void main(String[]args)throws Exception {
  var gson=new Gson();var input=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
  var book=gson.fromJson(input.get("knowledge"),ProductionKnowledge.class);var old=gson.fromJson(input.get("task"),ProductionDomain.Acquire.class);
  var observation=gson.fromJson(input.get("observation"),StoneTape.Observation.class);var world=StoneTape.reconstruct(observation,input.get("tick").getAsLong(),new HashMap<>());
  var task=new ProductionDomain.Acquire(old.item(),old.count(),old.reserved(),old.ancestors(),Set.of(),"");
  var view=new TaskKernel.View<ProductionDomain.Task>(1,task,false,3552,Optional.empty(),Optional.empty());
  var choice=new ProductionDomain(book).decide(view,world);
  if(!(choice instanceof TaskKernel.Child<ProductionDomain.Task,VoxelCommand> child) || !(child.continuation() instanceof ProductionDomain.Acquire selected))throw new AssertionError("Expected ingredient child");
  System.out.println("version="+ProductionTape.METHOD_VERSION+" selected="+selected.method()+" inventory="+world.inventory());
  if(!selected.method().contains("item=minecraft:spruce_planks"))throw new AssertionError("Stocked spruce route was not selected");
 }
}
