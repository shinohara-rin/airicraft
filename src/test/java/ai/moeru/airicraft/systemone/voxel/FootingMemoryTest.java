package ai.moeru.airicraft.systemone.voxel;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;

class FootingMemoryTest {
	private static final Pos CENTER = new Pos(0, 0, 0), EDGE = new Pos(1, 0, 0);
	@Test void straddlingAnExcavationProtectsTheObservedEdgeInsteadOfAirUnderTheCenter() {
		var known = Map.of(CENTER, new Seen("minecraft:air", true, true, false, 12, 2), EDGE, new Seen("minecraft:stone", false, true, true, 12, 2));
		var contact = Optional.of(new FootingMemory.Contact(CENTER, EDGE));
		assertEquals(Set.of(EDGE), FootingMemory.update(Set.of(CENTER), known, contact));
	}
	@Test void missingObservationDoesNotEraseARememberedSupportOrInventANewOne() {
		assertEquals(Set.of(CENTER), FootingMemory.update(Set.of(CENTER), Map.of(), Optional.of(new FootingMemory.Contact(CENTER, EDGE))));
		assertEquals(Set.of(), FootingMemory.update(Set.of(CENTER), Map.of(CENTER, new Seen("minecraft:air", true, true, false, 12, 2)), Optional.empty()));
	}
}
