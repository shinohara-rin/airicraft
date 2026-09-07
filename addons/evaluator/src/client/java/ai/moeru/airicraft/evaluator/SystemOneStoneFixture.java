package ai.moeru.airicraft.evaluator;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Evaluator-only ground truth. No fixture coordinates or setup state are passed to System 1. */
final class SystemOneStoneFixture {
	private CompletableFuture<Void> preparation;
	private long readyAfter = Long.MAX_VALUE;
	private boolean setupComplete;
	private int returnThresholdZ;
	private CompletableFuture<Void> depletion;
	private SystemOneTerrainProbe terrainProbe = new SystemOneTerrainProbe();
	private java.nio.file.Path output;

	void reset(java.nio.file.Path output) {
		preparation = null; depletion = null; readyAfter = Long.MAX_VALUE; setupComplete = false;
		terrainProbe = new SystemOneTerrainProbe(); this.output = output;
	}

	boolean ready(MinecraftClient client, String scenario, long tick) {
		if (!Set.of("system-one-stone", "system-one-terrain", "system-one-production", "system-one-production-discovery", "system-one-iron", "system-one-smelting", "system-one-charcoal", "system-one-underground", "system-one-cave-gap", "system-one-return-route", "system-one-survival-wait", "system-one-death-recovery", "system-one-lighting", "system-one-lighting-exhaustion", "system-one-lighting-stairs").contains(scenario)) return true;
		if (setupComplete) {
			if (scenario.equals("system-one-return-route")) depleteReturnSupplies(client, tick);
			if (scenario.equals("system-one-survival-wait") || scenario.equals("system-one-death-recovery")) injectSurvivalFailure(client, scenario, tick);
			return !scenario.equals("system-one-terrain") || terrainProbe.ready(client, tick, output);
		}
		if (client.getServer() == null || client.player == null || client.world == null) return false;
		if (preparation == null) {
			var server = client.getServer();
			var playerId = client.player.getUuid();
			var worldKey = client.world.getRegistryKey();
			preparation = CompletableFuture.runAsync(() -> {
				var player = server.getPlayerManager().getPlayer(playerId);
				if (player == null) throw new IllegalStateException("Fixture player unavailable");
				var world = server.getWorld(worldKey);
				if (world == null) throw new IllegalStateException("Fixture world unavailable");
				int x = player.getBlockX(), z = player.getBlockZ();
				for (int dx = -16; dx <= 16; dx++) for (int dz = -16; dz <= 16; dz++) {
					for (int y = 192; y <= 206; y++) {
						var block = y == 192 ? Blocks.BEDROCK : y <= 196 ? Blocks.STONE
							: y <= 198 ? Blocks.DIRT : y == 199 ? Blocks.GRASS_BLOCK : Blocks.AIR;
						world.setBlockState(new BlockPos(x + dx, y, z + dz), block.getDefaultState(), 3);
					}
				}
				world.setTimeOfDay(6000);
				player.changeGameMode(GameMode.SURVIVAL);
				player.getInventory().clear();
				if (scenario.equals("system-one-underground") || scenario.equals("system-one-cave-gap") || scenario.equals("system-one-return-route") || scenario.startsWith("system-one-lighting")) {
					boolean exhaustion = scenario.equals("system-one-lighting-exhaustion");
					boolean gap = scenario.equals("system-one-cave-gap");
					boolean returning = scenario.equals("system-one-return-route");
					int length = returning ? 60 : exhaustion ? 24 : 9;
					int halfWidth = scenario.equals("system-one-lighting-stairs") || gap || returning ? 0 : 1;
					for (int dx = -2; dx <= 2; dx++) for (int dz = 1; dz <= length + 3; dz++) for (int y = 195; y <= 204; y++) {
						world.setBlockState(new BlockPos(x + dx, y, z + dz), Blocks.STONE.getDefaultState(), 3);
					}
					for (int dz = 1; dz <= length; dz++) for (int dx = -halfWidth; dx <= halfWidth; dx++) {
						int floor = 200 - Math.min(dz, 4);
						for (int y = floor; y <= floor + 2; y++) world.setBlockState(new BlockPos(x + dx, y, z + dz), Blocks.AIR.getDefaultState(), 3);
						if (scenario.equals("system-one-lighting-stairs")) {
							var support = dz % 2 == 0 ? Blocks.ANDESITE : Blocks.DEEPSLATE;
							world.setBlockState(new BlockPos(x + dx, floor - 1, z + dz), support.getDefaultState(), 3);
						}
					}
					if (gap) for (int dz = 6; dz <= 7; dz++) for (int y = 192; y <= 195; y++) {
						world.setBlockState(new BlockPos(x, y, z + dz), Blocks.AIR.getDefaultState(), 3);
					}
					world.setBlockState(new BlockPos(x, 196, z + length + 2), Blocks.IRON_ORE.getDefaultState(), 3);
					world.setBlockState(new BlockPos(x + 1, 200, z), Blocks.TORCH.getDefaultState(), 3);
					player.getInventory().setStack(0, new ItemStack(Items.STONE_PICKAXE));
					if (scenario.equals("system-one-underground") || gap || returning) {
						player.getInventory().setStack(1, new ItemStack(Items.TORCH, returning ? 64 : 8));
						if (gap) player.getInventory().setStack(2, new ItemStack(Items.COBBLESTONE, 4));
					}
					else {
						player.getInventory().setStack(1, new ItemStack(Items.COAL, 2));
						player.getInventory().setStack(2, new ItemStack(Items.STICK, 2));
						if (exhaustion) player.getInventory().setStack(3, new ItemStack(Items.TORCH));
					}
					if (returning) {
						returnThresholdZ = z + 52;
						world.setBlockState(new BlockPos(x + 1, 200, z + 2), Blocks.FURNACE.getDefaultState(), 3);
						for (int y = 199; y <= 203; y++) world.setBlockState(new BlockPos(x - 1, y, z + 2), Blocks.OAK_LOG.getDefaultState(), 3);
					}
				}
				else if (scenario.equals("system-one-survival-wait")) {
					player.getInventory().setStack(0, new ItemStack(Items.RAW_IRON));
					player.getInventory().setStack(1, new ItemStack(Items.COAL));
					world.setBlockState(new BlockPos(x + 2, 200, z + 2), Blocks.FURNACE.getDefaultState(), 3);
				}
				else if (scenario.equals("system-one-death-recovery")) {
					for (int y = 200; y <= 204; y++) world.setBlockState(new BlockPos(x, y, z + 3), Blocks.OAK_LOG.getDefaultState(), 3);
					world.setSpawnPos(new BlockPos(x, 200, z), 0);
					world.getGameRules().get(net.minecraft.world.GameRules.SPAWN_RADIUS).set(0, server);
				}
				else if (scenario.equals("system-one-charcoal")) {
					player.getInventory().setStack(0, new ItemStack(Items.OAK_LOG, 2));
					world.setBlockState(new BlockPos(x, 200, z + 2), Blocks.FURNACE.getDefaultState(), 3);
					for (int y = 200; y <= 203; y++) world.setBlockState(new BlockPos(x - 2, y, z + 2), Blocks.OAK_LOG.getDefaultState(), 3);
				}
				else if (scenario.equals("system-one-smelting")) {
					player.getInventory().setStack(0, new ItemStack(Items.RAW_IRON));
					player.getInventory().setStack(1, new ItemStack(Items.OAK_PLANKS));
					world.setBlockState(new BlockPos(x, 200, z + 2), Blocks.FURNACE.getDefaultState(), 3);
				}
				else if (scenario.equals("system-one-production") || scenario.equals("system-one-production-discovery") || scenario.equals("system-one-iron")) {
					int treeOffset = scenario.equals("system-one-production-discovery") ? -3 : 3;
					for (int y = 200; y <= 204; y++) world.setBlockState(new BlockPos(x, y, z + treeOffset), Blocks.OAK_LOG.getDefaultState(), 3);
					if (scenario.equals("system-one-iron")) {
						for (int y = 200; y <= 204; y++) world.setBlockState(new BlockPos(x - 1, y, z + 3), Blocks.OAK_LOG.getDefaultState(), 3);
						for (int dz = 1; dz <= 3; dz++) {
							world.setBlockState(new BlockPos(x + 3, 200, z + dz), Blocks.IRON_ORE.getDefaultState(), 3);
							world.setBlockState(new BlockPos(x - 3, 200, z + dz), Blocks.COAL_ORE.getDefaultState(), 3);
						}
						for (int dx = -2; dx <= 1; dx++) for (int y = 200; y <= 202; y++) world.setBlockState(new BlockPos(x + dx, y, z + 6), Blocks.STONE.getDefaultState(), 3);
					}
				}
				else player.getInventory().setStack(0, new ItemStack(Items.WOODEN_PICKAXE));
				player.setHealth(player.getMaxHealth());
				player.getHungerManager().setFoodLevel(20);
				player.teleport(world, x + 0.5, 200, z + 0.5, Set.<PositionFlag>of(), 0, 45, true);
			}, server);
			return false;
		}
		if (!preparation.isDone()) return false;
		preparation.join();
		if (readyAfter == Long.MAX_VALUE) readyAfter = tick + 40;
		setupComplete = tick >= readyAfter && client.player.getBlockY() == 200;
		return setupComplete && (!scenario.equals("system-one-terrain") || terrainProbe.ready(client, tick, output));
	}
	private void injectSurvivalFailure(MinecraftClient client, String scenario, long tick) {
		if (depletion != null) { if (depletion.isDone()) depletion.join(); return; }
		if (client.player == null || client.getServer() == null) return;
		boolean dying = scenario.equals("system-one-death-recovery");
		if (dying) {
			if (client.player.getInventory().count(Items.OAK_LOG) == 0) return;
		}
		else if (!ai.moeru.airicraft.AiricraftClient.runtimeController().agentRuntime().semanticEventContains("system_one.task_waiting", java.util.Map.of())) return;
		var server = client.getServer(); var id = client.player.getUuid();
		depletion = CompletableFuture.runAsync(() -> {
			var player = server.getPlayerManager().getPlayer(id);
			if (player == null) throw new IllegalStateException("Survival fixture player unavailable");
			var world = player.getWorld(); var position = player.getBlockPos();
			if (dying) player.damage(world, world.getDamageSources().genericKill(), Float.MAX_VALUE);
			else world.setBlockState(position.south(), Blocks.LAVA.getDefaultState(), 3);
			try {
				var evidence = java.util.Map.of("kind", dying ? "death_after_first_log" : "lava_during_furnace_wait", "requestedAtRuntimeTick", tick,
					"x", position.getX(), "y", position.getY(), "z", position.getZ());
				java.nio.file.Files.writeString(output.resolve("fixture-intervention.json"), new com.google.gson.Gson().toJson(evidence));
			} catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
		}, server);
	}
	private void depleteReturnSupplies(MinecraftClient client, long tick) {
		if (depletion != null) { if (depletion.isDone()) depletion.join(); return; }
		if (client.player == null || client.getServer() == null || client.player.getBlockZ() < returnThresholdZ) return;
		var server = client.getServer(); var id = client.player.getUuid();
		depletion = CompletableFuture.runAsync(() -> {
			var player = server.getPlayerManager().getPlayer(id);
			if (player == null) throw new IllegalStateException("Return fixture player unavailable");
			int removed = 0;
			for (int slot = 0; slot < player.getInventory().size(); slot++) {
				var stack = player.getInventory().getStack(slot);
				if (stack.isOf(Items.TORCH)) { removed += stack.getCount(); player.getInventory().setStack(slot, ItemStack.EMPTY); }
			}
			player.currentScreenHandler.sendContentUpdates();
			try {
				var evidence = java.util.Map.of("kind", "torch_depletion", "requestedAtRuntimeTick", tick, "removed", removed,
					"x", player.getBlockX(), "y", player.getBlockY(), "z", player.getBlockZ());
				java.nio.file.Files.writeString(output.resolve("fixture-intervention.json"), new com.google.gson.Gson().toJson(evidence));
			} catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
		}, server);
	}

}
