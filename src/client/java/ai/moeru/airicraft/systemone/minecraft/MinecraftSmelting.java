package ai.moeru.airicraft.systemone.minecraft;

import ai.moeru.airicraft.systemone.TaskKernel.Outcome;
import ai.moeru.airicraft.systemone.voxel.ProductionKnowledge.Smelt;
import ai.moeru.airicraft.systemone.voxel.VoxelCommand;
import ai.moeru.airicraft.systemone.voxel.VoxelCommand.*;
import ai.moeru.airicraft.systemone.voxel.VoxelObservation.Pos;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import java.util.Optional;

/** A bounded furnace interaction. Cooking time belongs to a sleeping domain task, not this motor. */
final class MinecraftSmelting {
	private enum Phase { OPEN, WAIT_FOR_SCREEN, LOAD_FUEL, LOAD_INPUT, CHECK_INPUT, COLLECT, CHECK_INVENTORY }
	private final VoxelCommand command;
	private final Smelt recipe;
	private final Pos station;
	private final long started;
	private Phase phase = Phase.OPEN;
	private int syncId, before;

	MinecraftSmelting(VoxelCommand command, long tick) {
		this.command = command; started = tick;
		switch (command) {
			case StartSmelt start -> { recipe = start.recipe(); station = start.station(); }
			case CollectSmelt collect -> { recipe = collect.recipe(); station = collect.station(); }
			default -> throw new IllegalArgumentException("Furnace command required");
		}
	}
	String status() { return phase.name(); }
	Optional<Outcome> tick(MinecraftClient client, long tick) {
		if (tick - started > 100) return fail("furnace_interaction_timeout:" + phase);
		var player = client.player; var handler = player.currentScreenHandler;
		if (phase == Phase.OPEN) {
			if (handler != player.playerScreenHandler || !handler.getCursorStack().isEmpty()) return fail("furnace_container_not_clear");
			var pos = new BlockPos(station.x(), station.y(), station.z()); var hit = MinecraftInteractions.hit(client, pos);
			if (hit.isEmpty() || !Registries.BLOCK.getId(client.world.getBlockState(pos).getBlock()).toString().equals(recipe.station())) return fail("furnace_not_observed");
			client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit.get());
			phase = Phase.WAIT_FOR_SCREEN; return Optional.empty();
		}
		if (phase == Phase.WAIT_FOR_SCREEN) {
			if (!(handler instanceof FurnaceScreenHandler)) return Optional.empty();
			syncId = handler.syncId;
			if (command instanceof StartSmelt) {
				if (handler.getSlot(0).hasStack() || handler.getSlot(2).hasStack()) return fail("furnace_occupied");
				phase = Phase.LOAD_FUEL;
			}
			else phase = Phase.COLLECT;
		}
		if (!(handler instanceof FurnaceScreenHandler) || handler.syncId != syncId) return fail("furnace_container_changed");
		if (!handler.getCursorStack().isEmpty()) return fail("furnace_cursor_occupied");
		if (phase == Phase.LOAD_FUEL) {
			var start = (StartSmelt) command; var existing = handler.getSlot(1).getStack();
			if (!existing.isEmpty() && !itemId(existing).equals(start.fuel())) return fail("furnace_fuel_conflict");
			int missing = Math.max(0, start.fuelCount() - existing.getCount());
			if (missing > 0 && !insert(client, start.fuel(), 1, missing)) return fail("furnace_fuel_missing");
			phase = Phase.LOAD_INPUT; return Optional.empty();
		}
		if (phase == Phase.LOAD_INPUT) {
			if (!insert(client, recipe.input(), 0, 1)) return fail("furnace_input_missing");
			phase = Phase.CHECK_INPUT; return Optional.empty();
		}
		if (phase == Phase.CHECK_INPUT) {
			var input = handler.getSlot(0).getStack(); var output = handler.getSlot(2).getStack();
			if ((itemId(input).equals(recipe.input()) && input.getCount() == 1) || itemId(output).equals(recipe.output())) return Optional.of(Outcome.success("furnace_input_observed"));
			return Optional.empty();
		}
		if (phase == Phase.COLLECT) {
			var output = handler.getSlot(2).getStack();
			if (!itemId(output).equals(recipe.output()) || output.getCount() < recipe.yield()) return fail("furnace_output_not_ready");
			before = inventoryCount(client, recipe.output());
			client.interactionManager.clickSlot(syncId, 2, 0, SlotActionType.QUICK_MOVE, player);
			phase = Phase.CHECK_INVENTORY; return Optional.empty();
		}
		if (inventoryCount(client, recipe.output()) >= before + recipe.yield()) return Optional.of(Outcome.success("furnace_output_collected"));
		return Optional.empty();
	}

	private boolean insert(MinecraftClient client, String item, int target, int count) {
		var handler = client.player.currentScreenHandler;
		for (int slot = 3; slot < handler.slots.size(); slot++) {
			var stack = handler.getSlot(slot).getStack();
			if (!itemId(stack).equals(item) || stack.getCount() < count) continue;
			client.interactionManager.clickSlot(syncId, slot, 0, SlotActionType.PICKUP, client.player);
			for (int i = 0; i < count; i++) client.interactionManager.clickSlot(syncId, target, 1, SlotActionType.PICKUP, client.player);
			client.interactionManager.clickSlot(syncId, slot, 0, SlotActionType.PICKUP, client.player);
			return true;
		}
		return false;
	}

	/** Leave the furnace working, but never abandon a carried stack or an open container. */
	boolean release(MinecraftClient client) {
		if (client.player == null || client.interactionManager == null) return true;
		var player = client.player; var handler = player.currentScreenHandler; var cursor = handler.getCursorStack();
		if (!cursor.isEmpty()) {
			for (int index = Math.max(0, handler.slots.size() - 36); index < handler.slots.size(); index++) {
				var slot = handler.getSlot(index); var stack = slot.getStack();
				if (slot.canInsert(cursor) && (stack.isEmpty() || (net.minecraft.item.ItemStack.areItemsAndComponentsEqual(stack, cursor) && stack.getCount() < slot.getMaxItemCount(cursor)))) {
					client.interactionManager.clickSlot(handler.syncId, index, 0, SlotActionType.PICKUP, player); return false;
				}
			}
			return false;
		}
		if (handler != player.playerScreenHandler) { player.closeHandledScreen(); return false; }
		if (client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?>) client.setScreen(null);
		return true;
	}
	private static String itemId(net.minecraft.item.ItemStack stack) { return stack.isEmpty() ? "" : Registries.ITEM.getId(stack.getItem()).toString(); }
	private static Optional<Outcome> fail(String reason) { return Optional.of(Outcome.failure(reason)); }
	private static int inventoryCount(MinecraftClient client, String item) {
		int count = 0;
		for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
			var stack = client.player.getInventory().getStack(slot); if (itemId(stack).equals(item)) count += stack.getCount();
		}
		return count;
	}
}
