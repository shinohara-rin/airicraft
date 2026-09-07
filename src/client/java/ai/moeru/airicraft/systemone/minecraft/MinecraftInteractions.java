package ai.moeru.airicraft.systemone.minecraft;

import ai.moeru.airicraft.agent.control.CameraController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import java.util.Optional;

/** Small interaction mechanics shared by commands under the motor's single owner. */
final class MinecraftInteractions {
	static Optional<BlockHitResult> hit(MinecraftClient client, BlockPos pos) {
		var eye = client.player.getEyePos(); var center = Vec3d.ofCenter(pos);
		if (eye.squaredDistanceTo(center) > 4.5 * 4.5) return Optional.empty();
		new CameraController().lookAtNow(client, center);
		var hit = client.world.raycast(new RaycastContext(eye, center, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, client.player));
		return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos) ? Optional.of(hit) : Optional.empty();
	}
	static boolean selectItem(MinecraftClient client, String item) {
		for (int slot = 0; slot < 36; slot++) {
			var stack = client.player.getInventory().getStack(slot);
			if (!stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).toString().equals(item)) return selectSlot(client, slot);
		}
		return false;
	}
	static boolean selectSlot(MinecraftClient client, int slot) {
		var player = client.player;
		if (player.currentScreenHandler != player.playerScreenHandler || !player.currentScreenHandler.getCursorStack().isEmpty()) return false;
		if (slot >= 9) {
			client.interactionManager.clickSlot(player.playerScreenHandler.syncId, slot, 8, SlotActionType.SWAP, player);
			slot = 8;
		}
		player.getInventory().setSelectedSlot(slot);
		return true;
	}
}
