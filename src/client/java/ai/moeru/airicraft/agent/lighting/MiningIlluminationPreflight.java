package ai.moeru.airicraft.agent.lighting;

import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MiningIlluminationPreflight {
	private static final int HORIZONTAL_SCAN_RADIUS = 16;
	private static final int VERTICAL_SCAN_RADIUS = 12;
	private static final int SURFACE_OPENING_PROBE_BLOCKS = 6;
	private static final Set<String> UNDERGROUND_BLOCK_IDS = Set.of(
		"minecraft:stone",
		"minecraft:deepslate",
		"minecraft:tuff",
		"minecraft:netherrack",
		"minecraft:blackstone",
		"minecraft:basalt",
		"minecraft:ancient_debris"
	);

	private MiningIlluminationPreflight() {
	}

	public static Result inspect(MinecraftClient client, GoalMineSpec spec, int maxLightLevel) {
		if (client == null || client.world == null || client.player == null || spec == null) {
			return Result.notRequired();
		}
		BlockPos origin = client.player.getBlockPos();
		if (isLikelyUndergroundPosition(client, origin)) {
			return new Result(true, "current_position_underground");
		}
		if (hasLoadedUnilluminatedTarget(client, origin, spec.blockIds(), maxLightLevel)) {
			return new Result(true, "loaded_target_unilluminated");
		}
		if (likelyUndergroundTarget(spec.blockIds())) {
			return new Result(true, "target_type_likely_underground");
		}
		return Result.notRequired();
	}

	static Result assess(boolean currentUnderground, boolean loadedTargetUnilluminated, boolean targetTypeLikelyUnderground) {
		if (currentUnderground) {
			return new Result(true, "current_position_underground");
		}
		if (loadedTargetUnilluminated) {
			return new Result(true, "loaded_target_unilluminated");
		}
		if (targetTypeLikelyUnderground) {
			return new Result(true, "target_type_likely_underground");
		}
		return Result.notRequired();
	}

	static boolean likelyUndergroundTarget(List<String> blockIds) {
		if (blockIds == null) {
			return false;
		}
		return blockIds.stream().anyMatch(blockId ->
			blockId != null && (blockId.endsWith("_ore") || UNDERGROUND_BLOCK_IDS.contains(blockId))
		);
	}

	public static Admission admit(Result prediction, int torchCount, boolean allowUnilluminated) {
		if (prediction == null || !prediction.illuminationRequired()) {
			return new Admission(true, "illumination_not_required");
		}
		if (torchCount > 0) {
			return new Admission(true, "torch_available");
		}
		if (allowUnilluminated) {
			return new Admission(true, "planner_override");
		}
		return new Admission(false, "insufficient_illumination");
	}

	private static boolean hasLoadedUnilluminatedTarget(
		MinecraftClient client,
		BlockPos origin,
		List<String> requestedBlockIds,
		int maxLightLevel
	) {
		Set<String> blockIds = new HashSet<>(requestedBlockIds);
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		for (int x = -HORIZONTAL_SCAN_RADIUS; x <= HORIZONTAL_SCAN_RADIUS; x++) {
			for (int y = -VERTICAL_SCAN_RADIUS; y <= VERTICAL_SCAN_RADIUS; y++) {
				for (int z = -HORIZONTAL_SCAN_RADIUS; z <= HORIZONTAL_SCAN_RADIUS; z++) {
					cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
					if (!client.world.isChunkLoaded(cursor)) {
						continue;
					}
					BlockState state = client.world.getBlockState(cursor);
					if (!blockIds.contains(Registries.BLOCK.getId(state.getBlock()).toString())) {
						continue;
					}
					if (!client.world.isSkyVisible(cursor.up()) || client.world.getLightLevel(cursor) <= maxLightLevel) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static boolean isLikelyUndergroundPosition(MinecraftClient client, BlockPos position) {
		for (int offset = 1; offset <= SURFACE_OPENING_PROBE_BLOCKS; offset++) {
			if (client.world.isSkyVisible(position.up(offset))) {
				return false;
			}
		}
		return true;
	}

	public record Result(boolean illuminationRequired, String reason) {
		private static Result notRequired() {
			return new Result(false, "not_predicted");
		}
	}

	public record Admission(boolean allowed, String reason) {
	}
}
