package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.agent.job.ActiveJobProposal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ActionGraphPrimitiveDispatch(
	boolean dispatchable,
	String failureCode,
	String message,
	ActionPlanStep selectedStep,
	ActiveJobProposal proposal,
	Map<String, Object> payload
) {
	public ActionGraphPrimitiveDispatch {
		failureCode = failureCode == null ? "" : failureCode;
		message = message == null ? "" : message;
		payload = payload == null || payload.isEmpty()
			? Map.of()
			: Collections.unmodifiableMap(new LinkedHashMap<>(payload));
	}

	public static ActionGraphPrimitiveDispatch dispatchable(
		ActionPlanStep selectedStep,
		ActiveJobProposal proposal,
		Map<String, Object> payload
	) {
		return new ActionGraphPrimitiveDispatch(true, "", "", selectedStep, proposal, payload);
	}

	public static ActionGraphPrimitiveDispatch failed(String failureCode, String message, ActionPlanStep selectedStep) {
		return new ActionGraphPrimitiveDispatch(false, failureCode, message, selectedStep, null, Map.of());
	}
}
