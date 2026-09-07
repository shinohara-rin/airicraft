package ai.moeru.airicraft.systemone.voxel;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionKnowledge.*;
import ai.moeru.airicraft.systemone.voxel.InventoryReceipt.Stack;

class InventoryReceiptTest {
	private static final Recipe RECIPE = new Recipe("sticks", "stick", 4, 2, List.of(new Cell(0, "plank"), new Cell(2, "plank")));
	private static final Stack EMPTY = new Stack("air", 0);
	private static final List<Stack> FIRST_CELL = List.of(EMPTY, new Stack("plank", 1), EMPTY, EMPTY, EMPTY);
	@Test void matchingContentsMustComeFromANewerResponseForTheSameContainer() {
		assertFalse(new InventoryReceipt(3, 7, 12, FIRST_CELL, EMPTY).confirms(3, 7, RECIPE, 1));
		assertFalse(new InventoryReceipt(4, 8, 12, FIRST_CELL, EMPTY).confirms(3, 7, RECIPE, 1));
		assertTrue(new InventoryReceipt(4, 7, 12, FIRST_CELL, EMPTY).confirms(3, 7, RECIPE, 1));
	}
	@Test void intermediateServerCursorAndPartialOrOverfilledGridDoNotConfirmATransfer() {
		assertFalse(new InventoryReceipt(4, 7, 12, FIRST_CELL, new Stack("plank", 5)).confirms(3, 7, RECIPE, 1));
		assertFalse(new InventoryReceipt(4, 7, 12, FIRST_CELL, EMPTY).confirms(3, 7, RECIPE, 2));
		var wrong = new ArrayList<>(FIRST_CELL); wrong.set(1, new Stack("plank", 2));
		assertFalse(new InventoryReceipt(4, 7, 12, wrong, EMPTY).confirms(3, 7, RECIPE, 1));
		wrong.set(1, new Stack("plank", 1)); wrong.set(4, new Stack("stone", 1));
		assertFalse(new InventoryReceipt(4, 7, 12, wrong, EMPTY).confirms(3, 7, RECIPE, 1));
	}
}
