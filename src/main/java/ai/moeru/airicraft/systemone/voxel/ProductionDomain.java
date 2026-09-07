package ai.moeru.airicraft.systemone.voxel;

import ai.moeru.airicraft.systemone.TaskKernel;
import java.util.*;
import java.util.stream.Collectors;

import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionKnowledge.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelCommand.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import ai.moeru.airicraft.systemone.voxel.StoneAcquisition.World;

/** Reactive production. Recipes provide alternatives; the task kernel owns every dependency and command. */
public final class ProductionDomain implements TaskKernel.Domain<ProductionDomain.Task, World, VoxelCommand> {
	public sealed interface Task permits Acquire, Excavate, Gather, Station {}
	public record Acquire(String item, int count, Map<String, Integer> reserved, Set<String> ancestors, Set<String> failed, String method) implements Task {
		public Acquire { reserved = Map.copyOf(reserved); ancestors = Set.copyOf(ancestors); failed = Set.copyOf(failed); if (count < 1) throw new IllegalArgumentException("Positive quantity required"); }
		public static Acquire root(String item, int count) { return new Acquire(item, count, Map.of(), Set.of(), Set.of(), ""); }
	}
	public record Excavate(StoneAcquisition.Task state) implements Task {}
	public record Gather(Harvest rule, int count, Pos origin, int scans, Set<Pos> rejected, Set<Pos> visited, VoxelCommand last) implements Task {
		public Gather { rejected = Set.copyOf(rejected); visited = Set.copyOf(visited); }
	}
	public record Station(String item, Map<String, Integer> reserved, Set<String> ancestors, int scans, Set<Pos> rejected, VoxelCommand last) implements Task {
		public Station { reserved = Map.copyOf(reserved); ancestors = Set.copyOf(ancestors); rejected = Set.copyOf(rejected); }
	}
	private final Map<String, List<Recipe>> recipes;
	private final Map<String, Recipe> recipesById;
	private final Map<String, Harvest> harvesting;
	private final StoneAcquisition stone = new StoneAcquisition();

	public ProductionDomain(ProductionKnowledge knowledge) {
		recipes = knowledge.recipes().stream().collect(Collectors.groupingBy(Recipe::output));
		recipesById = knowledge.recipes().stream().collect(Collectors.toMap(Recipe::id, r -> r));
		harvesting = knowledge.harvesting().stream().collect(Collectors.toMap(Harvest::item, r -> r));
	}
	@Override public Decision<Task, VoxelCommand> decide(View<Task> view, World world) {
		return switch (view.task()) {
			case Acquire task -> acquire(view, task, world);
			case Station task -> station(view, task, world);
			case Gather task -> gather(view, task, world);
			case Excavate task -> {
				var result = stone.decide(new View<>(view.id(), task.state(), view.acting(), view.tick(), view.commandResult(), view.childResult()), world);
				if (result instanceof Execute<StoneAcquisition.Task, VoxelCommand> action) yield new Execute<>(new Excavate(action.continuation()), action.command());
				if (result instanceof Complete<StoneAcquisition.Task, VoxelCommand> done) yield new Complete<>(done.outcome());
				yield new Keep<>();
			}
		};
	}
	private Decision<Task, VoxelCommand> acquire(View<Task> view, Acquire task, World world) {
		if (free(world, task.reserved(), task.item()) >= task.count()) return success("inventory_observed:" + task.item() + ":" + task.count());
		if (view.acting()) return new Keep<>();
		if (task.ancestors().contains(task.item())) return failure("dependency_cycle:" + task.item());
		if (failed(view)) {
			var rejected = new HashSet<>(task.failed()); rejected.add(task.method());
			task = new Acquire(task.item(), task.count(), task.reserved(), task.ancestors(), rejected, "");
		}
		if (task.failed().size() >= 32) return failure("production_alternatives_exhausted:" + task.item());
		String harvestId = "harvest:" + task.item();
		if (harvesting.containsKey(task.item()) && !task.failed().contains(harvestId)) {
			var rule = harvesting.get(task.item());
			Acquire next = selected(task, harvestId);
			if (!rule.tools().isEmpty() && rule.tools().stream().noneMatch(tool -> world.inventory().getOrDefault(tool, 0) > 0)) {
				String tool = rule.tools().stream().min(Comparator.comparingDouble(id -> estimate(id, next.reserved(), world, ancestry(next), new int[]{256}))).orElseThrow();
				return new Child<>(next, dependency(next, tool, 1, next.reserved()), "tool_required:" + tool);
			}
			int target = task.count() + task.reserved().getOrDefault(task.item(), 0);
			Task child = rule.technique() == Technique.LOCAL_STONE ? new Excavate(StoneAcquisition.Task.begin(target, world.feet()))
				: new Gather(rule, target, world.feet(), 0, Set.of(), Set.of(), null);
			return new Child<>(next, child, harvestId);
		}
		Recipe recipe = recipesById.get(task.method());
		if (recipe == null) {
			Acquire current = task;
			record Ranked(Recipe recipe, double cost) {}
			recipe = recipes.getOrDefault(task.item(), List.of()).stream().filter(r -> !current.failed().contains(r.id()))
				.map(r -> new Ranked(r, recipeCost(r, current.reserved(), world, ancestry(current), new int[]{256})))
				.min(Comparator.comparingDouble(Ranked::cost).thenComparing(r -> r.recipe().id())).map(Ranked::recipe).orElse(null);
		}
		if (recipe == null) return failure("no_production_method:" + task.item() + " rejected=" + new TreeSet<>(task.failed()));
		Acquire next = selected(task, recipe.id());
		Map<String, Integer> needed = recipe.ingredients();
		for (var input : needed.entrySet()) {
			if (free(world, task.reserved(), input.getKey()) < input.getValue()) {
				return new Child<>(next, dependency(next, input.getKey(), input.getValue(), commitments(next.reserved(), needed, input.getKey(), world)), "ingredient:" + input.getKey());
			}
		}
		Pos station = null;
		if (recipe.width() == 3) {
			station = observedStation(world, "minecraft:crafting_table").orElse(null);
			if (station == null) return new Child<>(next, new Station("minecraft:crafting_table", commitments(next.reserved(), needed, "", world), ancestry(next), 0, Set.of(), null), "workstation_required");
		}
		return new Execute<>(next, new Craft(recipe, station));
	}

