package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.systemone.minecraft.ObservedTerrain;
import baritone.pathing.movement.movements.MovementFall;
import net.minecraft.entity.player.PlayerInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Falling may finish in water, but collecting or placing that water is a separate motor command. */
@Mixin(value = MovementFall.class, remap = false)
public class BaritoneFallInventoryMixin {
	@Redirect(method = "updateState", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/entity/player/PlayerInventory;isValidHotbarIndex(I)Z"))
	private static boolean airicraft$noBucketSelection(int slot) {
		return !ObservedTerrain.ENABLED && PlayerInventory.isValidHotbarIndex(slot);
	}
}
