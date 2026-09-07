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
	private static volatile View current = new View(Map.of(), Set.of());

	private ObservedTerrain() {}

	public record View(Map<BlockPos, BlockState> blocks, Set<Long> columns) {
		public View { blocks = Map.copyOf(blocks); columns = Set.copyOf(columns); }
		public BlockState get(int x, int y, int z) {
			return blocks.getOrDefault(new BlockPos(x, y, z), Blocks.BARRIER.getDefaultState());
		}
		public boolean knownColumn(int x, int z) { return columns.contains(column(x, z)); }
	}

	public static View capture() { return current; }
	public static void publish(Map<BlockPos, BlockState> observations) {
		current = new View(observations, observations.keySet().stream()
			.map(pos -> column(pos.getX(), pos.getZ())).collect(Collectors.toSet()));
	}
	public static void clear() { current = new View(Map.of(), Set.of()); }
	private static long column(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
}
