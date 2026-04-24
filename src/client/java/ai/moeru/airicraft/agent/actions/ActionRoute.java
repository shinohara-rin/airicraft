package ai.moeru.airicraft.agent.actions;

import java.util.List;

public record ActionRoute(
	List<ActionPlanStep> steps,
	int cost
) {
	public ActionRoute {
		steps = steps == null ? List.of() : List.copyOf(steps);
	}

	public static ActionRoute empty() {
		return new ActionRoute(List.of(), 0);
	}
}
