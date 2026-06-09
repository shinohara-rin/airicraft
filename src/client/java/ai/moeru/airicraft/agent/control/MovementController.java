package ai.moeru.airicraft.agent.control;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;

public final class MovementController {
	private static final long STUCK_TICKS = 20L;
	private static final double STUCK_DISTANCE_EPSILON = 0.15D;

	private boolean movingForward;
	private boolean sprinting;
	private boolean jumping;
	private boolean stuck;
	private long movingSinceTick = -1L;
	private Vec3d movementStartPos;
	private Boolean previousAutoJumpValue;

	public void moveForward(MinecraftClient client, boolean sprint, boolean jump, long tick) {
		if (client == null) {
			return;
		}

		ClientPlayerEntity player = client.player;
		if (player == null) {
			stop(client);
			return;
		}

		if (!movingForward || movingSinceTick < 0L) {
			movingSinceTick = tick;
			movementStartPos = new Vec3d(player.getX(), player.getY(), player.getZ());
			stuck = false;
		}

		movingForward = true;
		sprinting = sprint;
		boolean effectiveJump = shouldJump(player, jump);
		jumping = effectiveJump;
		enableAutoJump(client);

		client.options.forwardKey.setPressed(true);
		client.options.backKey.setPressed(false);
		client.options.leftKey.setPressed(false);
		client.options.rightKey.setPressed(false);
		client.options.sprintKey.setPressed(sprint);
		client.options.jumpKey.setPressed(effectiveJump);
		player.setSprinting(sprint);

		updateStuckState(player, tick);
	}

	public void swimUp(MinecraftClient client, boolean forward, boolean sprint, long tick) {
		if (client == null) {
			return;
		}

		ClientPlayerEntity player = client.player;
		if (player == null) {
			stop(client);
			return;
		}

		if (movingSinceTick < 0L) {
			movingSinceTick = tick;
			movementStartPos = new Vec3d(player.getX(), player.getY(), player.getZ());
			stuck = false;
		}

		movingForward = forward;
		sprinting = forward && sprint;
		jumping = true;
		enableAutoJump(client);

		client.options.forwardKey.setPressed(forward);
		client.options.backKey.setPressed(false);
		client.options.leftKey.setPressed(false);
		client.options.rightKey.setPressed(false);
		client.options.sprintKey.setPressed(forward && sprint);
		client.options.jumpKey.setPressed(true);
		player.setSprinting(forward && sprint);

		updateStuckState(player, tick);
	}

	public void stop(MinecraftClient client) {
		if (!isControllingMovement()) {
			return;
		}

		movingForward = false;
		sprinting = false;
		jumping = false;
		stuck = false;
		movingSinceTick = -1L;
		movementStartPos = null;

		if (client == null) {
			return;
		}

		client.options.forwardKey.setPressed(false);
		client.options.backKey.setPressed(false);
		client.options.leftKey.setPressed(false);
		client.options.rightKey.setPressed(false);
		client.options.jumpKey.setPressed(false);
		client.options.sprintKey.setPressed(false);
		restoreAutoJump(client);
		if (client.player != null) {
			client.player.setSprinting(false);
		}
	}

	public MovementStateSnapshot snapshot() {
		return new MovementStateSnapshot(movingForward, sprinting, jumping, stuck, movingSinceTick);
	}

	private void updateStuckState(ClientPlayerEntity player, long tick) {
		if (movementStartPos == null || movingSinceTick < 0L) {
			stuck = false;
			return;
		}
		if (tick - movingSinceTick < STUCK_TICKS) {
			stuck = false;
			return;
		}

		Vec3d currentPos = new Vec3d(player.getX(), player.getY(), player.getZ());
		double movedDistance = currentPos.distanceTo(movementStartPos);
		stuck = movedDistance < STUCK_DISTANCE_EPSILON;
		if (!stuck) {
			movingSinceTick = tick;
			movementStartPos = currentPos;
		}
	}

	private static boolean shouldJump(ClientPlayerEntity player, boolean requestedJump) {
		if (requestedJump) {
			return true;
		}

		if (player.isTouchingWater() || player.isSubmergedInWater()) {
			return true;
		}

		return player.horizontalCollision && player.isOnGround();
	}

	private boolean isControllingMovement() {
		return movingForward
			|| sprinting
			|| jumping
			|| movingSinceTick >= 0L
			|| movementStartPos != null
			|| previousAutoJumpValue != null;
	}

	private void enableAutoJump(MinecraftClient client) {
		if (client == null || client.options == null) {
			return;
		}
		if (previousAutoJumpValue == null) {
			previousAutoJumpValue = client.options.getAutoJump().getValue();
		}
		client.options.getAutoJump().setValue(true);
	}

	private void restoreAutoJump(MinecraftClient client) {
		if (client == null || client.options == null || previousAutoJumpValue == null) {
			return;
		}
		client.options.getAutoJump().setValue(previousAutoJumpValue);
		previousAutoJumpValue = null;
	}
}
