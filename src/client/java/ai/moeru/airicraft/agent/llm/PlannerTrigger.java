package ai.moeru.airicraft.agent.llm;

import java.util.Objects;

public record PlannerTrigger(
	long seqNo,
	PlannerTriggerType type,
	String speaker,
	String text,
	long tick,
	long timestampMs
) {
	public PlannerTrigger {
		type = Objects.requireNonNullElse(type, PlannerTriggerType.CHAT);
		speaker = speaker == null || speaker.isBlank() ? defaultSpeaker(type) : speaker;
		text = text == null ? "" : text;
	}

	public static PlannerTrigger pending(PlannerTriggerType type, String speaker, String text, long tick, long timestampMs) {
		return new PlannerTrigger(0L, type, speaker, text, tick, timestampMs);
	}

	public PlannerTrigger withSeqNo(long replacementSeqNo) {
		return new PlannerTrigger(replacementSeqNo, type, speaker, text, tick, timestampMs);
	}

	private static String defaultSpeaker(PlannerTriggerType type) {
		return switch (type) {
			case CHAT -> "player";
			case CRAFT -> "self";
			case DAMAGE -> "self";
			case PICKUP -> "self";
			case SYSTEM -> "server";
			case IDLE_THINK -> "self";
		};
	}
}
