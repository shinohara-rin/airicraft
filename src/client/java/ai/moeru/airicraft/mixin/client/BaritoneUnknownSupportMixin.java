package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.systemone.minecraft.ObservedTerrain;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.precompute.Ternary;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Desc;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Unknown terrain blocks movement but must never become invented solid footing. */
@Mixin(value = MovementHelper.class, remap = false)
public interface BaritoneUnknownSupportMixin {
	@Inject(target = @Desc(value = "canWalkOnBlockState", args = BlockState.class, ret = Ternary.class),
		at = @At("HEAD"), cancellable = true, remap = false)
	private static void airicraft$unknownCannotSupport(BlockState state, CallbackInfoReturnable<Ternary> cir) {
		if (ObservedTerrain.ENABLED && state.isOf(Blocks.BARRIER)) cir.setReturnValue(Ternary.NO);
	}
}
