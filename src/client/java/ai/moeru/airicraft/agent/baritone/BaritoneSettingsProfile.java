package ai.moeru.airicraft.agent.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;

import java.util.Objects;

public final class BaritoneSettingsProfile {
	private BaritoneSettingsProfile() {
	}

	public static void apply() {
		apply(BaritoneAPI.getSettings());
	}

	public static void apply(Settings settings) {
		Objects.requireNonNull(settings, "settings");
		apply(new SettingsTarget() {
			@Override
			public void chatControl(boolean value) {
				settings.chatControl.value = value;
			}

			@Override
			public void chatControlAnyway(boolean value) {
				settings.chatControlAnyway.value = value;
			}

			@Override
			public void prefixControl(boolean value) {
				settings.prefixControl.value = value;
			}

			@Override
			public void allowInventory(boolean value) {
				settings.allowInventory.value = value;
			}

			@Override
			public void allowPlace(boolean value) {
				settings.allowPlace.value = value;
			}

			@Override
			public void allowParkour(boolean value) {
				settings.allowParkour.value = value;
			}

			@Override
			public void allowWaterBucketFall(boolean value) {
				settings.allowWaterBucketFall.value = value;
			}

			@Override
			public void allowBreak(boolean value) {
				settings.allowBreak.value = value;
			}

			@Override
			public void autoTool(boolean value) {
				settings.autoTool.value = value;
			}

			@Override
			public void mineScanDroppedItems(boolean value) {
				settings.mineScanDroppedItems.value = value;
			}

			@Override
			public void mineDropLoiterDurationMs(long value) {
				settings.mineDropLoiterDurationMSThanksLouca.value = value;
			}
		});
	}

	static void apply(SettingsTarget settings) {
		Objects.requireNonNull(settings, "settings");

		settings.chatControl(false);
		settings.chatControlAnyway(false);
		settings.prefixControl(false);
		settings.allowInventory(false);
		settings.allowPlace(false);
		settings.allowParkour(false);
		settings.allowWaterBucketFall(false);

		settings.allowBreak(true);
		settings.autoTool(true);
		settings.mineScanDroppedItems(true);
		settings.mineDropLoiterDurationMs(3000L);
	}

	interface SettingsTarget {
		void chatControl(boolean value);

		void chatControlAnyway(boolean value);

		void prefixControl(boolean value);

		void allowInventory(boolean value);

		void allowPlace(boolean value);

		void allowParkour(boolean value);

		void allowWaterBucketFall(boolean value);

		void allowBreak(boolean value);

		void autoTool(boolean value);

		void mineScanDroppedItems(boolean value);

		void mineDropLoiterDurationMs(long value);
	}
}