	private Decision<Task, VoxelCommand> station(View<Task> view, Station task, World world) {
		if (observedStation(world, task.item()).isPresent()) return success("station_observed:" + task.item());
		if (view.acting()) return new Keep<>();
		if (view.childResult().filter(o -> o.kind() != ResultKind.SUCCEEDED).isPresent()) return failure("station_supply_failed:" + task.item());
		var rejected = new HashSet<>(task.rejected());
		if (failed(view) && task.last() instanceof Place place) rejected.add(place.support());
		if (view.commandResult().isPresent() && task.last() instanceof Navigate move) rejected.add(move.stance());
		if (rejected.size() >= 16) return failure("station_recovery_exhausted");
		var remembered = world.known().entrySet().stream().filter(e -> e.getValue().identified() && e.getValue().blockId().equals(task.item()))
			.map(Map.Entry::getKey).sorted(positionOrder(world.eye())).toList();
		for (var target : remembered) {
			if (rejected.size() >= 8) break;
			Optional<Pos> approach = world.known().keySet().stream().filter(pos -> !rejected.contains(pos) && !pos.equals(world.feet()))
				.filter(pos -> Math.abs(pos.y() - target.y()) <= 1 && StoneAcquisition.standable(world.known(), pos))
				.filter(pos -> usableStation(world.known(), new Pose(pos.x() + .5, pos.y() + 1.62, pos.z() + .5, 0, 0), target))
				.sorted(positionOrder(world.eye())).findFirst();
			if (approach.isPresent()) {
				var move = new Navigate(approach.get(), 24, 200);
				return new Execute<>(new Station(task.item(), task.reserved(), task.ancestors(), task.scans(), rejected, move), move);
			}
		}
		if (free(world, task.reserved(), task.item()) < 1) {
			return new Child<>(task, new Acquire(task.item(), 1, task.reserved(), task.ancestors(), Set.of(), ""), "station_item_required");
		}
		Optional<Pos> support = world.known().keySet().stream().filter(pos -> !rejected.contains(pos))
			.filter(pos -> StoneAcquisition.standable(world.known(), pos.offset(0, 1, 0)))
			.filter(pos -> !intersectsPlayer(world, pos.offset(0, 1, 0)) && distance(world.eye(), pos) <= 4.3 * 4.3)
			.sorted(positionOrder(world.eye())).findFirst();
		if (support.isPresent()) {
			var command = new Place(task.item(), support.get(), world.known().get(support.get()).blockId());
			return new Execute<>(new Station(task.item(), task.reserved(), task.ancestors(), task.scans(), rejected, command), command);
		}
		if (task.scans() >= 4) return failure("no_observed_station_support");
		var look = new Look((float) ((world.eye().yaw() + 90) % 360), 55);
		return new Execute<>(new Station(task.item(), task.reserved(), task.ancestors(), task.scans() + 1, rejected, look), look);
	}

