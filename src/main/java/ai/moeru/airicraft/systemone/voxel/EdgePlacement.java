package ai.moeru.airicraft.systemone.voxel;

import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelCommand.*;

/** Geometry for a crouched placement from a known full support block. */
public final class EdgePlacement {
	private EdgePlacement() {}
	public record Point(double x, double z) {}
	public static Point edge(Place placement) {
		if (placement.face().y != 0) throw new IllegalArgumentException("Horizontal support face required");
		return new Point(placement.support().x() + .5 + placement.face().x * .56,
			placement.support().z() + .5 + placement.face().z * .56);
	}
	public static Point center(Pos support) { return new Point(support.x() + .5, support.z() + .5); }
	public static double distance(double x, double z, Point point) { return Math.hypot(x - point.x(), z - point.z()); }
	public static boolean withinSupportEnvelope(Place placement, double x, double z) {
		double dx = x - placement.support().x() - .5, dz = z - placement.support().z() - .5;
		double outward = dx * placement.face().x + dz * placement.face().z;
		double sideways = dx * placement.face().z - dz * placement.face().x;
		return outward >= -.6 && outward <= .68 && Math.abs(sideways) <= .6;
	}
}
