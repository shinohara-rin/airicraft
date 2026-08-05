package ai.moeru.airicraft.agent.actions;

import ai.moeru.actionplan.ResolutionContext;

final class ResourceMethodProvider extends AiricraftMethodProvider {
	ResourceMethodProvider(AiricraftPlanningSnapshot snapshot) {
		super("resource_provider", 0, snapshot);
	}

	@Override
	protected ActionResolveResult resolveDomainRoute(ActionGoal goal, ResolutionContext context) {
		return AiricraftDomainMethodSession.resolveResourceProvider(snapshot(), goal, context);
	}
}
