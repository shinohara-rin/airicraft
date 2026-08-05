package ai.moeru.airicraft.agent.actions;

import ai.moeru.actionplan.BackwardChainingPlanAdvisor;
import ai.moeru.actionplan.CandidateRoute;
import ai.moeru.actionplan.MethodKey;
import ai.moeru.actionplan.PlanAdvice;
import ai.moeru.actionplan.PlanCommand;
import ai.moeru.actionplan.PlanningOptions;
import ai.moeru.actionplan.PlanningProblem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AutoCommittingRoutePlanner {
	private static final Set<ActionFactType> EXECUTABLE_GOALS = Set.of(
		ActionFactType.INVENTORY_ITEM,
		ActionFactType.INVENTORY_RESOURCE
	);

	public ActionResolveResult adviseAndCommit(
		AiricraftPlanningSnapshot snapshot,
		ActionGoal goal,
		Set<MethodKey> blockedMethods
	) {
		if (!EXECUTABLE_GOALS.contains(goal.factType())) {
			return ActionResolveResult.failure("unsupported_goal", "the auto-commit adapter rejects this goal type", List.of());
		}
		PlanAdvice advice = new BackwardChainingPlanAdvisor().advise(new PlanningProblem(
			AiricraftPlanConversions.toState(snapshot.facts()),
			AiricraftPlanConversions.toGoal(goal),
			List.of(
				new AiricraftMethodProvider("resource_provider", 0, snapshot),
				new AiricraftMethodProvider("recipe_provider", 0, snapshot),
				new AiricraftMethodProvider("smelting_provider", 1, snapshot),
				new AiricraftMethodProvider("mining_provider", 2, snapshot)
			),
			PlanningOptions.defaults(),
			blockedMethods
		));
		if (advice.recommendation().isEmpty()) {
			return ActionResolveResult.failure(advice.failure().code(), advice.failure().message(), trace(advice));
		}
		CandidateRoute recommendation = advice.recommendation().get();
		ArrayList<ActionPlanStep> steps = new ArrayList<>();
		for (PlanCommand command : recommendation.commands()) {
			Map<String, Object> arguments = command.arguments();
			String kindName = scalar(arguments.get("airicraftKind"));
			ActionStepKind kind;
			try {
				kind = ActionStepKind.valueOf(kindName);
			}
			catch (IllegalArgumentException exception) {
				return ActionResolveResult.failure("unknown_command_type", "the advisor returned an unknown command type", trace(advice));
			}
			if (kind != ActionStepKind.PRIMITIVE && kind != ActionStepKind.WATCH) {
				return ActionResolveResult.failure("unknown_command_type", "the advisor returned a non-executable command", trace(advice));
			}
			LinkedHashMap<String, Object> args = new LinkedHashMap<>(arguments);
			String actionId = scalar(args.remove("actionId"));
			String alternativeId = scalar(args.remove("alternativeId"));
			args.remove("airicraftKind");
			ActionWatchSpec watchSpec = kind == ActionStepKind.WATCH ? smeltingWatch(snapshot, args) : null;
			steps.add(new ActionPlanStep(kind, actionId, alternativeId, command.commandId(), command.commandType(), args, watchSpec, command.methodKey()));
		}
		return ActionResolveResult.success(new ActionRoute(steps, (int) Math.min(Integer.MAX_VALUE, recommendation.cost())), trace(advice));
	}

	private static List<ActionTraceEvent> trace(PlanAdvice advice) {
		return advice.trace().stream().map(event -> new ActionTraceEvent(
			event.eventType(), event.providerId(), event.methodId(), event.commandId(), event.payload()
		)).toList();
	}

	private static String scalar(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private static ActionWatchSpec smeltingWatch(AiricraftPlanningSnapshot snapshot, Map<String, Object> args) {
		String optionId = scalar(args.get("optionId"));
		String itemId = scalar(args.get("itemId"));
		long timeoutTicks = args.get("timeoutTicks") instanceof Number number ? number.longValue() : 1_200L;
		return new ActionWatchSpec(
			new ActionFactCondition(
				ActionFactType.SMELTING_PROCESS,
				Map.of(
					"worldId", snapshot.context().worldId(),
					"actorId", snapshot.context().actorId(),
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
}
