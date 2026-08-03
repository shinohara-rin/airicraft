package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public class MinecraftClientTickDebugMixin {
	@WrapWithCondition(
		method = "render",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;tick()V")
	)
	private boolean airicraft$gateClientTick(MinecraftClient client) {
		return AiricraftClient.runtimeController().startClientTick();
	}

	@Inject(method = "shouldTick", at = @At("RETURN"), cancellable = true)
	private void airicraft$gateClientTicks(CallbackInfoReturnable<Boolean> callback) {
		callback.setReturnValue(AiricraftClient.runtimeController().allowRenderTickCounter(callback.getReturnValue()));
	}
}
