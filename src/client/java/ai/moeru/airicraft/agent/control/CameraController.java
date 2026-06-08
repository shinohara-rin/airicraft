package ai.moeru.airicraft.agent.control;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;
import java.util.Optional;

public final class CameraController {
	private int defaultLerpTicks;
	private CameraMotion activeMotion;

	public CameraController() {
		this(0);
	}

	public CameraController(int defaultLerpTicks) {
		updateDefaultLerpTicks(defaultLerpTicks);
	}

	public void updateDefaultLerpTicks(int defaultLerpTicks) {
		this.defaultLerpTicks = Math.max(0, defaultLerpTicks);
	}

	public Optional<Rotation> lookAtNow(MinecraftClient client, Vec3d target) {
		ClientPlayerEntity player = client == null ? null : client.player;
		if (player == null) {
			return Optional.empty();
		}
		Optional<Rotation> rotation = lookRotation(player.getEyePos(), target);
		rotation.ifPresent(value -> applyRotation(player, value));
		activeMotion = null;
		return rotation;
	}

	public Optional<Rotation> lookAtStep(MinecraftClient client, Vec3d target, float maxYawStep, float maxPitchStep) {
		ClientPlayerEntity player = client == null ? null : client.player;
		if (player == null) {
			return Optional.empty();
		}
		Optional<Rotation> targetRotation = lookRotation(player.getEyePos(), target);
		if (targetRotation.isEmpty()) {
			return Optional.empty();
		}
		Rotation rotation = new Rotation(
			rotateToward(player.getYaw(), targetRotation.get().yaw(), maxYawStep),
			rotateToward(player.getPitch(), targetRotation.get().pitch(), maxPitchStep)
		);
		applyRotation(player, rotation);
		activeMotion = null;
		return Optional.of(rotation);
	}

	public Optional<Rotation> faceDirectionNow(ClientPlayerEntity player, String direction) {
		if (player == null) {
			return Optional.empty();
		}
		Optional<Rotation> rotation = directionRotation(direction);
		rotation.ifPresent(value -> applyRotation(player, value));
		activeMotion = null;
		return rotation;
	}

	public Optional<Rotation> startLookAt(MinecraftClient client, Vec3d target, String reason) {
		return startLookAt(client, target, defaultLerpTicks, reason);
	}

	public Optional<Rotation> startLookAt(MinecraftClient client, Vec3d target, int durationTicks, String reason) {
		ClientPlayerEntity player = client == null ? null : client.player;
		if (player == null) {
			return Optional.empty();
		}
		Optional<Rotation> targetRotation = lookRotation(player.getEyePos(), target);
		if (targetRotation.isEmpty()) {
			return Optional.empty();
		}
		int effectiveDuration = Math.max(0, durationTicks);
		if (effectiveDuration == 0) {
			applyRotation(player, targetRotation.get());
			activeMotion = null;
			return targetRotation;
		}
		activeMotion = new CameraMotion(
			new Rotation(player.getYaw(), player.getPitch()),
			targetRotation.get(),
			effectiveDuration,
			0,
			normalizeReason(reason)
		);
		return targetRotation;
	}

	public void tick(MinecraftClient client) {
		if (activeMotion == null) {
			return;
		}
		ClientPlayerEntity player = client == null ? null : client.player;
		if (player == null) {
			clear();
			return;
		}
		tickMotion().ifPresent(rotation -> applyRotation(player, rotation));
	}

	public void clear() {
		activeMotion = null;
	}

	public Optional<String> activeReason() {
		return activeMotion == null ? Optional.empty() : Optional.of(activeMotion.reason());
	}

	void startMotion(Rotation start, Rotation target, int durationTicks, String reason) {
		activeMotion = new CameraMotion(
			Objects.requireNonNull(start, "start"),
			Objects.requireNonNull(target, "target"),
			Math.max(1, durationTicks),
			0,
			normalizeReason(reason)
		);
	}

	Optional<Rotation> tickMotion() {
		if (activeMotion == null) {
			return Optional.empty();
		}
		int nextElapsed = activeMotion.elapsedTicks() + 1;
		float progress = Math.min(1.0F, nextElapsed / (float) activeMotion.durationTicks());
		Rotation rotation = interpolate(activeMotion.start(), activeMotion.target(), progress);
		if (nextElapsed >= activeMotion.durationTicks()) {
			activeMotion = null;
			return Optional.of(rotation);
		}
		activeMotion = new CameraMotion(
			activeMotion.start(),
			activeMotion.target(),
			activeMotion.durationTicks(),
			nextElapsed,
			activeMotion.reason()
		);
		return Optional.of(rotation);
	}

