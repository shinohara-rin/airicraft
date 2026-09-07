package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.systemone.minecraft.ObservedTerrain;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/** Baritone cannot probe block faces for an interaction the motor has not authorized. */
@Mixin(value = RotationUtils.class, remap = false)
public class BaritoneNavigationReachMixin {
	@Inject(method = "reachable*", at = @At("HEAD"), cancellable = true)
	private static void airicraft$noInteractionProbe(CallbackInfoReturnable<Optional<Rotation>> cir) {
		if (ObservedTerrain.ENABLED) cir.setReturnValue(Optional.empty());
	}
}
