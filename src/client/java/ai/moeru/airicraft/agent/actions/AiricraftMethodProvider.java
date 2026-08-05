package ai.moeru.airicraft.agent.actions;

import ai.moeru.actionplan.Goal;
import ai.moeru.actionplan.MethodKey;
import ai.moeru.actionplan.MethodProvider;
import ai.moeru.actionplan.MethodRoute;
import ai.moeru.actionplan.PlanCommand;
import ai.moeru.actionplan.ProviderId;
import ai.moeru.actionplan.ResolutionContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class AiricraftMethodProvider implements MethodProvider {
	private final ProviderId id;
	private final int rank;
	private final AiricraftPlanningSnapshot snapshot;

	AiricraftMethodProvider(String id, int rank, AiricraftPlanningSnapshot snapshot) {
		this.id = new ProviderId(id);
		this.rank = rank;
		this.snapshot = snapshot;
	}

	@Override
	public ProviderId id() {
		return id;
	}

	@Override
	public int rank() {
		return rank;
	}

	@Override
	public Optional<MethodRoute> resolve(Goal goal, ResolutionContext context) {
		ActionGoal actionGoal = AiricraftPlanConversions.toActionGoal(goal);
		ActionResolveResult result = ActionResolver.resolveProvider(new ActionResolutionRequest(
			ActionsetIndex.empty(),
			snapshot.facts(),
			snapshot.blockAcquisitions(),
			snapshot.nearbyBlockAvailability(),
			snapshot.context(),
			actionGoal,
			ActionResolutionRequest.DEFAULT_MAX_DEPTH,
			ActionResolutionRequest.DEFAULT_EXPLORATION_BUDGET,
			Set.of(),
			false
		), id.value(), context);
		result.trace().forEach(event -> context.trace(
			event.eventType(),
			new MethodKey(new ProviderId(nonEmpty(event.actionId(), id.value())), nonEmpty(event.alternativeId(), "unknown")),
			event.stepId(),
			event.payload()
		));
		if (!result.resolved()) {
			return Optional.empty();
		}
		String methodId = result.route().steps().isEmpty()
			? "already_satisfied"
			: result.route().steps().getLast().alternativeId();
		MethodKey methodKey = new MethodKey(id, nonEmpty(methodId, "default"));
		ArrayList<PlanCommand> commands = new ArrayList<>();
		for (ActionPlanStep step : result.route().steps()) {
			LinkedHashMap<String, Object> arguments = new LinkedHashMap<>(step.args());
			arguments.put("airicraftKind", step.kind().name());
			arguments.put("actionId", step.actionId());
			arguments.put("alternativeId", step.alternativeId());
			commands.add(new PlanCommand(step.stepId(), step.targetId(), arguments, methodKey));
		}
		return Optional.of(new MethodRoute(methodKey, commands, result.route().cost()));
	}

	private static String nonEmpty(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}
}
