import ai.moeru.airicraft.systemone.TaskKernel;
import ai.moeru.airicraft.systemone.voxel.*;
import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Checks observed ancestor completion and acknowledged release from complete production recordings. */
public class ObservedCompletionAudit {
 record Pending(long goal,TaskKernel.Token token,long tick,String item,int count,int available) {}
 public static void main(String[] args)throws Exception {
  var gson=new Gson();var replay=new ProductionTape.Replay();var stateField=ProductionTape.Replay.class.getDeclaredField("state");stateField.setAccessible(true);
  Pending pending=null;var completed=new ArrayList<Object>();
  try(var reader=new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(args[0]))))) {
   for(String line;(line=reader.readLine())!=null;) {
    var row=JsonParser.parseString(line).getAsJsonObject();var before=(TaskKernel.State<?>)stateField.get(replay);replay.accept(row);
    if(!row.get("type").getAsString().equals("turn"))continue;
    var turn=gson.fromJson(row,ProductionTape.Turn.class);var after=(TaskKernel.State<?>)stateField.get(replay);
    if(pending!=null) {
     Pending request=pending;
     boolean released=turn.feedback().stream().anyMatch(f->f.token().equals(request.token()) && Set.of("finished","released").contains(f.kind()));
     if(!released && turn.effects().stream().anyMatch(e->e.startsWith("Start[")))throw new AssertionError("New command before ancestor-completion release");
     if(released) {
      int releaseIndex=-1,endIndex=-1;for(int i=0;i<turn.events().size();i++) {
       var e=turn.events().get(i);
       if(e.task()==request.token().task() && e.type().equals("command_released"))releaseIndex=i;
       if(e.task()==request.goal() && e.type().equals("task_ended") && e.detail().equals("SUCCEEDED:inventory_observed:"+request.item()+":"+request.count()))endIndex=i;
      }
      if(releaseIndex<0 || endIndex<=releaseIndex)throw new AssertionError("Goal did not retire after acknowledged release");
      if(after.stack().stream().anyMatch(f->f.id()==request.goal() || f.id()==request.token().task()))throw new AssertionError("Completed goal or descendant still active");
      completed.add(Map.of("goal",request.goal(),"descendant",request.token().task(),"item",request.item(),"count",request.count(),"available",request.available(),"requestedTick",request.tick(),"releasedTick",turn.tick()));pending=null;
     }
    }
    if(pending==null && !after.stack().isEmpty() && after.stack().getLast().phase() instanceof TaskKernel.Releasing<?> release
      && release.next().getClass().getSimpleName().equals("EndSubtree")) {
     long goalId=(long)release.next().getClass().getMethod("task").invoke(release.next());
     if(goalId==release.token().task())continue; // This audit requires a nested executor.
     var frame=after.stack().stream().filter(f->f.id()==goalId).findFirst().orElseThrow();
     if(!(frame.task() instanceof ProductionDomain.Acquire goal))throw new AssertionError("Expected acquisition completion");
     int available=turn.observation().inventory().getOrDefault(goal.item(),0)-goal.reserved().getOrDefault(goal.item(),0);
     if(available<goal.count())throw new AssertionError("Ancestor completed without observed inventory");
     if(!(before.stack().getLast().phase() instanceof TaskKernel.Acting<?> active) || !active.token().equals(release.token())
       || !turn.effects().equals(List.of(new TaskKernel.Stop<>(release.token()).toString())))throw new AssertionError("Completion did not stop its existing motor token");
     pending=new Pending(goalId,release.token(),turn.tick(),goal.item(),goal.count(),available);
    }
   }
  }
  var outcome=replay.finish();if(pending!=null || completed.isEmpty())throw new AssertionError("No acknowledged observed-ancestor completion");
  System.out.println(gson.toJson(Map.of("exactReplayTurns",replay.turns(),"completedAncestors",completed,"finalOutcome",outcome,
   "scope","Observed inventory, original motor token release and descendant retirement; no full perception audit or required dark-light trigger.")));
 }
}
