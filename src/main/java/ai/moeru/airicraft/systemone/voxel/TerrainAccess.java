package ai.moeru.airicraft.systemone.voxel;

import java.util.*;
import java.util.function.Predicate;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelCommand.*;
import static ai.moeru.airicraft.systemone.TaskKernel.*;
import ai.moeru.airicraft.systemone.voxel.StoneAcquisition.World;

/** Bounded local approach preparation. Search proposals never authorize a hidden block interaction. */
public final class TerrainAccess {
	private TerrainAccess() {}
	private static final int RADIUS = 6, MAX_WORK = 48, MAX_EXPANSIONS = 512;
	public record Edge(Pos from, Pos to) {}
	public record State(Pos origin, Pos goal, long deadline, int work, Set<Edge> rejected,
		List<Edge> route, VoxelCommand last, Optional<Edge> failedApproach) {
		public State { rejected = Set.copyOf(rejected); route = List.copyOf(route); }
		public Optional<Edge> step() { return route.stream().findFirst(); }
		public static State begin(Pos origin, Pos goal, long tick) { return new State(origin, goal, tick + 1000, 0, Set.of(), List.of(), null, Optional.empty()); }
		/** Preparation may repair this edge, but repeating its unchanged navigation is not progress. */
		public static State afterFailedNavigation(Pos origin, Pos goal, long tick) {
			return new State(origin, goal, tick + 1000, 0, Set.of(), List.of(), null, Optional.of(new Edge(origin, goal)));
		}
	}
	public sealed interface Decision permits Action, Arrived, Unavailable {}
	public record Action(State state, VoxelCommand command) implements Decision {}
	public record Arrived() implements Decision {}
	public record Unavailable(String reason) implements Decision {}

	public static Decision advance(State state, World world, Optional<Outcome> feedback, long tick,
		Set<String> clearable, Predicate<Pos> eligible) {
		if (world.feet().equals(state.goal())) return StoneAcquisition.standable(world.known(), world.feet()) ? new Arrived() : new Unavailable("access_goal_support_unconfirmed");
		if (tick >= state.deadline() || state.work() >= MAX_WORK) return new Unavailable("access_budget_exhausted");
		var rejected = new HashSet<>(state.rejected());
		List<Edge> route = state.route();
		if (feedback.filter(o -> o.kind() != ResultKind.SUCCEEDED).isPresent()) {
			// A one-block descent may stop just past its landing cell while still
			// contacting its support. Recover that changed position before abandoning
			// the remaining staircase. An unchanged failed retry cannot take this branch.
			if (state.last() instanceof Navigate move && state.step().isPresent()
				&& !state.step().get().from().equals(world.feet())
				&& world.feet().y() == move.stance().y()
				&& Math.abs(world.eye().y() - world.feet().y() - 1.62) < .1
				&& Math.abs(world.eye().x() - move.stance().x() - .5) < .8
				&& Math.abs(world.eye().z() - move.stance().z() - .5) < .8
				&& world.footholds().contains(move.stance().offset(0,-1,0))
				&& StoneAcquisition.standable(world.known(), move.stance()) && eligible.test(move.stance())
				&& eligible.test(move.stance().offset(0,-1,0))) {
				var recovery = new ArrayList<>(route);
				recovery.set(0,new Edge(world.feet(),move.stance()));
				return new Action(new State(state.origin(),state.goal(),state.deadline(),state.work()+1,rejected,recovery,move,Optional.empty()),move);
			}
			state.step().ifPresent(rejected::add); route = List.of();
		} else if (!route.isEmpty() && route.getFirst().to().equals(world.feet())) {
			route = route.subList(1, route.size());
		}
		for (int attempts = 0; attempts < 12; attempts++) {
			// Retain the selected local route, but authorize only its next edge against
			// current observations. Arrival, failed execution, or changed terrain can replan.
			if (!route.isEmpty()) {
				Edge next = route.getFirst();
				if (!next.from().equals(world.feet()) || rejected.contains(next)
					|| !Double.isFinite(cost(next, world, clearable, eligible))) route = List.of();
			}
			if (route.isEmpty()) route = localRoute(state, world, rejected, clearable, eligible);
			if (route.isEmpty()) return new Unavailable("no_local_access_proposal");
			Edge selected = route.getFirst();
			VoxelCommand command = prepare(selected, world, clearable, eligible);
			// A completed look with no usable new evidence cannot retry the same proposal forever.
			if (command == null || command instanceof Look && command.equals(state.last()) && state.step().filter(selected::equals).isPresent()
				|| command instanceof Navigate && state.failedApproach().filter(selected::equals).isPresent()) {
				rejected.add(selected); route = List.of(); continue;
			}
			return new Action(new State(state.origin(), state.goal(), state.deadline(), state.work() + 1, rejected, route, command, Optional.empty()), command);
		}
		return new Unavailable("local_access_alternatives_exhausted");
	}

