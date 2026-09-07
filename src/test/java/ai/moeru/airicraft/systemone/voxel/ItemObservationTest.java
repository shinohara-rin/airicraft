package ai.moeru.airicraft.systemone.voxel;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import static org.junit.jupiter.api.Assertions.*;

class ItemObservationTest {
	private static final Lens LENS = new Lens(16, 100, 80, 2, 2);
	private static final Pose EYE = new Pose(.5, 2.5, .5, 0, 0);
	private static final Sample AIR = new Sample("air", true, false, 15);
	private static final ItemObservation.Point DROP = new ItemObservation.Point(.5, 2.5, 3.5);

	@Test void visibleItemRequiresAnUnbrokenCurrentView() {
		var view = new HashMap<>(observe(pos -> AIR, EYE, LENS, 1));
		assertTrue(ItemObservation.visible(view, EYE, LENS, DROP));
		view.remove(new Pos(0, 2, 2));
		assertFalse(ItemObservation.visible(view, EYE, LENS, DROP), "a known target does not authorize looking through an unobserved gap");
	}
	@Test void occlusionAndDarknessHideItems() {
		var occluded = observe(pos -> pos.z() == 2 ? new Sample("stone", false, true, 15) : AIR, EYE, LENS, 1);
		assertFalse(ItemObservation.visible(occluded, EYE, LENS, DROP));
		var dark = observe(pos -> new Sample("air", true, false, 0), EYE, LENS, 1);
		assertFalse(ItemObservation.visible(dark, EYE, LENS, new ItemObservation.Point(.5, 2.5, 1.5)));
	}
	@Test void rememberedCellsOutsideTheCurrentFrustumCannotRevealItems() {
		var view = observe(pos -> AIR, EYE, LENS, 1);
		assertFalse(ItemObservation.visible(view, new Pose(.5, 2.5, .5, 180, 0), LENS, DROP));
		assertFalse(ItemObservation.visible(view, EYE, new Lens(2,100,80,2,2), DROP));
		assertFalse(ItemObservation.visible(view, new Pose(.5,2.5,.5,0,80), LENS, DROP));
	}
	@Test void yawWrappingAndNoncollidingTargetCellsWork() {
		var eye = new Pose(.5, 2.5, .5, 179, 0);
		var point = new ItemObservation.Point(.55, 2.5, -2.5);
		assertTrue(ItemObservation.visible(observe(pos -> AIR, eye, LENS, 1), eye, LENS, point));
		var view = observe(pos -> pos.equals(DROP.cell()) ? new Sample("torch",false,false,15,true) : AIR, EYE, LENS, 1);
		assertTrue(ItemObservation.visible(view, EYE, LENS, DROP));
	}
}
