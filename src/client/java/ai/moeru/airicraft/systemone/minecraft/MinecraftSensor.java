package ai.moeru.airicraft.systemone.minecraft;

import ai.moeru.airicraft.systemone.voxel.StoneAcquisition;
import ai.moeru.airicraft.systemone.voxel.VoxelObservation;
import ai.moeru.airicraft.systemone.voxel.FootingMemory;
import ai.moeru.airicraft.systemone.voxel.RouteMemory;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EmptyBlockView;

import java.util.HashMap;
import java.util.Map;

import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;

final class MinecraftSensor {
	private static final Lens LENS = new Lens(16, 100, 80, 2, 2);
	private final Map<Pos, Seen> memory = new HashMap<>();
	private final Map<BlockPos, BlockState> terrain = new HashMap<>();
	private final java.util.Set<Pos> footholds = new java.util.HashSet<>();

	StoneAcquisition.World observe(MinecraftClient client, long tick, java.util.Set<Pos> retained) {
		var player = client.player;
		var eye = player.getEyePos();
		Pos feet = new Pos(player.getBlockX(), player.getBlockY(), player.getBlockZ());
		footholds.removeIf(pos -> !RouteMemory.keep(pos, feet, retained));
		Pose pose = new Pose(eye.x, eye.y, eye.z, player.getYaw(), player.getPitch());
		Map<Pos, BlockState> samples = new HashMap<>();
		Map<Pos, Seen> seen = VoxelObservation.observe(pos -> {
			BlockPos p = new BlockPos(pos.x(), pos.y(), pos.z());
			if (!client.world.isChunkLoaded(p)) return new Sample("unknown", false, false, 0);
			BlockState state = client.world.getBlockState(p);
			samples.put(pos, state);
			// Static collision shapes are cached by Minecraft; this does not query hidden neighbors.
			return new Sample(Registries.BLOCK.getId(state.getBlock()).toString(), state.isAir(), !state.getBlock().hasDynamicBounds() && state.isFullCube(EmptyBlockView.INSTANCE, p), client.world.getLightLevel(p));
		}, pose, LENS, tick);
		seen.forEach((pos, value) -> {
			// A dark re-observation cannot refresh a remembered block's identity.
			if (value.identified()) {
				memory.put(pos, value);
				BlockState state = samples.get(pos);
				if (state != null) terrain.put(new BlockPos(pos.x(), pos.y(), pos.z()), state);
			}
		});
		var body = player.getBoundingBox();
		var contact = player.isOnGround() ? java.util.Optional.of(new FootingMemory.Contact(
			new Pos((int) Math.floor(body.minX + 1e-7), player.getBlockY() - 1, (int) Math.floor(body.minZ + 1e-7)),
			new Pos((int) Math.floor(body.maxX - 1e-7), player.getBlockY() - 1, (int) Math.floor(body.maxZ - 1e-7)))) : java.util.Optional.<FootingMemory.Contact>empty();
		var supports = FootingMemory.update(footholds, memory, contact);
		footholds.clear(); footholds.addAll(supports);
		memory.keySet().removeIf(pos -> !RouteMemory.keep(pos, feet, retained));
		terrain.keySet().removeIf(pos -> !memory.containsKey(new Pos(pos.getX(), pos.getY(), pos.getZ())));
		ObservedTerrain.publish(terrain);
		Map<String, Integer> inventory = new HashMap<>();
		for (int i = 0; i < player.getInventory().size(); i++) {
			var stack = player.getInventory().getStack(i);
			if (!stack.isEmpty()) inventory.merge(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), Integer::sum);
		}
		return new StoneAcquisition.World(pose, feet, inventory, memory, footholds);
	}
	void clear() { memory.clear(); terrain.clear(); footholds.clear(); ObservedTerrain.clear(); }
}
