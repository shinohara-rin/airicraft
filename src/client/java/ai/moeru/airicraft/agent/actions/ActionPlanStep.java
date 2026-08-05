package ai.moeru.airicraft.agent.actions;

import ai.moeru.actionplan.MethodKey;
import ai.moeru.actionplan.ProviderId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ActionPlanStep(
	ActionStepKind kind,
	String actionId,
	String alternativeId,
	String stepId,
	String targetId,
	Map<String, Object> args,
	ActionWatchSpec watchSpec,
	MethodKey methodKey
) {
	public ActionPlanStep(
		ActionStepKind kind,
		String actionId,
		String alternativeId,
		String stepId,
		String targetId,
		Map<String, Object> args
	) {
		this(kind, actionId, alternativeId, stepId, targetId, args, null, methodKey(actionId, alternativeId));
	}

	public ActionPlanStep(
		ActionStepKind kind,
		String actionId,
		String alternativeId,
		String stepId,
		String targetId,
		Map<String, Object> args,
		ActionWatchSpec watchSpec
	) {
		this(kind, actionId, alternativeId, stepId, targetId, args, watchSpec, methodKey(actionId, alternativeId));
	}

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
		methodKey = methodKey == null ? methodKey(actionId, alternativeId) : methodKey;
	}

	private static MethodKey methodKey(String providerId, String methodId) {
		String safeProviderId = providerId == null || providerId.isBlank() ? "unknown" : providerId;
		String safeMethodId = methodId == null || methodId.isBlank() ? "unknown" : methodId;
		return new MethodKey(new ProviderId(safeProviderId), safeMethodId);
	}
}
