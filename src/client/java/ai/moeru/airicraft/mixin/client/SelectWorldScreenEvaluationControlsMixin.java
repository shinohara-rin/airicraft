package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.agent.evaluation.EvaluationWorldFixtureService;
import ai.moeru.airicraft.agent.evaluation.EvaluationWorldListUiState;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SelectWorldScreen.class)
public abstract class SelectWorldScreenEvaluationControlsMixin extends Screen {
	private static final EvaluationWorldFixtureService AIRICRAFT_FIXTURES = EvaluationWorldFixtureService.createDefault();

	@Shadow
	protected TextFieldWidget searchBox;

	@Shadow
	private WorldListWidget levelList;

	private ButtonWidget airicraft$evalCopiesToggleButton;
	private ButtonWidget airicraft$cleanupEvalCopiesButton;

	protected SelectWorldScreenEvaluationControlsMixin(Text title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void airicraft$addEvaluationWorldControls(CallbackInfo ci) {
		int x = this.width / 2 - 310;
		int y = this.height - 28;
		if (x < 4) {
			x = 4;
			y = this.height - 76;
		}

		airicraft$evalCopiesToggleButton = addDrawableChild(ButtonWidget.builder(
				airicraft$toggleMessage(),
				button -> {
					EvaluationWorldListUiState.toggleEvaluationCopies();
					airicraft$updateEvaluationControls();
					airicraft$reloadWorldList();
				}
			)
			.dimensions(x, y, 72, 20)
			.tooltip(Tooltip.of(Text.literal("Show or hide Airicraft eval copies")))
			.build());
		airicraft$cleanupEvalCopiesButton = addDrawableChild(ButtonWidget.builder(
				Text.literal("Clean Eval"),
				button -> airicraft$cleanupEvalCopies()
			)
			.dimensions(x + 76, y, 72, 20)
			.tooltip(Tooltip.of(Text.literal("Delete all Airicraft eval copies")))
			.build());
		airicraft$updateEvaluationControls();
	}

	private void airicraft$cleanupEvalCopies() {
		try {
			EvaluationWorldFixtureService.CleanupResult result = AIRICRAFT_FIXTURES.cleanupDisposableWorlds();
			airicraft$updateEvaluationControls();
			airicraft$reloadWorldList();
			airicraft$showToast("Cleaned eval copies", result.deletedCount() + " deleted");
		}
		catch (EvaluationWorldFixtureService.EvaluationWorldFixtureException exception) {
			Airicraft.LOGGER.error("Failed to clean Airicraft eval copies", exception);
			airicraft$showToast("Clean eval copies failed", exception.getMessage());
		}
	}

	private void airicraft$updateEvaluationControls() {
		if (airicraft$evalCopiesToggleButton != null) {
			airicraft$evalCopiesToggleButton.setMessage(airicraft$toggleMessage());
		}
		if (airicraft$cleanupEvalCopiesButton != null) {
			try {
				airicraft$cleanupEvalCopiesButton.active = AIRICRAFT_FIXTURES.disposableWorldCount() > 0;
			}
			catch (EvaluationWorldFixtureService.EvaluationWorldFixtureException exception) {
				Airicraft.LOGGER.warn("Failed to count Airicraft eval copies", exception);
				airicraft$cleanupEvalCopiesButton.active = false;
			}
		}
	}

	private void airicraft$reloadWorldList() {
		if (levelList != null) {
			((WorldListWidgetInvoker) levelList).airicraft$load();
		}
		if (searchBox != null && levelList != null) {
			levelList.setSearch(searchBox.getText());
		}
	}

	private Text airicraft$toggleMessage() {
		return Text.literal(EvaluationWorldListUiState.showEvaluationCopies() ? "Eval: On" : "Eval: Off");
	}

	private void airicraft$showToast(String title, String message) {
		if (client == null) {
			return;
		}
		SystemToast.add(
			client.getToastManager(),
			SystemToast.Type.PERIODIC_NOTIFICATION,
			Text.literal(title),
			Text.literal(message == null || message.isBlank() ? "" : message)
		);
	}
}
