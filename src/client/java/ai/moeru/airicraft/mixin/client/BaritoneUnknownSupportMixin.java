package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.systemone.minecraft.ObservedTerrain;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.precompute.Ternary;
import baritone.api.utils.IPlayerContext;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.pathing.movement.MovementState;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Desc;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Unknown terrain blocks movement but must never become invented solid footing. */
@Mixin(value = MovementHelper.class, remap = false)
public interface BaritoneUnknownSupportMixin {
	@Inject(target = @Desc(value = "attemptToPlaceABlock", args = {MovementState.class, IBaritone.class, BlockPos.class, boolean.class, boolean.class}, ret = MovementHelper.PlaceResult.class),
		at = @At("HEAD"), cancellable = true)
	private static void airicraft$noImplicitPlacement(MovementState state, IBaritone baritone, BlockPos pos, boolean preferDown, boolean sneak,
		CallbackInfoReturnable<MovementHelper.PlaceResult> cir) {
		if (ObservedTerrain.ENABLED) {
			state.setStatus(MovementStatus.UNREACHABLE);
			cir.setReturnValue(MovementHelper.PlaceResult.NO_OPTION);
		}
	}

	@Redirect(target = @Desc(value = "fullyPassable", args = {IPlayerContext.class, BlockPos.class}, ret = boolean.class), at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"))
	private static BlockState airicraft$observedPassability(World world, BlockPos pos) {
		return ObservedTerrain.ENABLED ? ObservedTerrain.get(pos) : world.getBlockState(pos);
	}

	@Inject(target = @Desc(value = "canWalkOnBlockState", args = BlockState.class, ret = Ternary.class),
		at = @At("HEAD"), cancellable = true, remap = false)
	private static void airicraft$unknownCannotSupport(BlockState state, CallbackInfoReturnable<Ternary> cir) {
		if (ObservedTerrain.ENABLED && state.isOf(Blocks.BARRIER)) cir.setReturnValue(Ternary.NO);
	}
}
