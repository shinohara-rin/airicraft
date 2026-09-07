package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.systemone.minecraft.InventorySyncAccess;
import ai.moeru.airicraft.systemone.voxel.InventoryReceipt;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;
import java.util.Optional;

@Mixin(ScreenHandler.class)
public abstract class ScreenHandlerInventoryReceiptMixin implements InventorySyncAccess {
	@Unique private long airicraft$sequence;
	@Unique private InventoryReceipt airicraft$receipt;
	@Inject(method = "updateSlotStacks", at = @At("TAIL"))
	private void airicraft$recordInventory(int revision, List<ItemStack> contents, ItemStack cursor, CallbackInfo ci) {
		airicraft$receipt = new InventoryReceipt(++airicraft$sequence, ((ScreenHandler) (Object) this).syncId, revision,
			contents.stream().map(ScreenHandlerInventoryReceiptMixin::airicraft$stack).toList(), airicraft$stack(cursor));
	}
	@Unique private static InventoryReceipt.Stack airicraft$stack(ItemStack stack) {
		return new InventoryReceipt.Stack(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount());
	}
	@Override public Optional<InventoryReceipt> airicraft$inventoryReceipt() { return Optional.ofNullable(airicraft$receipt); }
}
