import ai.moeru.airicraft.systemone.*;
import ai.moeru.airicraft.systemone.voxel.*;
import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Exports observed values after an exact replay prefix, never evaluator ground truth. */
public class AcquisitionSnapshot {
 public static void main(String[] args) throws Exception {
  var gson = new Gson(); var replay = new ProductionTape.Replay();
  var stateField = ProductionTape.Replay.class.getDeclaredField("state"); stateField.setAccessible(true);
  var known = new HashMap<VoxelObservation.Pos, VoxelObservation.Seen>();
  ProductionKnowledge book = null;
  long requestedTick = Long.parseLong(args[1]);
  try (var reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(args[0]))))) {
   for (String line; (line = reader.readLine()) != null;) {
    var row = JsonParser.parseString(line).getAsJsonObject(); replay.accept(row);
    String type = row.get("type").getAsString();
    if (type.equals("production_begin")) book = gson.fromJson(row, ProductionTape.Header.class).knowledge();
    if (!type.equals("turn")) continue;
    var turn = gson.fromJson(row, ProductionTape.Turn.class);
    var world = StoneTape.reconstruct(turn.observation(), turn.tick(), known);
    if (turn.tick() < requestedTick) continue;
    if (turn.tick() != requestedTick) throw new IllegalArgumentException("Requested tick is not recorded");
    var state = (TaskKernel.State<?>) stateField.get(replay);
    var task = state.stack().stream().map(TaskKernel.Frame::task)
     .filter(t -> t instanceof ProductionDomain.Acquire a && a.item().equals(args[2]))
     .map(ProductionDomain.Acquire.class::cast).findFirst().orElseThrow();
    Files.writeString(Path.of(args[3]), gson.toJson(Map.of("knowledge", book, "task", task,
     "observation", StoneTape.observation(null, world, turn.tick()), "tick", turn.tick(),
     "prefixReplayTurns", replay.turns(), "scope", "Observed values after exact prefix; selected method retained for explicit reconsideration.")));
    System.out.println("Exported acquisition " + task.item() + " at tick " + turn.tick() + " after " + replay.turns() + " exact decisions");
    return;
   }
  }
  throw new IllegalArgumentException("Requested tick is not recorded");
 }
}
