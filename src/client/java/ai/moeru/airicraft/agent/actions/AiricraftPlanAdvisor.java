package ai.moeru.airicraft.agent.actions;

import ai.moeru.actionplan.BackwardChainingPlanAdvisor;
import ai.moeru.actionplan.MethodKey;
import ai.moeru.actionplan.PlanAdvice;
import ai.moeru.actionplan.PlanAdvisor;
import ai.moeru.actionplan.PlanningOptions;
import ai.moeru.actionplan.PlanningProblem;

import java.util.Objects;
import java.util.Set;

public final class AiricraftPlanAdvisor {
	private final PlanAdvisor advisor;

	public AiricraftPlanAdvisor() {
		this(new BackwardChainingPlanAdvisor());
	}

	AiricraftPlanAdvisor(PlanAdvisor advisor) {
		this.advisor = Objects.requireNonNull(advisor, "advisor");
	}

	public PlanAdvice advise(
		AiricraftPlanningSnapshot snapshot,
		ActionGoal goal,
		Set<MethodKey> blockedMethods
	) {
		Objects.requireNonNull(snapshot, "snapshot");
		Objects.requireNonNull(goal, "goal");
		return advisor.advise(new PlanningProblem(
			AiricraftPlanConversions.toState(snapshot.facts()),
			AiricraftPlanConversions.toGoal(goal),
			AiricraftMethodProvider.providers(snapshot),
			PlanningOptions.defaults(),
			blockedMethods == null ? Set.of() : Set.copyOf(blockedMethods)
		));
	}
}
