package ai.moeru.airicraft.dataset;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BlockModelPart;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.texture.Sprite;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.block.ShapeContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Texel-accurate refinement for blocks whose rendered quads contain transparent
 * pixels (crossed plants, leaves in fancy mode, glass, ...). Voxel raycast hits
 * the block's outline shape, which is opaque to texture alpha; this sampler
 * intersects the same ray with the block's baked quads and samples the sprite's
 * alpha at the hit UV, treating fully transparent texels as a miss so the ray
 * continues to whatever actually shows through.
 */
public final class CutoutSampler {
	private static final int MAX_QUADS = 512;
	private final Map<BlockState, List<QuadGeometry>> quadCache = new HashMap<>();

	public record QuadHit(double t, Vec3d pos) {
	}

	/** True when the state can render transparent texels and is worth quad-testing. */
	public boolean needsCheck(BlockState state, BlockView world, BlockPos pos) {
		return !state.isOpaqueFullCube();
	}

	/** Whether the state's model exposes quads (false for fluids/air). */
	public boolean hasQuads(MinecraftClient client, BlockState state, BlockPos pos) {
		return !quadsFor(client, state, pos).isEmpty();
	}

	/**
	 * Nearest opaque-texel hit of the ray on the block's quads, or null when the
	 * ray passes through transparent regions / misses the quads entirely.
	 * {@code dir} need not be normalized; t is in units of dir length.
	 */
	public QuadHit sample(MinecraftClient client, BlockView world, BlockState state, BlockPos pos, Vec3d rayStart, Vec3d dir) {
		List<QuadGeometry> quads = quadsFor(client, state, pos);
		if (quads.isEmpty()) {
			return null;
		}
		// Quads are baked in model space; the renderer translates them by the
		// state's model offset (random horizontal shift for plants).
		Vec3d modelOffset = state.getModelOffset(pos);
		double ox = rayStart.x - pos.getX() - modelOffset.x;
		double oy = rayStart.y - pos.getY() - modelOffset.y;
		double oz = rayStart.z - pos.getZ() - modelOffset.z;
		double bestT = Double.POSITIVE_INFINITY;
		for (QuadGeometry quad : quads) {
			QuadIntersection hit = intersectQuad(ox, oy, oz, dir.x, dir.y, dir.z, quad.pos, quad.uv);
			if (hit == null || hit.t() >= bestT) {
				continue;
			}
			if (opaqueAt(quad.sprite(), hit.u(), hit.v())) {
				bestT = hit.t();
			}
		}
		if (!Double.isFinite(bestT)) {
			return null;
		}
		return new QuadHit(bestT, rayStart.add(dir.multiply(bestT)));
	}

	private List<QuadGeometry> quadsFor(MinecraftClient client, BlockState state, BlockPos pos) {
		return quadCache.computeIfAbsent(state, s -> decodeQuads(client, s, pos));
	}

	/**
	 * Where to resume the ray so the same block cannot be hit again. Uses the
	 * block outline's bounding box, slightly expanded, rather than the unit cube
	 * so shapes sticking out of the cell (torch tips, attached fences) are also
	 * skipped.
	 */
	public Vec3d advancePast(BlockView world, BlockPos pos, BlockState state, PlayerEntity viewer, Vec3d rayStart, Vec3d dir) {
		Box box;
		try {
			VoxelShape shape = state.getOutlineShape(world, pos, ShapeContext.of(viewer));
			Box local = shape.isEmpty() ? new Box(0, 0, 0, 1, 1, 1) : shape.getBoundingBox();
			box = local.offset(pos).expand(1.0E-4D);
		} catch (RuntimeException ignored) {
			box = new Box(pos).expand(1.0E-4D);
		}
		double tExit = rayBoxExit(rayStart, dir, box);
		if (!Double.isFinite(tExit)) {
			return rayStart.add(dir.multiply(1.0E-3D));
		}
		return rayStart.add(dir.multiply(tExit + 1.0E-4D));
	}

	static double rayBoxExit(Vec3d o, Vec3d d, Box box) {
		double tMin = Double.NEGATIVE_INFINITY;
		double tMax = Double.POSITIVE_INFINITY;
		double[] origin = {o.x, o.y, o.z};
		double[] dir = {d.x, d.y, d.z};
		double[] lo = {box.minX, box.minY, box.minZ};
		double[] hi = {box.maxX, box.maxY, box.maxZ};
		for (int axis = 0; axis < 3; axis++) {
			if (Math.abs(dir[axis]) < 1.0E-9D) {
				if (origin[axis] < lo[axis] || origin[axis] > hi[axis]) {
					return Double.NaN;
				}
				continue;
			}
			double t1 = (lo[axis] - origin[axis]) / dir[axis];
			double t2 = (hi[axis] - origin[axis]) / dir[axis];
			tMin = Math.max(tMin, Math.min(t1, t2));
			tMax = Math.min(tMax, Math.max(t1, t2));
		}
		return tMax < tMin ? Double.NaN : tMax;
	}

	private List<QuadGeometry> decodeQuads(MinecraftClient client, BlockState state, BlockPos pos) {
		List<QuadGeometry> result = new ArrayList<>();
		try {
			BlockStateModel model = client.getBlockRenderManager().getModel(state);
			if (model == null) {
				return result;
			}
			Random random = Random.create(pos.asLong() ^ 0x9E3779B97F4A7C15L);
			for (BlockModelPart part : model.getParts(random)) {
				collect(result, part.getQuads(null));
				for (Direction face : Direction.values()) {
					collect(result, part.getQuads(face));
				}
				if (result.size() > MAX_QUADS) {
					break;
				}
			}
		} catch (RuntimeException ignored) {
			return List.of();
		}
		return result;
	}

