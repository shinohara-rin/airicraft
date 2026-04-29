package ai.moeru.airicraft.agent.actions;

import java.util.List;
import java.util.Map;

public record ActionsetDraftWriteResult(
	String draftId,
	ActionsetDraftStatus status,
	String contentHash,
	List<ActionsetLoadDiagnostic> diagnostics
) {
	public ActionsetDraftWriteResult {
		draftId = draftId == null ? "" : draftId;
		status = status == null ? ActionsetDraftStatus.INVALID : status;
		contentHash = contentHash == null ? "" : contentHash;
		diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
	}

	public Map<String, Object> toPayload() {
		return ActionsetAuthoringService.draftPayload(draftId, status, contentHash, diagnostics);
	}
}
