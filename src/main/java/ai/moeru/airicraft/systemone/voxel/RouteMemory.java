package ai.moeru.airicraft.systemone.voxel;

import java.util.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;

/** Retention requests preserve previously observed corridor cells; they never supply new terrain. */
public final class RouteMemory {
	private RouteMemory() {}
	public static List<Pos> append(List<Pos> route, Pos feet) {
		if (!route.isEmpty() && route.getLast().equals(feet)) return route;
		var next = new ArrayList<>(route); next.add(feet); return List.copyOf(next);
	}
	public static void retain(Set<Pos> cells, List<Pos> route) {
		for (Pos feet : route) for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = -2; y <= 3; y++) {
			cells.add(feet.offset(x, y, z));
		}
	}
	public static boolean keep(Pos cell, Pos feet, Set<Pos> retained) {
		return retained.contains(cell) || (Math.abs(cell.x() - feet.x()) <= 48 && Math.abs(cell.z() - feet.z()) <= 48);
	}
}
