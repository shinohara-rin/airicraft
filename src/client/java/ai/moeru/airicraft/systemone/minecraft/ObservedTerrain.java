package ai.moeru.airicraft.systemone.minecraft;

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
	public static void publish(Map<BlockPos, BlockState> observations) {
		current = new View(net.minecraft.client.MinecraftClient.getInstance().world, observations, observations.keySet().stream()
			.map(pos -> column(pos.getX(), pos.getZ())).collect(Collectors.toSet()));
	}
	public static void clear() { current = EMPTY; }
	private static long column(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
}
