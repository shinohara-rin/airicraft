package ai.moeru.airicraft.agent.actions;

import ai.moeru.actionplan.BackwardChainingPlanAdvisor;
import ai.moeru.actionplan.MethodKey;
import ai.moeru.actionplan.PlanAdvice;
import ai.moeru.actionplan.PlanAdvisor;
import ai.moeru.actionplan.PlanningOptions;
import ai.moeru.actionplan.PlanningProblem;

import java.util.Objects;
import java.util.Set;
import java.util.Map;

public final class AiricraftPlanAdvisor {
	public Map<String, Object> recommend(AiricraftPlanningSnapshot snapshot, ActionGoal goal, String planContext) {
		PlanAdvice advice = advise(snapshot, goal, Set.of());
		return Map.of(
			"planContext", planContext,
			"advisory", true,
			"goal", goal.normalizedKey(),
			"failureCode", advice.failure().code(),
			"message", advice.failure().message(),
			"candidates", advice.candidates().stream().map(candidate -> Map.of(
				"cost", candidate.cost(),
				"recommended", advice.recommendation().filter(candidate::equals).isPresent(),
				"steps", CommittedActionPlan.stepsPayload(AiricraftPlanConversions.toActionRoute(candidate, snapshot.context()))
			)).toList()
		);
	}

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
