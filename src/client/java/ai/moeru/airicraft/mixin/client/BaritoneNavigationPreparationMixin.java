package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.systemone.minecraft.ObservedTerrain;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A changed route returns failure; it cannot turn navigation into mining or query hidden entities. */
@Mixin(value = Movement.class, remap = false)
public class BaritoneNavigationPreparationMixin {
	@Shadow @Final protected IPlayerContext ctx;
	@Shadow @Final protected BetterBlockPos[] positionsToBreak;

	@Inject(method = "prepared", at = @At("HEAD"), cancellable = true)
	private void airicraft$observeObstacles(MovementState state, CallbackInfoReturnable<Boolean> cir) {
		if (!ObservedTerrain.ENABLED) return;
		for (var pos : positionsToBreak) {
			if (!MovementHelper.canWalkThrough(ctx, pos)) {
				state.setStatus(MovementStatus.UNREACHABLE);
				break;
			}
		}
		cir.setReturnValue(true);
	}
}
