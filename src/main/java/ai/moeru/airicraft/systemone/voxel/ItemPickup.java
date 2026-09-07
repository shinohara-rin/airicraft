package ai.moeru.airicraft.systemone.voxel;

import java.util.*;
import java.util.function.Predicate;
import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelCommand.*;
import ai.moeru.airicraft.systemone.voxel.StoneAcquisition.World;
import ai.moeru.airicraft.systemone.voxel.ItemObservation.Drop;

/** Follow an observed item, allow collection to settle, and require actual inventory progress. */
public final class ItemPickup {
	private ItemPickup() {}
	public sealed interface Progress permits Seeking, Moving, Settling, Inspecting {}
	public record Seeking() implements Progress {}
	public record Moving(Navigate command) implements Progress {}
	public record Settling(Pos stance, long until) implements Progress {}
	public record Inspecting(VoxelCommand command) implements Progress {}
	public record State(Drop target, int inventoryGoal, Set<Pos> tried, Progress progress, int work, long deadline) {
		public State { tried=Set.copyOf(tried); }
		public static State begin(Drop target,int inventoryGoal,long tick) { return new State(target,inventoryGoal,Set.of(),new Seeking(),0,tick+600); }
	}
	public sealed interface Decision permits Action, Wait, Done {}
	public record Action(State state,VoxelCommand command) implements Decision {}
	public record Wait(State state,long until) implements Decision {}
	public record Done(Outcome outcome) implements Decision {}
	public static VoxelCommand command(State state) {
		return state.progress() instanceof Moving move ? move.command() : state.progress() instanceof Inspecting inspect ? inspect.command() : null;
	}
	public static Decision advance(State state,World world,Optional<Outcome> feedback,long tick,Set<String> clearable,Predicate<Pos> eligible) {
		if (world.inventory().getOrDefault(state.target().item(),0)>=state.inventoryGoal()) return new Done(Outcome.success("item_pickup_inventory_observed"));
		Drop target=world.drops().stream().filter(d->d.id().equals(state.target().id()) && d.item().equals(state.target().item())).findFirst().orElse(state.target());
		if (tick>=state.deadline() || state.work()>=16) return new Done(Outcome.failure("item_pickup_budget_exhausted"));
		if (tick-target.tick()>200) return new Done(Outcome.failure("item_pickup_observation_expired"));
		var tried=new HashSet<>(state.tried());
		var old=state.target().position(); var point=target.position();
		if (Math.pow(old.x()-point.x(),2)+Math.pow(old.y()-point.y(),2)+Math.pow(old.z()-point.z(),2)>.25) tried.clear();
		Progress progress=state.progress();
		if (progress instanceof Moving move && feedback.isPresent()) {
			if (feedback.get().kind()==ResultKind.SUCCEEDED) progress=new Settling(move.command().stance(),tick+10);
			else { tried.add(move.command().stance()); progress=new Seeking(); }
		}
		if (progress instanceof Settling settling) {
			if (tick<settling.until()) return new Wait(new State(target,state.inventoryGoal(),tried,progress,state.work(),state.deadline()),settling.until());
			tried.add(settling.stance()); progress=new Seeking();
		}
		var candidate=world.known().keySet().stream()
			.filter(p->!tried.contains(p) && StoneAcquisition.standable(world.known(),p) && eligible.test(p) && eligible.test(p.offset(0,-1,0)))
			.filter(p->Math.abs(p.x()+.5-point.x())<=1 && Math.abs(p.z()+.5-point.z())<=1 && p.y()<=point.y()+.25 && point.y()-p.y()<=2)
			.min(Comparator.<Pos>comparingDouble(p->Math.pow(p.x()+.5-point.x(),2)+Math.pow(p.z()+.5-point.z(),2)+Math.pow(p.y()-point.y(),2))
				.thenComparingInt(Pos::x).thenComparingInt(Pos::y).thenComparingInt(Pos::z));
		VoxelCommand action;
		if (candidate.isPresent()) {
			var move=new Navigate(candidate.get(),24,200);action=move;progress=new Moving(move);
		} else {
			action=TerrainAccess.inspect(world,point.cell().offset(0,-1,0),clearable,eligible);
			if (progress instanceof Inspecting previous && (feedback.filter(o->o.kind()!=ResultKind.SUCCEEDED).isPresent() || previous.command().equals(action)))
				return new Done(Outcome.failure("item_pickup_stance_unobserved"));
			progress=new Inspecting(action);
		}
		return new Action(new State(target,state.inventoryGoal(),tried,progress,state.work()+1,state.deadline()),action);
	}
}
