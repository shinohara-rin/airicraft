package ai.moeru.airicraft.agent.actions;

@FunctionalInterface
public interface ActionGraphPrimitiveDispatcher {
	ActionGraphPrimitiveDispatchResult dispatch(ActionPlanStep step);
}
