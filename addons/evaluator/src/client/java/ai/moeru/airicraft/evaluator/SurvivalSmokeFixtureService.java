package ai.moeru.airicraft.evaluator;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.World;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Evaluator-only deterministic setup for the live survival reflex smokes. */
final class SurvivalSmokeFixtureService {
	private static final int FIXTURE_Y = 200;
	private static final int PLATFORM_RADIUS = 16;
	private static final int CLEAR_RADIUS = PLATFORM_RADIUS + 1;
	private static final int CLEAR_BOTTOM = FIXTURE_Y - 1;
	private static final int CLEAR_TOP = FIXTURE_Y + 6;
	private static final int FLEE_THREAT_SYNC_DELAY_TICKS = 20;

	private Origin origin;
	private BlockPos fixtureCenter;
	private Entity threat;
	private long underwaterExitAtWorldTime = -1L;
	private long fleeThreatSpawnAtWorldTime = -1L;

	void onClientTick(MinecraftClient client) {
		if (client.player == null || client.world == null || client.getServer() == null) {
			return;
		}
		ServerWorld world = client.getServer().getWorld(client.world.getRegistryKey());
		if (world == null) {
			return;
		}
		ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(client.player.getUuid());
		if (player == null) {
			return;
		}
		if (underwaterExitAtWorldTime >= 0L && world.getTime() >= underwaterExitAtWorldTime) {
			cleanup(world, player);
			return;
		}
		if (fleeThreatSpawnAtWorldTime >= 0L && world.getTime() >= fleeThreatSpawnAtWorldTime) {
			fleeThreatSpawnAtWorldTime = -1L;
			threat = spawnZombie(world, player);
		}
	}

	Map<String, Object> apply(Request request) {
		Mode mode = request == null ? null : Mode.parse(request.mode());
		if (mode == null) {
			throw new FixtureException("invalid_request", "mode must be loadout, underwater, mob_defend, mob_flee, mob_flee_natural, or cleanup");
		}

		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.world == null || client.getServer() == null) {
			throw new FixtureException("world_not_loaded", "A singleplayer world must be loaded");
		}
		ServerWorld world = client.getServer().getWorld(client.world.getRegistryKey());
		if (world == null) {
			throw new FixtureException("world_not_loaded", "The integrated server world is unavailable");
		}
		ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(client.player.getUuid());
		if (player == null) {
			throw new FixtureException("player_not_loaded", "The integrated server player is unavailable");
		}

		if (mode == Mode.CLEANUP) {
			cleanup(world, player);
			return payload(mode, player, null);
		}

		cleanup(world, player);
		origin = new Origin(world.getRegistryKey(), player.getPos(), player.getYaw(), player.getPitch());
		world.getServer().setDifficulty(Difficulty.NORMAL, false);
		if (mode == Mode.MOB_FLEE_NATURAL) {
			return setupNaturalMobFlee(world, player);
		}
		fixtureCenter = new BlockPos(player.getBlockX(), FIXTURE_Y, player.getBlockZ());
		clearFixture(world);

