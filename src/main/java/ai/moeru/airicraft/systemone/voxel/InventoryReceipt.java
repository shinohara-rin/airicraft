package ai.moeru.airicraft.systemone.voxel;

import java.util.List;

/** A complete server inventory response, kept separate from predicted client slot contents. */
public record InventoryReceipt(long sequence, int syncId, int revision, List<Stack> slots, Stack cursor) {
	public record Stack(String item, int count) {}
	public InventoryReceipt { slots = List.copyOf(slots); }
	public boolean confirms(long after, int expectedSyncId, ProductionKnowledge.Recipe recipe, int filled) {
		int size = recipe.width() * recipe.width();
		if (sequence <= after || syncId != expectedSyncId || cursor.count() != 0 || slots.size() <= size) return false;
		for (int slot = 0; slot < size; slot++) {
			String expected = null;
			for (int i = 0; i < filled; i++) if (recipe.cells().get(i).slot() == slot) expected = recipe.cells().get(i).item();
			Stack actual = slots.get(slot + 1);
			if (expected == null ? actual.count() != 0 : actual.count() != 1 || !expected.equals(actual.item())) return false;
		}
		return true;
	}
}
