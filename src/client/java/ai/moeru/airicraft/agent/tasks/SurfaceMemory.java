package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Optional;

public final class SurfaceMemory {
	private static final int NEAREST_SURFACE_RADIUS = 12;
	private static final int NEAREST_SURFACE_UP = 48;
	private static final int NEAREST_SURFACE_DOWN = 96;
	static final long NEAREST_SURFACE_FAST_REFRESH_TICKS = 10L;
	static final long NEAREST_SURFACE_REFRESH_TICKS = 40L;
	static final double NEAREST_SURFACE_MOVE_REFRESH_DISTANCE_SQUARED = 16.0;

	private SurfaceTarget lastGround;
	private SurfaceTarget lastSurface;
	private SurfaceTarget nearestSurface;
	private BlockPos lastNearestSurfaceScanOrigin;
	private long lastNearestSurfaceScanTick = Long.MIN_VALUE;

	public void clear() {
		lastGround = null;
		lastSurface = null;
		nearestSurface = null;
		lastNearestSurfaceScanOrigin = null;
		lastNearestSurfaceScanTick = Long.MIN_VALUE;
	}

	public void tick(MinecraftClient client, long tick) {
		ClientPlayerEntity player = client == null ? null : client.player;
		if (client == null || client.world == null || player == null) {
			clear();
			return;
		}
		BlockPos playerPos = player.getBlockPos();
		if (player.isOnGround() && isSafeStandingPosition(client, playerPos)) {
			lastGround = new SurfaceTarget(toGoalPosition(playerPos), "last_ground", tick);
			if (isSkyVisible(client, playerPos)) {
				lastSurface = new SurfaceTarget(toGoalPosition(playerPos), "last_surface", tick);
			}
		}
		if (shouldRefreshNearestSurface(nearestSurface != null, lastNearestSurfaceScanOrigin, lastNearestSurfaceScanTick, playerPos, tick)) {
			lastNearestSurfaceScanOrigin = playerPos;
			lastNearestSurfaceScanTick = tick;
			SurfaceTarget nearby = findNearestSurface(client, playerPos, tick).orElse(null);
			if (nearby != null) {
				nearestSurface = nearby;
			}
		}
	}

	public Optional<SurfaceTarget> bestTarget() {
		if (nearestSurface != null) {
			return Optional.of(nearestSurface);
		}
		if (lastSurface != null) {
			return Optional.of(lastSurface);
		}
		return Optional.ofNullable(lastGround);
	}

	public Optional<SurfaceTarget> lastGround() {
		return Optional.ofNullable(lastGround);
	}

	public Optional<SurfaceTarget> lastSurface() {
		return Optional.ofNullable(lastSurface);
	}

	public Optional<SurfaceTarget> nearestSurface() {
		return Optional.ofNullable(nearestSurface);
	}

	public static boolean isSkyVisible(MinecraftClient client, BlockPos pos) {
		return client != null
			&& client.world != null
			&& pos != null
			&& client.world.isChunkLoaded(pos)
			&& client.world.isSkyVisible(pos.up());
	}

	private static Optional<SurfaceTarget> findNearestSurface(MinecraftClient client, BlockPos origin, long tick) {
		if (client == null || client.world == null || origin == null) {
			return Optional.empty();
		}
		SurfaceTarget best = null;
		double bestDistance = Double.MAX_VALUE;
		int minY = Math.max(client.world.getBottomY() + 1, origin.getY() - NEAREST_SURFACE_DOWN);
		int maxY = Math.min(client.world.getTopYInclusive() - 2, origin.getY() + NEAREST_SURFACE_UP);
		for (int dx = -NEAREST_SURFACE_RADIUS; dx <= NEAREST_SURFACE_RADIUS; dx++) {
			for (int dz = -NEAREST_SURFACE_RADIUS; dz <= NEAREST_SURFACE_RADIUS; dz++) {
				BlockPos column = origin.add(dx, 0, dz);
				if (!client.world.isChunkLoaded(column)) {
					continue;
				}
				for (int y = maxY; y >= minY; y--) {
					BlockPos candidate = new BlockPos(column.getX(), y, column.getZ());
					if (!isSkyVisible(client, candidate) || !isSafeStandingPosition(client, candidate)) {
						continue;
					}
					double distance = candidate.getSquaredDistance(origin);
					if (distance < bestDistance) {
						bestDistance = distance;
						best = new SurfaceTarget(toGoalPosition(candidate), "nearest_surface", tick);
					}
					break;
				}
			}
		}
		return Optional.ofNullable(best);
	}

	static boolean shouldRefreshNearestSurface(
		boolean hasNearestSurface,
		BlockPos lastScanOrigin,
		long lastScanTick,
		BlockPos currentOrigin,
		long tick
	) {
		if (!hasNearestSurface || lastScanOrigin == null || currentOrigin == null || lastScanTick == Long.MIN_VALUE) {
			return true;
		}
		long elapsed = tick - lastScanTick;
		if (elapsed < NEAREST_SURFACE_FAST_REFRESH_TICKS) {
			return false;
		}
		if (lastScanOrigin.getSquaredDistance(currentOrigin) >= NEAREST_SURFACE_MOVE_REFRESH_DISTANCE_SQUARED) {
			return true;
		}
		return elapsed >= NEAREST_SURFACE_REFRESH_TICKS;
	}

	private static boolean isSafeStandingPosition(MinecraftClient client, BlockPos feetPos) {
		if (client == null || client.world == null || feetPos == null) {
			return false;
		}
		if (!client.world.isChunkLoaded(feetPos) || !client.world.isChunkLoaded(feetPos.down())) {
			return false;
		}
		BlockState feet = client.world.getBlockState(feetPos);
		BlockState head = client.world.getBlockState(feetPos.up());
		BlockState support = client.world.getBlockState(feetPos.down());
		return (feet.isAir() || feet.isReplaceable())
			&& (head.isAir() || head.isReplaceable())
			&& support.isSideSolidFullSquare(client.world, feetPos.down(), Direction.UP);
	}

	private static GoalPosition toGoalPosition(BlockPos pos) {
		return new GoalPosition(pos.getX(), pos.getY(), pos.getZ(), false);
	}

	public record SurfaceTarget(GoalPosition position, String kind, long tick) {
	}
}
