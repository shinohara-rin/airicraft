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
		if (!Set.of("system-one-stone", "system-one-terrain", "system-one-production", "system-one-iron", "system-one-smelting").contains(scenario)) return true;
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
				if (scenario.equals("system-one-smelting")) {
					player.getInventory().setStack(0, new ItemStack(Items.RAW_IRON));
					player.getInventory().setStack(1, new ItemStack(Items.OAK_PLANKS));
					world.setBlockState(new BlockPos(x, 200, z + 2), Blocks.FURNACE.getDefaultState(), 3);
				}
				else if (scenario.equals("system-one-production") || scenario.equals("system-one-iron")) {
					for (int y = 200; y <= 204; y++) world.setBlockState(new BlockPos(x, y, z + 3), Blocks.OAK_LOG.getDefaultState(), 3);
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
