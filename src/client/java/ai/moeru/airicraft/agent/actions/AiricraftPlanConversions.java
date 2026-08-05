package ai.moeru.airicraft.agent.actions;

import ai.moeru.actionplan.Fact;
import ai.moeru.actionplan.FactIdentity;
import ai.moeru.actionplan.FactType;
import ai.moeru.actionplan.Goal;
import ai.moeru.actionplan.StateSnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AiricraftPlanConversions {
	private AiricraftPlanConversions() {
	}

	static Goal toGoal(ActionGoal goal) {
		LinkedHashMap<String, Long> minimums = new LinkedHashMap<>();
		goal.minimums().forEach((key, value) -> minimums.put(key, value.longValue()));
		return new Goal(new FactType(goal.factType().id()), goal.keys(), minimums);
	}

	static ActionGoal toActionGoal(Goal goal) {
		ActionFactType type = java.util.Arrays.stream(ActionFactType.values())
			.filter(value -> value.id().equals(goal.type().id()))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("unsupported goal type " + goal.type().id()));
		LinkedHashMap<String, Integer> minimums = new LinkedHashMap<>();
		goal.minimums().forEach((key, value) -> minimums.put(key, Math.toIntExact(value)));
		return new ActionGoal(type, goal.keys(), minimums);
	}

	static StateSnapshot toState(List<ActionFact> facts) {
		return new StateSnapshot(facts.stream().map(AiricraftPlanConversions::toFact).toList());
	}

	private static Fact toFact(ActionFact fact) {
		LinkedHashMap<String, Long> metrics = new LinkedHashMap<>();
		fact.payload().forEach((key, value) -> {
			if (value instanceof Number number) {
				metrics.put(key, number.longValue());
			}
		});
		return new Fact(
			new FactIdentity(new FactType(fact.identity().type().id()), fact.identity().keys()),
			metrics
		);
	}
}
