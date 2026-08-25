package ai.moeru.airicraft.agent.lighting;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.item.Items;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class LightingRuntime {
	private static final long ATTEMPT_INTERVAL_TICKS = 10L;
	private static final long CONFIRMATION_TIMEOUT_TICKS = 20L;
	private static final double MAX_REACH_SQUARED = 4.5D * 4.5D;
	private static final int OFFHAND_SWAP_BUTTON = 40;

	private LightingPolicy policy = LightingPolicy.disabled();
	private PendingPlacement pendingPlacement;
	private long nextAttemptTick;

	public LightingPolicy configure(
		boolean enabled,
		LightingPolicy.Mode mode,
		int maxLightLevel,
		boolean requireUnderground,
		int minSpacingBlocks
	) {
		policy = new LightingPolicy(
			enabled,
			mode,
			maxLightLevel,
			requireUnderground,
			minSpacingBlocks,
			policy.revision() + 1L
		);
		pendingPlacement = null;
		return policy;
	}

	public Optional<PlacementEvent> tick(MinecraftClient client, boolean miningActive, long tick) {
		if (client == null || client.world == null || client.player == null || client.interactionManager == null) {
			pendingPlacement = null;
			return Optional.empty();
		}
		Optional<PlacementEvent> confirmation = confirmPending(client, tick);
		if (confirmation.isPresent() || pendingPlacement != null || tick < nextAttemptTick) {
			return confirmation;
		}
		nextAttemptTick = tick + ATTEMPT_INTERVAL_TICKS;

		ClientPlayerEntity player = client.player;
		BlockPos origin = player.getBlockPos();
		boolean nearbyTorch = hasNearbyTorch(client, origin, policy.minSpacingBlocks());
		boolean placementRequired = LightingPolicyEvaluator.shouldPlace(
			policy,
			miningActive,
			true,
			client.world.isSkyVisible(origin.up()),
			client.world.getLightLevel(origin),
			client.world.getLightLevel(LightType.BLOCK, origin),
			nearbyTorch
		);
		if (!placementRequired) {
			return Optional.empty();
		}
		if (!player.getOffHandStack().isOf(Items.TORCH)) {
			if (player.getOffHandStack().isEmpty()) {
				moveTorchToOffhand(client, player);
			}
			return Optional.empty();
		}

		for (BlockPos target : placementCandidates(player)) {
			if (tryPlace(client, player, target, tick)) {
				break;
			}
		}
		return Optional.empty();
	}

	public void reset() {
		policy = LightingPolicy.disabled();
		pendingPlacement = null;
		nextAttemptTick = 0L;
	}

	public LightingPolicy policy() {
		return policy;
	}

	private Optional<PlacementEvent> confirmPending(MinecraftClient client, long tick) {
		if (pendingPlacement == null) {
			return Optional.empty();
		}
		BlockState state = client.world.getBlockState(pendingPlacement.target());
		if (state.isOf(Blocks.TORCH) || state.isOf(Blocks.WALL_TORCH)) {
			PendingPlacement confirmed = pendingPlacement;
			pendingPlacement = null;
			return Optional.of(new PlacementEvent(Map.of(
				"policyRevision", confirmed.policyRevision(),
				"mode", confirmed.mode().wireName(),
				"x", confirmed.target().getX(),
				"y", confirmed.target().getY(),
				"z", confirmed.target().getZ(),
				"lightLevelBefore", confirmed.lightLevelBefore(),
				"offhandCount", client.player.getOffHandStack().getCount(),
				"miningActive", true
			)));
		}
		if (tick - pendingPlacement.startedTick() > CONFIRMATION_TIMEOUT_TICKS) {
			pendingPlacement = null;
		}
		return Optional.empty();
	}

	private boolean tryPlace(MinecraftClient client, ClientPlayerEntity player, BlockPos target, long tick) {
		if (!client.world.isChunkLoaded(target)) {
			return false;
		}
		BlockState targetState = client.world.getBlockState(target);
		BlockState torchState = Blocks.TORCH.getDefaultState();
		if (!(targetState.isAir() || targetState.isReplaceable()) || !torchState.canPlaceAt(client.world, target)) {
			return false;
		}
		BlockPos support = target.down();
		Vec3d hit = Vec3d.ofCenter(support).add(0.0D, 0.5D, 0.0D);
		if (player.getEyePos().squaredDistanceTo(hit) > MAX_REACH_SQUARED) {
			return false;
		}
		ActionResult result = client.interactionManager.interactBlock(
			player,
			Hand.OFF_HAND,
			new BlockHitResult(hit, Direction.UP, support, false)
		);
		if (!result.isAccepted()) {
			return false;
		}
		player.swingHand(Hand.OFF_HAND);
		pendingPlacement = new PendingPlacement(
			target.toImmutable(),
			tick,
			policy.revision(),
			policy.mode(),
			client.world.getLightLevel(target)
		);
		return true;
	}

	private static List<BlockPos> placementCandidates(Entity player) {
		BlockPos origin = player.getBlockPos();
		Direction forward = player.getHorizontalFacing();
		return List.of(
			origin.offset(forward.getOpposite()),
			origin.offset(forward.rotateYCounterclockwise()),
			origin.offset(forward.rotateYClockwise()),
			origin.offset(forward)
		);
	}

	private static boolean hasNearbyTorch(MinecraftClient client, BlockPos origin, int radius) {
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		for (int x = -radius; x <= radius; x++) {
			for (int y = -2; y <= 2; y++) {
				for (int z = -radius; z <= radius; z++) {
					cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
					if (!client.world.isChunkLoaded(cursor)) {
						continue;
					}
					BlockState state = client.world.getBlockState(cursor);
					if (state.isOf(Blocks.TORCH) || state.isOf(Blocks.WALL_TORCH)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static void moveTorchToOffhand(MinecraftClient client, ClientPlayerEntity player) {
		if (player.currentScreenHandler != player.playerScreenHandler) {
			return;
		}
		ScreenHandler handler = player.currentScreenHandler;
		for (int slot = PlayerScreenHandler.INVENTORY_START; slot < PlayerScreenHandler.HOTBAR_END; slot++) {
			if (!handler.getSlot(slot).getStack().isOf(Items.TORCH)) {
				continue;
			}
			client.interactionManager.clickSlot(handler.syncId, slot, OFFHAND_SWAP_BUTTON, SlotActionType.SWAP, player);
			return;
		}
	}

	public record PlacementEvent(Map<String, Object> payload) {
	}

	private record PendingPlacement(
		BlockPos target,
		long startedTick,
		long policyRevision,
		LightingPolicy.Mode mode,
		int lightLevelBefore
	) {
	}
}
