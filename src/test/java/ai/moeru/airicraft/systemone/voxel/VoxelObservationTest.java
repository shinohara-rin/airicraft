package ai.moeru.airicraft.systemone.voxel;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import static org.junit.jupiter.api.Assertions.*;

class VoxelObservationTest {
	private static final Lens LENS = new Lens(16, 90, 70, 3, 2);
	private static final Pose EYE = new Pose(0.5, 2.5, 0.5, 0, 0);
	private static final Sample AIR = new Sample("air", true, 15);

	@Test void hiddenLayoutCannotChangeObservationOrQueries() {
		Set<Pos> firstReads = new HashSet<>(), secondReads = new HashSet<>();
		Scene first = pos -> { firstReads.add(pos); return pos.z() < 3 ? AIR : new Sample(pos.z() == 3 ? "wall" : "iron", false, 15); };
		Scene second = pos -> { secondReads.add(pos); return pos.z() < 3 ? AIR : new Sample(pos.z() == 3 ? "wall" : "diamond", false, 15); };
		assertEquals(observe(first, EYE, LENS, 1), observe(second, EYE, LENS, 1));
		assertEquals(firstReads, secondReads);
		assertFalse(firstReads.stream().anyMatch(pos -> pos.z() > 3));
	}

	@Test void darknessHidesIdentityWithoutPretendingOccupiedSpaceIsAir() {
		Scene first = pos -> new Sample("iron", false, 0);
		Scene second = pos -> new Sample("stone", false, 0);
		var seen = observe(first, EYE, LENS, 1);
		assertEquals(seen, observe(second, EYE, LENS, 1));
		assertTrue(seen.values().stream().noneMatch(Seen::identified));
		assertTrue(seen.values().stream().noneMatch(Seen::empty));
	}

	@Test void aLitFaceCanBeIdentifiedFromTheAdjacentAir() {
		var seen = new LinkedHashMap<Pos, Seen>();
		ray(pos -> pos.z() < 2 ? AIR : new Sample("stone", false, 0), EYE, 0, 0, 1, LENS, 7, seen);
		assertEquals(new Seen("stone", false, true, 15, 7), seen.get(new Pos(0, 2, 2)));
	}

	@Test void diagonalCornerDoesNotRevealTerrainBehindTheFirstWall() {
		Set<Pos> reads = new HashSet<>();
		var seen = new LinkedHashMap<Pos, Seen>();
		ray(pos -> { reads.add(pos); return pos.equals(new Pos(0, 2, 0)) ? AIR : new Sample("wall", false, 15); },
			EYE, Math.sqrt(0.5), 0, Math.sqrt(0.5), LENS, 1, seen);
		assertFalse(reads.contains(new Pos(1, 2, 1)));
		assertEquals(2, reads.size());
	}

	@Test void lookingAwayAndRangeBoundTheSensor() {
		Set<Pos> reads = new HashSet<>();
		observe(pos -> { reads.add(pos); return AIR; }, EYE, LENS, 1);
		assertFalse(reads.stream().anyMatch(pos -> pos.z() < 0));
		assertTrue(reads.stream().allMatch(pos -> Math.abs(pos.x()) <= 17 && Math.abs(pos.y() - 2) <= 17 && pos.z() <= 17));
	}

	@Test void darkAirDoesNotRevealTheShapeOfADistantCave() {
		Set<Pos> reads = new HashSet<>();
		var seen = new LinkedHashMap<Pos, Seen>();
		ray(pos -> { reads.add(pos); return new Sample("air", true, 0); }, EYE, 0, 0, 1, LENS, 1, seen);
		assertTrue(reads.stream().allMatch(pos -> pos.z() <= 2));
	}
}
