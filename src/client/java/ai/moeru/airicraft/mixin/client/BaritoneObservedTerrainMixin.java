package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.systemone.minecraft.ObservedTerrain;
import baritone.utils.BlockStateInterface;
import net.minecraft.block.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Desc;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Bypass both live chunk lookup and Baritone's cached terrain in the replacement runtime. */
@Mixin(value = BlockStateInterface.class, remap = false)
public class BaritoneObservedTerrainMixin {
	@Unique private ObservedTerrain.View airicraft$terrain;

	@Inject(method = "<init>*", at = @At("RETURN"), remap = false)
	private void airicraft$captureTerrain(CallbackInfo ci) {
		if (ObservedTerrain.ENABLED) airicraft$terrain = ObservedTerrain.capture();
	}

	@Inject(target = @Desc(value = "get0", args = {int.class, int.class, int.class}, ret = BlockState.class),
		at = @At("HEAD"), cancellable = true, remap = false)
	private void airicraft$readObserved(int x, int y, int z, CallbackInfoReturnable<BlockState> cir) {
		if (ObservedTerrain.ENABLED) cir.setReturnValue(airicraft$terrain.get(x, y, z));
	}

	@Inject(method = {"isLoaded", "worldContainsLoadedChunk"}, at = @At("HEAD"), cancellable = true, remap = false)
	private void airicraft$knownColumn(int x, int z, CallbackInfoReturnable<Boolean> cir) {
		if (ObservedTerrain.ENABLED) cir.setReturnValue(airicraft$terrain.knownColumn(x, z));
	}
}
