package ai.moeru.airicraft.agent.actions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ActionPlanStep(
	ActionStepKind kind,
	String actionId,
	String alternativeId,
	String stepId,
	String targetId,
	Map<String, Object> args
) {
	public ActionPlanStep {
		if (kind == null) {
			throw new IllegalArgumentException("kind is required");
		}
		actionId = actionId == null ? "" : actionId;
		alternativeId = alternativeId == null ? "" : alternativeId;
		stepId = stepId == null ? "" : stepId;
		targetId = targetId == null ? "" : targetId;
		args = args == null || args.isEmpty()
			? Map.of()
			: Collections.unmodifiableMap(new LinkedHashMap<>(args));
	}
}
