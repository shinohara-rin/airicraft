package ai.moeru.airicraft.systemone.voxel;

import java.util.Map;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;

/** Reach through remembered free space. Unknown cells obstruct; no live world is consulted. */
public final class ObservedReach {
	private ObservedReach() {}
	private static final double[][] POINTS = {{0, 0, 0}, {0, -.499, 0}, {0, .499, 0}, {0, 0, -.499}, {0, 0, .499}, {-.499, 0, 0}, {.499, 0, 0}};
	/** An observed occupied cell that blocks inspecting a location; never reveals the target behind it. */
	public static java.util.Optional<Pos> obstruction(Map<Pos, Seen> known, Pose eye, Pos target, double range) {
		double dx = target.x() + .5 - eye.x(), dy = target.y() + .5 - eye.y(), dz = target.z() + .5 - eye.z();
		if (dx * dx + dy * dy + dz * dz > range * range) return java.util.Optional.empty();
		var ray = trace(known, eye, dx, dy, dz, range);
		if (ray.containsKey(target)) return java.util.Optional.empty();
		Pos origin = new Pos((int)Math.floor(eye.x()), (int)Math.floor(eye.y()), (int)Math.floor(eye.z()));
		return ray.keySet().stream().filter(pos -> {
			Seen seen = known.get(pos);
			return seen != null && seen.identified() && !seen.empty() && !(pos.equals(origin) && seen.clearForBody());
		}).findFirst();
	}
	public static boolean visible(Map<Pos, Seen> known, Pose eye, Pos target, double range) {
		Seen block = known.get(target);
		if (block == null || !block.identified() || block.empty()) return false;
		for (var point : POINTS) {
			double dx = target.x() + .5 + point[0] - eye.x(), dy = target.y() + .5 + point[1] - eye.y(), dz = target.z() + .5 + point[2] - eye.z();
			if (rayHits(known, eye, target, dx, dy, dz, range)) return true;
		}
		return false;
	}
	public static boolean visibleFace(Map<Pos, Seen> known, Pose eye, Pos target, VoxelCommand.Face face, double range) {
		Seen block = known.get(target);
		if (block == null || !block.identified() || !block.fullSupport()) return false;
		double x = eye.x() - target.x() - .5, y = eye.y() - target.y() - .5, z = eye.z() - target.z() - .5;
		if (x * face.x + y * face.y + z * face.z <= .5) return false;
		return rayHits(known, eye, target, face.x * .5 - x, face.y * .5 - y, face.z * .5 - z, range);
	}
	private static boolean rayHits(Map<Pos, Seen> known, Pose eye, Pos target, double dx, double dy, double dz, double range) {
		if (dx * dx + dy * dy + dz * dz > range * range) return false;
		return trace(known, eye, dx, dy, dz, range).containsKey(target);
	}
	private static Map<Pos, Seen> trace(Map<Pos, Seen> known, Pose eye, double dx, double dy, double dz, double range) {
		var aimed = new Pose(eye.x(), eye.y(), eye.z(), Math.toDegrees(Math.atan2(-dx, dz)), -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
		return VoxelObservation.observe(pos -> {
			var seen = known.get(pos);
			return seen == null ? new Sample("unknown", false, false, 0) : new Sample(seen.blockId(), seen.empty(), seen.fullSupport(), 15, seen.identified() && seen.clearForBody());
		}, aimed, new Lens(range, 1, 1, 1, 0), 0);
	}
}
