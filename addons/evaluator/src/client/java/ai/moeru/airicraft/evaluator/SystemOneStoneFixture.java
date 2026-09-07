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
	private CompletableFuture<Void> routeChange;
	private BlockPos returnBarrier;
	private SystemOneTerrainProbe terrainProbe = new SystemOneTerrainProbe();
	private java.nio.file.Path output;

	void reset(java.nio.file.Path output) {
		preparation = null; depletion = null; routeChange = null; returnBarrier = null; readyAfter = Long.MAX_VALUE; setupComplete = false;
		terrainProbe = new SystemOneTerrainProbe(); this.output = output;
	}

	boolean ready(MinecraftClient client, String scenario, long tick) {
		if (!Set.of("system-one-stone", "system-one-terrain", "system-one-production", "system-one-production-discovery", "system-one-iron", "system-one-smelting", "system-one-station-stairs", "system-one-charcoal", "system-one-underground", "system-one-descent", "system-one-tool-wear", "system-one-harvest-approach", "system-one-distant-approach", "system-one-pickup-step", "system-one-cave-gap", "system-one-edge-bridge", "system-one-return-route", "system-one-return-blocked", "system-one-remote-fuel", "system-one-survival-wait", "system-one-survival-mining", "system-one-death-recovery", "system-one-lighting", "system-one-lighting-exhaustion", "system-one-lighting-stairs", "system-one-lighting-low-ceiling").contains(scenario)) return true;
		if (setupComplete) {
			if (isReturnScenario(scenario)) depleteReturnSupplies(client, tick);
			if (scenario.equals("system-one-return-blocked")) blockReturnRoute(client, tick);
			if (scenario.startsWith("system-one-survival-") || scenario.equals("system-one-death-recovery")) injectSurvivalFailure(client, scenario, tick);
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
				if (scenario.equals("system-one-underground") || (scenario.equals("system-one-cave-gap") || scenario.equals("system-one-edge-bridge")) || isReturnScenario(scenario) || scenario.equals("system-one-remote-fuel") || scenario.startsWith("system-one-lighting")) {
					boolean exhaustion = scenario.equals("system-one-lighting-exhaustion");
					boolean gap = scenario.equals("system-one-cave-gap") || scenario.equals("system-one-edge-bridge");
					boolean remoteFuel = scenario.equals("system-one-remote-fuel");
					boolean returning = isReturnScenario(scenario) || remoteFuel;
					int length = remoteFuel ? 40 : returning ? 60 : exhaustion ? 24 : 9;
					int halfWidth = scenario.equals("system-one-lighting-stairs") || scenario.equals("system-one-lighting-low-ceiling") || gap || returning ? 0 : 1;
					for (int dx = -2; dx <= 2; dx++) for (int dz = 1; dz <= length + 3; dz++) for (int y = 195; y <= 204; y++) {
						world.setBlockState(new BlockPos(x + dx, y, z + dz), Blocks.STONE.getDefaultState(), 3);
					}
					for (int dz = 1; dz <= length; dz++) for (int dx = -halfWidth; dx <= halfWidth; dx++) {
						int floor = 200 - Math.min(dz, 4);
						for (int y = floor; y <= floor + (scenario.equals("system-one-lighting-low-ceiling") ? 1 : 2); y++) world.setBlockState(new BlockPos(x + dx, y, z + dz), Blocks.AIR.getDefaultState(), 3);
						if (scenario.equals("system-one-lighting-stairs")) {
							var support = dz % 2 == 0 ? Blocks.ANDESITE : Blocks.DEEPSLATE;
							world.setBlockState(new BlockPos(x + dx, floor - 1, z + dz), support.getDefaultState(), 3);
						}
					}
					if (gap) for (int dz = 6; dz <= 7; dz++) for (int y = 192; y <= 195; y++) {
						world.setBlockState(new BlockPos(x, y, z + dz), Blocks.AIR.getDefaultState(), 3);
					}
					if (scenario.equals("system-one-edge-bridge")) for (int dx = -2; dx <= 2; dx++) for (int dz = 6; dz <= 8; dz++) for (int y = 192; y <= 198; y++) {
						world.setBlockState(new BlockPos(x + dx, y, z + dz), Blocks.AIR.getDefaultState(), 3);
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
						returnBarrier = new BlockPos(x, 196, z + 28);
						world.setBlockState(new BlockPos(x + 1, remoteFuel ? 197 : 200, z + (remoteFuel ? 39 : 2)), Blocks.FURNACE.getDefaultState(), 3);
						for (int y = 199; y <= 203; y++) world.setBlockState(new BlockPos(x - 1, y, z + 2), Blocks.OAK_LOG.getDefaultState(), 3);
					}
				}
				else if (scenario.equals("system-one-harvest-approach") || scenario.equals("system-one-distant-approach")) {
					int distance = scenario.equals("system-one-distant-approach") ? 12 : 6;
					for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= distance + 2; dz++) for (int y = 193; y <= 204; y++) {
						world.setBlockState(new BlockPos(x + dx, y, z + dz), Blocks.STONE.getDefaultState(), 3);
					}
					world.setBlockState(new BlockPos(x, 200, z), Blocks.TORCH.getDefaultState(), 3);
					world.setBlockState(new BlockPos(x, 201, z), Blocks.AIR.getDefaultState(), 3);
					for (int dx = -2; dx <= 2; dx++) for (int dz = 1; dz <= distance + 1; dz++) for (int y = 198; y <= 203; y++) {
						world.setBlockState(new BlockPos(x + dx, y, z + dz), Blocks.AIR.getDefaultState(), 3);
					}
					world.setBlockState(new BlockPos(x, 198, z + distance), Blocks.IRON_ORE.getDefaultState(), 3);
					for (int dz = 4; dz < distance; dz += 6) for (int dx : new int[]{-2, 2}) world.setBlockState(new BlockPos(x + dx, 198, z + dz), Blocks.TORCH.getDefaultState(), 3);
					player.getInventory().setStack(0, new ItemStack(Items.STONE_PICKAXE));
					player.getInventory().setStack(1, new ItemStack(Items.TORCH, 8));
				}
				else if (scenario.equals("system-one-pickup-step")) {
					for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 6; dz++) for (int y = 193; y <= 204; y++) {
						world.setBlockState(new BlockPos(x + dx, y, z + dz), Blocks.STONE.getDefaultState(), 3);
					}
					world.setBlockState(new BlockPos(x, 197, z), Blocks.TORCH.getDefaultState(), 3);
					world.setBlockState(new BlockPos(x, 198, z), Blocks.AIR.getDefaultState(), 3);
					for (int y = 196; y <= 198; y++) world.setBlockState(new BlockPos(x, y, z + 1), Blocks.AIR.getDefaultState(), 3);
					world.setBlockState(new BlockPos(x, 195, z + 1), Blocks.IRON_ORE.getDefaultState(), 3);
					player.getInventory().setStack(0, new ItemStack(Items.STONE_PICKAXE));
				}
				else if (scenario.equals("system-one-descent") || scenario.equals("system-one-tool-wear")) {
					for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 12; dz++) for (int y = 193; y <= 204; y++) {
						world.setBlockState(new BlockPos(x + dx, y, z + dz), Blocks.STONE.getDefaultState(), 3);
					}
					world.setBlockState(new BlockPos(x, 200, z), Blocks.TORCH.getDefaultState(), 3);
					world.setBlockState(new BlockPos(x, 201, z), Blocks.AIR.getDefaultState(), 3);
					world.setBlockState(new BlockPos(x, 195, z + 5), Blocks.IRON_ORE.getDefaultState(), 3);
					player.getInventory().setStack(0, new ItemStack(Items.STONE_PICKAXE));
					player.getInventory().setStack(1, new ItemStack(Items.TORCH, 16));
					if (scenario.equals("system-one-tool-wear")) {
						var pick = player.getInventory().getStack(0);
						pick.setDamage(pick.getMaxDamage() - 2);
						player.getInventory().setStack(2, new ItemStack(Items.COBBLESTONE, 3));
						player.getInventory().setStack(3, new ItemStack(Items.STICK, 2));
						world.setBlockState(new BlockPos(x - 1, 200, z), Blocks.CRAFTING_TABLE.getDefaultState(), 3);
						world.setBlockState(new BlockPos(x - 1, 201, z), Blocks.AIR.getDefaultState(), 3);
					}
				}
				else if (scenario.equals("system-one-station-stairs")) {
					for (int dz = 1; dz <= 7; dz++) for (int dx = -1; dx <= 1; dx++) {
						int floor = 199 + Math.min(dz, 4);
						for (int y = 199; y <= floor; y++) world.setBlockState(new BlockPos(x + dx, y, z + dz), Blocks.STONE.getDefaultState(), 3);
					}
					world.setBlockState(new BlockPos(x, 204, z + 6), Blocks.FURNACE.getDefaultState(), 3);
					player.getInventory().setStack(0, new ItemStack(Items.RAW_IRON));
					player.getInventory().setStack(1, new ItemStack(Items.COAL));
				}
				else if (scenario.equals("system-one-survival-wait")) {
					player.getInventory().setStack(0, new ItemStack(Items.RAW_IRON));
					player.getInventory().setStack(1, new ItemStack(Items.COAL));
					world.setBlockState(new BlockPos(x + 2, 200, z + 2), Blocks.FURNACE.getDefaultState(), 3);
				}
				else if (scenario.equals("system-one-death-recovery") || scenario.equals("system-one-survival-mining")) {
					for (int y = 200; y <= 204; y++) world.setBlockState(new BlockPos(x, y, z + 3), Blocks.OAK_LOG.getDefaultState(), 3);
					if (scenario.equals("system-one-death-recovery")) {
						world.setSpawnPos(new BlockPos(x, 200, z), 0);
						world.getGameRules().get(net.minecraft.world.GameRules.SPAWN_RADIUS).set(0, server);
					}
					else for (int dx : new int[]{-6, 6}) for (int y = 200; y <= 204; y++) world.setBlockState(new BlockPos(x + dx, y, z + 3), Blocks.OAK_LOG.getDefaultState(), 3);
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
				player.teleport(world, x + 0.5, scenario.equals("system-one-pickup-step") ? 197 : 200, z + 0.5, Set.<PositionFlag>of(), 0, 45, true);
			}, server);
			return false;
		}
		if (!preparation.isDone()) return false;
		preparation.join();
		if (readyAfter == Long.MAX_VALUE) readyAfter = tick + 40;
		setupComplete = tick >= readyAfter && client.player.getBlockY() == (scenario.equals("system-one-pickup-step") ? 197 : 200);
		return setupComplete && (!scenario.equals("system-one-terrain") || terrainProbe.ready(client, tick, output));
	}
	private void injectSurvivalFailure(MinecraftClient client, String scenario, long tick) {
		if (depletion != null) { if (depletion.isDone()) depletion.join(); return; }
		if (client.player == null || client.getServer() == null) return;
		boolean dying = scenario.equals("system-one-death-recovery");
		boolean mining = scenario.equals("system-one-survival-mining");
		if (dying) {
			if (client.player.getInventory().count(Items.OAK_LOG) == 0) return;
		}
		else if (!ai.moeru.airicraft.AiricraftClient.runtimeController().agentRuntime().semanticEventContains(mining ? "system_one.command_started" : "system_one.task_waiting", java.util.Map.of())) return;
		var server = client.getServer(); var id = client.player.getUuid();
		depletion = CompletableFuture.runAsync(() -> {
			var player = server.getPlayerManager().getPlayer(id);
			if (player == null) throw new IllegalStateException("Survival fixture player unavailable");
			var world = player.getWorld(); var position = player.getBlockPos();
			if (dying) player.damage(world, world.getDamageSources().genericKill(), Float.MAX_VALUE);
			else world.setBlockState(position.south(), Blocks.LAVA.getDefaultState(), 3);
			try {
				var evidence = java.util.Map.of("kind", dying ? "death_after_first_log" : mining ? "lava_during_mining" : "lava_during_furnace_wait", "requestedAtRuntimeTick", tick,
					"x", position.getX(), "y", position.getY(), "z", position.getZ());
				java.nio.file.Files.writeString(output.resolve("fixture-intervention.json"), new com.google.gson.Gson().toJson(evidence));
			} catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
		}, server);
	}
	private static boolean isReturnScenario(String scenario) {
		return scenario.equals("system-one-return-route") || scenario.equals("system-one-return-blocked");
	}
	private void blockReturnRoute(MinecraftClient client, long tick) {
		if (routeChange != null) { if (routeChange.isDone()) routeChange.join(); return; }
		if (depletion == null || !depletion.isDone() || client.player == null || client.getServer() == null) return;
		if (!ai.moeru.airicraft.AiricraftClient.runtimeController().agentRuntime().semanticEventContains("system_one.task_resumed",
			java.util.Map.of("detail", "SUCCEEDED:inventory_observed:minecraft:torch:8"))) return;
		var server = client.getServer(); var id = client.player.getUuid(); var barrier = returnBarrier;
		routeChange = CompletableFuture.runAsync(() -> {
			var player = server.getPlayerManager().getPlayer(id);
			if (player == null) throw new IllegalStateException("Return fixture player unavailable");
			var world = player.getWorld();
			world.setBlockState(barrier, Blocks.STONE.getDefaultState(), 3);
			world.setBlockState(barrier.up(), Blocks.STONE.getDefaultState(), 3);
			try {
				var evidence = java.util.Map.of("kind", "blocked_return_route", "requestedAtRuntimeTick", tick,
					"barrierX", barrier.getX(), "barrierY", barrier.getY(), "barrierZ", barrier.getZ(),
					"playerX", player.getBlockX(), "playerY", player.getBlockY(), "playerZ", player.getBlockZ());
				java.nio.file.Files.writeString(output.resolve("fixture-route-change.json"), new com.google.gson.Gson().toJson(evidence));
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
