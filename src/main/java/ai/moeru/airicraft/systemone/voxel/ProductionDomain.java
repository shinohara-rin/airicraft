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
	public sealed interface Task permits Mission, Abandon, Escape, AfterEscape, Acquire, Excavate, Gather, Station, SmeltBatch, FinishSmeltStart, CollectBatch, Explore, ResumeExplore, PlaceLight, Retreat, AfterRetreat, Access, AfterAccess {}
	public record Access(TerrainAccess.State state, Set<String> clearable) implements Task { public Access { clearable = Set.copyOf(clearable); } }
	public record AfterAccess(Task saved) implements Task {}
	public record Mission(String item, int count, long life, int deaths) implements Task {}
	public record Abandon(String reason) implements Task {}
	public record Escape(Pos origin, Set<Pos> rejected, Optional<Pos> last, long deadline) implements Task {
		public Escape { rejected = Set.copyOf(rejected); }
	}
	public record AfterEscape(Task saved, Optional<Outcome> command, Optional<Outcome> child) implements Task {}
	public record Acquire(String item, int count, Map<String, Integer> reserved, Set<String> ancestors, Set<String> failed, String method) implements Task {
		public Acquire { reserved = Map.copyOf(reserved); ancestors = Set.copyOf(ancestors); failed = Set.copyOf(failed); if (count < 1) throw new IllegalArgumentException("Positive quantity required"); }
		public static Acquire root(String item, int count) { return new Acquire(item, count, Map.of(), Set.of(), Set.of(), ""); }
	}
	public record Excavate(StoneAcquisition.Task state) implements Task {}
	public record Gather(Harvest rule, int count, Pos origin, int scans, Set<Pos> rejected, Set<Pos> visited, VoxelCommand last, Set<Pos> drops) implements Task {
		public Gather { rejected = Set.copyOf(rejected); visited = Set.copyOf(visited); drops = Set.copyOf(drops); }
		public Gather(Harvest rule, int count, Pos origin, int scans, Set<Pos> rejected, Set<Pos> visited, VoxelCommand last) { this(rule, count, origin, scans, rejected, visited, last, Set.of()); }
	}
	public record Station(String item, Map<String, Integer> reserved, Set<String> ancestors, int scans, Set<Pos> rejected, VoxelCommand last) implements Task {
		public Station { reserved = Map.copyOf(reserved); ancestors = Set.copyOf(ancestors); rejected = Set.copyOf(rejected); }
	}
	public record SmeltBatch(Smelt recipe, Map<String, Integer> reserved, Set<String> ancestors, Set<String> rejectedFuel, String fuel) implements Task {
		public SmeltBatch { reserved = Map.copyOf(reserved); ancestors = Set.copyOf(ancestors); rejectedFuel = Set.copyOf(rejectedFuel); }
	}
	public record FinishSmeltStart(Smelt recipe, Pos station, int before) implements Task {}
	public record CollectBatch(Smelt recipe, Pos station, int before, long deadline, int attempts) implements Task {}
	public record Explore(UndergroundSearch.Task search, LightingPolicy.State light, Map<String, Integer> reserved, Set<String> ancestors) implements Task {
		public Explore { reserved = Map.copyOf(reserved); ancestors = Set.copyOf(ancestors); }
	}
	public record ResumeExplore(Explore saved, ReturnNavigation.State returning, LightingPolicy.Repair repair) implements Task {}
	public record PlaceLight(Set<Pos> rejected, Optional<Pos> last) implements Task { public PlaceLight { rejected = Set.copyOf(rejected); } }
	public record Retreat(ReturnNavigation.State returning) implements Task {}
	public record AfterRetreat(String reason) implements Task {}
	private final Map<String, List<Recipe>> recipes;
	private final Map<String, Recipe> recipesById;
	private final Map<String, Harvest> harvesting;
	private final Map<String, List<Smelt>> smelting;
	private final Map<String, Smelt> smeltsById;
	private final List<Fuel> fuels;
	private final Map<String, SearchPrior> searches;
	private final LightingPolicy lighting;
	private final SurvivalPolicy survival;
	private final UndergroundSearch underground = new UndergroundSearch();
	private final StoneAcquisition stone = new StoneAcquisition();

	public ProductionDomain(ProductionKnowledge knowledge) {
		recipes = knowledge.recipes().stream().collect(Collectors.groupingBy(Recipe::output));
		recipesById = knowledge.recipes().stream().collect(Collectors.toMap(Recipe::id, r -> r));
		harvesting = knowledge.harvesting().stream().collect(Collectors.toMap(Harvest::item, r -> r));
		smelting = knowledge.smelting().stream().collect(Collectors.groupingBy(Smelt::output));
		smeltsById = knowledge.smelting().stream().collect(Collectors.toMap(Smelt::id, r -> r));
		fuels = knowledge.fuels();
		searches = knowledge.searches().stream().collect(Collectors.toMap(SearchPrior::item, p -> p));
		lighting = new LightingPolicy(knowledge.lighting());
		survival = new SurvivalPolicy(knowledge.survival());
	}
	@Override public Optional<Outcome> completion(Task root, World world) {
		if (world == null || !world.vitals().alive() || survival.urgent(world)) return Optional.empty();
		while (root instanceof AfterEscape after) root = after.saved();
		if (root instanceof Mission mission) root = Acquire.root(mission.item(), mission.count());
		if (world != null && root instanceof Acquire goal && free(world, goal.reserved(), goal.item()) >= goal.count()) {
			return Optional.of(Outcome.success("inventory_observed:" + goal.item() + ":" + goal.count()));
		}
		return Optional.empty();
	}
	@Override public Optional<Interruption<Task>> interrupt(List<View<Task>> branch, World world) {
		if (world == null || !survival.urgent(world) || branch.stream().anyMatch(v -> v.task() instanceof Escape || v.task() instanceof AfterEscape || v.task() instanceof Abandon)) return Optional.empty();
		var leaf = branch.getLast();
		var command = leaf.acting() ? Optional.of(Outcome.cancelled("survival_interruption")) : leaf.commandResult();
		Task saved = leaf.task();
		if (leaf.acting() && saved instanceof Gather gather) {
			// Preemption is not evidence that the resource target itself failed. Revalidate it after escape.
			saved = new Gather(gather.rule(), gather.count(), gather.origin(), gather.scans(), gather.rejected(), gather.visited(), null, gather.drops());
			command = Optional.empty();
		}
		return Optional.of(new Interruption<>(new AfterEscape(saved, command, leaf.childResult()),
			new Escape(world.feet(), Set.of(), Optional.empty(), leaf.tick() + survival.parameters().escapeTicks()), "survival_escape"));
	}
	@Override public Optional<Revision<Task>> reconsider(List<View<Task>> branch, World world) {
		if (world == null) return Optional.empty();
		var root = branch.getFirst();
		Task rootTask = root.task(); while (rootTask instanceof AfterEscape after) rootTask = after.saved();
		if (rootTask instanceof Mission mission) {
			if (world.vitals().life() != mission.life()) {
				int deaths = mission.deaths() + 1;
				Task next = deaths > survival.parameters().maxDeaths() ? new Abandon("death_recovery_budget_exhausted") : new Mission(mission.item(), mission.count(), world.vitals().life(), deaths);
				return Optional.of(new Revision<>(root.id(), next, "life_changed:" + world.vitals().life()));
			}
			if (!world.vitals().alive() && branch.size() > 1) return Optional.of(new Revision<>(root.id(), mission, "player_died"));
		}
		if (branch.stream().anyMatch(view -> view.task() instanceof AfterEscape && view.childResult().filter(o -> o.kind() != ResultKind.SUCCEEDED).isPresent())) {
			return Optional.of(new Revision<>(root.id(), new Abandon("survival_escape_failed"), "survival_escape_failed"));
		}
		var leaf = branch.getLast();
		// Reconsider unavailable inputs at completed observation/travel boundaries, not mid-harvest.
		if (world == null || leaf.acting() || !(leaf.task() instanceof Gather gather) || !gather.drops().isEmpty()
			|| !(gather.last() instanceof Look || gather.last() instanceof Navigate)
			|| world.inventory().getOrDefault(gather.rule().item(), 0) >= gather.count()
			|| world.known().values().stream().anyMatch(seen -> seen.identified() && gather.rule().blocks().contains(seen.blockId()))) return Optional.empty();
		for (int i = branch.size() - 2; i >= 0; i--) {
			var ancestor = branch.get(i);
			if (!(ancestor.task() instanceof Acquire task)) continue;
			if (!recipesById.containsKey(task.method()) && !smeltsById.containsKey(task.method())) continue;
			var options = rankedMethods(task, world, productionReserve(task, world));
			double currentCost = options.stream().filter(option -> option.id().equals(task.method())).mapToDouble(Ranked::cost).findFirst().orElse(Double.POSITIVE_INFINITY);
			var best = options.stream().filter(option -> !option.id().equals(task.method()))
				.filter(candidate -> Double.isFinite(candidate.cost()) && candidate.cost() * 1.5 + 2 < currentCost)
				.min(Comparator.comparingDouble(Ranked::cost).thenComparing(Ranked::id));
			if (best.isPresent()) return Optional.of(new Revision<>(ancestor.id(), selected(task, best.get().id()), "better_observed_method:" + best.get().id()));
		}
		return Optional.empty();
	}
	@Override public Decision<Task, VoxelCommand> decide(View<Task> view, World world) {
		return switch (view.task()) {
			case Mission task -> {
				if (!world.vitals().alive()) yield new Sleep<>(task, view.tick() + 5);
				if (view.childResult().isPresent()) yield new Complete<>(view.childResult().get());
				yield new Child<>(task, Acquire.root(task.item(), task.count()), "mission_inventory:" + task.item());
			}
			case Abandon task -> failure(task.reason());
			case Escape task -> escape(view, task, world);
			case AfterEscape task -> {
				// The next kernel reconsideration terminates the root after an exhausted escape.
				if (view.childResult().filter(o -> o.kind() != ResultKind.SUCCEEDED).isPresent()) yield new Keep<>();
				yield decide(new View<>(view.id(), task.saved(), false, view.tick(), task.command(), task.child()), world);
			}
			case Access task -> {
				if (view.acting()) yield new Keep<>();
				var result = TerrainAccess.advance(task.state(), world, view.commandResult(), view.tick(), task.clearable(), p -> !survival.nearHazard(world, p));
				if (result instanceof TerrainAccess.Arrived) yield success("access_stance_reached");
				if (result instanceof TerrainAccess.Unavailable unavailable) yield failure(unavailable.reason());
				var action = (TerrainAccess.Action) result;
				yield new Execute<>(new Access(action.state(), task.clearable()), action.command());
			}
			case AfterAccess task -> decide(new View<>(view.id(), task.saved(), false, view.tick(),
				Optional.of(failedChild(view) ? Outcome.failure("access_preparation_failed") : Outcome.success("access_prepared")), Optional.empty()), world);
			case Acquire task -> acquire(view, task, world);
			case Station task -> station(view, task, world);
			case Gather task -> gather(view, task, world);
			case SmeltBatch task -> smelt(view, task, world);
			case Explore task -> explore(view, task, world);
			case ResumeExplore task -> resumeExplore(view, task, world);
			case PlaceLight task -> placeLight(view, task, world);
			case Retreat task -> retreat(view, task, world);
			case AfterRetreat task -> failure(task.reason() + (failed(view) ? ":retreat_failed" : ":retreated"));
			case FinishSmeltStart task -> {
				if (view.acting()) yield new Keep<>();
				if (failed(view)) yield failure("smelt_start_failed:" + view.commandResult().orElseThrow().evidence());
				yield new Sleep<>(new CollectBatch(task.recipe(), task.station(), task.before(), view.tick() + task.recipe().ticks() + 200, 0), view.tick() + task.recipe().ticks());
			}
			case CollectBatch task -> {
				if (world.inventory().getOrDefault(task.recipe().output(), 0) >= task.before() + task.recipe().yield()) yield success("smelted_inventory_observed:" + task.recipe().output());
				if (view.acting()) yield new Keep<>();
				if (view.tick() < task.deadline() - 200) yield new Sleep<>(task, task.deadline() - 200);
				if (view.tick() >= task.deadline() || task.attempts() >= 4) yield failure("smelting_collection_exhausted");
				if (failed(view)) yield new Sleep<>(task, view.tick() + 20);
				yield new Execute<>(new CollectBatch(task.recipe(), task.station(), task.before(), task.deadline(), task.attempts() + 1), new CollectSmelt(task.recipe(), task.station()));
			}
			case Excavate task -> {
				var result = stone.decide(new View<>(view.id(), task.state(), view.acting(), view.tick(), view.commandResult(), view.childResult()), world);
				if (result instanceof Execute<StoneAcquisition.Task, VoxelCommand> action) {
					if (action.command() instanceof Break broken && survival.nearHazard(world, broken.target())) yield failure("observed_excavation_hazard");
					yield new Execute<>(new Excavate(action.continuation()), action.command());
				}
				if (result instanceof Complete<StoneAcquisition.Task, VoxelCommand> done) yield new Complete<>(done.outcome());
				yield new Keep<>();
			}
		};
	}
	private Decision<Task, VoxelCommand> escape(View<Task> view, Escape task, World world) {
		if (!world.vitals().alive()) return failure("player_died_during_escape");
		if (!world.vitals().inLava() && survival.safeStance(world, world.feet())) {
			if (!world.vitals().burning()) return success("survival_refuge_observed");
			if (!view.acting() && view.tick() < task.deadline()) return new Sleep<>(task, view.tick() + 5);
		}
		if (view.tick() >= task.deadline()) return failure("survival_escape_deadline");
		if (view.acting()) return new Keep<>();
		var rejected = new HashSet<>(task.rejected());
		if (view.commandResult().isPresent()) task.last().ifPresent(rejected::add);
		if (rejected.size() >= survival.parameters().maxAttempts()) return failure("survival_escape_attempts_exhausted");
		var refuge = survival.refuge(world, task.origin(), rejected);
		if (refuge.isEmpty()) return failure("no_observed_survival_refuge");
		return new Execute<>(new Escape(task.origin(), rejected, refuge, task.deadline()), new Navigate(refuge.get(), 16, 100));
	}
	private Decision<Task, VoxelCommand> acquire(View<Task> view, Acquire task, World world) {
		if (free(world, task.reserved(), task.item()) >= task.count()) return success("inventory_observed:" + task.item() + ":" + task.count());
		if (view.acting()) return new Keep<>();
		String searchId = "search:" + task.item();
		if (task.method().equals(searchId) && view.childResult().filter(o -> o.kind() == ResultKind.SUCCEEDED).isPresent()) {
			var alternatives = new HashSet<>(task.failed()); alternatives.remove("harvest:" + task.item());
			task = new Acquire(task.item(), task.count(), task.reserved(), task.ancestors(), alternatives, "");
		}
		if (task.ancestors().contains(task.item())) return failure("dependency_cycle:" + task.item());
		if (failed(view)) {
			var rejected = new HashSet<>(task.failed()); rejected.add(task.method());
			task = new Acquire(task.item(), task.count(), task.reserved(), task.ancestors(), rejected, "");
		}
		if (task.failed().size() >= 32) return failure("production_alternatives_exhausted:" + task.item());
		Map<String, Integer> productionReserve = productionReserve(task, world);
		String harvestId = "harvest:" + task.item();
		var search = searches.get(task.item());
		if (search != null && !task.failed().contains(searchId)) {
			var rule = harvesting.get(task.item());
			var observed = world.known().entrySet().stream().filter(e -> e.getValue().identified() && rule.blocks().contains(e.getValue().blockId())).map(Map.Entry::getKey).collect(Collectors.toSet());
			if (observed.isEmpty() || task.failed().contains(harvestId)) {
				String tool = rule.tools().stream().filter(id -> world.inventory().getOrDefault(id, 0) > 0).findFirst().orElse(null);
				if (tool == null) {
					Acquire current = task;
					tool = rule.tools().stream().filter(id -> !current.failed().contains("tool:" + id) && !ancestry(current).contains(id)).findFirst().orElse(null);
					if (tool == null) return failure("search_tool_alternatives_exhausted:" + task.item());
					return new Child<>(selected(task, "tool:" + tool), dependency(task, tool, 1, productionReserve), "search_tool_required:" + tool);
				}
				return new Child<>(selected(task, searchId), new Explore(UndergroundSearch.Task.begin(search, rule.blocks(), world, observed), LightingPolicy.State.begin(), productionReserve, ancestry(task)), searchId);
			}
		}
		if (harvesting.containsKey(task.item()) && !task.failed().contains(harvestId)) {
			var rule = harvesting.get(task.item());
			Acquire next = selected(task, harvestId);
			if (!rule.tools().isEmpty() && rule.tools().stream().noneMatch(tool -> world.inventory().getOrDefault(tool, 0) > 0)) {
				String tool = rule.tools().stream().filter(id -> !next.failed().contains("tool:" + id) && !ancestry(next).contains(id)).findFirst().orElse(null);
				if (tool == null) return failure("harvest_tool_alternatives_exhausted:" + task.item());
				return new Child<>(selected(next, "tool:" + tool), dependency(next, tool, 1, productionReserve), "tool_required:" + tool);
			}
			int target = task.count() + task.reserved().getOrDefault(task.item(), 0);
			Task child = rule.technique() == Technique.LOCAL_STONE ? new Excavate(StoneAcquisition.Task.begin(target, world.feet()))
				: new Gather(rule, target, world.feet(), 0, Set.of(), Set.of(), null);
			return new Child<>(next, child, harvestId);
		}
		String method = task.method();
		if (!recipesById.containsKey(method) && !smeltsById.containsKey(method)) {
			method = rankedMethods(task, world, productionReserve).stream().min(Comparator.comparingDouble(Ranked::cost).thenComparing(Ranked::id)).map(Ranked::id).orElse("");
		}
		int missing = task.count() - free(world, task.reserved(), task.item());
		if (smeltsById.containsKey(method)) {
			var recipe = smeltsById.get(method);
			int inputs = Math.ceilDiv(missing, recipe.yield());
			if (free(world, productionReserve, recipe.input()) < inputs) {
				return new Child<>(selected(task, method), dependency(task, recipe.input(), inputs, productionReserve), "smelting_inputs:" + recipe.input());
			}
			var batchReserve = new TreeMap<>(productionReserve);
			batchReserve.merge(recipe.input(), inputs - 1, Math::addExact);
			return new Child<>(selected(task, method), new SmeltBatch(recipe, batchReserve, ancestry(task), Set.of(), ""), "smelting:" + task.item());
		}
		Recipe recipe = recipesById.get(method);
		if (recipe == null) return failure("no_production_method:" + task.item() + " rejected=" + new TreeSet<>(task.failed()));
		Acquire next = selected(task, recipe.id());
		int batches = Math.ceilDiv(missing, recipe.yield());
		Map<String, Integer> needed = new TreeMap<>();
		recipe.ingredients().forEach((item, count) -> needed.put(item, Math.multiplyExact(count, batches)));
		for (var input : needed.entrySet()) {
			if (free(world, task.reserved(), input.getKey()) < input.getValue()) {
				return new Child<>(next, dependency(next, input.getKey(), input.getValue(), commitments(productionReserve, needed, input.getKey(), world)), "ingredient:" + input.getKey());
			}
		}
		Pos station = null;
		if (recipe.width() == 3) {
			station = observedStation(world, "minecraft:crafting_table").orElse(null);
			if (station == null) return new Child<>(next, new Station("minecraft:crafting_table", commitments(productionReserve, needed, "", world), ancestry(next), 0, Set.of(), null), "workstation_required");
		}
		return new Execute<>(next, new Craft(recipe, station));
	}

	private Decision<Task, VoxelCommand> explore(View<Task> view, Explore task, World world) {
		var assessment = lighting.assess(task.light(), light(world), world.inventory().getOrDefault("minecraft:torch", 0), !task.ancestors().contains("minecraft:torch"), world.feet(), view.tick());
		Explore next = new Explore(task.search(), assessment.state(), task.reserved(), task.ancestors());
		if (assessment.action() == LightingPolicy.Action.SUPPLY || assessment.action() == LightingPolicy.Action.PLACE) {
			var repair = assessment.action() == LightingPolicy.Action.SUPPLY ? LightingPolicy.Repair.SUPPLY : LightingPolicy.Repair.PLACEMENT;
			Task child = repair == LightingPolicy.Repair.SUPPLY
				? new Acquire("minecraft:torch", lighting.parameters().supplyCount(), task.reserved(), task.ancestors(), Set.of(), "")
				: new PlaceLight(Set.of(), Optional.empty());
			return new Child<>(new ResumeExplore(next, ReturnNavigation.State.begin(RouteMemory.append(next.search().route(), world.feet())), repair), child, "lighting_" + repair.name().toLowerCase(Locale.ROOT));
		}
		if (assessment.action() == LightingPolicy.Action.RETREAT) return leaveSearch(next, "lighting_allowance_exhausted", world);
		var decision = underground.decide(new View<>(view.id(), task.search(), view.acting(), view.tick(), view.commandResult(), view.childResult()), world, task.reserved(), pos -> !survival.nearHazard(world, pos));
		if (decision instanceof Execute<UndergroundSearch.Task, VoxelCommand> action) {
			Pos affected = action.command() instanceof Navigate move ? move.stance() : action.command() instanceof Break broken ? broken.target() : action.command() instanceof Place placed ? placed.destination() : action.command() instanceof EdgePlace edge ? edge.placement().destination() : world.feet();
			if (assessment.state().allowance().filter(a -> !lighting.permits(a, affected, view.tick())).isPresent()) return leaveSearch(next, "lighting_region_exhausted", world);
			return new Execute<>(new Explore(action.continuation(), assessment.state(), task.reserved(), task.ancestors()), action.command());
		}
		if (decision instanceof Complete<UndergroundSearch.Task, VoxelCommand> completed) return new Complete<>(completed.outcome());
		return new Keep<>();
	}
	public static Set<Pos> retainedCells(List<Task> branch) {
		var cells = new HashSet<Pos>();
		for (Task task : branch) {
			while (task instanceof AfterEscape || task instanceof AfterAccess) task = task instanceof AfterEscape after ? after.saved() : ((AfterAccess) task).saved();
			if (task instanceof Explore explore) RouteMemory.retain(cells, explore.search().route());
			else if (task instanceof ResumeExplore resume) RouteMemory.retain(cells, resume.returning().route());
			else if (task instanceof Retreat retreat) RouteMemory.retain(cells, retreat.returning().route());
		}
		return Set.copyOf(cells);
	}
	private Decision<Task, VoxelCommand> leaveSearch(Explore task, String reason, World world) {
		var route = new ArrayList<>(RouteMemory.append(task.search().route(), world.feet())); Collections.reverse(route);
		return new Child<>(new AfterRetreat(reason), new Retreat(ReturnNavigation.State.begin(route)), "lighting_retreat");
	}
	private Decision<Task, VoxelCommand> resumeExplore(View<Task> view, ResumeExplore task, World world) {
		if (needsAccess(view) && task.returning().last().isPresent()) return access(task, task.returning().last().get(), world, view.tick(), new HashSet<>(task.saved().search().prior().excavatable()));
		Explore saved = task.saved();
		if (view.childResult().filter(o -> o.kind() != ResultKind.SUCCEEDED).isPresent()) saved = new Explore(saved.search(), saved.light().failed(task.repair()), saved.reserved(), saved.ancestors());
		if (view.acting()) return new Keep<>();
		var returning = ReturnNavigation.advance(task.returning(), world, view.commandResult());
		if (returning instanceof ReturnNavigation.Arrived) return explore(new View<>(view.id(), saved, false, view.tick(), Optional.empty(), Optional.empty()), saved, world);
		if (returning instanceof ReturnNavigation.Unavailable failed) return failure("exploration_return_route_unavailable:" + failed.reason());
		var move = (ReturnNavigation.Move) returning;
		return new Execute<>(new ResumeExplore(saved, move.state(), task.repair()), move.command());
	}
	private Decision<Task, VoxelCommand> retreat(View<Task> view, Retreat task, World world) {
		if (view.acting()) return new Keep<>();
		if (needsAccess(view) && task.returning().last().isPresent()) return access(task, task.returning().last().get(), world, view.tick(), searches.values().stream().flatMap(p -> p.excavatable().stream()).collect(Collectors.toSet()));
		var returning = ReturnNavigation.advance(task.returning(), world, view.commandResult());
		if (returning instanceof ReturnNavigation.Arrived) return success("search_refuge_reached");
		if (returning instanceof ReturnNavigation.Unavailable failed) return failure("search_refuge_unreachable:" + failed.reason());
		var move = (ReturnNavigation.Move) returning;
		return new Execute<>(new Retreat(move.state()), move.command());
	}
	private Decision<Task, VoxelCommand> placeLight(View<Task> view, PlaceLight task, World world) {
		if (light(world) >= lighting.parameters().resumeAt()) return success("working_light_observed");
		if (view.acting()) return new Keep<>();
		var rejected = new HashSet<>(task.rejected());
		if (view.commandResult().isPresent()) {
			task.last().ifPresent(rejected::add);
			if (!failed(view)) return new Sleep<>(new PlaceLight(rejected, Optional.empty()), view.tick() + 5);
		}
		if (rejected.size() >= 4 || world.inventory().getOrDefault("minecraft:torch", 0) == 0) return failure("light_placement_unavailable");
		var support = world.known().keySet().stream().filter(p -> !rejected.contains(p) && StoneAcquisition.standable(world.known(), p.offset(0, 1, 0)))
			.filter(p -> !world.footholds().contains(p) && world.eye().y() > p.y() + 1 && !intersectsPlayer(world, p.offset(0, 1, 0)))
			.filter(p -> distance(world.eye(), p) <= 4.3 * 4.3).sorted(positionOrder(world.eye())).findFirst();
		// Keep the passage floor available, including cells the player has not reached yet.
		for (Pos wall : world.known().keySet().stream().sorted(positionOrder(world.eye())).toList()) {
			Seen seen = world.known().get(wall);
			if (rejected.contains(wall) || !seen.identified() || seen.empty()) continue;
			for (Face face : List.of(Face.NORTH, Face.SOUTH, Face.WEST, Face.EAST)) {
				Pos target = face.adjacent(wall); Seen space = world.known().get(target);
				if (space == null || !space.empty() || intersectsPlayer(world, target)) continue;
				// Wall torches have no body collision and preserve the observed walking route.
				double facing = (world.eye().x() - wall.x() - .5) * face.x + (world.eye().z() - wall.z() - .5) * face.z;
				if (facing <= .5 || !ObservedReach.visible(world.known(), world.eye(), wall, 4.3)) continue;
				return new Execute<>(new PlaceLight(rejected, Optional.of(wall)), new Place("minecraft:torch", wall, seen.blockId(), face, "minecraft:wall_torch"));
			}
		}
		if (support.isPresent()) return new Execute<>(new PlaceLight(rejected, support), new Place("minecraft:torch", support.get(), world.known().get(support.get()).blockId()));
		return failure("no_observed_light_support");
	}
	static int light(World world) {
		Seen cell = world.known().get(new Pos((int) Math.floor(world.eye().x()), (int) Math.floor(world.eye().y()), (int) Math.floor(world.eye().z())));
		return cell == null ? 0 : cell.light();
	}

	private Decision<Task, VoxelCommand> smelt(View<Task> view, SmeltBatch task, World world) {
		if (view.acting()) return new Keep<>();
		if (failed(view)) {
			if (task.fuel().isEmpty()) return failure("smelting_prerequisite_failed");
			var rejected = new HashSet<>(task.rejectedFuel()); rejected.add(task.fuel());
			task = new SmeltBatch(task.recipe(), task.reserved(), task.ancestors(), rejected, "");
		}
		if (free(world, task.reserved(), task.recipe().input()) < 1) return new Child<>(task,
			new Acquire(task.recipe().input(), 1, task.reserved(), task.ancestors(), Set.of(), ""), "smelting_input");
		var reserved = new TreeMap<>(task.reserved()); reserved.merge(task.recipe().input(), 1, Integer::sum);
		Pos station = observedStation(world, task.recipe().station()).orElse(null);
		if (station == null) return new Child<>(task, new Station(task.recipe().station(), reserved, task.ancestors(), 0, Set.of(), null), "smelting_station");
		SmeltBatch current = task;
		Fuel fuel = fuels.stream().filter(f -> !current.rejectedFuel().contains(f.item()))
			.min(Comparator.<Fuel>comparingDouble(f -> {
				int missing = Math.max(0, f.quantity(current.recipe().ticks()) - free(world, reserved, f.item()));
				return missing * (1 + estimate(f.item(), reserved, world, current.ancestors(), new int[]{128})) + f.quantity(current.recipe().ticks()) * .01;
			}).thenComparing(Fuel::item)).orElse(null);
		if (fuel == null || task.rejectedFuel().size() >= 16) return failure("smelting_fuel_alternatives_exhausted");
		int count = fuel.quantity(task.recipe().ticks());
		if (free(world, reserved, fuel.item()) < count) return new Child<>(new SmeltBatch(task.recipe(), task.reserved(), task.ancestors(), task.rejectedFuel(), fuel.item()),
			new Acquire(fuel.item(), count, reserved, task.ancestors(), Set.of(), ""), "smelting_fuel:" + fuel.item());
		return new Execute<>(new FinishSmeltStart(task.recipe(), station, world.inventory().getOrDefault(task.recipe().output(), 0)), new StartSmelt(task.recipe(), station, fuel.item(), count));
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
		var supports = world.known().keySet().stream().filter(pos -> !rejected.contains(pos))
			.filter(pos -> StoneAcquisition.standable(world.known(), pos.offset(0, 1, 0)))
			.filter(pos -> !world.footholds().contains(pos) && !world.footholds().contains(pos.offset(0, -1, 0)))
			.sorted(positionOrder(world.eye())).limit(32).toList();
		Optional<Pos> support = supports.stream()
			.filter(pos -> world.eye().y() > pos.y() + 1 && !intersectsPlayer(world, pos.offset(0, 1, 0)) && distance(world.eye(), pos) <= 4.3 * 4.3)
			.findFirst();
		if (support.isPresent()) {
			var command = new Place(task.item(), support.get(), world.known().get(support.get()).blockId());
			return new Execute<>(new Station(task.item(), task.reserved(), task.ancestors(), task.scans(), rejected, command), command);
		}
		// A visible support may be above the player's eye. Reach a worksite before trying its top face.
		Optional<Pos> worksite = world.known().keySet().stream()
			.filter(pos -> !pos.equals(world.feet()) && !rejected.contains(pos) && StoneAcquisition.standable(world.known(), pos))
			.filter(pos -> horizontal(world.feet(), pos) <= 12 * 12)
			.filter(pos -> supports.stream().anyMatch(base -> pos.y() == base.y() + 1 && horizontal(pos, base) >= 1 && horizontal(pos, base) <= 4))
			.sorted(positionOrder(world.eye())).findFirst();
		if (worksite.isPresent()) {
			var move = new Navigate(worksite.get(), 24, 200);
			return new Execute<>(new Station(task.item(), task.reserved(), task.ancestors(), task.scans(), rejected, move), move);
		}
		if (task.scans() >= 4) return failure("no_observed_station_support");
		var look = new Look((float) ((world.eye().yaw() + 90) % 360), 55);
		return new Execute<>(new Station(task.item(), task.reserved(), task.ancestors(), task.scans() + 1, rejected, look), look);
	}

	private Decision<Task, VoxelCommand> gather(View<Task> view, Gather task, World world) {
		if (world.inventory().getOrDefault(task.rule().item(), 0) >= task.count()) return success("harvest_inventory_observed:" + task.rule().item());
		if (view.acting()) return new Keep<>();
		if (needsAccess(view) && task.last() instanceof Navigate move && !task.drops().isEmpty() && searches.containsKey(task.rule().item())) {
			return access(task, move.stance(), world, view.tick(), new HashSet<>(searches.get(task.rule().item()).excavatable()));
		}
		var rejected = new HashSet<>(task.rejected()); var visited = new HashSet<>(task.visited());
		var drops = new HashSet<>(task.drops());
		if (failed(view)) {
			if (task.last() instanceof Break broken) rejected.add(broken.target());
			if (task.last() instanceof Navigate move) visited.add(move.stance());
		}
		else if (task.last() instanceof Break broken && task.rule().blocks().contains(broken.expectedBlock())) {
			drops.add(broken.target());
		}
		task = new Gather(task.rule(), task.count(), task.origin(), task.scans(), rejected, visited, task.last(), drops);
		Optional<Pos> pickup = world.known().keySet().stream()
			.filter(pos -> !pos.equals(world.feet()) && !visited.contains(pos) && survival.safeStance(world, pos))
			// A mined cavity can be only one block high. Pick up from its accessible edge.
			.filter(pos -> drops.stream().anyMatch(drop -> horizontal(pos, drop) <= 1 && pos.y() <= drop.y() && drop.y() - pos.y() <= 6))
			.sorted(Comparator.<Pos>comparingDouble(pos -> drops.stream().mapToDouble(drop -> horizontal(pos, drop) + Math.pow(pos.y() - drop.y(), 2)).min().orElseThrow())
				.thenComparing(positionOrder(world.eye()))).findFirst();
		if (pickup.isPresent()) {
			visited.add(pickup.get());
			return gatherAction(task, new Navigate(pickup.get(), 24, 200), 0, rejected, visited);
		}
		if (rejected.size() + visited.size() >= 24) return failure("harvest_search_exhausted:" + task.rule().item());
		var prior = searches.get(task.rule().item());
		if (prior != null) for (Pos drop : drops.stream().sorted(positionOrder(world.eye())).toList()) {
			Pos ceiling = drop.offset(0, 1, 0);
			Seen space = world.known().get(drop), head = world.known().get(ceiling);
			if (space == null || !space.empty() || !StoneAcquisition.supportsStanding(world.known().get(drop.offset(0, -1, 0)))) continue;
			if (head == null || !head.identified() || head.empty() || !prior.excavatable().contains(head.blockId())) continue;
			if (rejected.contains(ceiling) || world.footholds().contains(ceiling) || !ObservedReach.visible(world.known(), world.eye(), ceiling, 4.3)) continue;
			return gatherAction(task, new Break(ceiling, head.blockId()), 0, rejected, visited);
		}
		Gather current = task;
		var targets = world.known().entrySet().stream().filter(e -> e.getValue().identified() && current.rule().blocks().contains(e.getValue().blockId()))
			.filter(e -> !survival.nearHazard(world, e.getKey()))
			.filter(e -> !rejected.contains(e.getKey()) && !e.getKey().equals(world.feet().offset(0, -1, 0)))
			.sorted(Map.Entry.comparingByKey(positionOrder(world.eye()))).toList();
		for (var target : targets) {
			if (ObservedReach.visible(world.known(), world.eye(), target.getKey(), 4.3)) return gatherAction(task, new Break(target.getKey(), target.getValue().blockId()), task.scans(), rejected, visited);
			Optional<Pos> stance = world.known().keySet().stream().filter(pos -> !pos.equals(world.feet()) && StoneAcquisition.standable(world.known(), pos) && !visited.contains(pos))
				.filter(pos -> Math.abs(pos.x() - target.getKey().x()) + Math.abs(pos.z() - target.getKey().z()) <= 2)
				.filter(pos -> ObservedReach.visible(world.known(), new Pose(pos.x() + .5, pos.y() + 1.62, pos.z() + .5, 0, 0), target.getKey(), 4.3))
				.sorted(positionOrder(world.eye())).findFirst();
			if (stance.isPresent()) { visited.add(stance.get()); return gatherAction(task, new Navigate(stance.get(), 24, 200), 0, rejected, visited); }
		}
		if (task.scans() < 4) return gatherAction(task, new Look((float) ((world.eye().yaw() + 90) % 360), 15), task.scans() + 1, rejected, visited);
		Optional<Pos> frontier = world.known().keySet().stream().filter(pos -> StoneAcquisition.standable(world.known(), pos) && !visited.contains(pos))
			.filter(pos -> horizontal(world.feet(), pos) >= 4 && horizontal(current.origin(), pos) <= 32 * 32)
			.sorted(positionOrder(world.eye())).findFirst();
		if (frontier.isEmpty()) return failure("no_observed_harvest_frontier:" + task.rule().item());
		visited.add(frontier.get());
		return gatherAction(task, new Navigate(frontier.get(), 24, 200), 0, rejected, visited);
	}
	private Execute<Task, VoxelCommand> gatherAction(Gather task, VoxelCommand action, int scans, Set<Pos> rejected, Set<Pos> visited) {
		return new Execute<>(new Gather(task.rule(), task.count(), task.origin(), scans, rejected, visited, action, task.drops()), action);
	}
	private static boolean needsAccess(View<Task> view) {
		return !view.acting() && view.commandResult().filter(o -> o.kind() == ResultKind.FAILED && Set.of("observed_route_unavailable", "navigation_budget_exhausted").contains(o.evidence())).isPresent();
	}
	private static boolean failedChild(View<Task> view) { return view.childResult().filter(o -> o.kind() != ResultKind.SUCCEEDED).isPresent(); }
	private Decision<Task, VoxelCommand> access(Task saved, Pos goal, World world, long tick, Set<String> clearable) {
		return new Child<>(new AfterAccess(saved), new Access(TerrainAccess.State.begin(world.feet(), goal, tick), clearable), "prepare_observed_access");
	}
	private Optional<Pos> observedStation(World world, String block) {
		return world.known().entrySet().stream().filter(e -> e.getValue().identified() && e.getValue().blockId().equals(block) && usableStation(world.known(), world.eye(), e.getKey()))
			.map(Map.Entry::getKey).sorted(positionOrder(world.eye())).findFirst();
	}
	private static boolean usableStation(Map<Pos, Seen> known, Pose eye, Pos target) {
		return ObservedReach.visible(known, eye, target, 4.3);
	}
	private static boolean intersectsPlayer(World world, Pos pos) {
		return Math.abs(world.eye().x() - pos.x() - .5) < .8 && Math.abs(world.eye().z() - pos.z() - .5) < .8
			&& pos.y() <= world.eye().y() && pos.y() + 1 > world.feet().y();
	}
	/** Existing progress belongs to this goal and cannot fund its nested prerequisites. */
	private static Map<String, Integer> productionReserve(Acquire task, World world) {
		var result = new TreeMap<>(task.reserved());
		result.merge(task.item(), world.inventory().getOrDefault(task.item(), 0), Math::max);
		return result;
	}
	private static Map<String, Integer> commitments(Map<String, Integer> reserved, Map<String, Integer> needed, String acquiring, World world) {
		var result = new TreeMap<>(reserved);
		needed.forEach((item, count) -> { if (!item.equals(acquiring)) result.merge(item, Math.min(count, Math.max(0, free(world, reserved, item))), Integer::sum); });
		return result;
	}
	private static Set<String> ancestry(Acquire task) { var result = new HashSet<>(task.ancestors()); result.add(task.item()); return result; }
	private static Acquire dependency(Acquire task, String item, int count, Map<String, Integer> reserved) { return new Acquire(item, count, reserved, ancestry(task), Set.of(), ""); }
	private static Acquire selected(Acquire task, String method) { return new Acquire(task.item(), task.count(), task.reserved(), task.ancestors(), task.failed(), method); }
	private record Ranked(String id, double cost) {}
	private List<Ranked> rankedMethods(Acquire task, World world, Map<String, Integer> reserved) {
		var options = new ArrayList<Ranked>();
		for (var recipe : recipes.getOrDefault(task.item(), List.of())) if (!task.failed().contains(recipe.id())) {
			options.add(new Ranked(recipe.id(), recipeCost(recipe, reserved, world, ancestry(task), new int[]{256})));
		}
		for (var recipe : smelting.getOrDefault(task.item(), List.of())) if (!task.failed().contains(recipe.id())) {
			options.add(new Ranked(recipe.id(), smeltCost(recipe, reserved, world, ancestry(task), new int[]{256})));
		}
		return options;
	}
	private double estimate(String item, Map<String, Integer> reserved, World world, Set<String> trail, int[] budget) {
		if (free(world, reserved, item) > 0) return 0;
		if (--budget[0] <= 0 || trail.contains(item) || trail.size() >= 12) return Double.POSITIVE_INFINITY;
		var next = new HashSet<>(trail); next.add(item);
		var harvest = harvesting.get(item);
		double best = harvest == null ? Double.POSITIVE_INFINITY : world.known().entrySet().stream()
			.filter(entry -> entry.getValue().identified() && harvest.blocks().contains(entry.getValue().blockId()))
			.mapToDouble(entry -> 5 + Math.sqrt(distance(world.eye(), entry.getKey()))).min().orElse(30);
		for (var recipe : recipes.getOrDefault(item, List.of())) best = Math.min(best, recipeCost(recipe, reserved, world, next, budget) / recipe.yield());
		for (var recipe : smelting.getOrDefault(item, List.of())) best = Math.min(best, smeltCost(recipe, reserved, world, next, budget) / recipe.yield());
		return best;
	}
	private double smeltCost(Smelt recipe, Map<String, Integer> reserved, World world, Set<String> trail, int[] budget) {
		return 3 + estimate(recipe.input(), reserved, world, trail, budget);
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
