package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.systemone.minecraft.ObservedTerrain;
import baritone.api.utils.input.Input;
import baritone.utils.InputOverrideHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Physical interactions belong to MinecraftMotor. Baritone only supplies locomotion. */
@Mixin(value = InputOverrideHandler.class, remap = false)
public class BaritoneNavigationInputMixin {
	@Inject(method = "isInputForcedDown", at = @At("HEAD"), cancellable = true)
	private void airicraft$noImplicitInteraction(Input input, CallbackInfoReturnable<Boolean> cir) {
		if (ObservedTerrain.ENABLED && (input == Input.CLICK_LEFT || input == Input.CLICK_RIGHT)) cir.setReturnValue(false);
	}
}
