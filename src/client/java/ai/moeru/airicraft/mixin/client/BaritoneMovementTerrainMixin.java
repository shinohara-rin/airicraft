package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.systemone.minecraft.ObservedTerrain;
import baritone.pathing.movement.movements.MovementFall;
import baritone.pathing.path.PathExecutor;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Execution-time ladder scans and sprint checks must use the same evidence boundary as search. */
@Mixin(value = {MovementFall.class, PathExecutor.class}, remap = false)
public class BaritoneMovementTerrainMixin {
	@Redirect(method = "*", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"))
	private static BlockState airicraft$observedMovementTerrain(World world, BlockPos pos) {
		return ObservedTerrain.ENABLED ? ObservedTerrain.get(pos) : world.getBlockState(pos);
	}
}
