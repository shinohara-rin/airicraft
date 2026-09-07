package ai.moeru.airicraft.systemone.minecraft;

import ai.moeru.airicraft.systemone.voxel.StoneAcquisition;
import ai.moeru.airicraft.systemone.voxel.VoxelObservation;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;

final class MinecraftSensor {
	private static final Lens LENS = new Lens(16, 100, 80, 2, 2);
	private final Map<Pos, Seen> memory = new HashMap<>();
	private final Map<BlockPos, BlockState> terrain = new HashMap<>();

	StoneAcquisition.World observe(MinecraftClient client, long tick) {
		var player = client.player;
		var eye = player.getEyePos();
		Pose pose = new Pose(eye.x, eye.y, eye.z, player.getYaw(), player.getPitch());
		Map<Pos, BlockState> samples = new HashMap<>();
		Map<Pos, Seen> seen = VoxelObservation.observe(pos -> {
			BlockPos p = new BlockPos(pos.x(), pos.y(), pos.z());
			if (!client.world.isChunkLoaded(p)) return new Sample("unknown", false, 0);
			BlockState state = client.world.getBlockState(p);
			samples.put(pos, state);
			return new Sample(Registries.BLOCK.getId(state.getBlock()).toString(), state.isAir(), client.world.getLightLevel(p));
		}, pose, LENS, tick);
		seen.forEach((pos, value) -> {
			// A dark re-observation cannot refresh a remembered block's identity.
			if (value.identified()) {
				memory.put(pos, value);
				BlockState state = samples.get(pos);
				if (state != null) terrain.put(new BlockPos(pos.x(), pos.y(), pos.z()), state);
			}
		});
		memory.keySet().removeIf(pos -> Math.abs(pos.x() - player.getBlockX()) > 48 || Math.abs(pos.z() - player.getBlockZ()) > 48);
		terrain.keySet().removeIf(pos -> !memory.containsKey(new Pos(pos.getX(), pos.getY(), pos.getZ())));
		ObservedTerrain.publish(terrain);
		Map<String, Integer> inventory = new HashMap<>();
		for (int i = 0; i < player.getInventory().size(); i++) {
			var stack = player.getInventory().getStack(i);
			if (!stack.isEmpty()) inventory.merge(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), Integer::sum);
		}
		return new StoneAcquisition.World(pose, new Pos(player.getBlockX(), player.getBlockY(), player.getBlockZ()), inventory, memory);
	}
	void clear() { memory.clear(); terrain.clear(); ObservedTerrain.clear(); }
}
