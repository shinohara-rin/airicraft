package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.systemone.minecraft.ObservedTerrain;
import baritone.api.utils.VecUtils;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Locomotion aims at a geometric cell center without inspecting a hidden collision shape. */
@Mixin(value = VecUtils.class, remap = false)
public class BaritoneNavigationAimMixin {
	@Inject(method = "calculateBlockCenter", at = @At("HEAD"), cancellable = true)
	private static void airicraft$cellCenter(World world, BlockPos pos, CallbackInfoReturnable<Vec3d> cir) {
		if (ObservedTerrain.ENABLED) cir.setReturnValue(Vec3d.ofCenter(pos));
	}
}
