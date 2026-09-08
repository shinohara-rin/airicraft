package ai.moeru.airicraft.systemone.minecraft;

import ai.moeru.airicraft.systemone.voxel.VoxelObservation;
import net.minecraft.block.BlockState;
import net.minecraft.block.SideShapeType;
import net.minecraft.util.math.Direction;
import ai.moeru.airicraft.systemone.voxel.VoxelCommand.Face;
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
	public MinecraftScene(ClientWorld world) { this.world = world; }
	@Override public Sample sample(Pos pos) {
		BlockPos p = new BlockPos(pos.x(), pos.y(), pos.z());
		// ClientWorld.isChunkLoaded always returns true; its fallback chunk contains void air.
		var chunk = world.getChunkManager().getChunk(pos.x() >> 4, pos.z() >> 4, ChunkStatus.FULL, false);
		if (chunk == null || world.isOutOfHeightLimit(p)) return new Sample("unknown", false, false, 0);
		BlockState state = chunk.getBlockState(p);
		// Static collision shapes do not query hidden neighbors.
		boolean staticShape = !state.getBlock().hasDynamicBounds();
		return new Sample(Registries.BLOCK.getId(state.getBlock()).toString(), state.isAir(),
			staticShape && state.isFullCube(EmptyBlockView.INSTANCE, p), world.getLightLevel(p),
			staticShape && state.getFluidState().isEmpty() && state.getCollisionShape(EmptyBlockView.INSTANCE, p).isEmpty(), attachment(state), properties(state));
	}
	/** Registry-defined attachment geometry of an identified block; never reads the surrounding world. */
	public static Attachment attachment(BlockState state) {
		if (state.getBlock().hasDynamicBounds()) return Attachment.none();
		var faces = java.util.EnumSet.noneOf(Face.class);
		for (Face face : Face.values()) if (state.isSideSolidFullSquare(EmptyBlockView.INSTANCE,BlockPos.ORIGIN,Direction.valueOf(face.name()))) faces.add(face);
		return new Attachment(faces,state.isSideSolid(EmptyBlockView.INSTANCE,BlockPos.ORIGIN,Direction.UP,SideShapeType.CENTER));
	}

	/** Complete registry state of an identified surface, recorded before navigation consumes it. */
	public static Map<String,String> properties(BlockState state) {
		var values = new HashMap<String,String>();
		for (var property : state.getProperties()) values.put(property.getName(), propertyValue(state, property));
		return Map.copyOf(values);
	}
	private static <T extends Comparable<T>> String propertyValue(BlockState state, net.minecraft.state.property.Property<T> property) {
		return property.name(state.get(property));
	}
}
