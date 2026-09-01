package ai.moeru.airicraft.agent.tasks;

import net.minecraft.client.option.KeyBinding;

public final class OwnedKeyPress {
	private boolean ownsKey;

	public void press(KeyBinding keyBinding) {
		if (keyBinding == null) {
			return;
		}
		keyBinding.setPressed(true);
		ownsKey = true;
	}

	public void release(KeyBinding keyBinding) {
		if (!ownsKey) {
			return;
		}
		ownsKey = false;
		if (keyBinding != null) {
			keyBinding.setPressed(false);
		}
	}
}
