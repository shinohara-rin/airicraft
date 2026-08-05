package ai.moeru.airicraft.agent.actions;

import ai.moeru.actionplan.CandidateRoute;
import ai.moeru.actionplan.MethodKey;
import ai.moeru.actionplan.PlanAdvice;
import ai.moeru.actionplan.PlanCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class AutoCommittingRoutePlanner {
	private static final Set<ActionFactType> EXECUTABLE_GOALS = Set.of(
		ActionFactType.INVENTORY_ITEM,
		ActionFactType.INVENTORY_RESOURCE
	);
	private static final PrimitiveActionRegistry PRIMITIVES = PrimitiveActionRegistry.defaults();
	private final AiricraftPlanAdvisor advisor;

	public AutoCommittingRoutePlanner() {
		this(new AiricraftPlanAdvisor());
	}

	AutoCommittingRoutePlanner(AiricraftPlanAdvisor advisor) {
		this.advisor = Objects.requireNonNull(advisor, "advisor");
	}

	public ActionResolveResult adviseAndCommit(
		AiricraftPlanningSnapshot snapshot,
		ActionGoal goal,
		Set<MethodKey> blockedMethods
	) {
		if (!EXECUTABLE_GOALS.contains(goal.factType())) {
			return ActionResolveResult.failure("unsupported_goal", "the auto-commit adapter rejects this goal type", List.of());
		}
		PlanAdvice advice = advisor.advise(snapshot, goal, blockedMethods);
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
			if (!executableCommand(kind, command.commandType())) {
				return ActionResolveResult.failure("unknown_command_type", "the advisor returned an unknown command type", trace(advice));
			}
			steps.add(AiricraftPlanConversions.toActionStep(command, snapshot.context()));
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

	private static boolean executableCommand(ActionStepKind kind, String commandType) {
		if (kind == ActionStepKind.WATCH) {
			return "watch".equals(commandType);
		}
		PrimitiveActionMetadata metadata = PRIMITIVES.find(commandType);
		return metadata != null && metadata.executable();
	}

}
