package ai.moeru.airicraft.systemone.voxel;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;

/** Conservative support memory from observed blocks under a grounded body's footprint. */
public final class FootingMemory {
	public record Contact(Pos minimum, Pos maximum) {}
	private FootingMemory() {}
	public static Set<Pos> update(Set<Pos> previous, Map<Pos, Seen> known, Optional<Contact> contact) {
		var next = new HashSet<>(previous);
		next.removeIf(pos -> known.get(pos) != null && known.get(pos).empty());
		contact.ifPresent(area -> {
			for (int x = area.minimum().x(); x <= area.maximum().x(); x++) for (int z = area.minimum().z(); z <= area.maximum().z(); z++) {
				Pos pos = new Pos(x, area.minimum().y(), z); Seen seen = known.get(pos);
				if (seen != null && seen.identified() && !seen.empty()) next.add(pos);
			}
		});
		return Set.copyOf(next);
	}
}
