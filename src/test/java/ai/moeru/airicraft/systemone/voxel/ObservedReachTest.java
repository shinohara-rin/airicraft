package ai.moeru.airicraft.systemone.voxel;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import static org.junit.jupiter.api.Assertions.*;

class ObservedReachTest {
	@Test void unknownAndOccupiedCellsBlockReachEvenWhenTheTargetIsRemembered() {
		var eye = new Pose(.5, 1.5, .5, 0, 0); var target = new Pos(0, 1, 3);
		var known = new HashMap<Pos, Seen>();
		for (int z = 0; z < 3; z++) known.put(new Pos(0, 1, z), new Seen("air", true, true, false, 15, 0));
		known.put(target, new Seen("stone", false, true, true, 15, 0));
		assertTrue(ObservedReach.visible(known, eye, target, 4.3));
		assertTrue(ObservedReach.visibleFace(known, eye, target, VoxelCommand.Face.NORTH, 4.3));
		assertFalse(ObservedReach.visibleFace(known, eye, target, VoxelCommand.Face.SOUTH, 4.3));
		assertFalse(ObservedReach.visibleFace(known, eye, target, VoxelCommand.Face.NORTH, 1));
		known.remove(new Pos(0, 1, 1));
		assertFalse(ObservedReach.visible(known, eye, target, 4.3));
		assertFalse(ObservedReach.visibleFace(known, eye, target, VoxelCommand.Face.NORTH, 4.3));
		known.put(new Pos(0, 1, 1), new Seen("stone", false, true, true, 15, 0));
		assertFalse(ObservedReach.visible(known, eye, target, 4.3));
	}
}
