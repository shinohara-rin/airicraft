package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.agent.evaluation.EvaluationWorldFixtureService;
import ai.moeru.airicraft.agent.evaluation.FrozenWorldLoadService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldListWidget.WorldEntry.class)
public class WorldEntryFrozenLoadMixin {
	private static final FrozenWorldLoadService AIRICRAFT_FROZEN_WORLDS = FrozenWorldLoadService.createDefault();

	@Shadow
	@Final
	private MinecraftClient client;

	@Shadow
	@Final
	LevelSummary level;

	@Inject(method = "play", at = @At("HEAD"), cancellable = true)
	private void airicraft$loadFrozenWorldCopy(CallbackInfo ci) {
		if (!level.isSelectable() || level instanceof LevelSummary.SymlinkLevelSummary) {
			return;
		}

		FrozenWorldLoadService.WorldFixtureStatus status = AIRICRAFT_FROZEN_WORLDS.statusForDirectory(level.getName());
		if (!status.frozenLoadMustDetour()) {
			return;
		}

		ci.cancel();
		try {
			EvaluationWorldFixtureService.RestoredWorld restoredWorld = AIRICRAFT_FROZEN_WORLDS
				.restoreFrozenDisposableCopy(level.getName())
				.orElseThrow(() -> new EvaluationWorldFixtureService.EvaluationWorldFixtureException(
					"scenario_not_frozen",
					"Scenario is not frozen: " + level.getName()
				));
			client.createIntegratedServerLoader().start(restoredWorld.worldName(), () -> {
			});
		}
		catch (RuntimeException exception) {
			Airicraft.LOGGER.error("Failed to restore frozen scenario world {}", level.getName(), exception);
			showRestoreFailureToast(exception);
		}
	}

	private void showRestoreFailureToast(RuntimeException exception) {
		String message = exception.getMessage();
		if (message == null || message.isBlank()) {
			message = "Failed to restore disposable world copy";
		}
		SystemToast.add(
			client.getToastManager(),
			SystemToast.Type.WORLD_ACCESS_FAILURE,
			Text.literal("Airicraft frozen world"),
			Text.literal(message)
		);
	}
}
