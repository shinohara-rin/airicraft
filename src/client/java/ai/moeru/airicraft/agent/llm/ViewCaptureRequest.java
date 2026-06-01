package ai.moeru.airicraft.agent.llm;

import java.util.Locale;
import java.util.Objects;

public record ViewCaptureRequest(
	TargetType targetType,
	String direction,
	Integer x,
	Integer y,
	Integer z,
	String targetPlayer
) {
	public ViewCaptureRequest {
		targetType = targetType == null ? TargetType.CURRENT : targetType;
		direction = direction == null || direction.isBlank() ? null : direction.trim().toLowerCase(Locale.ROOT);
		targetPlayer = targetPlayer == null || targetPlayer.isBlank() ? null : targetPlayer.trim();
	}

	public static ViewCaptureRequest current() {
		return new ViewCaptureRequest(TargetType.CURRENT, null, null, null, null, null);
	}

	public static ViewCaptureRequest direction(String direction) {
		return new ViewCaptureRequest(TargetType.DIRECTION, Objects.requireNonNull(direction, "direction"), null, null, null, null);
	}

	public static ViewCaptureRequest block(int x, int y, int z) {
		return new ViewCaptureRequest(TargetType.BLOCK, null, x, y, z, null);
	}

	public static ViewCaptureRequest player(String targetPlayer) {
		return new ViewCaptureRequest(TargetType.PLAYER, null, null, null, null, Objects.requireNonNull(targetPlayer, "targetPlayer"));
	}

	public boolean isCurrent() {
		return targetType == TargetType.CURRENT;
	}

	public enum TargetType {
		CURRENT,
		DIRECTION,
		BLOCK,
		PLAYER
	}
}
