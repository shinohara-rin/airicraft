package ai.moeru.airicraft.agent.actions;

import java.util.List;
import java.util.Map;

public record PrimitiveActionMetadata(
	String id,
	int version,
	String summary,
	Map<String, PrimitiveParameter> parameterSchema,
	List<String> guardFactTypes,
	List<String> needFactTypes,
	List<String> producedFactTypes,
	List<String> consumedFactTypes,
	int cost,
	List<String> failureCodes,
	boolean foregroundActuation,
	boolean cancellable,
	int defaultTimeoutTicks,
	List<String> capabilityTags,
	String executorBinding
) {
	public PrimitiveActionMetadata {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("id is required");
		}
		if (version < 1) {
			throw new IllegalArgumentException("version must be positive");
		}
		summary = summary == null ? "" : summary;
		parameterSchema = parameterSchema == null ? Map.of() : Map.copyOf(parameterSchema);
		guardFactTypes = guardFactTypes == null ? List.of() : List.copyOf(guardFactTypes);
		needFactTypes = needFactTypes == null ? List.of() : List.copyOf(needFactTypes);
		producedFactTypes = producedFactTypes == null ? List.of() : List.copyOf(producedFactTypes);
		consumedFactTypes = consumedFactTypes == null ? List.of() : List.copyOf(consumedFactTypes);
		failureCodes = failureCodes == null ? List.of() : List.copyOf(failureCodes);
		capabilityTags = capabilityTags == null ? List.of() : List.copyOf(capabilityTags);
		executorBinding = executorBinding == null ? "" : executorBinding;
	}
}
