package ai.moeru.airicraft.agent.actions;

import ai.moeru.actionplan.Fact;
import ai.moeru.actionplan.FactIdentity;
import ai.moeru.actionplan.FactType;
import ai.moeru.actionplan.Goal;
import ai.moeru.actionplan.CandidateRoute;
import ai.moeru.actionplan.PlanCommand;
import ai.moeru.actionplan.StateSnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AiricraftPlanConversions {
	private AiricraftPlanConversions() {
	}

	static Goal toGoal(ActionGoal goal) {
		LinkedHashMap<String, Long> minimums = new LinkedHashMap<>();
		goal.minimums().forEach((key, value) -> minimums.put(metricKey(key), value.longValue()));
		return new Goal(new FactType(goal.factType().id()), goal.keys(), minimums);
	}

	static ActionGoal toActionGoal(Goal goal) {
		ActionFactType type = java.util.Arrays.stream(ActionFactType.values())
			.filter(value -> value.id().equals(goal.type().id()))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("unsupported goal type " + goal.type().id()));
		LinkedHashMap<String, Integer> minimums = new LinkedHashMap<>();
		goal.minimums().forEach((key, value) -> minimums.put(goalMinimumKey(key), Math.toIntExact(value)));
		return new ActionGoal(type, goal.keys(), minimums);
	}

	static StateSnapshot toState(List<ActionFact> facts) {
		return new StateSnapshot(facts.stream().map(AiricraftPlanConversions::toFact).toList());
	}

	static ActionRoute toActionRoute(CandidateRoute route, ActionResolverContext context) {
		List<ActionPlanStep> steps = route.commands().stream()
			.map(command -> toActionStep(command, context))
			.toList();
		return new ActionRoute(steps, (int) Math.min(Integer.MAX_VALUE, route.cost()));
	}

	static ActionPlanStep toActionStep(PlanCommand command, ActionResolverContext context) {
		LinkedHashMap<String, Object> args = new LinkedHashMap<>(command.arguments());
		String kindName = String.valueOf(args.remove("airicraftKind"));
		ActionStepKind kind = ActionStepKind.valueOf(kindName);
		String actionId = String.valueOf(args.remove("actionId"));
		String alternativeId = String.valueOf(args.remove("alternativeId"));
		ActionWatchSpec watchSpec = kind == ActionStepKind.WATCH ? smeltingWatch(context, args) : null;
		return new ActionPlanStep(
			kind,
			actionId,
			alternativeId,
			command.commandId(),
			command.commandType(),
			args,
			watchSpec,
			command.methodKey()
		);
	}

	private static ActionWatchSpec smeltingWatch(ActionResolverContext context, Map<String, Object> args) {
		String optionId = scalar(args.get("optionId"));
		String itemId = scalar(args.get("itemId"));
		long timeoutTicks = args.get("timeoutTicks") instanceof Number number ? number.longValue() : 1_200L;
		return new ActionWatchSpec(
			new ActionFactCondition(
				ActionFactType.SMELTING_PROCESS,
				Map.of(
					"worldId", context.worldId(),
					"actorId", context.actorId(),
					"optionId", optionId,
					"itemId", itemId
				),
				Map.of("ready", 1)
			),
			null,
			timeoutTicks,
			ActionWatchProgressKind.AREA_TICKING,
			null
		);
	}

	private static String scalar(Object value) {
		return value == null ? "" : String.valueOf(value);
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

	private static String metricKey(String key) {
		return switch (key) {
			case "countAtLeast" -> "count";
			case "matureCountAtLeast" -> "matureCount";
			case "readyAtLeast" -> "ready";
			default -> key;
		};
	}

	private static String goalMinimumKey(String key) {
		return switch (key) {
			case "count" -> "countAtLeast";
			case "matureCount" -> "matureCountAtLeast";
			case "ready" -> "readyAtLeast";
			default -> key;
		};
	}
}
