package ai.moeru.airicraft.agent.actions;

import ai.moeru.actionplan.ResolutionContext;

final class RecipeMethodProvider extends AiricraftMethodProvider {
	RecipeMethodProvider(AiricraftPlanningSnapshot snapshot) {
		super("recipe_provider", 0, snapshot);
	}

	@Override
	protected ActionResolveResult resolveDomainRoute(ActionGoal goal, ResolutionContext context) {
		return AiricraftDomainMethodSession.resolveRecipeProvider(snapshot(), goal, context);
	}
}
