package ai.moeru.airicraft.systemone.minecraft;

import ai.moeru.airicraft.systemone.voxel.VoxelObservation;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Only the sensing adapter publishes here. Each path calculation captures one immutable revision. */
public final class ObservedTerrain {
	public static final boolean ENABLED = Boolean.getBoolean("airicraft.systemOne");
	private static final View EMPTY = new View(null, Map.of(), Set.of());
	private static volatile View current = EMPTY;

	private ObservedTerrain() {}

	public record View(net.minecraft.client.world.ClientWorld world, Map<BlockPos, BlockState> blocks, Set<Long> columns) {
		public View { blocks = Map.copyOf(blocks); columns = Set.copyOf(columns); }
		private boolean currentWorld() { return world != null && world == net.minecraft.client.MinecraftClient.getInstance().world; }
		@Override public Map<BlockPos, BlockState> blocks() { return currentWorld() ? blocks : Map.of(); }
		@Override public Set<Long> columns() { return currentWorld() ? columns : Set.of(); }
		public BlockState get(int x, int y, int z) {
			return currentWorld() ? blocks.getOrDefault(new BlockPos(x, y, z), Blocks.BARRIER.getDefaultState()) : Blocks.BARRIER.getDefaultState();
		}
		public boolean knownColumn(int x, int z) { return currentWorld() && columns.contains(column(x, z)); }
	}

	public static View capture() { var view = current; return view.currentWorld() ? view : EMPTY; }
	public static BlockState get(BlockPos pos) { return current.get(pos.getX(), pos.getY(), pos.getZ()); }
	/** Production navigation is reconstructed solely from recorded observation values and the registry. */
	public static void publishObserved(Map<VoxelObservation.Pos, VoxelObservation.Seen> observations) {
		var states = new java.util.HashMap<BlockPos,BlockState>();
		observations.forEach((pos, seen) -> states.put(new BlockPos(pos.x(),pos.y(),pos.z()), decode(seen)));
		publish(states);
	}
	/** Missing, unknown or malformed state is impassable; never guess registry defaults for omitted properties. */
	public static BlockState decode(VoxelObservation.Seen seen) {
		var blocked = Blocks.BARRIER.getDefaultState();
		if (!seen.identified()) return blocked;
		var id = Identifier.tryParse(seen.blockId());
		if (id == null || !Registries.BLOCK.containsId(id)) return blocked;
		var block = Registries.BLOCK.get(id);
		var properties = block.getStateManager().getProperties();
		if (!seen.properties().keySet().equals(properties.stream().map(p -> p.getName()).collect(Collectors.toSet()))) return blocked;
		BlockState state = block.getDefaultState();
		for (var property : properties) {
			state = apply(state, property, seen.properties().get(property.getName()));
			if (state == null) return blocked;
		}
		return state;
	}
	private static <T extends Comparable<T>> BlockState apply(BlockState state, net.minecraft.state.property.Property<T> property, String value) {
		return property.parse(value).map(parsed -> state.with(property, parsed)).orElse(null);
	}
	/** Synthetic adapter probes may publish engine states directly; the live sensor uses publishObserved. */
	public static void publish(Map<BlockPos, BlockState> observations) {
		current = new View(net.minecraft.client.MinecraftClient.getInstance().world, observations, observations.keySet().stream()
			.map(pos -> column(pos.getX(), pos.getZ())).collect(Collectors.toSet()));
	}
	public static void clear() { current = EMPTY; }
	private static long column(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
}
