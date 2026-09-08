package ai.moeru.airicraft.systemone.minecraft;

import ai.moeru.airicraft.systemone.voxel.StoneAcquisition;
import ai.moeru.airicraft.systemone.voxel.VoxelObservation;
import ai.moeru.airicraft.systemone.voxel.FootingMemory;
import ai.moeru.airicraft.systemone.voxel.RouteMemory;
import ai.moeru.airicraft.systemone.voxel.SurvivalPolicy;
import ai.moeru.airicraft.systemone.voxel.ItemObservation;
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
	private final java.util.Set<Pos> footholds = new java.util.HashSet<>();
	private net.minecraft.client.network.ClientPlayerEntity previousPlayer;
	private net.minecraft.client.world.ClientWorld previousWorld;
	private long life;

	StoneAcquisition.World observe(MinecraftClient client, long tick, java.util.Set<Pos> retained) {
		if (previousWorld != null && previousWorld != client.world) clear();
		previousWorld = client.world;
		var player = client.player;
		if (client.world.getChunkManager().getChunk(player.getBlockX() >> 4, player.getBlockZ() >> 4, net.minecraft.world.chunk.ChunkStatus.FULL, false) == null) {
			ObservedTerrain.clear();
			return null;
		}
		if (previousPlayer != null && previousPlayer != player) { life++; footholds.clear(); }
		previousPlayer = player;
		var eye = player.getEyePos();
		Pos feet = new Pos(player.getBlockX(), player.getBlockY(), player.getBlockZ());
		footholds.removeIf(pos -> !RouteMemory.keep(pos, feet, retained));
		Pose pose = new Pose(eye.x, eye.y, eye.z, player.getYaw(), player.getPitch());
		var scene = new MinecraftScene(client.world);
		Map<Pos, Seen> seen = VoxelObservation.observe(scene, pose, LENS, tick);
		seen.forEach((pos, value) -> {
			// A dark re-observation cannot refresh a remembered block's identity.
			if (value.identified()) {
				memory.put(pos, value);
				BlockState state = scene.sampledState(pos);
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
		var drops = new java.util.ArrayList<ItemObservation.Drop>();
		for (var entity : client.world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class,player.getBoundingBox().expand(LENS.range()),item -> item.isAlive() && !item.isInvisible())) {
			var center=entity.getBoundingBox().getCenter();
			if (!ItemObservation.visible(seen,pose,LENS,new ItemObservation.Point(center.x,center.y,center.z))) continue;
			// Candidate geometry is filtered before reading item identity/count. No hidden item reaches the value boundary.
			var stack=entity.getStack();
			if (!stack.isEmpty()) drops.add(new ItemObservation.Drop(entity.getUuidAsString(),Registries.ITEM.getId(stack.getItem()).toString(),stack.getCount(),new ItemObservation.Point(entity.getX(),entity.getY(),entity.getZ()),tick));
		}
		drops.sort(java.util.Comparator.comparing(ItemObservation.Drop::id));
		return new StoneAcquisition.World(pose, feet, inventory, memory, footholds, new SurvivalPolicy.Vitals(life, player.getHealth(), player.isInLava(), player.isOnFire()), drops);
	}
	void clear() { memory.clear(); terrain.clear(); footholds.clear(); previousPlayer = null; previousWorld = null; life = 0; ObservedTerrain.clear(); }
}
