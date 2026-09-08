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
	private static final Sample AIR = new Sample("air", true, false, 15);

	@Test void statePropertiesAreImmutableAndDoNotEscapeDarkness() {
		var properties = new java.util.HashMap<>(java.util.Map.of("type","top","waterlogged","true"));
		var sample = new Sample("slab",false,false,15,false,Attachment.none(),properties);
		properties.clear();
		var observed = observe(pos -> sample,EYE,LENS,1).values().iterator().next();
		assertEquals(java.util.Map.of("type","top","waterlogged","true"),observed.properties());
		assertThrows(UnsupportedOperationException.class,()->observed.properties().clear());
		var dark = observe(pos -> new Sample("slab",false,false,0,false,Attachment.none(),sample.properties()),EYE,LENS,2);
		assertTrue(dark.values().stream().allMatch(v -> v.properties().isEmpty() && !v.identified()));
		assertThrows(IllegalArgumentException.class,()->new Seen("unknown",false,false,false,0,2,false,Attachment.none(),sample.properties()));
	}
	@Test void attachmentIsImmutableAndOnlyExposedForIdentifiedCells() {
		var faces = new HashSet<>(Set.of(VoxelCommand.Face.NORTH));
		var shape = new Attachment(faces,false); faces.clear();
		assertEquals(Set.of(VoxelCommand.Face.NORTH),shape.fullFaces());
		var lit = observe(pos -> new Sample("fixture",false,true,15,false,shape),EYE,LENS,1);
		assertEquals(shape,lit.values().iterator().next().attachment());
		var dark = observe(pos -> new Sample("fixture",false,true,0,false,shape),EYE,LENS,1);
		assertTrue(dark.values().stream().allMatch(v -> v.attachment().equals(Attachment.none())));
	}
	@Test void hiddenLayoutCannotChangeObservationOrQueries() {
		Set<Pos> firstReads = new HashSet<>(), secondReads = new HashSet<>();
		Scene first = pos -> { firstReads.add(pos); return pos.z() < 3 ? AIR : new Sample(pos.z() == 3 ? "wall" : "iron", false, true, 15); };
		Scene second = pos -> { secondReads.add(pos); return pos.z() < 3 ? AIR : new Sample(pos.z() == 3 ? "wall" : "diamond", false, true, 15); };
		assertEquals(observe(first, EYE, LENS, 1), observe(second, EYE, LENS, 1));
		assertEquals(firstReads, secondReads);
		assertFalse(firstReads.stream().anyMatch(pos -> pos.z() > 3));
	}

	@Test void darknessHidesIdentityWithoutPretendingOccupiedSpaceIsAir() {
		Scene first = pos -> new Sample("iron", false, true, 0);
		Scene second = pos -> new Sample("stone", false, false, 0);
		var seen = observe(first, EYE, LENS, 1);
		assertEquals(seen, observe(second, EYE, LENS, 1));
		assertTrue(seen.values().stream().noneMatch(Seen::identified));
		assertTrue(seen.values().stream().noneMatch(Seen::empty));
		assertTrue(seen.values().stream().noneMatch(Seen::fullSupport));
	}
	@Test void bodyClearanceDoesNotMakeAnOccupiedVoxelTransparent() {
		var seen = new LinkedHashMap<Pos, Seen>();
		ray(pos -> pos.z() < 2 ? AIR : new Sample("torch", false, false, 15, true), EYE, 0, 0, 1, LENS, 1, seen);
		assertTrue(seen.get(new Pos(0, 2, 2)).traversable());
		assertFalse(seen.get(new Pos(0, 2, 2)).empty());
		assertFalse(seen.containsKey(new Pos(0, 2, 3)), "collision clearance does not extend sensing rays");
		var dark = observe(pos -> new Sample("plant", false, false, 0, true), EYE, LENS, 1);
		assertTrue(dark.values().stream().noneMatch(Seen::traversable), "unidentified material cannot grant clearance");
	}
	@Test void theCameraCanLookOutOfANoncollidingFixtureInItsOwnVoxel() {
		var seen = new LinkedHashMap<Pos, Seen>();
		ray(pos -> pos.z() == 0 ? new Sample("wall_torch", false, false, 14, true)
			: pos.z() == 1 ? AIR : new Sample("stone", false, true, 14), EYE, 0, 0, 1, LENS, 1, seen);
		assertTrue(seen.containsKey(new Pos(0, 2, 1)));
		assertEquals("stone", seen.get(new Pos(0, 2, 2)).blockId());
		assertFalse(seen.containsKey(new Pos(0, 2, 3)));
		var solid = observe(pos -> new Sample("stone", false, true, 14), EYE, LENS, 1);
		assertEquals(1, solid.size(), "origin exception cannot see out through solid terrain");
	}

	@Test void aLitFaceCanBeIdentifiedFromTheAdjacentAir() {
		var seen = new LinkedHashMap<Pos, Seen>();
		ray(pos -> pos.z() < 2 ? AIR : new Sample("stone", false, true, 0), EYE, 0, 0, 1, LENS, 7, seen);
		assertEquals(new Seen("stone", false, true, true, 15, 7), seen.get(new Pos(0, 2, 2)));
	}

	@Test void diagonalCornerDoesNotRevealTerrainBehindTheFirstWall() {
		Set<Pos> reads = new HashSet<>();
		var seen = new LinkedHashMap<Pos, Seen>();
		ray(pos -> { reads.add(pos); return pos.equals(new Pos(0, 2, 0)) ? AIR : new Sample("wall", false, true, 15); },
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
		ray(pos -> { reads.add(pos); return new Sample("air", true, false, 0); }, EYE, 0, 0, 1, LENS, 1, seen);
		assertTrue(reads.stream().allMatch(pos -> pos.z() <= 2));
	}
}