	private Decision<Task, VoxelCommand> gather(View<Task> view, Gather task, World world) {
		if (world.inventory().getOrDefault(task.rule().item(), 0) >= task.count()) return success("harvest_inventory_observed:" + task.rule().item());
		if (view.acting()) return new Keep<>();
		var rejected = new HashSet<>(task.rejected()); var visited = new HashSet<>(task.visited());
		if (failed(view)) {
			if (task.last() instanceof Break broken) rejected.add(broken.target());
			if (task.last() instanceof Navigate move) visited.add(move.stance());
		}
		else if (task.last() instanceof Break broken) {
			Pos pickup = new Pos(broken.target().x(), world.feet().y(), broken.target().z());
			if (!pickup.equals(world.feet()) && StoneAcquisition.standable(world.known(), pickup)) return gatherAction(task, new Navigate(pickup, 24, 200), 0, rejected, visited);
		}
		if (rejected.size() + visited.size() >= 24) return failure("harvest_search_exhausted:" + task.rule().item());
		var targets = world.known().entrySet().stream().filter(e -> e.getValue().identified() && task.rule().blocks().contains(e.getValue().blockId()))
			.filter(e -> !rejected.contains(e.getKey()) && !e.getKey().equals(world.feet().offset(0, -1, 0)))
			.sorted(Map.Entry.comparingByKey(positionOrder(world.eye()))).toList();
		for (var target : targets) {
			if (distance(world.eye(), target.getKey()) <= 4.3 * 4.3) return gatherAction(task, new Break(target.getKey(), target.getValue().blockId()), task.scans(), rejected, visited);
			Optional<Pos> stance = world.known().keySet().stream().filter(pos -> StoneAcquisition.standable(world.known(), pos) && !visited.contains(pos))
				.filter(pos -> Math.abs(pos.x() - target.getKey().x()) + Math.abs(pos.z() - target.getKey().z()) <= 2)
				.sorted(positionOrder(world.eye())).findFirst();
			if (stance.isPresent()) { visited.add(stance.get()); return gatherAction(task, new Navigate(stance.get(), 24, 200), 0, rejected, visited); }
		}
		if (task.scans() < 4) return gatherAction(task, new Look((float) ((world.eye().yaw() + 90) % 360), 15), task.scans() + 1, rejected, visited);
		Optional<Pos> frontier = world.known().keySet().stream().filter(pos -> StoneAcquisition.standable(world.known(), pos) && !visited.contains(pos))
			.filter(pos -> horizontal(world.feet(), pos) >= 4 && horizontal(task.origin(), pos) <= 32 * 32)
			.sorted(positionOrder(world.eye())).findFirst();
		if (frontier.isEmpty()) return failure("no_observed_harvest_frontier:" + task.rule().item());
		visited.add(frontier.get());
		return gatherAction(task, new Navigate(frontier.get(), 24, 200), 0, rejected, visited);
	}
	private Execute<Task, VoxelCommand> gatherAction(Gather task, VoxelCommand action, int scans, Set<Pos> rejected, Set<Pos> visited) {
		return new Execute<>(new Gather(task.rule(), task.count(), task.origin(), scans, rejected, visited, action), action);
	}
	private Optional<Pos> observedStation(World world, String block) {
		return world.known().entrySet().stream().filter(e -> e.getValue().identified() && e.getValue().blockId().equals(block) && usableStation(world.known(), world.eye(), e.getKey()))
			.map(Map.Entry::getKey).sorted(positionOrder(world.eye())).findFirst();
	}
	private static boolean usableStation(Map<Pos, Seen> known, Pose eye, Pos target) {
		if (distance(eye, target) > 4.3 * 4.3) return false;
		double dx = target.x() + .5 - eye.x(), dy = target.y() + .5 - eye.y(), dz = target.z() + .5 - eye.z();
		var aimed = new Pose(eye.x(), eye.y(), eye.z(), Math.toDegrees(Math.atan2(-dx, dz)), -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
		return VoxelObservation.observe(pos -> {
			var seen = known.get(pos);
			return seen == null ? new Sample("unknown", false, 0) : new Sample(seen.blockId(), seen.empty(), 15);
		}, aimed, new Lens(4.5, 1, 1, 1, 0), 0).containsKey(target);
	}
	private static boolean intersectsPlayer(World world, Pos pos) {
		return Math.abs(world.eye().x() - pos.x() - .5) < .8 && Math.abs(world.eye().z() - pos.z() - .5) < .8
			&& pos.y() <= world.eye().y() && pos.y() + 1 > world.feet().y();
	}
	private static Map<String, Integer> commitments(Map<String, Integer> reserved, Map<String, Integer> needed, String acquiring, World world) {
		var result = new TreeMap<>(reserved);
		needed.forEach((item, count) -> { if (!item.equals(acquiring)) result.merge(item, Math.min(count, Math.max(0, free(world, reserved, item))), Integer::sum); });
		return result;
	}
	private static Set<String> ancestry(Acquire task) { var result = new HashSet<>(task.ancestors()); result.add(task.item()); return result; }
	private static Acquire dependency(Acquire task, String item, int count, Map<String, Integer> reserved) { return new Acquire(item, count, reserved, ancestry(task), Set.of(), ""); }
	private static Acquire selected(Acquire task, String method) { return new Acquire(task.item(), task.count(), task.reserved(), task.ancestors(), task.failed(), method); }
	private double estimate(String item, Map<String, Integer> reserved, World world, Set<String> trail, int[] budget) {
		if (free(world, reserved, item) > 0) return 0;
		if (--budget[0] <= 0 || trail.contains(item) || trail.size() >= 12) return 1e6;
		var next = new HashSet<>(trail); next.add(item);
		var harvest = harvesting.get(item);
		double best = harvest == null ? 1e6 : world.known().entrySet().stream()
			.filter(entry -> entry.getValue().identified() && harvest.blocks().contains(entry.getValue().blockId()))
			.mapToDouble(entry -> 5 + Math.sqrt(distance(world.eye(), entry.getKey()))).min().orElse(30);
		for (var recipe : recipes.getOrDefault(item, List.of())) best = Math.min(best, recipeCost(recipe, reserved, world, next, budget) / recipe.yield());
		return best;
	}
	private double recipeCost(Recipe recipe, Map<String, Integer> reserved, World world, Set<String> trail, int[] budget) {
		double cost = recipe.width() == 3 ? 2 : 1;
		for (var entry : recipe.ingredients().entrySet()) {
			int missing = Math.max(0, entry.getValue() - free(world, reserved, entry.getKey()));
			if (missing > 0) cost += missing * (1 + estimate(entry.getKey(), reserved, world, trail, budget));
		}
		return cost;
	}
	private static int free(World world, Map<String, Integer> reserved, String item) { return world.inventory().getOrDefault(item, 0) - reserved.getOrDefault(item, 0); }
	private static boolean failed(View<Task> view) { return view.commandResult().or(() -> view.childResult()).filter(o -> o.kind() != ResultKind.SUCCEEDED).isPresent(); }
	private static Complete<Task, VoxelCommand> success(String evidence) { return new Complete<>(Outcome.success(evidence)); }
	private static Complete<Task, VoxelCommand> failure(String reason) { return new Complete<>(Outcome.failure(reason)); }
	private static Comparator<Pos> positionOrder(Pose eye) { return Comparator.<Pos>comparingDouble(pos -> distance(eye, pos)).thenComparingInt(Pos::x).thenComparingInt(Pos::y).thenComparingInt(Pos::z); }
	private static double distance(Pose eye, Pos pos) { return Math.pow(eye.x() - pos.x() - .5, 2) + Math.pow(eye.y() - pos.y() - .5, 2) + Math.pow(eye.z() - pos.z() - .5, 2); }
	private static double horizontal(Pos a, Pos b) { return Math.pow(a.x() - b.x(), 2) + Math.pow(a.z() - b.z(), 2); }
}
