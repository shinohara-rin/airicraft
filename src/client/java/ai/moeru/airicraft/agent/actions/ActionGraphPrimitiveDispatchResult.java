package ai.moeru.airicraft.agent.actions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ActionGraphPrimitiveDispatchResult(
	boolean accepted,
	String taskId,
	String failureCode,
	String message,
	Map<String, Object> payload,
	Map<String, Object> task,
	Map<String, Object> taskExecution
) {
	public ActionGraphPrimitiveDispatchResult {
		taskId = taskId == null ? "" : taskId.trim();
		failureCode = failureCode == null ? "" : failureCode.trim();
		message = message == null ? "" : message.trim();
		payload = payload == null || payload.isEmpty()
			? Map.of()
			: Collections.unmodifiableMap(new LinkedHashMap<>(payload));
		task = task == null || task.isEmpty()
			? Map.of()
			: Collections.unmodifiableMap(new LinkedHashMap<>(task));
		taskExecution = taskExecution == null || taskExecution.isEmpty()
			? Map.of()
			: Collections.unmodifiableMap(new LinkedHashMap<>(taskExecution));
	}

	public static ActionGraphPrimitiveDispatchResult accepted(String taskId, Map<String, Object> payload) {
		return accepted(taskId, payload, Map.of(), Map.of());
	}

	public static ActionGraphPrimitiveDispatchResult accepted(
		String taskId,
		Map<String, Object> payload,
		Map<String, Object> task,
		Map<String, Object> taskExecution
	) {
		return new ActionGraphPrimitiveDispatchResult(true, taskId, "", "", payload, task, taskExecution);
	}

	public static ActionGraphPrimitiveDispatchResult failed(String failureCode, String message, Map<String, Object> payload) {
		return new ActionGraphPrimitiveDispatchResult(false, "", failureCode, message, payload, Map.of(), Map.of());
	}
}
