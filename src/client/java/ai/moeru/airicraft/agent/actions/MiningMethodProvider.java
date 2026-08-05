package ai.moeru.airicraft.agent.actions;

import ai.moeru.actionplan.ResolutionContext;

final class MiningMethodProvider extends AiricraftMethodProvider {
	MiningMethodProvider(AiricraftPlanningSnapshot snapshot) {
		super("mining_provider", 2, snapshot);
	}

	@Override
	protected ActionResolveResult resolveDomainRoute(ActionGoal goal, ResolutionContext context) {
		return AiricraftDomainMethodSession.resolveMiningProvider(snapshot(), goal, context);
	}
}