	public static Optional<Rotation> lookRotation(Vec3d eyePos, Vec3d target) {
		if (eyePos == null || target == null) {
			return Optional.empty();
		}
		Vec3d delta = target.subtract(eyePos);
		double horizontalDistance = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
		if (horizontalDistance < 1.0E-7D && Math.abs(delta.y) < 1.0E-7D) {
			return Optional.empty();
		}
		float yaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0F;
		float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontalDistance));
		return Optional.of(new Rotation(yaw, MathHelper.clamp(pitch, -90.0F, 90.0F)));
	}

	public static Optional<Rotation> directionRotation(String direction) {
		return switch (direction == null ? "" : direction) {
			case "north" -> Optional.of(new Rotation(180.0F, 0.0F));
			case "northeast" -> Optional.of(new Rotation(-135.0F, 0.0F));
			case "east" -> Optional.of(new Rotation(-90.0F, 0.0F));
			case "southeast" -> Optional.of(new Rotation(-45.0F, 0.0F));
			case "south" -> Optional.of(new Rotation(0.0F, 0.0F));
			case "southwest" -> Optional.of(new Rotation(45.0F, 0.0F));
			case "west" -> Optional.of(new Rotation(90.0F, 0.0F));
			case "northwest" -> Optional.of(new Rotation(135.0F, 0.0F));
			default -> Optional.empty();
		};
	}

	static Rotation interpolate(Rotation start, Rotation target, float progress) {
		Objects.requireNonNull(start, "start");
		Objects.requireNonNull(target, "target");
		float clampedProgress = MathHelper.clamp(progress, 0.0F, 1.0F);
		if (clampedProgress >= 1.0F) {
			return target;
		}
		float yawDelta = MathHelper.wrapDegrees(target.yaw() - start.yaw());
		float pitchDelta = target.pitch() - start.pitch();
		return new Rotation(
			start.yaw() + yawDelta * clampedProgress,
			MathHelper.clamp(start.pitch() + pitchDelta * clampedProgress, -90.0F, 90.0F)
		);
	}

	static float rotateToward(float current, float target, float maxStep) {
		float delta = MathHelper.wrapDegrees(target - current);
		float step = MathHelper.clamp(delta, -maxStep, maxStep);
		return current + step;
	}

	static void applyRotation(MutableRotation target, Rotation rotation) {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(rotation, "rotation");
		float pitch = MathHelper.clamp(rotation.pitch(), -90.0F, 90.0F);
		target.setAngles(rotation.yaw(), pitch);
		target.setYaw(rotation.yaw());
		target.setPitch(pitch);
		target.setHeadYaw(rotation.yaw());
		target.setBodyYaw(rotation.yaw());
		target.setLastYaw(rotation.yaw());
		target.setLastPitch(pitch);
		target.setRenderYaw(rotation.yaw());
		target.setLastRenderYaw(rotation.yaw());
		target.setRenderPitch(pitch);
		target.setLastRenderPitch(pitch);
	}

	private static void applyRotation(ClientPlayerEntity player, Rotation rotation) {
		applyRotation(new PlayerRotationTarget(player), rotation);
	}

	private static String normalizeReason(String reason) {
		return reason == null || reason.isBlank() ? "unspecified" : reason.trim();
	}

	public record Rotation(float yaw, float pitch) {
	}

	private record CameraMotion(
		Rotation start,
		Rotation target,
		int durationTicks,
		int elapsedTicks,
		String reason
	) {
	}

	interface MutableRotation {
		void setAngles(float yaw, float pitch);
		void setYaw(float yaw);
		void setPitch(float pitch);
		void setHeadYaw(float yaw);
		void setBodyYaw(float yaw);
		void setLastYaw(float yaw);
		void setLastPitch(float pitch);
		void setRenderYaw(float yaw);
		void setLastRenderYaw(float yaw);
		void setRenderPitch(float pitch);
		void setLastRenderPitch(float pitch);
	}

	private static final class PlayerRotationTarget implements MutableRotation {
		private final ClientPlayerEntity player;

		private PlayerRotationTarget(ClientPlayerEntity player) {
			this.player = Objects.requireNonNull(player, "player");
		}

		@Override
		public void setAngles(float yaw, float pitch) {
			player.setAngles(yaw, pitch);
		}

		@Override
		public void setYaw(float yaw) {
			player.setYaw(yaw);
		}

		@Override
		public void setPitch(float pitch) {
			player.setPitch(pitch);
		}

		@Override
		public void setHeadYaw(float yaw) {
			player.setHeadYaw(yaw);
		}

		@Override
		public void setBodyYaw(float yaw) {
			player.setBodyYaw(yaw);
		}

		@Override
		public void setLastYaw(float yaw) {
			player.lastYaw = yaw;
		}

		@Override
		public void setLastPitch(float pitch) {
			player.lastPitch = pitch;
		}

		@Override
		public void setRenderYaw(float yaw) {
			player.renderYaw = yaw;
		}

		@Override
		public void setLastRenderYaw(float yaw) {
			player.lastRenderYaw = yaw;
		}

		@Override
		public void setRenderPitch(float pitch) {
			player.renderPitch = pitch;
		}

		@Override
		public void setLastRenderPitch(float pitch) {
			player.lastRenderPitch = pitch;
		}
	}
}
