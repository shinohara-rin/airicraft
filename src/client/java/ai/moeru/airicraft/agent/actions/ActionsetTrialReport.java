package ai.moeru.airicraft.agent.actions;

import java.util.LinkedHashMap;
import java.util.Map;

public record ActionsetTrialReport(
	String draftId,
	String contentHash,
	boolean passed,
	String failureCode,
	String message,
	Map<String, Object> snapshot
) {
	public ActionsetTrialReport {
		draftId = draftId == null ? "" : draftId;
		contentHash = contentHash == null ? "" : contentHash;
		failureCode = failureCode == null ? "" : failureCode;
		message = message == null ? "" : message;
		snapshot = snapshot == null ? Map.of() : Map.copyOf(snapshot);
	}

	public static ActionsetTrialReport passed(String draftId, String contentHash, Map<String, Object> snapshot) {
		return new ActionsetTrialReport(draftId, contentHash, true, "", "trial_passed", snapshot);
	}

	public static ActionsetTrialReport failed(String draftId, String contentHash, String failureCode, String message, Map<String, Object> snapshot) {
		return new ActionsetTrialReport(draftId, contentHash, false, failureCode, message, snapshot);
	}

	public Map<String, Object> toPayload() {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("draftId", draftId);
		payload.put("contentHash", contentHash);
		payload.put("passed", passed);
		payload.put("failureCode", failureCode);
		payload.put("message", message);
		payload.put("snapshot", snapshot);
		return payload;
	}
}
