package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.agent.evaluation.FrozenWorldLoadService;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelSummary.class)
public class LevelSummaryMixin {
	private static final FrozenWorldLoadService AIRICRAFT_FROZEN_WORLDS = FrozenWorldLoadService.createDefault();

	@Inject(method = "createDetails", at = @At("RETURN"), cancellable = true)
	private void airicraft$appendFixtureStatus(CallbackInfoReturnable<Text> cir) {
		String directoryName = ((LevelSummary) (Object) this).getName();
		AIRICRAFT_FROZEN_WORLDS.statusForDirectory(directoryName)
			.menuLabel()
			.ifPresent(label -> cir.setReturnValue(
				Text.empty()
					.append(cir.getReturnValue())
					.append(Text.literal(", " + label).formatted(Formatting.AQUA))
			));
	}
}