		return switch (mode) {
			case LOADOUT -> setupLoadout(world, player);
			case UNDERWATER -> setupUnderwater(world, player);
			case MOB_DEFEND -> setupMob(world, player, true);
			case MOB_FLEE -> setupMob(world, player, false);
			case MOB_FLEE_NATURAL -> throw new IllegalStateException("natural flee handled above");
			case CLEANUP -> throw new IllegalStateException("cleanup handled above");
		};
	}

	private Map<String, Object> setupNaturalMobFlee(ServerWorld world, ServerPlayerEntity player) {
		player.equipStack(EquipmentSlot.HEAD, ItemStack.EMPTY);
		player.equipStack(EquipmentSlot.CHEST, ItemStack.EMPTY);
		player.equipStack(EquipmentSlot.LEGS, ItemStack.EMPTY);
		player.equipStack(EquipmentSlot.FEET, ItemStack.EMPTY);
		player.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_SWORD));
		player.setVelocity(Vec3d.ZERO);
		player.setAir(player.getMaxAir());
		player.setHealth(player.getMaxHealth() * 0.5F);
		BlockPos spawn = findNaturalThreatSpawn(world, player.getBlockPos());
		ZombieEntity zombie = spawnZombie(world, player, spawn);
		threat = zombie;
		return payload(Mode.MOB_FLEE_NATURAL, player, zombie.getUuid());
	}

	private Map<String, Object> setupLoadout(ServerWorld world, ServerPlayerEntity player) {
		prepareMobPlatform(world, player);
		player.getInventory().clear();
		player.equipStack(EquipmentSlot.HEAD, ItemStack.EMPTY);
		player.equipStack(EquipmentSlot.CHEST, ItemStack.EMPTY);
		player.equipStack(EquipmentSlot.LEGS, ItemStack.EMPTY);
		player.equipStack(EquipmentSlot.FEET, ItemStack.EMPTY);
		player.equipStack(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
		player.getInventory().insertStack(new ItemStack(Items.IRON_CHESTPLATE));
		player.getInventory().insertStack(new ItemStack(Items.IRON_LEGGINGS));
		player.getInventory().insertStack(new ItemStack(Items.IRON_SWORD));
		player.getInventory().insertStack(new ItemStack(Items.BREAD, 2));
		player.getHungerManager().setFoodLevel(12);
		player.getHungerManager().setSaturationLevel(0.0F);
		player.setHealth(player.getMaxHealth());
		return payload(Mode.LOADOUT, player, null);
	}

	private Map<String, Object> setupUnderwater(ServerWorld world, ServerPlayerEntity player) {
		BlockPos center = fixtureCenter;
		fill(world, center.add(-4, -1, -4), center.add(4, -1, 4), Blocks.STONE.getDefaultState());
		fill(world, center.add(-2, 0, -2), center.add(-2, 4, 2), Blocks.GLASS.getDefaultState());
		fill(world, center.add(2, 0, -2), center.add(2, 4, 2), Blocks.GLASS.getDefaultState());
		fill(world, center.add(-1, 0, -2), center.add(1, 4, -2), Blocks.GLASS.getDefaultState());
		fill(world, center.add(-1, 0, 2), center.add(1, 4, 2), Blocks.GLASS.getDefaultState());
		fill(world, center.add(-1, 0, -1), center.add(1, 3, 1), Blocks.WATER.getDefaultState());
		teleport(player, world, center.getX() + 0.5, center.getY() + 0.1, center.getZ() + 0.5);
		player.setVelocity(Vec3d.ZERO);
		player.setHealth(player.getMaxHealth());
		player.setAir(80);
		underwaterExitAtWorldTime = world.getTime() + 80L;
		return payload(Mode.UNDERWATER, player, null);
	}

	private Map<String, Object> setupMob(ServerWorld world, ServerPlayerEntity player, boolean defend) {
		prepareMobPlatform(world, player);
		if (!defend) {
			BlockPos center = fixtureCenter;
			fill(world, center.add(-15, -2, -3), center.add(-1, -2, 3), Blocks.STONE.getDefaultState());
			fill(world, center.add(-15, -1, -3), center.add(-1, -1, 3), Blocks.WATER.getDefaultState());
			player.equipStack(EquipmentSlot.HEAD, ItemStack.EMPTY);
			player.equipStack(EquipmentSlot.CHEST, ItemStack.EMPTY);
			player.equipStack(EquipmentSlot.LEGS, ItemStack.EMPTY);
			player.equipStack(EquipmentSlot.FEET, ItemStack.EMPTY);
			player.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		}
		player.setHealth(defend ? player.getMaxHealth() : player.getMaxHealth() * 0.5F);
		if (!defend) {
			fleeThreatSpawnAtWorldTime = world.getTime() + FLEE_THREAT_SYNC_DELAY_TICKS;
			return payload(Mode.MOB_FLEE, player, null);
		}
		ZombieEntity zombie = spawnZombie(world, player);
		threat = zombie;
		return payload(Mode.MOB_DEFEND, player, zombie.getUuid());
	}

	private ZombieEntity spawnZombie(ServerWorld world, ServerPlayerEntity player) {
		return spawnZombie(world, player, fixtureCenter);
	}

	private ZombieEntity spawnZombie(ServerWorld world, ServerPlayerEntity player, BlockPos center) {
		ZombieEntity zombie = new ZombieEntity(EntityType.ZOMBIE, world);
		double xOffset = fixtureCenter == null ? 0.5D : 2.5D;
		zombie.refreshPositionAndAngles(center.getX() + xOffset, center.getY(), center.getZ() + 0.5, 90.0F, 0.0F);
		zombie.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
		var attackDamage = zombie.getAttributeInstance(EntityAttributes.ATTACK_DAMAGE);
		if (attackDamage != null) {
			attackDamage.setBaseValue(0.5D);
		}
		zombie.setPersistent();
		zombie.setTarget(player);
		if (!world.spawnEntity(zombie)) {
			throw new FixtureException("fixture_setup_failed", "Failed to spawn the survival fixture zombie");
		}
		return zombie;
	}

	private static BlockPos findNaturalThreatSpawn(ServerWorld world, BlockPos playerPos) {
		int[][] offsets = {
			{2, 0}, {-2, 0}, {0, 2}, {0, -2},
			{3, 0}, {-3, 0}, {0, 3}, {0, -3},
			{2, 2}, {-2, 2}, {2, -2}, {-2, -2}
		};
		for (int[] offset : offsets) {
			for (int dy = 2; dy >= -2; dy--) {
				BlockPos feet = playerPos.add(offset[0], dy, offset[1]);
				if (isNaturalStandingPosition(world, feet)) {
					return feet;
				}
			}
		}
		throw new FixtureException("fixture_setup_failed", "No nearby natural standing position for the survival threat");
	}

	private static boolean isNaturalStandingPosition(ServerWorld world, BlockPos feet) {
		return world.getFluidState(feet).isEmpty()
			&& world.getFluidState(feet.up()).isEmpty()
			&& (world.getBlockState(feet).isAir() || world.getBlockState(feet).isReplaceable())
			&& (world.getBlockState(feet.up()).isAir() || world.getBlockState(feet.up()).isReplaceable())
			&& world.getBlockState(feet.down()).isSideSolidFullSquare(world, feet.down(), Direction.UP);
	}

	private void prepareMobPlatform(ServerWorld world, ServerPlayerEntity player) {
		BlockPos center = fixtureCenter;
		fill(
			world,
			center.add(-PLATFORM_RADIUS, -1, -PLATFORM_RADIUS),
			center.add(PLATFORM_RADIUS, -1, PLATFORM_RADIUS),
			Blocks.STONE.getDefaultState()
		);
		fill(world, center.add(-PLATFORM_RADIUS, 0, -PLATFORM_RADIUS), center.add(-PLATFORM_RADIUS, 2, PLATFORM_RADIUS), Blocks.BARRIER.getDefaultState());
		fill(world, center.add(PLATFORM_RADIUS, 0, -PLATFORM_RADIUS), center.add(PLATFORM_RADIUS, 2, PLATFORM_RADIUS), Blocks.BARRIER.getDefaultState());
		fill(world, center.add(-PLATFORM_RADIUS + 1, 0, -PLATFORM_RADIUS), center.add(PLATFORM_RADIUS - 1, 2, -PLATFORM_RADIUS), Blocks.BARRIER.getDefaultState());
		fill(world, center.add(-PLATFORM_RADIUS + 1, 0, PLATFORM_RADIUS), center.add(PLATFORM_RADIUS - 1, 2, PLATFORM_RADIUS), Blocks.BARRIER.getDefaultState());
		teleport(player, world, center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
		player.setVelocity(Vec3d.ZERO);
		player.setAir(player.getMaxAir());
	}

	private void cleanup(ServerWorld currentWorld, ServerPlayerEntity player) {
		if (threat != null && !threat.isRemoved()) {
			threat.discard();
		}
		threat = null;
		underwaterExitAtWorldTime = -1L;
		fleeThreatSpawnAtWorldTime = -1L;
		Origin savedOrigin = origin;
		if (savedOrigin != null) {
			ServerWorld originWorld = currentWorld.getServer().getWorld(savedOrigin.world());
			if (originWorld != null) {
				teleport(player, originWorld, savedOrigin.position().x, savedOrigin.position().y, savedOrigin.position().z, savedOrigin.yaw(), savedOrigin.pitch());
			}
		}
		if (fixtureCenter != null) {
			clearFixture(currentWorld);
		}
		origin = null;
		fixtureCenter = null;
	}

	private void clearFixture(ServerWorld world) {
		if (fixtureCenter == null) {
			return;
		}
		fill(
			world,
			fixtureCenter.add(-CLEAR_RADIUS, CLEAR_BOTTOM - FIXTURE_Y, -CLEAR_RADIUS),
			fixtureCenter.add(CLEAR_RADIUS, CLEAR_TOP - FIXTURE_Y, CLEAR_RADIUS),
			Blocks.AIR.getDefaultState()
		);
	}

	private static void fill(ServerWorld world, BlockPos from, BlockPos to, net.minecraft.block.BlockState state) {
		for (BlockPos pos : BlockPos.iterate(from, to)) {
			world.setBlockState(pos, state);
		}
	}

	private static void teleport(ServerPlayerEntity player, ServerWorld world, double x, double y, double z) {
		teleport(player, world, x, y, z, player.getYaw(), player.getPitch());
	}

	private static void teleport(ServerPlayerEntity player, ServerWorld world, double x, double y, double z, float yaw, float pitch) {
		if (!player.teleport(world, x, y, z, Set.<PositionFlag>of(), yaw, pitch, true)) {
			throw new FixtureException("fixture_setup_failed", "Failed to teleport the survival fixture player");
		}
	}

	private Map<String, Object> payload(Mode mode, ServerPlayerEntity player, UUID threatId) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("available", true);
		payload.put("mode", mode.wireValue);
		payload.put("difficulty", player.getWorld().getDifficulty().getName());
		payload.put("health", player.getHealth());
		payload.put("air", player.getAir());
		payload.put("hunger", player.getHungerManager().getFoodLevel());
		payload.put("armor", player.getArmor());
		payload.put("mainHandItemId", Registries.ITEM.getId(player.getMainHandStack().getItem()).toString());
		payload.put("player", position(player.getPos()));
		payload.put("fixtureCenter", fixtureCenter == null ? Map.of() : position(Vec3d.ofCenter(fixtureCenter)));
		payload.put("threatId", threatId == null ? "" : threatId.toString());
		return payload;
	}

	private static Map<String, Double> position(Vec3d position) {
		return Map.of("x", position.x, "y", position.y, "z", position.z);
	}

	record Request(String mode) {
	}

	private record Origin(RegistryKey<World> world, Vec3d position, float yaw, float pitch) {
	}

	private enum Mode {
		LOADOUT("loadout"),
		UNDERWATER("underwater"),
		MOB_DEFEND("mob_defend"),
		MOB_FLEE("mob_flee"),
		MOB_FLEE_NATURAL("mob_flee_natural"),
		CLEANUP("cleanup");

		private final String wireValue;

		Mode(String wireValue) {
			this.wireValue = wireValue;
		}

		private static Mode parse(String value) {
			if (value == null) {
				return null;
			}
			for (Mode mode : values()) {
				if (mode.wireValue.equals(value.trim().toLowerCase())) {
					return mode;
				}
			}
			return null;
		}
	}

	static final class FixtureException extends RuntimeException {
		private final String code;

		private FixtureException(String code, String message) {
			super(message);
			this.code = code;
		}

		String code() {
			return code;
		}
	}
}
