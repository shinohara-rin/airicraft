package ai.moeru.airicraft.mixin.client;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the effective per-frame FOV so dataset captures can rebuild the exact
 * projection matrix via {@code GameRenderer#getBasicProjectionMatrix}.
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Invoker("getFov")
	float airicraft$invokeGetFov(Camera camera, float tickDelta, boolean changingFov);
}
