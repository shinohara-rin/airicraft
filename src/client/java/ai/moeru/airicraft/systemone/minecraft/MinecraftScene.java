package ai.moeru.airicraft.systemone.minecraft;

import ai.moeru.airicraft.systemone.voxel.VoxelObservation;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EmptyBlockView;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.HashMap;
import java.util.Map;

import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;

/** One sensing pass: engine reads occur only at cells requested by the geometric lens. */
public final class MinecraftScene implements VoxelObservation.Scene {
	private final ClientWorld world;
	private final Map<Pos, BlockState> sampled = new HashMap<>();
	public MinecraftScene(ClientWorld world) { this.world = world; }
	@Override public Sample sample(Pos pos) {
		BlockPos p = new BlockPos(pos.x(), pos.y(), pos.z());
		// ClientWorld.isChunkLoaded always returns true; its fallback chunk contains void air.
		var chunk = world.getChunkManager().getChunk(pos.x() >> 4, pos.z() >> 4, ChunkStatus.FULL, false);
		if (chunk == null || world.isOutOfHeightLimit(p)) return new Sample("unknown", false, false, 0);
		BlockState state = chunk.getBlockState(p);
		sampled.put(pos, state);
		// Static collision shapes do not query hidden neighbors.
		boolean staticShape = !state.getBlock().hasDynamicBounds();
		return new Sample(Registries.BLOCK.getId(state.getBlock()).toString(), state.isAir(),
			staticShape && state.isFullCube(EmptyBlockView.INSTANCE, p), world.getLightLevel(p),
			staticShape && state.getFluidState().isEmpty() && state.getCollisionShape(EmptyBlockView.INSTANCE, p).isEmpty());
	}
	BlockState sampledState(Pos pos) { return sampled.get(pos); }
}
