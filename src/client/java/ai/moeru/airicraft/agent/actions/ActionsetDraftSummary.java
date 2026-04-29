package ai.moeru.airicraft.agent.actions;

import java.util.LinkedHashMap;
import java.util.Map;

public record ActionsetDraftSummary(
	String draftId,
	ActionsetDraftStatus status,
	String contentHash,
	int diagnosticCount
) {
	public ActionsetDraftSummary {
		draftId = draftId == null ? "" : draftId;
		status = status == null ? ActionsetDraftStatus.INVALID : status;
		contentHash = contentHash == null ? "" : contentHash;
		diagnosticCount = Math.max(0, diagnosticCount);
	}

	public Map<String, Object> toPayload() {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("draftId", draftId);
		payload.put("status", status.name());
		payload.put("contentHash", contentHash);
		payload.put("diagnosticCount", diagnosticCount);
		return payload;
	}
}
