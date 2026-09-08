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
	private BlockPos lightSource;
	private SystemOneTerrainProbe terrainProbe = new SystemOneTerrainProbe();
	private SystemOneTargetFault targetFault = new SystemOneTargetFault();
	private SystemOneWorldProbe worldProbe = new SystemOneWorldProbe();
	private java.nio.file.Path output;

	void reset(java.nio.file.Path output) {
		preparation = null; depletion = null; routeChange = null; returnBarrier = null; lightSource = null; readyAfter = Long.MAX_VALUE; setupComplete = false;
		terrainProbe = new SystemOneTerrainProbe(); targetFault = new SystemOneTargetFault(); worldProbe = new SystemOneWorldProbe(); this.output = output;
	}

	boolean ready(MinecraftClient client, String scenario, long tick) {
		if (!Set.of("system-one-lighting-bootstrap", "system-one-furnace-footing", "system-one-furnace-return", "system-one-return-long", "system-one-pickup-headroom", "system-one-lighting-region", "system-one-lighting-leaves", "system-one-lighting-gather", "system-one-lighting-wait", "system-one-dim-health-loss", "system-one-trunk-descent", "system-one-dim-coal-bootstrap", "system-one-mushroom-obstruction", "system-one-observed-processed-wood", "system-one-remote-tool", "system-one-recording-overflow", "system-one-world-change", "system-one-search-dead-end", "system-one-furnace-reach", "system-one-tree-indicator", "system-one-target-change", "system-one-stone-low-canopy", "system-one-search-radius", "system-one-search-areas", "system-one-feedback-delivery", "system-one-stone", "system-one-stone-canopy", "system-one-terrain", "system-one-production", "system-one-production-discovery", "system-one-iron", "system-one-smelting", "system-one-station-stairs", "system-one-charcoal", "system-one-underground", "system-one-obscured-support", "system-one-cave-turn", "system-one-descent", "system-one-tool-wear", "system-one-harvest-approach", "system-one-log-pickup", "system-one-distant-approach", "system-one-pickup-step", "system-one-cave-gap", "system-one-edge-bridge", "system-one-return-route", "system-one-return-blocked", "system-one-remote-fuel", "system-one-survival-wait", "system-one-survival-mining", "system-one-death-recovery", "system-one-lighting", "system-one-lighting-exhaustion", "system-one-lighting-stairs", "system-one-lighting-low-ceiling").contains(scenario)) return true;
		if (setupComplete) {
			if (scenario.equals("system-one-furnace-return")) displaceDuringCooking(client,tick);
			if (scenario.equals("system-one-world-change")) return worldProbe.ready(client,tick,output);
			if (scenario.equals("system-one-target-change")) targetFault.tick(client, tick, output);
			if (scenario.equals("system-one-dim-health-loss")) injureDuringDimSearch(client, tick);
			if ((scenario.equals("system-one-lighting-gather") || scenario.equals("system-one-lighting-leaves") || scenario.equals("system-one-lighting-bootstrap")) || scenario.equals("system-one-lighting-wait")) darkenActiveWork(client, scenario, tick);
			if (scenario.equals("system-one-lighting-region")) changeRegionLight(client,tick);
			if (isReturnScenario(scenario)) depleteReturnSupplies(client, tick);
			if (scenario.equals("system-one-return-blocked")) blockReturnRoute(client, tick);
			if (scenario.equals("system-one-return-long")) slowReturnTravel(client,tick);
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
				if (scenario.equals("system-one-pickup-headroom")) {
					for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=6;dz++)for(int y=198;y<=204;y++)world.setBlockState(new BlockPos(x+dx,y,z+dz),Blocks.BEDROCK.getDefaultState(),3);
					for(int dz=-1;dz<=5;dz++)for(int y=200;y<=202;y++)world.setBlockState(new BlockPos(x,y,z+dz),Blocks.AIR.getDefaultState(),3);
					for(int dz : new int[]{2,3})world.setBlockState(new BlockPos(x,201,z+dz),Blocks.STONE.getDefaultState(),3);
					for(int dz : new int[]{0,4})world.setBlockState(new BlockPos(x,199,z+dz),Blocks.SEA_LANTERN.getDefaultState(),3);
					var drop=new net.minecraft.entity.ItemEntity(world,x+.125,200,z+3.125,new ItemStack(Items.RAW_IRON));
					drop.setVelocity(net.minecraft.util.math.Vec3d.ZERO);drop.setPickupDelay(0);world.spawnEntity(drop);
					player.getInventory().setStack(0,new ItemStack(Items.STONE_PICKAXE));
				}
				else if (scenario.equals("system-one-lighting-region")) {
					for(int dx=-2;dx<=2;dx++)for(int dz=-3;dz<=20;dz++)for(int y=198;y<=205;y++)world.setBlockState(new BlockPos(x+dx,y,z+dz),Blocks.BEDROCK.getDefaultState(),3);
					var leaves=Blocks.SPRUCE_LEAVES.getDefaultState().with(net.minecraft.state.property.Properties.PERSISTENT,true);
					for(int dx=-1;dx<=1;dx++)for(int dz=-2;dz<=18;dz++)for(int y=199;y<=204;y++)world.setBlockState(new BlockPos(x+dx,y,z+dz),leaves,3);
					for(int dz=-1;dz<=17;dz++)for(int y=200;y<=202;y++)world.setBlockState(new BlockPos(x,y,z+dz),Blocks.AIR.getDefaultState(),3);
					for(int dz : new int[]{0,7,15})world.setBlockState(new BlockPos(x,199,z+dz),Blocks.SEA_LANTERN.getDefaultState(),3);
					lightSource=new BlockPos(x,199,z+7);
					world.setBlockState(new BlockPos(x,200,z+16),Blocks.COAL_ORE.getDefaultState(),3);
					player.getInventory().setStack(0,new ItemStack(Items.WOODEN_PICKAXE));
					player.getInventory().setStack(1,new ItemStack(Items.TORCH,8));
				}
				else if ((scenario.equals("system-one-lighting-gather") || scenario.equals("system-one-lighting-leaves") || scenario.equals("system-one-lighting-bootstrap")) || scenario.equals("system-one-lighting-wait")) {
					for (int dx=-5;dx<=5;dx++) for (int dz=-2;dz<=6;dz++) for (int y=198;y<=205;y++) world.setBlockState(new BlockPos(x+dx,y,z+dz),Blocks.BEDROCK.getDefaultState(),3);
					for (int dx=-4;dx<=4;dx++) for (int dz=-1;dz<=5;dz++) for (int y=200;y<=204;y++) world.setBlockState(new BlockPos(x+dx,y,z+dz),Blocks.AIR.getDefaultState(),3);
					if (scenario.equals("system-one-lighting-leaves")) {
						// Collision-full leaf faces surround the work; bedrock floor remains a valid light support.
						var leaves=Blocks.SPRUCE_LEAVES.getDefaultState().with(net.minecraft.state.property.Properties.PERSISTENT,true);
						for (int dx : new int[]{-1,1}) for (int dz=-1;dz<=4;dz++) for (int y=200;y<=203;y++) world.setBlockState(new BlockPos(x+dx,y,z+dz),leaves,3);
					}
					lightSource = new BlockPos(x,203,z);
					world.setBlockState(lightSource.up(),Blocks.BEDROCK.getDefaultState(),3);
					world.setBlockState(lightSource,Blocks.LANTERN.getDefaultState().with(net.minecraft.state.property.Properties.HANGING,true),3);
					player.getInventory().setStack(0,new ItemStack(Items.WOODEN_PICKAXE));
					player.getInventory().setStack(1,new ItemStack(Items.TORCH,8));
					if (scenario.equals("system-one-lighting-bootstrap")) {
						player.getInventory().setStack(1,new ItemStack(Items.COBBLESTONE,3));
						player.getInventory().setStack(2,new ItemStack(Items.OAK_LOG));
						player.getInventory().setStack(3,new ItemStack(Items.OAK_PLANKS,3));
						player.getInventory().setStack(4,new ItemStack(Items.STICK,2));
						player.getInventory().setStack(5,new ItemStack(Items.CRAFTING_TABLE));
						for(int dx=-2;dx<=2;dx++) world.setBlockState(new BlockPos(x+dx,200,z+3),Blocks.STONE.getDefaultState(),3);
						for(int dx : new int[]{-2,2}) world.setBlockState(new BlockPos(x+dx,200,z+1),Blocks.COAL_ORE.getDefaultState(),3);
						world.setBlockState(new BlockPos(x,203,z-1),Blocks.REDSTONE_WALL_TORCH.getDefaultState().with(net.minecraft.state.property.Properties.HORIZONTAL_FACING,net.minecraft.util.math.Direction.SOUTH),3);
					} else if ((scenario.equals("system-one-lighting-gather") || scenario.equals("system-one-lighting-leaves"))) world.setBlockState(new BlockPos(x,200,z+3),Blocks.COAL_ORE.getDefaultState(),3);
					else {
						world.setBlockState(new BlockPos(x+2,199,z+3),Blocks.FURNACE.getDefaultState(),3);
						player.getInventory().setStack(2,new ItemStack(Items.RAW_IRON));
						player.getInventory().setStack(3,new ItemStack(Items.OAK_PLANKS));
					}
				}
				else if (scenario.equals("system-one-dim-coal-bootstrap") || scenario.equals("system-one-dim-health-loss")) {
					for (int dx=-2;dx<=2;dx++) for (int dz=-2;dz<=6;dz++) for (int y=198;y<=205;y++) world.setBlockState(new BlockPos(x+dx,y,z+dz),Blocks.BEDROCK.getDefaultState(),3);
					for (int dz=0;dz<=4;dz++) for (int y=200;y<=203;y++) world.setBlockState(new BlockPos(x,y,z+dz),Blocks.AIR.getDefaultState(),3);
					for (int y=200;y<=202;y++) world.setBlockState(new BlockPos(x,y,z+1),Blocks.DIRT.getDefaultState(),3);
					var dimLight = Blocks.REDSTONE_WALL_TORCH.getDefaultState().with(net.minecraft.state.property.Properties.HORIZONTAL_FACING,net.minecraft.util.math.Direction.WEST);
					for (int dz : new int[]{0,3}) world.setBlockState(new BlockPos(x,203,z+dz),dimLight,3);
					world.setBlockState(new BlockPos(x,200,z+3),Blocks.COAL_ORE.getDefaultState(),3);
					player.getInventory().setStack(0,new ItemStack(Items.WOODEN_PICKAXE));
					player.getInventory().setStack(1,new ItemStack(Items.STICK));
				}
				else if (scenario.equals("system-one-observed-processed-wood")) {
					world.setBlockState(new BlockPos(x, 200, z + 3), Blocks.STRIPPED_OAK_LOG.getDefaultState(), 3);
				}
				else if (scenario.equals("system-one-tree-indicator")) {
					var leaves = Blocks.ACACIA_LEAVES.getDefaultState().with(net.minecraft.state.property.Properties.PERSISTENT,true);
					for (int dx=-1;dx<=1;dx++) for (int dz=4;dz<=6;dz++) for (int y=200;y<=203;y++) world.setBlockState(new BlockPos(x+dx,y,z+dz),leaves,3);
					for (int y=200;y<=202;y++) world.setBlockState(new BlockPos(x,y,z+5),Blocks.ACACIA_LOG.getDefaultState(),3);
				}
				else if (scenario.equals("system-one-underground") || (scenario.equals("system-one-cave-gap") || scenario.equals("system-one-edge-bridge")) || isReturnScenario(scenario) || scenario.equals("system-one-remote-fuel") || scenario.equals("system-one-remote-tool") || scenario.startsWith("system-one-lighting")) {
					boolean exhaustion = scenario.equals("system-one-lighting-exhaustion");
					boolean gap = scenario.equals("system-one-cave-gap") || scenario.equals("system-one-edge-bridge");
					boolean remoteFuel = scenario.equals("system-one-remote-fuel");
					boolean remoteTool = scenario.equals("system-one-remote-tool");
					boolean returning = isReturnScenario(scenario) || remoteFuel || remoteTool;
					int length = remoteFuel || remoteTool ? 40 : scenario.equals("system-one-return-long") ? 160 : returning ? 60 : exhaustion ? 24 : 9;
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
						returnThresholdZ = z + (scenario.equals("system-one-return-long") ? length - 32 : scenario.equals("system-one-return-blocked") ? 32 : 52);
						returnBarrier = new BlockPos(x, 196, z + 28);
						if (!remoteTool) world.setBlockState(new BlockPos(x + 1, remoteFuel ? 197 : 200, z + (remoteFuel ? 39 : 2)), Blocks.FURNACE.getDefaultState(), 3);
						for (int y = 199; y <= 203; y++) world.setBlockState(new BlockPos(x - 1, y, z + 2), Blocks.OAK_LOG.getDefaultState(), 3);
						if (remoteTool) {
							var pick = player.getInventory().getStack(0);
							pick.setDamage(pick.getMaxDamage() - 2);
							player.getInventory().setStack(2, new ItemStack(Items.COBBLESTONE, 3));
							world.setBlockState(new BlockPos(x - 1, 200, z), Blocks.CRAFTING_TABLE.getDefaultState(), 3);
							world.setBlockState(new BlockPos(x - 1, 201, z), Blocks.AIR.getDefaultState(), 3);
							// Wear out the tool well beyond the entrance, while leaving its observed return route intact.
							for (int y = 196; y <= 197; y++) world.setBlockState(new BlockPos(x, y, z + 25), Blocks.STONE.getDefaultState(), 3);
						}
					}
				}
				else if (scenario.equals("system-one-harvest-approach") || scenario.equals("system-one-distant-approach") || scenario.equals("system-one-log-pickup")) {
					boolean logPickup = scenario.equals("system-one-log-pickup");
					int distance = logPickup ? 2 : scenario.equals("system-one-distant-approach") ? 12 : 6;
					for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= distance + 2; dz++) for (int y = 193; y <= 204; y++) {
						world.setBlockState(new BlockPos(x + dx, y, z + dz), (logPickup ? Blocks.DIRT : Blocks.STONE).getDefaultState(), 3);
					}
					world.setBlockState(new BlockPos(x, 200, z), Blocks.TORCH.getDefaultState(), 3);
					world.setBlockState(new BlockPos(x, 201, z), Blocks.AIR.getDefaultState(), 3);
					for (int dx = -2; dx <= 2; dx++) for (int dz = 1; dz <= distance + 1; dz++) for (int y = 198; y <= 203; y++) {
						world.setBlockState(new BlockPos(x + dx, y, z + dz), Blocks.AIR.getDefaultState(), 3);
					}
					world.setBlockState(new BlockPos(x, 198, z + distance), (logPickup ? Blocks.DARK_OAK_LOG : Blocks.IRON_ORE).getDefaultState(), 3);
					for (int dz = 4; dz < distance; dz += 6) for (int dx : new int[]{-2, 2}) world.setBlockState(new BlockPos(x + dx, 198, z + dz), Blocks.TORCH.getDefaultState(), 3);
					if (!logPickup) {
						player.getInventory().setStack(0, new ItemStack(Items.STONE_PICKAXE));
						player.getInventory().setStack(1, new ItemStack(Items.TORCH, 8));
					}
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
				else if (scenario.equals("system-one-search-dead-end")) {
					for (int dx=-2;dx<=7;dx++) for (int dz=-2;dz<=9;dz++) for (int y=198;y<=204;y++) world.setBlockState(new BlockPos(x+dx,y,z+dz),Blocks.BEDROCK.getDefaultState(),3);
					// The preferred forward passage ends after a side junction. Ore is behind the side branch's bend.
					for (int dz=0;dz<=7;dz++) for (int y=200;y<=202;y++) world.setBlockState(new BlockPos(x,y,z+dz),Blocks.AIR.getDefaultState(),3);
					for (int dx=1;dx<=5;dx++) for (int y=200;y<=202;y++) world.setBlockState(new BlockPos(x+dx,y,z+3),Blocks.AIR.getDefaultState(),3);
					for (int dz=1;dz<=3;dz++) for (int y=200;y<=202;y++) world.setBlockState(new BlockPos(x+5,y,z+dz),Blocks.AIR.getDefaultState(),3);
					var facing=net.minecraft.state.property.Properties.HORIZONTAL_FACING;
					var light=Blocks.WALL_TORCH.getDefaultState().with(facing,net.minecraft.util.math.Direction.WEST);
					for (int dz : new int[]{0,5,7}) world.setBlockState(new BlockPos(x,202,z+dz),light,3);
					world.setBlockState(new BlockPos(x+3,202,z+3),light.with(facing,net.minecraft.util.math.Direction.SOUTH),3);
					world.setBlockState(new BlockPos(x+5,202,z+1),light,3);
					world.setBlockState(new BlockPos(x+5,200,z),Blocks.IRON_ORE.getDefaultState(),3);
					player.getInventory().setStack(0,new ItemStack(Items.STONE_PICKAXE));
					player.getInventory().setStack(1,new ItemStack(Items.TORCH,8));
				}
				else if (scenario.equals("system-one-search-radius")) {
					for (int dx=-2;dx<=2;dx++) for (int dz=-2;dz<=86;dz++) for (int y=198;y<=204;y++) world.setBlockState(new BlockPos(x+dx,y,z+dz),Blocks.BEDROCK.getDefaultState(),3);
					for (int dz=0;dz<=82;dz++) for (int y=200;y<=202;y++) world.setBlockState(new BlockPos(x,y,z+dz),Blocks.AIR.getDefaultState(),3);
					var light = Blocks.WALL_TORCH.getDefaultState().with(net.minecraft.state.property.Properties.HORIZONTAL_FACING,net.minecraft.util.math.Direction.WEST);
					for (int dz=0;dz<=78;dz+=6) world.setBlockState(new BlockPos(x,202,z+dz),light,3);
					world.setBlockState(new BlockPos(x,200,z+83),Blocks.IRON_ORE.getDefaultState(),3);
					player.getInventory().setStack(0,new ItemStack(Items.STONE_PICKAXE));
					player.getInventory().setStack(1,new ItemStack(Items.TORCH,8));
				}
				else if (scenario.equals("system-one-search-areas")) {
					for (int dx=-22;dx<=2;dx++) for (int dz=-2;dz<=63;dz++) for (int y=198;y<=204;y++) world.setBlockState(new BlockPos(x+dx,y,z+dz),Blocks.BEDROCK.getDefaultState(),3);
					for (int dz=0;dz<=60;dz++) for (int y=200;y<=202;y++) world.setBlockState(new BlockPos(x,y,z+dz),Blocks.AIR.getDefaultState(),3);
					for (int dx=-20;dx<=0;dx++) for (int y=200;y<=202;y++) world.setBlockState(new BlockPos(x+dx,y,z+60),Blocks.AIR.getDefaultState(),3);
					for (int dz=20;dz<=60;dz++) for (int y=200;y<=202;y++) world.setBlockState(new BlockPos(x-20,y,z+dz),Blocks.AIR.getDefaultState(),3);
					var facing = net.minecraft.state.property.Properties.HORIZONTAL_FACING;
					for (int dz=0;dz<=60;dz+=6) world.setBlockState(new BlockPos(x,202,z+dz),Blocks.WALL_TORCH.getDefaultState().with(facing,net.minecraft.util.math.Direction.WEST),3);
					for (int dx=-5;dx>=-15;dx-=5) world.setBlockState(new BlockPos(x+dx,202,z+60),Blocks.WALL_TORCH.getDefaultState().with(facing,net.minecraft.util.math.Direction.NORTH),3);
					for (int dz=24;dz<=60;dz+=6) world.setBlockState(new BlockPos(x-20,202,z+dz),Blocks.WALL_TORCH.getDefaultState().with(facing,net.minecraft.util.math.Direction.EAST),3);
					world.setBlockState(new BlockPos(x-20,200,z+19),Blocks.IRON_ORE.getDefaultState(),3);
					player.getInventory().setStack(0,new ItemStack(Items.STONE_PICKAXE));
					player.getInventory().setStack(1,new ItemStack(Items.TORCH,8));
				}
				else if (scenario.equals("system-one-obscured-support") || scenario.equals("system-one-cave-turn")) {
					boolean turn = scenario.equals("system-one-cave-turn");
					for (int dx=-4;dx<=2;dx++) for (int dz=-1;dz<=8;dz++) for (int y=198;y<=204;y++) world.setBlockState(new BlockPos(x+dx,y,z+dz),Blocks.BEDROCK.getDefaultState(),3);
					for (int dz=0;dz<=6;dz++) for (int y=200;y<=202;y++) world.setBlockState(new BlockPos(x,y,z+dz),Blocks.AIR.getDefaultState(),3);
					var wallLight = Blocks.WALL_TORCH.getDefaultState().with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, net.minecraft.util.math.Direction.WEST);
					world.setBlockState(new BlockPos(x,202,z),wallLight,3);
					if (turn) {
						for (int dx=-2;dx<=0;dx++) for (int y=200;y<=202;y++) world.setBlockState(new BlockPos(x+dx,y,z+6),Blocks.AIR.getDefaultState(),3);
						for (int dz=2;dz<=6;dz++) for (int y=200;y<=202;y++) world.setBlockState(new BlockPos(x-2,y,z+dz),Blocks.AIR.getDefaultState(),3);
						world.setBlockState(new BlockPos(x,202,z+3),wallLight,3);
						world.setBlockState(new BlockPos(x-2,202,z+6),wallLight.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, net.minecraft.util.math.Direction.EAST),3);
						world.setBlockState(new BlockPos(x-2,200,z+1),Blocks.IRON_ORE.getDefaultState(),3);
					} else {
						for (int dz=1;dz<=5;dz++) {
							world.setBlockState(new BlockPos(x,199,z+dz),(dz==5 ? Blocks.IRON_ORE : Blocks.STONE).getDefaultState(),3);
							world.setBlockState(new BlockPos(x,200,z+dz),Blocks.LEAF_LITTER.getDefaultState(),3);
						}
						world.setBlockState(new BlockPos(x,202,z+6),wallLight,3);
					}
					player.getInventory().setStack(0,new ItemStack(Items.STONE_PICKAXE));
					player.getInventory().setStack(1,new ItemStack(Items.TORCH,8));
				}
				else if (scenario.equals("system-one-stone-low-canopy") || scenario.equals("system-one-mushroom-obstruction")) {
					var canopy = scenario.equals("system-one-mushroom-obstruction") ? Blocks.BROWN_MUSHROOM_BLOCK.getDefaultState()
						: Blocks.SPRUCE_LEAVES.getDefaultState().with(net.minecraft.state.property.Properties.PERSISTENT,true);
					for (int dx=-8;dx<=8;dx++) for (int dz=-8;dz<=8;dz++) if(dx!=0||dz!=0) world.setBlockState(new BlockPos(x+dx,201,z+dz),canopy,3);
					player.getInventory().setStack(0,new ItemStack(Items.WOODEN_PICKAXE));
				}
				else if (scenario.equals("system-one-trunk-descent")) {
					// A tall observed trunk offers a reversible staircase; the surrounding air does not.
					for (int dx=-8;dx<=8;dx++) for (int dz=-8;dz<=8;dz++) for (int y=195;y<=199;y++)
						world.setBlockState(new BlockPos(x+dx,y,z+dz),Blocks.AIR.getDefaultState(),3);
					for (int dx=0;dx<=1;dx++) for (int dz=0;dz<=1;dz++) for (int y=195;y<=199;y++)
						world.setBlockState(new BlockPos(x+dx,y,z+dz),Blocks.DARK_OAK_LOG.getDefaultState(),3);
					player.getInventory().setStack(0,new ItemStack(Items.WOODEN_PICKAXE));
				}
				else if (scenario.equals("system-one-stone-canopy")) {
					for (int dx=-6;dx<=6;dx++) for (int dz=-6;dz<=8;dz++) {
						world.setBlockState(new BlockPos(x+dx,194,z+dz),Blocks.DIRT.getDefaultState(),3);
						for (int y=195;y<=199;y++) world.setBlockState(new BlockPos(x+dx,y,z+dz),Blocks.AIR.getDefaultState(),3);
					}
					var leaves = Blocks.OAK_LEAVES.getDefaultState().with(net.minecraft.state.property.Properties.PERSISTENT,true);
					for (int dx=-1;dx<=1;dx++) for (int dz=-1;dz<=1;dz++) for (int y=195;y<=199;y++) world.setBlockState(new BlockPos(x+dx,y,z+dz),leaves,3);
					player.getInventory().setStack(0,new ItemStack(Items.WOODEN_PICKAXE));
				}
				else if (scenario.equals("system-one-station-stairs")) {
					for (int dz = 1; dz <= 7; dz++) for (int dx = -1; dx <= 1; dx++) {
						int floor = 199 + Math.min(dz, 4);
						for (int y = 199; y <= floor; y++) world.setBlockState(new BlockPos(x + dx, y, z + dz), Blocks.STONE.getDefaultState(), 3);
					}
					for (int dx = 2; dx <= 3; dx++) world.setBlockState(new BlockPos(x + dx, 203, z + 4), Blocks.STONE.getDefaultState(), 3);
					world.setBlockState(new BlockPos(x + 3, 204, z + 4), Blocks.FURNACE.getDefaultState(), 3);
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
				else if (scenario.equals("system-one-smelting") || scenario.equals("system-one-furnace-reach") || scenario.equals("system-one-furnace-return") || scenario.equals("system-one-furnace-footing")) {
					player.getInventory().setStack(0, new ItemStack(Items.RAW_IRON));
					player.getInventory().setStack(1, new ItemStack(Items.OAK_PLANKS));
					world.setBlockState(new BlockPos(x, 200, z + 2), Blocks.FURNACE.getDefaultState(), 3);
					if (scenario.equals("system-one-furnace-footing")) for(int y=197;y<=199;y++) world.setBlockState(new BlockPos(x,y,z),Blocks.AIR.getDefaultState(),3);
					if (scenario.equals("system-one-furnace-reach")) {
						world.setBlockState(new BlockPos(x,200,z), Blocks.LARGE_FERN.getDefaultState(), 3);
						world.setBlockState(new BlockPos(x,201,z), Blocks.LARGE_FERN.getDefaultState().with(net.minecraft.block.TallPlantBlock.HALF, net.minecraft.block.enums.DoubleBlockHalf.UPPER), 3);
					}
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
				player.teleport(world, x + (scenario.equals("system-one-furnace-footing") ? 0.05 : 0.5), scenario.equals("system-one-pickup-step") ? 197 : 200, z + 0.5, Set.<PositionFlag>of(), 0, scenario.equals("system-one-station-stairs") ? -25 : 45, true);
			}, server);
			return false;
		}
		if (!preparation.isDone()) return false;
		preparation.join();
		if (readyAfter == Long.MAX_VALUE) readyAfter = tick + 40;
		setupComplete = tick >= readyAfter && client.player.getBlockY() == (scenario.equals("system-one-pickup-step") ? 197 : 200);
		return setupComplete && (!scenario.equals("system-one-world-change") || worldProbe.ready(client,tick,output)) && (!scenario.equals("system-one-terrain") || terrainProbe.ready(client, tick, output));
	}
	private void changeRegionLight(MinecraftClient client,long tick) {
		if(client.player==null||client.getServer()==null)return;
		boolean restore=depletion!=null;
		if(restore) {
			if(!depletion.isDone())return;depletion.join();
			if(routeChange!=null){if(routeChange.isDone())routeChange.join();return;}
			if(!ai.moeru.airicraft.AiricraftClient.runtimeController().agentRuntime().semanticEventContains("system_one.task_revised",java.util.Map.of("detail","working_light_regained")))return;
		} else if(client.player.getBlockZ()<lightSource.getZ())return;
		var server=client.getServer();var id=client.player.getUuid();var source=lightSource;
		var change=CompletableFuture.runAsync(()->{
			var player=server.getPlayerManager().getPlayer(id);
			if(player==null)throw new IllegalStateException("Lighting region player unavailable");
			player.getWorld().setBlockState(source,restore?Blocks.SEA_LANTERN.getDefaultState():Blocks.SPRUCE_LEAVES.getDefaultState().with(net.minecraft.state.property.Properties.PERSISTENT,true),3);
			try { java.nio.file.Files.writeString(output.resolve(restore?"fixture-light-restored.json":"fixture-light-removed.json"),new com.google.gson.Gson().toJson(java.util.Map.of("runtimeTick",tick,"restored",restore,"sourceX",source.getX(),"sourceY",source.getY(),"sourceZ",source.getZ()))); }
			catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
		},server);
		if(restore)routeChange=change;else depletion=change;
	}

	private void darkenActiveWork(MinecraftClient client, String scenario, long tick) {
		if (depletion != null) { if (depletion.isDone()) depletion.join(); return; }
		if (client.player == null || client.getServer() == null) return;
		boolean gathering = (scenario.equals("system-one-lighting-gather") || scenario.equals("system-one-lighting-leaves") || scenario.equals("system-one-lighting-bootstrap"));
		var payload = gathering ? java.util.Map.of("commandType","Break","targetBlock",scenario.equals("system-one-lighting-bootstrap") ? "minecraft:stone" : "minecraft:coal_ore") : java.util.Map.<String,String>of();
		if (!ai.moeru.airicraft.AiricraftClient.runtimeController().agentRuntime().semanticEventContains(gathering ? "system_one.motor_effect" : "system_one.task_waiting",payload)) return;
		var server=client.getServer(); var id=client.player.getUuid(); var source=lightSource;
		depletion=CompletableFuture.runAsync(() -> {
			var player=server.getPlayerManager().getPlayer(id);
			if (player==null) throw new IllegalStateException("Lighting fixture player unavailable");
			player.getWorld().setBlockState(source,Blocks.AIR.getDefaultState(),3);
			try {
				var evidence=java.util.Map.of("kind",gathering ? "darkened_gather" : "darkened_furnace_wait","requestedAtRuntimeTick",tick,"sourceX",source.getX(),"sourceY",source.getY(),"sourceZ",source.getZ());
				java.nio.file.Files.writeString(output.resolve("fixture-intervention.json"),new com.google.gson.Gson().toJson(evidence));
			} catch(java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
		},server);
	}
	private void displaceDuringCooking(MinecraftClient client,long tick) {
		if (depletion!=null) { if(depletion.isDone())depletion.join(); return; }
		if(client.player==null || client.getServer()==null)return;
		if(!ai.moeru.airicraft.AiricraftClient.runtimeController().agentRuntime().semanticEventContains("system_one.task_waiting",java.util.Map.of()))return;
		var server=client.getServer();var id=client.player.getUuid();
		depletion=CompletableFuture.runAsync(()->{
			var player=server.getPlayerManager().getPlayer(id);
			if(player==null)throw new IllegalStateException("Furnace return player unavailable");
			double x=player.getX(),y=player.getY(),z=player.getZ();
			player.teleport(player.getWorld(),x,y,z-7,Set.<PositionFlag>of(),0,25,true);
			try { java.nio.file.Files.writeString(output.resolve("fixture-displacement.json"),new com.google.gson.Gson().toJson(java.util.Map.of(
				"kind","displaced_during_cooking","runtimeTick",tick,"from",java.util.Map.of("x",x,"y",y,"z",z),"to",java.util.Map.of("x",x,"y",y,"z",z-7)))); }
			catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
		},server);
	}

	private void injureDuringDimSearch(MinecraftClient client, long tick) {
		if (depletion != null) { if (depletion.isDone()) depletion.join(); return; }
		if (client.player == null || client.getServer() == null) return;
		if (!ai.moeru.airicraft.AiricraftClient.runtimeController().agentRuntime().semanticEventContains("system_one.motor_effect",
			java.util.Map.of("commandType", "Break", "targetBlock", "minecraft:dirt"))) return;
		var server = client.getServer(); var id = client.player.getUuid();
		depletion = CompletableFuture.runAsync(() -> {
			var player = server.getPlayerManager().getPlayer(id);
			if (player == null) throw new IllegalStateException("Dim health fixture player unavailable");
			float before = player.getHealth();
			player.damage(player.getWorld(), player.getWorld().getDamageSources().generic(), 2);
			try {
				var evidence = java.util.Map.of("kind", "health_loss_during_dim_search", "requestedAtRuntimeTick", tick,
					"healthBefore", before, "healthAfter", player.getHealth());
				java.nio.file.Files.writeString(output.resolve("fixture-intervention.json"), new com.google.gson.Gson().toJson(evidence));
			} catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
		}, server);
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
		return scenario.equals("system-one-return-route") || scenario.equals("system-one-return-long") || scenario.equals("system-one-return-blocked");
	}
	private void slowReturnTravel(MinecraftClient client,long tick) {
		if(routeChange!=null){if(routeChange.isDone())routeChange.join();return;}
		if(depletion==null||!depletion.isDone()||client.player==null||client.getServer()==null)return;
		if(!ai.moeru.airicraft.AiricraftClient.runtimeController().agentRuntime().semanticEventContains("system_one.task_resumed",java.util.Map.of("detail","SUCCEEDED:inventory_observed:minecraft:torch:8")))return;
		var server=client.getServer();var id=client.player.getUuid();
		routeChange=CompletableFuture.runAsync(()->{
			var player=server.getPlayerManager().getPlayer(id);
			if(player==null)throw new IllegalStateException("Long return player unavailable");
			player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.SLOWNESS,12000,3));
			try {java.nio.file.Files.writeString(output.resolve("fixture-return-slowed.json"),new com.google.gson.Gson().toJson(java.util.Map.of("runtimeTick",tick,"effect","minecraft:slowness","amplifier",3,"durationTicks",12000)));}
			catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
		},server);
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
