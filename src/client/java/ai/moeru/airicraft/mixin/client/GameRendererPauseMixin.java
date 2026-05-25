package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GameRenderer.class)
public class GameRendererPauseMixin {
	@Redirect(
		method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/MinecraftClient;openGameMenu(Z)V"
		)
	)
	private void airicraft$suppressAutoPauseOnFocusLost(MinecraftClient client, boolean pauseOnly) {
		if (AiricraftClient.runtimeController().config().suppressAutoPauseOnFocusLost()) {
			return;
		}
		client.openGameMenu(pauseOnly);
	}
}
