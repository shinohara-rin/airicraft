package ai.moeru.airicraft.agent.actions;

import ai.moeru.actionplan.ResolutionContext;

final class SmeltingMethodProvider extends AiricraftMethodProvider {
	SmeltingMethodProvider(AiricraftPlanningSnapshot snapshot) {
		super("smelting_provider", 1, snapshot);
	}

	@Override
	protected ActionResolveResult resolveDomainRoute(ActionGoal goal, ResolutionContext context) {
		return AiricraftDomainMethodSession.resolveSmeltingProvider(snapshot(), goal, context);
	}
}
