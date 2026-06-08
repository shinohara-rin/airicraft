package ai.moeru.airicraft;

public record AiricraftConfig(
	int socialChatMaxDistanceBlocks,
	boolean readSystemChatMessages,
	boolean enableProactiveSocialMode,
	boolean suppressAutoPauseOnFocusLost,
	int blockInteractionDelayTicks
) {
	public static final int DEFAULT_BLOCK_INTERACTION_DELAY_TICKS = 2;

	public static AiricraftConfig defaults() {
		return new AiricraftConfig(-1, true, false, true, DEFAULT_BLOCK_INTERACTION_DELAY_TICKS);
	}

	public AiricraftConfig {
		blockInteractionDelayTicks = Math.max(0, blockInteractionDelayTicks);
	}

	public boolean socialChatDistanceUnlimited() {
		return socialChatMaxDistanceBlocks < 0;
	}
}
