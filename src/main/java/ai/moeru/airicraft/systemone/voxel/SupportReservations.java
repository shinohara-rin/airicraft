package ai.moeru.airicraft.systemone.voxel;

import java.util.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import ai.moeru.airicraft.systemone.voxel.StoneAcquisition.World;

/** Historical footing is releasable only with an observed, bidirectional walking bypass. */
public final class SupportReservations {
	private SupportReservations() {}
	private static final int MAX_VISITS = 2048;
	public static World protect(World world, Collection<Pos> returnStances) {
		var protectedCells = new HashSet<>(world.footholds());
		var required = new HashSet<Pos>();
		var contacts = new ArrayList<Pos>();
		returnStances.forEach(p -> required.add(p.offset(0, -1, 0)));
		// Include partial contact across voxel boundaries, even if the feet voxel is over air.
		for (int x = (int)Math.floor(world.eye().x() - .3 + 1e-7); x <= (int)Math.floor(world.eye().x() + .3 - 1e-7); x++)
			for (int z = (int)Math.floor(world.eye().z() - .3 + 1e-7); z <= (int)Math.floor(world.eye().z() + .3 - 1e-7); z++)
				{ var support = new Pos(x, world.feet().y() - 1, z); required.add(support); contacts.add(support.offset(0,1,0)); }
		var nodes = new HashSet<Pos>();
		for (Pos stance : world.known().keySet()) if (StoneAcquisition.standable(world.known(), stance)) nodes.add(stance);
		var graph = new HashMap<Pos, List<Pos>>();
		for (Pos stance : nodes) {
			var adjacent = new ArrayList<Pos>();
			for (int[] direction : new int[][]{{0,1},{-1,0},{0,-1},{1,0}}) for (int dy : new int[]{0,-1,1}) {
				Pos next = stance.offset(direction[0], dy, direction[1]);
				if (nodes.contains(next) && clear(world.known(), stance, next) && clear(world.known(), next, stance)) adjacent.add(next);
			}
			graph.put(stance, List.copyOf(adjacent));
		}
		var previous = new HashMap<Pos, Pos>();
		var queue = new ArrayDeque<Pos>();
		for (Pos contact : contacts) if (graph.containsKey(contact)) { previous.put(contact,contact); queue.add(contact); }
		for (int visits = 0; !queue.isEmpty() && visits < MAX_VISITS; visits++) {
			Pos pos = queue.removeFirst();
			for (Pos next : graph.get(pos)) if (!previous.containsKey(next)) { previous.put(next,pos); queue.addLast(next); }
		}
		boolean connected = true;
		for (Pos anchor : returnStances) {
			if (!graph.containsKey(anchor)) continue;
			if (!previous.containsKey(anchor)) { connected = false; continue; }
			for (Pos pos = anchor;; pos = previous.get(pos)) {
				required.add(pos.offset(0,-1,0));
				if (pos.equals(previous.get(pos))) break;
			}
		}
		if (connected) for (Pos support : world.footholds()) {
			if (!required.contains(support) && bypass(graph, support.offset(0, 1, 0))) protectedCells.remove(support);
		}
		protectedCells.addAll(required);
		return new World(world.eye(), world.feet(), world.inventory(), world.known(), protectedCells, world.vitals(), world.drops());
	}
	private static boolean clear(Map<Pos, Seen> known, Pos from, Pos to) {
		if (to.y() > from.y() && !traversable(known, from.offset(0,2,0))) return false;
		return traversable(known, to) && traversable(known, to.offset(0,1,0))
			&& traversable(known, to.offset(0,Math.max(1, from.y() + 1 - to.y()),0));
	}
	private static boolean traversable(Map<Pos, Seen> known, Pos pos) {
		Seen seen = known.get(pos); return seen != null && seen.traversable();
	}
	private static boolean bypass(Map<Pos, List<Pos>> graph, Pos removed) {
		var neighbors = graph.getOrDefault(removed, List.of());
		if (neighbors.size() < 2) return false;
		var pending = new HashSet<>(neighbors);
		var seen = new HashSet<Pos>(); seen.add(removed);
		var queue = new ArrayDeque<Pos>(); queue.add(neighbors.getFirst()); seen.add(neighbors.getFirst());
		for (int visits = 0; !queue.isEmpty() && visits < MAX_VISITS; visits++) {
			Pos pos = queue.removeFirst(); pending.remove(pos);
			if (pending.isEmpty()) return true;
			for (Pos next : graph.get(pos)) if (seen.add(next)) queue.addLast(next);
		}
		return false;
	}
}
