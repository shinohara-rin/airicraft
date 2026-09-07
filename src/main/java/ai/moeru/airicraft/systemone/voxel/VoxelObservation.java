package ai.moeru.airicraft.systemone.voxel;

import java.util.LinkedHashMap;
import java.util.Map;

/** A bounded geometric sensor. The scene is queried only along rays up to their first occupied voxel. */
public final class VoxelObservation {
	private VoxelObservation() {}

	public record Pos(int x, int y, int z) {
		public Pos offset(int dx, int dy, int dz) { return new Pos(x + dx, y + dy, z + dz); }
	}
	public record Pose(double x, double y, double z, double yaw, double pitch) {}
	/** fullSupport describes observed full-block collision geometry, not a hazard policy. */
	public record Sample(String blockId, boolean empty, boolean fullSupport, int light, boolean clearForBody) {
		public Sample(String blockId, boolean empty, boolean fullSupport, int light) { this(blockId, empty, fullSupport, light, empty); }
	}
	/** Body clearance is distinct from an empty voxel: a visible torch can be walked through. */
	public record Seen(String blockId, boolean empty, boolean identified, boolean fullSupport, int light, long tick, boolean clearForBody) {
		public Seen(String blockId, boolean empty, boolean identified, boolean fullSupport, int light, long tick) { this(blockId, empty, identified, fullSupport, light, tick, empty); }
		public boolean traversable() { return empty || identified && clearForBody; }
	}
	public record Lens(double range, int yawDegrees, int pitchDegrees, int spacingDegrees, int identificationLight) {
		public Lens {
			if (!Double.isFinite(range) || range <= 0 || range > 64 || yawDegrees < 1 || yawDegrees > 180
				|| pitchDegrees < 1 || pitchDegrees > 180 || spacingDegrees < 1 || spacingDegrees > 30
				|| identificationLight < 0 || identificationLight > 15) throw new IllegalArgumentException("Invalid sensor lens");
		}
	}
	@FunctionalInterface public interface Scene { Sample sample(Pos pos); }

	public static Map<Pos, Seen> observe(Scene scene, Pose pose, Lens lens, long tick) {
		Map<Pos, Seen> visible = new LinkedHashMap<>();
		Map<Pos, Sample> sampled = new LinkedHashMap<>();
		Scene cached = pos -> sampled.computeIfAbsent(pos, scene::sample);
		for (int yaw = -lens.yawDegrees() / 2; yaw <= lens.yawDegrees() / 2; yaw += lens.spacingDegrees()) {
			for (int pitch = -lens.pitchDegrees() / 2; pitch <= lens.pitchDegrees() / 2; pitch += lens.spacingDegrees()) {
				double y = Math.toRadians(pose.yaw() + yaw);
				double p = Math.toRadians(Math.clamp(pose.pitch() + pitch, -90, 90));
				ray(cached, pose, -Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p), lens, tick, visible);
			}
		}
		return Map.copyOf(visible);
	}

	static void ray(Scene scene, Pose eye, double dx, double dy, double dz, Lens lens, long tick, Map<Pos, Seen> visible) {
		int x = (int) Math.floor(eye.x()), y = (int) Math.floor(eye.y()), z = (int) Math.floor(eye.z());
		Pos origin = new Pos(x, y, z);
		int sx = dx >= 0 ? 1 : -1, sy = dy >= 0 ? 1 : -1, sz = dz >= 0 ? 1 : -1;
		double tx = boundary(eye.x(), x, dx), ty = boundary(eye.y(), y, dy), tz = boundary(eye.z(), z, dz);
		double distance = 0;
		int previousLight = 0;
		while (distance <= lens.range()) {
			// A conservative near-field model in darkness; do not map an unlit cave's distant geometry.
			if (distance > 1.5 && previousLight < lens.identificationLight()) return;
			Pos pos = new Pos(x, y, z);
			Sample sample = scene.sample(pos);
			int light = Math.max(sample.light(), previousLight);
			boolean identified = sample.empty() || light >= lens.identificationLight();
			Seen seen = new Seen(identified ? sample.blockId() : "unknown", sample.empty(), identified, identified && !sample.empty() && sample.fullSupport(), light, tick, identified && sample.clearForBody());
			visible.merge(pos, seen, (a, b) -> a.identified() && !b.identified() ? a : b);
			// A noncolliding fixture sharing the eye voxel must not seal every outgoing ray.
			// All subsequently occupied voxels still occlude, including other torches.
			if (!sample.empty() && !(pos.equals(origin) && identified && sample.clearForBody())) return;
			previousLight = sample.light();
			// Break ties one axis at a time: a zero-width corner must not reveal diagonal hidden blocks.
			if (tx <= ty && tx <= tz) { distance = tx; tx += 1 / Math.abs(dx); x += sx; }
			else if (ty <= tz) { distance = ty; ty += 1 / Math.abs(dy); y += sy; }
			else { distance = tz; tz += 1 / Math.abs(dz); z += sz; }
		}
	}

	private static double boundary(double origin, int cell, double direction) {
		return Math.abs(direction) < 1e-12 ? Double.POSITIVE_INFINITY
			: ((direction > 0 ? cell + 1 : cell) - origin) / direction;
	}
}
