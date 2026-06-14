package ai.moeru.airicraft.agent.baritone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaritoneSettingsProfileTest {
	@Test
	void appliesConservativeSettingsProfile() {
		FakeSettingsTarget target = new FakeSettingsTarget();

		BaritoneSettingsProfile.apply(target);

		assertFalse(target.chatControl);
		assertFalse(target.chatControlAnyway);
		assertFalse(target.prefixControl);
		assertFalse(target.allowInventory);
		assertFalse(target.allowPlace);
		assertFalse(target.allowParkour);
		assertFalse(target.allowWaterBucketFall);
		assertTrue(target.allowBreak);
		assertTrue(target.autoTool);
		assertTrue(target.mineScanDroppedItems);
		assertEquals(750L, target.mineDropLoiterDurationMs);
	}

	private static final class FakeSettingsTarget implements BaritoneSettingsProfile.SettingsTarget {
		private boolean chatControl = true;
		private boolean chatControlAnyway = true;
		private boolean prefixControl = true;
		private boolean allowInventory = true;
		private boolean allowPlace = true;
		private boolean allowParkour = true;
		private boolean allowWaterBucketFall = true;
		private boolean allowBreak = false;
		private boolean autoTool = false;
		private boolean mineScanDroppedItems = false;
		private long mineDropLoiterDurationMs = 250L;

		@Override
		public void chatControl(boolean value) {
			chatControl = value;
		}

		@Override
		public void chatControlAnyway(boolean value) {
			chatControlAnyway = value;
		}

		@Override
		public void prefixControl(boolean value) {
			prefixControl = value;
		}

		@Override
		public void allowInventory(boolean value) {
			allowInventory = value;
		}

		@Override
		public void allowPlace(boolean value) {
			allowPlace = value;
		}

		@Override
		public void allowParkour(boolean value) {
			allowParkour = value;
		}

		@Override
		public void allowWaterBucketFall(boolean value) {
			allowWaterBucketFall = value;
		}

		@Override
		public void allowBreak(boolean value) {
			allowBreak = value;
		}

		@Override
		public void autoTool(boolean value) {
			autoTool = value;
		}

		@Override
		public void mineScanDroppedItems(boolean value) {
			mineScanDroppedItems = value;
		}

		@Override
		public void mineDropLoiterDurationMs(long value) {
			mineDropLoiterDurationMs = value;
		}
	}
}