	private void collect(List<QuadGeometry> out, List<BakedQuad> quads) {
		for (BakedQuad quad : quads) {
			QuadGeometry geometry = decodeQuad(quad);
			if (geometry != null) {
				out.add(geometry);
			}
		}
	}

	/**
	 * Decodes a baked quad into corner positions (0..1 block space) and atlas UVs.
	 * All vanilla quad layouts keep pos at ints 0..2 and uv at ints 4..5 of each
	 * vertex, so the stride is derived from the array length.
	 */
	static QuadGeometry decodeQuad(BakedQuad quad) {
		int[] data = quad.vertexData();
		if (data == null || quad.sprite() == null) {
			return null;
		}
		int stride = data.length / 4;
		if (data.length % 4 != 0 || stride < 8) {
			return null;
		}
		float[] pos = new float[12];
		float[] uv = new float[8];
		for (int i = 0; i < 4; i++) {
			int base = i * stride;
			pos[i * 3] = Float.intBitsToFloat(data[base]);
			pos[i * 3 + 1] = Float.intBitsToFloat(data[base + 1]);
			pos[i * 3 + 2] = Float.intBitsToFloat(data[base + 2]);
			uv[i * 2] = Float.intBitsToFloat(data[base + 4]);
			uv[i * 2 + 1] = Float.intBitsToFloat(data[base + 5]);
		}
		return new QuadGeometry(pos, uv, quad.sprite());
	}

	static boolean opaqueAt(Sprite sprite, float atlasU, float atlasV) {
		try {
			var contents = sprite.getContents();
			float u16 = sprite.getFrameFromU(atlasU);
			float v16 = sprite.getFrameFromV(atlasV);
			int x = clampPixel(u16, contents.getWidth());
			int y = clampPixel(v16, contents.getHeight());
			return !contents.isPixelTransparent(0, x, y);
		} catch (RuntimeException ignored) {
			return true;
		}
	}

	private static int clampPixel(float frameCoord, int size) {
		int px = (int) Math.floor(frameCoord / 16.0F * size);
		return Math.max(0, Math.min(size - 1, px));
	}

	/**
	 * Intersects ray origin+dir with the quad (v0..v3, corners in order).
	 * Solves p = v0 + a*(v1-v0) + b*(v3-v0) in the dominant 2D projection; quads
	 * from block models are parallelograms so a,b in [0,1] means inside. Returns
	 * t (in units of dir) plus the interpolated atlas UV.
	 */
	static QuadIntersection intersectQuad(
		double ox, double oy, double oz,
		double dx, double dy, double dz,
		float[] p, float[] uv
	) {
		double e1x = p[3] - p[0], e1y = p[4] - p[1], e1z = p[5] - p[2];
		double e2x = p[9] - p[0], e2y = p[10] - p[1], e2z = p[11] - p[2];
		double nx = e1y * e2z - e1z * e2y;
		double ny = e1z * e2x - e1x * e2z;
		double nz = e1x * e2y - e1y * e2x;
		double denom = dx * nx + dy * ny + dz * nz;
		if (Math.abs(denom) < 1.0E-12D) {
			return null;
		}
		double wx = p[0] - ox, wy = p[1] - oy, wz = p[2] - oz;
		double t = (wx * nx + wy * ny + wz * nz) / denom;
		if (t <= 0.0D) {
			return null;
		}
		double px = ox + dx * t - p[0];
		double py = oy + dy * t - p[1];
		double pz = oz + dz * t - p[2];

		// Drop the axis with the largest normal component for the 2D solve.
		double ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
		double u, v;
		if (ax >= ay && ax >= az) {
			// solve in (y,z)
			double det = e1y * e2z - e1z * e2y;
			if (Math.abs(det) < 1.0E-12D) return null;
			u = (py * e2z - pz * e2y) / det;
			v = (e1y * pz - e1z * py) / det;
		} else if (ay >= ax && ay >= az) {
			// solve in (x,z)
			double det = e1x * e2z - e1z * e2x;
			if (Math.abs(det) < 1.0E-12D) return null;
			u = (px * e2z - pz * e2x) / det;
			v = (e1x * pz - e1z * px) / det;
		} else {
			// solve in (x,y)
			double det = e1x * e2y - e1y * e2x;
			if (Math.abs(det) < 1.0E-12D) return null;
			u = (px * e2y - py * e2x) / det;
			v = (e1x * py - e1y * px) / det;
		}
		double eps = 1.0E-4D;
		if (u < -eps || u > 1.0D + eps || v < -eps || v > 1.0D + eps) {
			return null;
		}
		float texU = (float) (uv[0] + u * (uv[2] - uv[0]) + v * (uv[6] - uv[0]));
		float texV = (float) (uv[1] + u * (uv[3] - uv[1]) + v * (uv[7] - uv[1]));
		return new QuadIntersection(t, texU, texV);
	}

	record QuadIntersection(double t, float u, float v) {
	}

	record QuadGeometry(float[] pos, float[] uv, Sprite sprite) {
	}
}
