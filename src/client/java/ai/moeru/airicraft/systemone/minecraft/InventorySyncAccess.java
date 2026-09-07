package ai.moeru.airicraft.systemone.minecraft;

import ai.moeru.airicraft.systemone.voxel.InventoryReceipt;
import java.util.Optional;

/** Implemented on each client screen handler; changing containers also changes receipt identity. */
public interface InventorySyncAccess {
	Optional<InventoryReceipt> airicraft$inventoryReceipt();
}
