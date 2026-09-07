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
		Optional<Edge> step, VoxelCommand last, Optional<Edge> failedApproach) {
		public State { rejected = Set.copyOf(rejected); }
		public static State begin(Pos origin, Pos goal, long tick) { return new State(origin, goal, tick + 1000, 0, Set.of(), Optional.empty(), null, Optional.empty()); }
		/** Preparation may repair this edge, but repeating its unchanged navigation is not progress. */
		public static State afterFailedNavigation(Pos origin, Pos goal, long tick) {
			return new State(origin, goal, tick + 1000, 0, Set.of(), Optional.empty(), null, Optional.of(new Edge(origin, goal)));
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
		if (!within(state.origin(), state.goal())) return new Unavailable("access_goal_out_of_range");
		var rejected = new HashSet<>(state.rejected());
		Optional<Edge> retained = state.step().filter(e -> e.from().equals(world.feet()));
		if (feedback.filter(o -> o.kind() != ResultKind.SUCCEEDED).isPresent()) {
			retained.ifPresent(rejected::add); retained = Optional.empty();
		}
		for (int attempts = 0; attempts < 12; attempts++) {
			Optional<Edge> edge = retained.filter(e -> !rejected.contains(e) && Double.isFinite(cost(e, world, clearable, eligible)));
			if (edge.isEmpty()) edge = firstStep(state, world, rejected, clearable, eligible);
			if (edge.isEmpty()) return new Unavailable("no_local_access_proposal");
			Edge selected = edge.get();
			VoxelCommand command = prepare(selected, world, clearable);
			// A completed look with no usable new evidence cannot retry the same proposal forever.
			if (command == null || command instanceof Look && command.equals(state.last()) && state.step().filter(selected::equals).isPresent()
				|| command instanceof Navigate && state.failedApproach().filter(selected::equals).isPresent()) {
				rejected.add(selected); retained = Optional.empty(); continue;
			}
			return new Action(new State(state.origin(), state.goal(), state.deadline(), state.work() + 1, rejected, Optional.of(selected), command, Optional.empty()), command);
		}
		return new Unavailable("local_access_alternatives_exhausted");
	}

	private record Node(Pos pos, Pos first, double cost, double estimate) {}
	private static Optional<Edge> firstStep(State state, World world, Set<Edge> rejected, Set<String> clearable, Predicate<Pos> eligible) {
		var open = new PriorityQueue<Node>(Comparator.comparingDouble(Node::estimate).thenComparingDouble(Node::cost)
			.thenComparingInt(n -> n.pos().x()).thenComparingInt(n -> n.pos().y()).thenComparingInt(n -> n.pos().z()));
		var best = new HashMap<Pos, Double>();
		open.add(new Node(world.feet(), null, 0, distance(world.feet(), state.goal()))); best.put(world.feet(), 0.0);
		for (int expanded = 0; !open.isEmpty() && expanded < MAX_EXPANSIONS; expanded++) {
			Node node = open.remove();
			if (node.cost() > best.getOrDefault(node.pos(), Double.POSITIVE_INFINITY)) continue;
			if (node.pos().equals(state.goal())) return Optional.of(new Edge(world.feet(), node.first()));
			for (int[] direction : new int[][]{{0,1},{-1,0},{0,-1},{1,0}}) for (int dy : new int[]{0,-1,1}) {
				Pos next = node.pos().offset(direction[0], dy, direction[1]); Edge edge = new Edge(node.pos(), next);
				if (!within(state.origin(), next) || rejected.contains(edge)) continue;
				double cost = node.cost() + cost(edge, world, clearable, eligible);
				if (cost >= best.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
				best.put(next, cost); open.add(new Node(next, node.first() == null ? next : node.first(), cost, cost + distance(next, state.goal())));
			}
		}
		return Optional.empty();
	}
	private static double cost(Edge edge, World world, Set<String> clearable, Predicate<Pos> eligible) {
		Pos floor = edge.to().offset(0, -1, 0);
		if (!eligible.test(floor)) return Double.POSITIVE_INFINITY;
		Seen support = world.known().get(floor);
		if (support != null && !StoneAcquisition.supportsStanding(support)) return Double.POSITIVE_INFINITY;
		double cost = support == null ? 9 : 1;
		for (Pos cell : clearance(edge)) {
			if (!eligible.test(cell)) return Double.POSITIVE_INFINITY;
			Seen seen = world.known().get(cell);
			if (seen == null) { cost += 8; continue; }
			if (seen.traversable()) continue;
			if (!seen.identified() || !clearable.contains(seen.blockId()) || world.footholds().contains(cell)) return Double.POSITIVE_INFINITY;
			cost += 4;
		}
		return cost;
	}
	private static VoxelCommand prepare(Edge edge, World world, Set<String> clearable) {
		var cells = clearance(edge);
		for (Pos cell : cells) {
			Seen seen = world.known().get(cell);
			if (seen != null && seen.identified() && !seen.traversable() && clearable.contains(seen.blockId())
				&& ObservedReach.visible(world.known(), world.eye(), cell, 4.3)) return new Break(cell, seen.blockId());
		}
		for (Pos cell : cells) {
			Seen seen = world.known().get(cell);
			if (seen == null || !seen.traversable()) return look(world.eye(), cell);
		}
		Pos floor = edge.to().offset(0, -1, 0);
		if (!StoneAcquisition.supportsStanding(world.known().get(floor))) return look(world.eye(), floor);
		return new Navigate(edge.to(), 12, 100);
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
	private static double distance(Pos a, Pos b) { return Math.max(Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()), Math.abs(a.y() - b.y())); }
}
