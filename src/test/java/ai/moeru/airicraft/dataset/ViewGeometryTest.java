package ai.moeru.airicraft.dataset;

import ai.moeru.airicraft.dataset.ViewGeometry.Basis;
import ai.moeru.airicraft.dataset.ViewGeometry.Egocentric;
import ai.moeru.airicraft.dataset.ViewGeometry.Letterbox;
import ai.moeru.airicraft.dataset.ViewGeometry.Projection;
import ai.moeru.airicraft.dataset.ViewGeometry.ScreenPoint;
import ai.moeru.airicraft.dataset.ViewGeometry.SourcePixel;
import ai.moeru.airicraft.dataset.ViewGeometry.Vec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ViewGeometryTest {
	private static final double EPS = 1.0E-6;

	private static void assertVec(Vec expected, Vec actual) {
		assertEquals(expected.x(), actual.x(), EPS);
		assertEquals(expected.y(), actual.y(), EPS);
		assertEquals(expected.z(), actual.z(), EPS);
	}

	@Test
	void forwardMatchesMinecraftYawPitch() {
		assertVec(new Vec(0, 0, 1), ViewGeometry.forward(0, 0));        // south = +Z
		assertVec(new Vec(-1, 0, 0), ViewGeometry.forward(90, 0));      // west = -X
		assertVec(new Vec(1, 0, 0), ViewGeometry.forward(-90, 0));      // east = +X
		assertVec(new Vec(0, 0, -1), ViewGeometry.forward(180, 0));     // north = -Z
		assertVec(new Vec(0, 1, 0), ViewGeometry.forward(0, -90));      // up
		assertVec(new Vec(0, -1, 0), ViewGeometry.forward(0, 90));      // down
	}

	@Test
	void cameraBasisIsOrthonormal() {
		for (double yaw : new double[]{0, 45, -90, 135, 179}) {
			for (double pitch : new double[]{-80, -45, 0, 30, 89}) {
				Basis basis = ViewGeometry.cameraBasis(yaw, pitch);
				assertEquals(1.0, basis.right().length(), 1.0E-6);
				assertEquals(1.0, basis.up().length(), 1.0E-6);
				assertEquals(1.0, basis.forward().length(), 1.0E-6);
				assertEquals(0.0, basis.right().dot(basis.forward()), 1.0E-6);
				assertEquals(0.0, basis.up().dot(basis.forward()), 1.0E-6);
				assertEquals(0.0, basis.right().dot(basis.up()), 1.0E-6);
			}
		}
	}

	@Test
	void southFacingRightPointsWest() {
		Basis basis = ViewGeometry.cameraBasis(0, 0);
		assertVec(new Vec(-1, 0, 0), basis.right());
		assertVec(new Vec(0, 1, 0), basis.up());
		assertVec(new Vec(0, 0, 1), basis.forward());
	}

	@Test
	void degeneratePitchKeepsValidBasis() {
		Basis up = ViewGeometry.cameraBasis(0, -90);
		assertVec(new Vec(0, 1, 0), up.forward());
		assertEquals(1.0, up.right().length(), 1.0E-6);
		assertEquals(0.0, up.right().dot(up.forward()), 1.0E-6);
	}

	@Test
	void centerPixelRayEqualsForward() {
		Basis basis = ViewGeometry.cameraBasis(0, 0);
		Projection projection = Projection.perspective(70.0, 854.0 / 480.0);
		Vec ray = ViewGeometry.sourcePixelRay(basis, projection, 426.5, 239.5, 854, 480);
		assertVec(new Vec(0, 0, 1), ray);
	}

	@Test
	void edgePixelRaysPointSideways() {
		Basis basis = ViewGeometry.cameraBasis(0, 0);
		Projection projection = Projection.perspective(70.0, 2.0);
		Vec left = ViewGeometry.sourcePixelRay(basis, projection, 0, 239.5, 854, 480);
		Vec right = ViewGeometry.sourcePixelRay(basis, projection, 853, 239.5, 854, 480);
		// Facing south, the right half of the screen looks toward -X.
		assertTrue(right.dot(basis.right()) > 0);
		assertTrue(left.dot(basis.right()) < 0);
		// Top edge looks up, bottom edge looks down.
		Vec top = ViewGeometry.sourcePixelRay(basis, projection, 426.5, 0, 854, 480);
		Vec bottom = ViewGeometry.sourcePixelRay(basis, projection, 426.5, 479, 854, 480);
		assertTrue(top.y() > 0);
		assertTrue(bottom.y() < 0);
	}

	@Test
	void projectRoundTripsThroughCenter() {
		Basis basis = ViewGeometry.cameraBasis(0, 0);
		Projection projection = Projection.perspective(70.0, 854.0 / 480.0);
		Vec cameraPos = new Vec(0, 80, 0);

		ScreenPoint center = ViewGeometry.project(cameraPos, basis, projection, new Vec(0, 80, 10), 854, 480);
		assertNotNull(center);
		assertEquals(426.5, center.x(), EPS);
		assertEquals(239.5, center.y(), EPS);
		assertEquals(10.0, center.depth(), EPS);

		// -X is screen-right when facing south.
		ScreenPoint right = ViewGeometry.project(cameraPos, basis, projection, new Vec(-2, 80, 10), 854, 480);
		assertNotNull(right);
		assertTrue(right.x() > center.x());
		assertEquals(center.y(), right.y(), EPS);

		ScreenPoint above = ViewGeometry.project(cameraPos, basis, projection, new Vec(0, 85, 10), 854, 480);
		assertNotNull(above);
		assertTrue(above.y() < center.y());

		// Behind the camera projects to nothing.
		assertNull(ViewGeometry.project(cameraPos, basis, projection, new Vec(0, 80, -5), 854, 480));
	}

	@Test
	void egocentricDecomposesWorldOffset() {
		Basis basis = ViewGeometry.cameraBasis(0, 0);
		Egocentric ego = ViewGeometry.egocentric(basis, new Vec(-2, 3, 4));
		assertEquals(4.0, ego.forward(), EPS);
		assertEquals(2.0, ego.right(), EPS);   // -X maps onto the +right axis
		assertEquals(3.0, ego.up(), EPS);
	}

	@Test
	void letterboxMatchesScalerLayout() {
		Letterbox box = ViewGeometry.letterbox(1920, 1080, 854, 480);
		assertEquals(853, box.scaledWidth());
		assertEquals(480, box.scaledHeight());
		assertEquals(0, box.offsetX());
		assertEquals(0, box.offsetY());

		Letterbox tall = ViewGeometry.letterbox(600, 900, 854, 480);
		assertEquals(320, tall.scaledWidth());
		assertEquals(480, tall.scaledHeight());
		assertEquals(267, tall.offsetX());
		assertEquals(0, tall.offsetY());
	}

	@Test
	void outputToSourceRejectsLetterboxPadding() {
		Letterbox box = ViewGeometry.letterbox(600, 900, 854, 480);
		assertNull(ViewGeometry.outputToSource(box, 0, 240, 600, 900));
		assertNull(ViewGeometry.outputToSource(box, 853, 240, 600, 900));
		SourcePixel center = ViewGeometry.outputToSource(box, 427, 240, 600, 900);
		assertNotNull(center);
		assertEquals(300.0, center.x(), 1.0);
		assertEquals(450.0, center.y(), 1.0);
	}
}
