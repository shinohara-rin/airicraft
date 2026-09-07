package ai.moeru.airicraft.systemone.voxel;

import java.util.Map;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;

/** Reach through remembered free space. Unknown cells obstruct; no live world is consulted. */
public final class ObservedReach {
	private ObservedReach() {}
	private static final double[][] POINTS = {{0, 0, 0}, {0, -.499, 0}, {0, .499, 0}, {0, 0, -.499}, {0, 0, .499}, {-.499, 0, 0}, {.499, 0, 0}};
	public static boolean visible(Map<Pos, Seen> known, Pose eye, Pos target, double range) {
		Seen block = known.get(target);
		if (block == null || !block.identified() || block.empty()) return false;
		for (var point : POINTS) {
			double dx = target.x() + .5 + point[0] - eye.x(), dy = target.y() + .5 + point[1] - eye.y(), dz = target.z() + .5 + point[2] - eye.z();
			if (dx * dx + dy * dy + dz * dz > range * range) continue;
			var aimed = new Pose(eye.x(), eye.y(), eye.z(), Math.toDegrees(Math.atan2(-dx, dz)), -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
			if (VoxelObservation.observe(pos -> {
				var seen = known.get(pos);
				return seen == null ? new Sample("unknown", false, false, 0) : new Sample(seen.blockId(), seen.empty(), seen.fullSupport(), 15);
			}, aimed, new Lens(range, 1, 1, 1, 0), 0).containsKey(target)) return true;
		}
		return false;
	}
}
