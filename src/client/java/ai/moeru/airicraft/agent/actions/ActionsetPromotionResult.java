package ai.moeru.airicraft.agent.actions;

import java.util.LinkedHashMap;
import java.util.Map;

public record ActionsetPromotionResult(
	String draftId,
	String enabledId,
	ActionsetDraftStatus status,
	String contentHash
) {
	public ActionsetPromotionResult {
		draftId = draftId == null ? "" : draftId;
		enabledId = enabledId == null ? "" : enabledId;
		status = status == null ? ActionsetDraftStatus.ENABLED : status;
		contentHash = contentHash == null ? "" : contentHash;
	}

	public Map<String, Object> toPayload() {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("available", true);
		payload.put("draftId", draftId);
		payload.put("enabledId", enabledId);
		payload.put("status", status.name());
		payload.put("contentHash", contentHash);
		payload.put("promoted", true);
		return payload;
	}
}
