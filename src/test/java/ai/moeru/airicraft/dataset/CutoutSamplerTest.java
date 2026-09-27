package ai.moeru.airicraft.dataset;

import ai.moeru.airicraft.dataset.CutoutSampler.QuadIntersection;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CutoutSamplerTest {
	// Unit quad in the plane z=0.5, corners v0..v3 = (0,0),(1,0),(1,1),(0,1) in x,y.
	private static final float[] QUAD_POS = {
		0f, 0f, 0.5f,
		1f, 0f, 0.5f,
		1f, 1f, 0.5f,
		0f, 1f, 0.5f
	};
	private static final float[] QUAD_UV = {
		0f, 0f,
		1f, 0f,
		1f, 1f,
		0f, 1f
	};

	@Test
	void hitsQuadCenter() {
		QuadIntersection hit = CutoutSampler.intersectQuad(
			0.5, 0.5, 0.0, 0.0, 0.0, 1.0, QUAD_POS, QUAD_UV);
		assertNotNull(hit);
		assertEquals(0.5, hit.t(), 1.0E-6);
		assertEquals(0.5, hit.u(), 1.0E-6);
		assertEquals(0.5, hit.v(), 1.0E-6);
	}

	@Test
	void interpolatesUvTowardV1() {
		QuadIntersection hit = CutoutSampler.intersectQuad(
			0.75, 0.5, 0.0, 0.0, 0.0, 1.0, QUAD_POS, QUAD_UV);
		assertNotNull(hit);
		assertEquals(0.75, hit.u(), 1.0E-6);
		assertEquals(0.5, hit.v(), 1.0E-6);
	}

	@Test
	void missesOutsideQuad() {
		assertNull(CutoutSampler.intersectQuad(
			1.5, 0.5, 0.0, 0.0, 0.0, 1.0, QUAD_POS, QUAD_UV));
		assertNull(CutoutSampler.intersectQuad(
			0.5, -0.5, 0.0, 0.0, 0.0, 1.0, QUAD_POS, QUAD_UV));
	}

	@Test
	void missesWhenBehindOrParallel() {
		// Quad behind the ray origin.
		assertNull(CutoutSampler.intersectQuad(
			0.5, 0.5, 1.0, 0.0, 0.0, 1.0, QUAD_POS, QUAD_UV));
		// Ray parallel to the quad plane.
		assertNull(CutoutSampler.intersectQuad(
			0.5, 0.5, 0.0, 0.0, 1.0, 0.0, QUAD_POS, QUAD_UV));
	}

	@Test
	void hitsVerticalQuad() {
		// Crossed-plant style quad standing in the x direction: plane y=0.5.
		float[] vertical = {
			0f, 0.5f, 0f,
			1f, 0.5f, 0f,
			1f, 0.5f, 1f,
			0f, 0.5f, 1f
		};
		QuadIntersection hit = CutoutSampler.intersectQuad(
			0.5, 0.0, 0.5, 0.0, 1.0, 0.0, vertical, QUAD_UV);
		assertNotNull(hit);
		assertEquals(0.5, hit.t(), 1.0E-6);
		assertEquals(0.5, hit.u(), 1.0E-6);
		assertEquals(0.5, hit.v(), 1.0E-6);
	}

	@Test
	void boxExitIsEntryPlusDepth() {
		Box box = new Box(0, 0, 0, 1, 1, 1);
		double exit = CutoutSampler.rayBoxExit(new Vec3d(0.5, 0.5, -1.0), new Vec3d(0, 0, 1), box);
		assertEquals(2.0, exit, 1.0E-9);
	}

	@Test
	void boxExitFromInsideIsPositive() {
		Box box = new Box(0, 0, 0, 1, 1, 1);
		double exit = CutoutSampler.rayBoxExit(new Vec3d(0.5, 0.5, 0.5), new Vec3d(0, 0, 1), box);
		assertTrue(exit > 0.0 && exit <= 0.5 + 1.0E-6);
	}

	@Test
	void boxExitMissesParallelOutside() {
		Box box = new Box(0, 0, 0, 1, 1, 1);
		assertTrue(Double.isNaN(CutoutSampler.rayBoxExit(new Vec3d(2.0, 0.5, -1.0), new Vec3d(0, 0, 1), box)));
	}
}