	// A route changes terrain and commits return footing as it advances. Position alone
	// cannot identify a search state: two approaches may leave different usable stairs.
	// These are proposals only; prepare() still checks the next command against observations.
	private record Footprint(Pos pos, Set<Pos> opened, Set<Pos> supports) {
		private Footprint { opened = Set.copyOf(opened); supports = Set.copyOf(supports); }
	}
	private record Node(Footprint footprint, Node previous, double cost, double estimate) {
		Pos pos() { return footprint.pos(); }
	}
	private record SearchResult(List<Edge> route, int expanded) {}
	private record Survey(Node node, boolean surface) {}
	private static List<Edge> localRoute(State state, World world, Set<Edge> rejected, Set<String> clearable, Predicate<Pos> eligible) {
		// First keep the cheapest approach to each position. If its history blocks a
		// solution, distinguish approaches by their terrain effects within the remaining budget.
		var simple = search(state, world, rejected, clearable, eligible, false, MAX_EXPANSIONS / 2);
		if (!simple.route().isEmpty()) return simple.route();
		return search(state, world, rejected, clearable, eligible, true, MAX_EXPANSIONS - simple.expanded()).route();
	}
	private static SearchResult search(State state, World world, Set<Edge> rejected, Set<String> clearable, Predicate<Pos> eligible, boolean distinguishHistory, int limit) {
		var open = new PriorityQueue<Node>(Comparator.comparingDouble(Node::estimate).thenComparingDouble(Node::cost)
			.thenComparingInt(n -> n.pos().x()).thenComparingInt(n -> n.pos().y()).thenComparingInt(n -> n.pos().z()));
		var best = new HashMap<Footprint, Double>();
		Pos initialFloor = world.feet().offset(0,-1,0);
		var initial = new Footprint(world.feet(), Set.of(), StoneAcquisition.supportsStanding(world.known().get(initialFloor)) ? Set.of(initialFloor) : Set.of());
		open.add(new Node(initial, null, 0, distance(world.feet(), state.goal()))); best.put(searchKey(initial, distinguishHistory), 0.0);
		Node frontier = null;
		Survey survey = null;
		boolean distant = !within(world.feet(), state.goal());
		int expanded = 0;
		for (; !open.isEmpty() && expanded < limit; expanded++) {
			Node node = open.remove();
			if (node.cost() > best.getOrDefault(searchKey(node.footprint(), distinguishHistory), Double.POSITIVE_INFINITY)) continue;
			if (node.pos().equals(state.goal())) return new SearchResult(routeTo(node), expanded + 1);
			// Keep the destination while advancing a bounded local planning horizon. A proposed
			// frontier still goes through prepare(), so unknown cells authorize only observation.
			if (distant && atHorizon(world.feet(), node.pos()) && distance(node.pos(), state.goal()) < distance(world.feet(), state.goal())
				&& (frontier == null || node.estimate() < frontier.estimate())) frontier = node;
			for (int[] direction : new int[][]{{0,1},{-1,0},{0,-1},{1,0}}) for (int dy : new int[]{0,-1,1}) {
				Pos next = node.pos().offset(direction[0], dy, direction[1]); Edge edge = new Edge(node.pos(), next);
				if (!within(world.feet(), next) || rejected.contains(edge)) continue;
				double cost = node.cost() + cost(edge, world, clearable, eligible, node.footprint().opened(), node.footprint().supports());
				if (!Double.isFinite(cost)) continue;
				var opened = new HashSet<>(node.footprint().opened()); opened.addAll(clearance(edge));
				var supports = new HashSet<>(node.footprint().supports()); supports.add(next.offset(0,-1,0));
				var footprint = new Footprint(next, opened, supports);
				if (world.known().get(next.offset(0,-1,0)) == null) {
					// Stop the proposal at unknown footing. Inspect/expose it before
					// deciding whether another step exists; never route onward through it.
					boolean surface = clearance(edge).stream().filter(p -> !node.footprint().opened().contains(p))
						.map(world.known()::get).anyMatch(seen -> seen != null && seen.identified() && !seen.traversable());
					var candidate = new Survey(new Node(footprint,node,cost,cost+distance(next,state.goal())),surface);
					if (survey == null || surveyOrder(state.goal()).compare(candidate,survey) < 0) survey = candidate;
					continue;
				}
				if (cost >= best.getOrDefault(searchKey(footprint, distinguishHistory), Double.POSITIVE_INFINITY)) continue;
				best.put(searchKey(footprint, distinguishHistory), cost); open.add(new Node(footprint, node, cost, cost + distance(next, state.goal())));
			}
		}
		return new SearchResult(frontier != null ? routeTo(frontier) : survey != null ? routeTo(survey.node()) : List.of(), expanded);
	}
	private static Comparator<Survey> surveyOrder(Pos goal) {
		return Comparator.<Survey>comparingInt(s -> s.surface() ? 0 : 1)
			.thenComparingInt(s -> Math.abs(s.node().pos().x()-goal.x()) + Math.abs(s.node().pos().y()-goal.y()) + Math.abs(s.node().pos().z()-goal.z()))
			.thenComparingDouble(s -> s.node().cost())
			.thenComparingInt(s -> s.node().pos().x()).thenComparingInt(s -> s.node().pos().y()).thenComparingInt(s -> s.node().pos().z());
	}
	private static Footprint searchKey(Footprint footprint, boolean distinguishHistory) {
		return distinguishHistory ? footprint : new Footprint(footprint.pos(), Set.of(), Set.of());
	}
	private static List<Edge> routeTo(Node node) {
		var route = new ArrayList<Edge>();
		for (; node.previous() != null; node = node.previous()) route.add(new Edge(node.previous().pos(), node.pos()));
		Collections.reverse(route);
		return List.copyOf(route);
	}
	private static double cost(Edge edge, World world, Set<String> clearable, Predicate<Pos> eligible) {
		return cost(edge, world, clearable, eligible, Set.of(), Set.of());
	}
	private static double cost(Edge edge, World world, Set<String> clearable, Predicate<Pos> eligible, Set<Pos> opened, Set<Pos> supports) {
		Pos floor = edge.to().offset(0, -1, 0);
		if (!eligible.test(floor) || opened.contains(floor)) return Double.POSITIVE_INFINITY;
		Seen support = world.known().get(floor);
		if (support != null && !StoneAcquisition.supportsStanding(support)) return Double.POSITIVE_INFINITY;
		double cost = support == null ? 9 : 1;
		for (Pos cell : clearance(edge)) {
			if (!eligible.test(cell) || supports.contains(cell)) return Double.POSITIVE_INFINITY;
			if (opened.contains(cell)) continue;
			Seen seen = world.known().get(cell);
			if (seen == null) { cost += 8; continue; }
			if (seen.traversable()) continue;
			if (!seen.identified() || !clearable.contains(seen.blockId()) || world.footholds().contains(cell)) return Double.POSITIVE_INFINITY;
			cost += 4;
		}
		return cost;
	}
	private static VoxelCommand prepare(Edge edge, World world, Set<String> clearable, Predicate<Pos> eligible) {
		var cells = clearance(edge);
		for (Pos cell : cells) {
			Seen seen = world.known().get(cell);
			if (seen != null && seen.identified() && !seen.traversable() && clearable.contains(seen.blockId())
				&& ObservedReach.visible(world.known(), world.eye(), cell, 4.3)) return new Break(cell, seen.blockId());
		}
		for (Pos cell : cells) {
			Seen seen = world.known().get(cell);
			if (seen == null || !seen.traversable()) return inspect(world, cell, clearable, eligible);
		}
		Pos floor = edge.to().offset(0, -1, 0);
		if (!StoneAcquisition.supportsStanding(world.known().get(floor))) return inspect(world, floor, clearable, eligible);
		return new Navigate(edge.to(), 12, 100);
	}
	static VoxelCommand inspect(World world, Pos target, Set<String> clearable, Predicate<Pos> eligible) {
		return ObservedReach.obstruction(world.known(), world.eye(), target, 4.3)
			.filter(pos -> eligible.test(pos) && !world.footholds().contains(pos) && !pos.equals(world.feet().offset(0,-1,0)))
			.filter(pos -> clearable.contains(world.known().get(pos).blockId()) && ObservedReach.visible(world.known(), world.eye(), pos, 4.3))
			.<VoxelCommand>map(pos -> new Break(pos, world.known().get(pos).blockId())).orElseGet(() -> look(world.eye(), target));
	}
	private static List<Pos> clearance(Edge edge) {
		var cells = new LinkedHashSet<Pos>();
		if (edge.to().y() > edge.from().y()) cells.add(edge.from().offset(0, 2, 0));
		cells.add(edge.to().offset(0, Math.max(1, edge.from().y() + 1 - edge.to().y()), 0));
		cells.add(edge.to().offset(0, 1, 0)); cells.add(edge.to());
		return List.copyOf(cells);
	}
	private static Look look(Pose eye, Pos cell) {
		double dx = cell.x() + .5 - eye.x(), dy = cell.y() + .5 - eye.y(), dz = cell.z() + .5 - eye.z();
		return new Look((float) Math.toDegrees(Math.atan2(-dx, dz)), (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
	}
	private static boolean within(Pos a, Pos b) { return UndergroundSearch.squared(a, b) <= RADIUS * RADIUS && Math.abs(a.y() - b.y()) <= RADIUS; }
	private static boolean atHorizon(Pos a, Pos b) { return UndergroundSearch.squared(a, b) >= (RADIUS - 1) * (RADIUS - 1) || Math.abs(a.y() - b.y()) >= RADIUS - 1; }
	private static double distance(Pos a, Pos b) { return Math.max(Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()), Math.abs(a.y() - b.y())); }
}
