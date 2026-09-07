package ai.moeru.airicraft.systemone.minecraft;

import ai.moeru.airicraft.systemone.TaskKernel.Outcome;
import ai.moeru.airicraft.systemone.voxel.VoxelCommand.Craft;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import java.util.Optional;

/** One recipe batch. This command never acquires ingredients or chooses a workstation. */
final class MinecraftCrafting {
	private enum Phase { OPEN, WAIT_FOR_STATION, FILL, WAIT_FOR_CELL, WAIT_FOR_RESULT, WAIT_FOR_INVENTORY }
	private final Craft command;
	private final long started;
	private Phase phase = Phase.OPEN;
	private int nextCell;
	private int beforeCount;
	private int syncId;
	private int settledTicks;

	MinecraftCrafting(Craft command, long started) { this.command = command; this.started = started; }
	String status() { return phase.name(); }
	Optional<Outcome> tick(MinecraftClient client, long tick) {
		if (tick - started > 200) return failed("crafting_timeout:" + phase);
		var player = client.player;
		ScreenHandler handler = player.currentScreenHandler;
		if (phase == Phase.OPEN) {
			if (handler != player.playerScreenHandler || !handler.getCursorStack().isEmpty()) return failed("crafting_container_not_clear");
			if (command.recipe().width() == 3) {
				var target = command.station(); var pos = new BlockPos(target.x(), target.y(), target.z());
				var hit = MinecraftInteractions.hit(client, pos);
				if (hit.isEmpty() || !Registries.BLOCK.getId(client.world.getBlockState(pos).getBlock()).toString().equals("minecraft:crafting_table")) return failed("crafting_station_not_observed");
				client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit.get());
				phase = Phase.WAIT_FOR_STATION;
				return Optional.empty();
			}
			syncId = handler.syncId; phase = Phase.FILL;
		}
		if (phase == Phase.WAIT_FOR_STATION) {
			if (!(handler instanceof CraftingScreenHandler)) return Optional.empty();
			syncId = handler.syncId; phase = Phase.FILL;
		}
		if (handler.syncId != syncId || (command.recipe().width() == 2 ? !(handler instanceof PlayerScreenHandler) : !(handler instanceof CraftingScreenHandler))) return failed("crafting_container_changed");
		int gridSize = command.recipe().width() * command.recipe().width();
		if (phase == Phase.FILL) {
			if (!handler.getCursorStack().isEmpty()) return failed("crafting_cursor_occupied");
			if (nextCell == 0) for (int slot = 1; slot <= gridSize; slot++) if (handler.getSlot(slot).hasStack()) return failed("crafting_grid_occupied");
			var cell = command.recipe().cells().get(nextCell);
			int source = -1;
			for (int slot = inventoryStart(handler); slot < handler.slots.size(); slot++) {
				var stack = handler.getSlot(slot).getStack();
				if (!stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).toString().equals(cell.item())) { source = slot; break; }
			}
			if (source < 0) return failed("crafting_ingredient_missing:" + cell.item());
			client.interactionManager.clickSlot(syncId, source, 0, SlotActionType.PICKUP, player);
			client.interactionManager.clickSlot(syncId, 1 + cell.slot(), 1, SlotActionType.PICKUP, player);
			client.interactionManager.clickSlot(syncId, source, 0, SlotActionType.PICKUP, player);
			++nextCell;
			settledTicks = 0; phase = Phase.WAIT_FOR_CELL;
			return Optional.empty();
		}
		if (phase == Phase.WAIT_FOR_CELL) {
			// Click prediction can be corrected by intermediate server slot/cursor updates.
			// Observe a settled transfer before issuing another ingredient's clicks.
			boolean settled = handler.getCursorStack().isEmpty();
			for (int index = 0; index < nextCell && settled; index++) {
				var cell = command.recipe().cells().get(index); var stack = handler.getSlot(1 + cell.slot()).getStack();
				settled = stack.getCount() == 1 && Registries.ITEM.getId(stack.getItem()).toString().equals(cell.item());
			}
			settledTicks = settled ? settledTicks + 1 : 0;
			if (settledTicks >= 2) phase = nextCell == command.recipe().cells().size() ? Phase.WAIT_FOR_RESULT : Phase.FILL;
			return Optional.empty();
		}
		if (phase == Phase.WAIT_FOR_RESULT) {
			var result = handler.getSlot(0).getStack();
			if (!result.isEmpty() && Registries.ITEM.getId(result.getItem()).toString().equals(command.recipe().output()) && result.getCount() == command.recipe().yield()) {
				beforeCount = inventoryCount(client, command.recipe().output());
				client.interactionManager.clickSlot(syncId, 0, 0, SlotActionType.QUICK_MOVE, player);
				phase = Phase.WAIT_FOR_INVENTORY;
			}
			return Optional.empty();
		}
		if (inventoryCount(client, command.recipe().output()) >= beforeCount + command.recipe().yield()) return Optional.of(Outcome.success("crafted_inventory_observed:" + command.recipe().output()));
		return Optional.empty();
	}

	/** Drain the cursor/grid before acknowledging release. No item is discarded to obtain an ACK. */
	boolean release(MinecraftClient client) {
		if (client.player == null || client.interactionManager == null) return true;
		var player = client.player; var handler = player.currentScreenHandler;
		var cursor = handler.getCursorStack();
		if (!cursor.isEmpty()) {
			for (int index = inventoryStart(handler); index < handler.slots.size(); index++) {
				var slot = handler.getSlot(index); var stack = slot.getStack();
				if (slot.canInsert(cursor) && (stack.isEmpty() || (net.minecraft.item.ItemStack.areItemsAndComponentsEqual(stack, cursor) && stack.getCount() < slot.getMaxItemCount(cursor)))) {
					client.interactionManager.clickSlot(handler.syncId, index, 0, SlotActionType.PICKUP, player);
					return false;
				}
			}
			return false;
		}
		int gridSize = handler instanceof CraftingScreenHandler ? 9 : handler instanceof PlayerScreenHandler ? 4 : 0;
		for (int slot = 1; slot <= gridSize; slot++) if (handler.getSlot(slot).hasStack()) {
			client.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.QUICK_MOVE, player);
			return false;
		}
		if (handler != player.playerScreenHandler) { player.closeHandledScreen(); return false; }
		if (client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?>) client.setScreen(null);
		return true;
	}
	private static int inventoryStart(ScreenHandler handler) { return handler instanceof PlayerScreenHandler ? 9 : handler instanceof CraftingScreenHandler ? 10 : Math.max(0, handler.slots.size() - 36); }
	private static Optional<Outcome> failed(String reason) { return Optional.of(Outcome.failure(reason)); }
	private static int inventoryCount(MinecraftClient client, String item) {
		int count = 0;
		for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
			var stack = client.player.getInventory().getStack(slot);
			if (!stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).toString().equals(item)) count += stack.getCount();
		}
		return count;
	}
}
