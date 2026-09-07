package ai.moeru.airicraft.systemone.voxel;

import java.util.LinkedHashMap;
import java.util.Map;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;

/** Item identity is admitted only through the current geometric view, never remembered empty space. */
public final class ItemObservation {
	private ItemObservation() {}
	public record Point(double x, double y, double z) {
		public Pos cell() { return new Pos((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z)); }
	}
	public record Drop(String id, String item, int count, Point position, long tick) {}
	public static boolean visible(Map<Pos,Seen> currentView, Pose eye, Lens lens, Point point) {
		double dx=point.x()-eye.x(), dy=point.y()-eye.y(), dz=point.z()-eye.z();
		double distance=Math.sqrt(dx*dx+dy*dy+dz*dz);
		if (distance > lens.range()) return false;
		double yaw=Math.toDegrees(Math.atan2(-dx,dz)), pitch=-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz)));
		double yawDelta=Math.toDegrees(Math.atan2(Math.sin(Math.toRadians(yaw-eye.yaw())),Math.cos(Math.toRadians(yaw-eye.yaw()))));
		if (Math.abs(yawDelta)>lens.yawDegrees()/2.0 || Math.abs(pitch-eye.pitch())>lens.pitchDegrees()/2.0) return false;
		Seen cell=currentView.get(point.cell());
		if (cell==null || !cell.identified() || !cell.traversable() || cell.light()<lens.identificationLight()) return false;
		if (distance < 1e-8) return true;
		var ray=new LinkedHashMap<Pos,Seen>();
		VoxelObservation.ray(pos -> {
			Seen seen=currentView.get(pos);
			return seen==null ? new Sample("unknown",false,false,0) : new Sample(seen.blockId(),seen.empty(),seen.fullSupport(),seen.light(),seen.clearForBody());
		},eye,dx/distance,dy/distance,dz/distance,new Lens(Math.max(distance,.001),1,1,1,lens.identificationLight()),0,ray);
		return ray.containsKey(point.cell());
	}
}
