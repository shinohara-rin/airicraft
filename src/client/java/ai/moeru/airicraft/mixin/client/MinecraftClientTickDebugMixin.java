package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public class MinecraftClientTickDebugMixin {
	@Inject(method = "shouldTick", at = @At("RETURN"), cancellable = true)
	private void airicraft$gateClientTicks(CallbackInfoReturnable<Boolean> callback) {
		callback.setReturnValue(AiricraftClient.runtimeController().allowClientTick(callback.getReturnValue()));
	}
}
