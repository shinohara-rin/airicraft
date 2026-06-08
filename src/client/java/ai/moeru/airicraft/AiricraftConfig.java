package ai.moeru.airicraft;

public record AiricraftConfig(
	int socialChatMaxDistanceBlocks,
	boolean readSystemChatMessages,
	boolean enableProactiveSocialMode,
	boolean suppressAutoPauseOnFocusLost,
	int blockInteractionDelayTicks,
	int cameraLerpDefaultTicks
) {
	public static final int DEFAULT_BLOCK_INTERACTION_DELAY_TICKS = 2;
	public static final int DEFAULT_CAMERA_LERP_DEFAULT_TICKS = 0;

	public static AiricraftConfig defaults() {
		return new AiricraftConfig(-1, true, false, true, DEFAULT_BLOCK_INTERACTION_DELAY_TICKS, DEFAULT_CAMERA_LERP_DEFAULT_TICKS);
	}

	public AiricraftConfig {
		blockInteractionDelayTicks = Math.max(0, blockInteractionDelayTicks);
		cameraLerpDefaultTicks = Math.max(0, cameraLerpDefaultTicks);
	}

	public boolean socialChatDistanceUnlimited() {
		return socialChatMaxDistanceBlocks < 0;
	}
}
