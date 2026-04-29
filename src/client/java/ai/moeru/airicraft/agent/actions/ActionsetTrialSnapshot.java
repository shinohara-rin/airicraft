package ai.moeru.airicraft.agent.actions;

import java.util.LinkedHashMap;
import java.util.Map;

public record ActionsetTrialSnapshot(
	boolean available,
	ActionsetTrialState state,
	String draftId,
	String contentHash,
	boolean passed,
	String failureCode,
	String message,
	ActionGraphExecutionSnapshot execution
) {
	public ActionsetTrialSnapshot {
		state = state == null ? ActionsetTrialState.IDLE : state;
		draftId = draftId == null ? "" : draftId;
		contentHash = contentHash == null ? "" : contentHash;
		failureCode = failureCode == null ? "" : failureCode;
		message = message == null ? "" : message;
	}

	public static ActionsetTrialSnapshot idle() {
		return new ActionsetTrialSnapshot(true, ActionsetTrialState.IDLE, "", "", false, "", "", ActionGraphExecutionSnapshot.idle());
	}

	public Map<String, Object> toPayload(boolean verbose) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("available", available);
		payload.put("state", state.name());
		payload.put("draftId", draftId);
		payload.put("contentHash", contentHash);
		payload.put("passed", passed);
		payload.put("failureCode", failureCode);
		payload.put("message", message);
		if (execution != null) {
			payload.put("execution", execution.toPayload(verbose));
		}
		return payload;
	}
}
