import ai.moeru.airicraft.systemone.*;
import ai.moeru.airicraft.systemone.voxel.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Re-evaluates one recorded acquisition observation; this is not a full-run replay. */
public class SupplySnapshotChoice {
 public static void main(String[] args) throws Exception {
  var gson = new Gson();
  var input = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
  var book = gson.fromJson(input.get("knowledge"), ProductionKnowledge.class);
  var saved = gson.fromJson(input.get("task"), ProductionDomain.Acquire.class);
  long tick = input.get("tick").getAsLong();
  var observation = gson.fromJson(input.get("observation"), StoneTape.Observation.class);
  var world = StoneTape.reconstruct(observation, tick, new HashMap<>());
  var task = new ProductionDomain.Acquire(saved.item(), saved.count(), saved.reserved(), saved.ancestors(), saved.failed(), "");
  var view = new TaskKernel.View<ProductionDomain.Task>(1, task, false, tick, Optional.empty(), Optional.empty());
  var decision = new ProductionDomain(book).decide(view, world);
  if (!(decision instanceof TaskKernel.Child<ProductionDomain.Task, VoxelCommand> child)
    || !(child.continuation() instanceof ProductionDomain.Acquire selected)) throw new AssertionError("Expected an acquisition dependency");
  System.out.println(gson.toJson(Map.of("tick", tick, "item", task.item(), "selectedMethod", selected.method(), "dependency", child.child().toString())));
  if (!selected.method().contains(args[1])) throw new AssertionError("Expected method containing: " + args[1]);
 }
}
