package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class BlockInteractionTaskExecutor implements WorldTaskExecutor {
	private static final double INTERACTION_RANGE_SQUARED = 20.25D;
	private static final List<Direction> DEFAULT_SUPPORT_ORDER = List.of(
		Direction.DOWN,
		Direction.NORTH,
		Direction.SOUTH,
		Direction.WEST,
		Direction.EAST,
		Direction.UP
	);

	private final Supplier<MinecraftClient> clientSupplier;

	private WorldTaskRequest appliedTask;
	private boolean terminalEventEmitted;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public BlockInteractionTaskExecutor() {
		this(MinecraftClient::getInstance);
	}

	BlockInteractionTaskExecutor(Supplier<MinecraftClient> clientSupplier) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || !isBlockInteraction(activeTask.get().type())) {
			reset();
			return Optional.empty();
		}
		WorldTaskRequest request = activeTask.get();
		if (!sameTask(request, appliedTask)) {
			reset();
			appliedTask = request;
		}
		if (!actuationAllowed(sessionSnapshot)) {
			snapshot = snapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, request, "session_gate");
			return Optional.empty();
		}
		MinecraftClient client = clientSupplier.get();
		ClientPlayerEntity player = client == null ? null : client.player;
		if (client == null || client.interactionManager == null || client.world == null || player == null) {
			return fail(request, "world_unavailable");
		}
		if (player.currentScreenHandler != player.playerScreenHandler || !player.currentScreenHandler.getCursorStack().isEmpty()) {
			return fail(request, "interaction_busy");
		}
		return request.type() == WorldTaskType.PLACE_BLOCK
			? placeBlock(client, player, request)
			: useBlock(client, player, request);
	}

	private Optional<TaskTerminalEvent> placeBlock(MinecraftClient client, ClientPlayerEntity player, WorldTaskRequest request) {
		BlockPlacementStepArgs args = request.blockPlacement();
		BlockPos target = blockPos(args.targetPosition());
		if (!client.world.isChunkLoaded(target)) {
			return fail(request, "target_unloaded targetPos=" + compactPos(target));
		}
		BlockState before = client.world.getBlockState(target);
		if (!targetMaterial(args.requiredTargetMaterial(), "air_or_replaceable").matches(before)) {
			return fail(request, "target_material_mismatch targetPos=" + compactPos(target) + " beforeBlockId=" + blockId(before));
		}
		Hand hand = resolveInteractionHand(client, player, args.itemId());
		if (hand == null) {
			return fail(request, "required_item_missing itemId=" + args.itemId());
		}
		Optional<HitTarget> hitTarget = resolvePlacementHit(client, player, target, args.facePreference());
		if (hitTarget.isEmpty()) {
			return fail(request, "support_not_found targetPos=" + compactPos(target));
		}
		return interact(client, player, request, hand, target, before, hitTarget.get());
	}

	private Optional<TaskTerminalEvent> useBlock(MinecraftClient client, ClientPlayerEntity player, WorldTaskRequest request) {
		BlockUseStepArgs args = request.blockUse();
		BlockPos target = blockPos(args.targetPosition());
		if (!client.world.isChunkLoaded(target)) {
			return fail(request, "target_unloaded targetPos=" + compactPos(target));
		}
		BlockState before = client.world.getBlockState(target);
		TargetMaterial expectedTargetMaterial = targetMaterial(args.expectedTargetMaterial(), null);
		if (expectedTargetMaterial != null && !expectedTargetMaterial.matches(before)) {
			return fail(request, "target_material_mismatch targetPos=" + compactPos(target) + " beforeBlockId=" + blockId(before));
		}
		Hand hand = resolveInteractionHand(client, player, args.itemId());
		if (hand == null) {
			return fail(request, "required_item_missing itemId=" + args.itemId());
		}
		Optional<HitTarget> hitTarget = (before.isAir() || before.isReplaceable())
			? resolvePlacementHit(client, player, target, args.facePreference())
			: Optional.of(hitOnBlock(target, before, facePreference(args.facePreference()).orElse(Direction.UP)));
		if (hitTarget.isEmpty()) {
			return fail(request, "support_not_found targetPos=" + compactPos(target));
		}
		if (!args.expectedSupportBlockIds().isEmpty() && !args.expectedSupportBlockIds().contains(blockId(hitTarget.get().supportState()))) {
			return fail(request, "support_block_mismatch supportPos=" + compactPos(hitTarget.get().supportPos()) + " supportBlockId=" + blockId(hitTarget.get().supportState()));
		}
		return interact(client, player, request, hand, target, before, hitTarget.get());
	}

	private Optional<TaskTerminalEvent> interact(
		MinecraftClient client,
		ClientPlayerEntity player,
		WorldTaskRequest request,
		Hand hand,
		BlockPos target,
		BlockState before,
		HitTarget hitTarget
	) {
		if (!withinInteractionRange(player, hitTarget.hitVec())) {
			return fail(request, "target_out_of_range targetPos=" + compactPos(target) + " supportPos=" + compactPos(hitTarget.supportPos()));
		}
		ActionResult result = client.interactionManager.interactBlock(player, hand, hitTarget.hitResult());
		if (!result.isAccepted()) {
			return fail(request, "interaction_failed interactionResult=" + result + " targetPos=" + compactPos(target));
		}
		player.swingHand(hand);
		BlockState after = client.world.isChunkLoaded(target) ? client.world.getBlockState(target) : before;
		String message = "block_interaction_succeeded"
			+ " type=" + request.type().name()
			+ " targetPos=" + compactPos(target)
			+ " itemId=" + itemId(hand == Hand.OFF_HAND ? player.getOffHandStack() : player.getMainHandStack())
			+ " supportPos=" + compactPos(hitTarget.supportPos())
			+ " face=" + hitTarget.face().asString()
			+ " interactionResult=" + result
			+ " beforeBlockId=" + blockId(before)
			+ " afterBlockId=" + blockId(after);
		return complete(request, message);
	}

	private Optional<HitTarget> resolvePlacementHit(MinecraftClient client, ClientPlayerEntity player, BlockPos target, String facePreference) {
		List<Direction> directions = facePreference(facePreference)
			.map(List::of)
			.orElse(DEFAULT_SUPPORT_ORDER);
		for (Direction direction : directions) {
			BlockPos support = target.offset(direction);
			if (!client.world.isChunkLoaded(support)) {
				continue;
			}
			BlockState supportState = client.world.getBlockState(support);
			Direction face = direction.getOpposite();
			if (supportState.isAir() || supportState.isReplaceable()) {
				continue;
			}
			HitTarget hitTarget = hitOnBlock(support, supportState, face);
			if (withinInteractionRange(player, hitTarget.hitVec())) {
				return Optional.of(hitTarget);
			}
		}
		return Optional.empty();
	}

	private static HitTarget hitOnBlock(BlockPos support, BlockState supportState, Direction face) {
		Vec3d center = Vec3d.ofCenter(support);
		Vec3d hitVec = center.add(
			face.getOffsetX() * 0.5D,
			face.getOffsetY() * 0.5D,
			face.getOffsetZ() * 0.5D
		);
		return new HitTarget(support, supportState, face, hitVec, new BlockHitResult(hitVec, face, support, false));
	}

	private static Hand resolveInteractionHand(MinecraftClient client, ClientPlayerEntity player, String itemId) {
		if (itemId == null || itemId.isBlank()) {
			return Hand.MAIN_HAND;
		}
		String offHandItemId = Registries.ITEM.getId(player.getOffHandStack().getItem()).toString();
		if (itemId.equals(offHandItemId) && !player.getOffHandStack().isEmpty()) {
			return Hand.OFF_HAND;
		}
		ScreenHandler handler = player.currentScreenHandler;
		int sourceSlot = findInventorySlot(handler, itemId);
		if (sourceSlot < 0) {
			return null;
		}
		int hotbarIndex = player.getInventory().getSelectedSlot();
		if (sourceSlot >= PlayerScreenHandler.HOTBAR_START && sourceSlot < PlayerScreenHandler.HOTBAR_END) {
			player.getInventory().setSelectedSlot(sourceSlot - PlayerScreenHandler.HOTBAR_START);
			return Hand.MAIN_HAND;
		}
		client.interactionManager.clickSlot(handler.syncId, sourceSlot, hotbarIndex, SlotActionType.SWAP, player);
		player.getInventory().setSelectedSlot(hotbarIndex);
		ItemStack selected = player.getInventory().getSelectedStack();
		if (selected.isEmpty()) {
			return null;
		}
		String selectedItemId = Registries.ITEM.getId(selected.getItem()).toString();
		return itemId.equals(selectedItemId) ? Hand.MAIN_HAND : null;
	}

	private static int findInventorySlot(ScreenHandler handler, String itemId) {
		for (int slot = PlayerScreenHandler.INVENTORY_START; slot < PlayerScreenHandler.HOTBAR_END; slot++) {
			ItemStack stack = handler.getSlot(slot).getStack();
			if (!stack.isEmpty() && itemId.equals(itemId(stack))) {
				return slot;
			}
		}
		return -1;
	}

	private Optional<TaskTerminalEvent> complete(WorldTaskRequest request, String message) {
		snapshot = snapshot(TaskExecutionState.COMPLETED, request, message);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.COMPLETED, message, TaskTerminationCause.GOAL_REACHED));
	}

	private Optional<TaskTerminalEvent> fail(WorldTaskRequest request, String reason) {
		snapshot = snapshot(TaskExecutionState.FAILED, request, reason);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.FAILED, reason, null));
	}

	private static Optional<Direction> facePreference(String value) {
		if (value == null || value.isBlank() || "auto".equals(value)) {
			return Optional.empty();
		}
		return Optional.of(switch (value) {
			case "down" -> Direction.DOWN;
			case "north" -> Direction.NORTH;
			case "south" -> Direction.SOUTH;
			case "east" -> Direction.EAST;
			case "west" -> Direction.WEST;
			case "up" -> Direction.UP;
			default -> Direction.DOWN;
		});
	}

	private static TargetMaterial targetMaterial(String value, String fallback) {
		String normalized = value == null || value.isBlank() ? fallback : value;
		if (normalized == null || normalized.isBlank()) {
			return null;
		}
		return switch (normalized) {
			case "air" -> TargetMaterial.AIR;
			case "replaceable" -> TargetMaterial.REPLACEABLE;
			case "air_or_replaceable" -> TargetMaterial.AIR_OR_REPLACEABLE;
			default -> TargetMaterial.AIR_OR_REPLACEABLE;
		};
	}

	private static boolean withinInteractionRange(ClientPlayerEntity player, Vec3d pos) {
		return player.squaredDistanceTo(pos) <= INTERACTION_RANGE_SQUARED;
	}

	private static BlockPos blockPos(GoalPosition position) {
		return new BlockPos(position.x(), position.y(), position.z());
	}

	private static String blockId(BlockState state) {
		return Registries.BLOCK.getId(state.getBlock()).toString();
	}

	private static String itemId(ItemStack stack) {
		return stack == null || stack.isEmpty() ? "none" : Registries.ITEM.getId(stack.getItem()).toString();
	}

	private static String compactPos(BlockPos pos) {
		return pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	private static boolean sameTask(WorldTaskRequest left, WorldTaskRequest right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null) {
			return false;
		}
		return Objects.equals(left.taskId(), right.taskId())
			&& Objects.equals(left.sourceJobId(), right.sourceJobId())
			&& left.type() == right.type();
	}

	private static boolean isBlockInteraction(WorldTaskType type) {
		return type == WorldTaskType.PLACE_BLOCK || type == WorldTaskType.USE_BLOCK;
	}

	static boolean actuationAllowed(SessionSnapshot sessionSnapshot) {
		return sessionSnapshot != null && sessionSnapshot.companionActuationAllowed();
	}

	private static TaskExecutionSnapshot snapshot(TaskExecutionState state, WorldTaskRequest request, String event) {
		return new TaskExecutionSnapshot(state, request.taskId(), null, "BlockInteraction", event, null, null);
	}

	@Override
	public TaskExecutionSnapshot snapshot() {
		return snapshot;
	}

	@Override
	public void onWorldLeave() {
		reset();
	}

	@Override
	public void shutdown() {
		reset();
	}

	private void reset() {
		appliedTask = null;
		terminalEventEmitted = false;
		snapshot = TaskExecutionSnapshot.idle();
	}

	private enum TargetMaterial {
		AIR,
		REPLACEABLE,
		AIR_OR_REPLACEABLE;

		boolean matches(BlockState state) {
			return switch (this) {
				case AIR -> state.isAir();
				case REPLACEABLE -> !state.isAir() && state.isReplaceable();
				case AIR_OR_REPLACEABLE -> state.isAir() || state.isReplaceable();
			};
		}
	}

	private record HitTarget(
		BlockPos supportPos,
		BlockState supportState,
		Direction face,
		Vec3d hitVec,
		BlockHitResult hitResult
	) {
	}
}
