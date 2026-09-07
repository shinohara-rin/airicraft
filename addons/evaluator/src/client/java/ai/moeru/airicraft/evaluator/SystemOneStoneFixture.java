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
	private SystemOneTerrainProbe terrainProbe = new SystemOneTerrainProbe();
	private java.nio.file.Path output;

	void reset(java.nio.file.Path output) {
		preparation = null; readyAfter = Long.MAX_VALUE; setupComplete = false;
		terrainProbe = new SystemOneTerrainProbe(); this.output = output;
	}

	boolean ready(MinecraftClient client, String scenario, long tick) {
		if (!Set.of("system-one-stone", "system-one-terrain", "system-one-production", "system-one-production-discovery", "system-one-iron", "system-one-smelting", "system-one-underground", "system-one-lighting", "system-one-lighting-exhaustion", "system-one-lighting-stairs").contains(scenario)) return true;
		if (setupComplete) return !scenario.equals("system-one-terrain") || terrainProbe.ready(client, tick, output);
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
				if (scenario.equals("system-one-underground") || scenario.startsWith("system-one-lighting")) {
					boolean exhaustion = scenario.equals("system-one-lighting-exhaustion");
					int length = exhaustion ? 24 : 9;
					int halfWidth = scenario.equals("system-one-lighting-stairs") ? 0 : 1;
					for (int dx = -2; dx <= 2; dx++) for (int dz = 1; dz <= length + 3; dz++) for (int y = 195; y <= 204; y++) {
						world.setBlockState(new BlockPos(x + dx, y, z + dz), Blocks.STONE.getDefaultState(), 3);
					}
					for (int dz = 1; dz <= length; dz++) for (int dx = -halfWidth; dx <= halfWidth; dx++) {
						int floor = 200 - Math.min(dz, 4);
						for (int y = floor; y <= floor + 2; y++) world.setBlockState(new BlockPos(x + dx, y, z + dz), Blocks.AIR.getDefaultState(), 3);
					}
					world.setBlockState(new BlockPos(x, 196, z + length + 2), Blocks.IRON_ORE.getDefaultState(), 3);
					world.setBlockState(new BlockPos(x + 1, 200, z), Blocks.TORCH.getDefaultState(), 3);
					player.getInventory().setStack(0, new ItemStack(Items.STONE_PICKAXE));
					if (scenario.equals("system-one-underground")) player.getInventory().setStack(1, new ItemStack(Items.TORCH, 8));
					else {
						player.getInventory().setStack(1, new ItemStack(Items.COAL, 2));
						player.getInventory().setStack(2, new ItemStack(Items.STICK, 2));
						if (exhaustion) player.getInventory().setStack(3, new ItemStack(Items.TORCH));
					}
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
}
