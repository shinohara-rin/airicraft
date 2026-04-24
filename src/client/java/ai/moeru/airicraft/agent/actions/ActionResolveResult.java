package ai.moeru.airicraft.agent.actions;

import java.util.List;

public record ActionResolveResult(
	boolean resolved,
	ActionRoute route,
	String failureCode,
	String message,
	List<ActionTraceEvent> trace
) {
	public ActionResolveResult {
		route = route == null ? ActionRoute.empty() : route;
		failureCode = failureCode == null ? "" : failureCode;
		message = message == null ? "" : message;
		trace = trace == null ? List.of() : List.copyOf(trace);
	}

	public static ActionResolveResult success(ActionRoute route, List<ActionTraceEvent> trace) {
		return new ActionResolveResult(true, route, "", "", trace);
	}

	public static ActionResolveResult failure(String failureCode, String message, List<ActionTraceEvent> trace) {
		return new ActionResolveResult(false, ActionRoute.empty(), failureCode, message, trace);
	}
}
