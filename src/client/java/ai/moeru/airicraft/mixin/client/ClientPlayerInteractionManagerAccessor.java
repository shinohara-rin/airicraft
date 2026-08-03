package ai.moeru.airicraft.mixin.client;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientPlayerInteractionManager.class)
public interface ClientPlayerInteractionManagerAccessor {
	@Accessor("currentBreakingPos")
	BlockPos airicraft$currentBreakingPos();

	@Accessor("currentBreakingProgress")
	float airicraft$currentBreakingProgress();

	@Accessor("breakingBlock")
	boolean airicraft$breakingBlock();
}
