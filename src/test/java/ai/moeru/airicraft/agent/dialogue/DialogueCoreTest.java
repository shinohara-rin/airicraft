package ai.moeru.airicraft.agent.dialogue;

import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.llm.LlmFailureType;
import ai.moeru.airicraft.agent.llm.PlannerIntent;
import ai.moeru.airicraft.agent.llm.PlannerResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueCoreTest {
	@Test
	void plannerSuccessResetsFailureTrackingWithoutEffects() {
		DialogueState state = DialogueState.initial()
			.withConsecutiveFailureCount(2)
			.withLastFailureType(LlmFailureType.TIMEOUT)
			.withLastFailureTick(42L);

		DialogueTransition transition = DialogueCore.onPlannerSuccess(
			state,
			new PlannerResponse("Following.", new PlannerIntent("set_goal", GoalType.FOLLOW_PLAYER, "Alice")),
			99L
		);

		assertEquals(0, transition.state().consecutiveFailureCount());
		assertFalse(transition.state().degraded());
		assertTrue(transition.effects().isEmpty());
		assertEquals("Following.", transition.lastVisibleResponse().text());
	}

	@Test
	void thirdFailureEntersDegradedAndEmitsSemanticEffects() {
		DialogueState state = DialogueState.initial()
			.withConsecutiveFailureCount(2);

		DialogueTransition transition = DialogueCore.onPlannerFailure(
			state,
			LlmFailureType.TIMEOUT,
			"planner timed out",
			false,
			77L
		);

		assertTrue(transition.state().degraded());
		assertEquals(3, transition.state().consecutiveFailureCount());
		assertEquals(2, transition.effects().size());
		assertTrue(transition.lastVisibleResponse().text().contains("@agent reset"));
	}

	@Test
	void directChatTimeoutUsesFreshVisibleReply() {
		DialogueTransition transition = DialogueCore.onPlannerFailure(
			DialogueState.initial(),
			LlmFailureType.TIMEOUT,
			"planner timed out",
			true,
			91L
		);

		assertEquals("I hit a timeout just now. Please try again.", transition.lastVisibleResponse().text());
		assertEquals("timeout_visible_reply", transition.state().pendingReplyReason());
	}

	@Test
	void directChatProviderUnavailableUsesFreshVisibleReply() {
		DialogueTransition transition = DialogueCore.onPlannerFailure(
			DialogueState.initial(),
			LlmFailureType.PROVIDER_UNAVAILABLE,
			"LLM request failed: ConnectException",
			true,
			91L
		);

		assertEquals("I can't reach the LLM provider right now. Please try again.", transition.lastVisibleResponse().text());
		assertEquals("provider_unavailable_visible_reply", transition.state().pendingReplyReason());
	}

	@Test
	void resetClearsDegradedStateAndEmitsRecoveryEffects() {
		DialogueState state = DialogueState.initial()
			.withDegraded(true)
			.withConsecutiveFailureCount(3)
			.withLastFailureType(LlmFailureType.TIMEOUT)
			.withLastFailureTick(12L);

		DialogueTransition transition = DialogueCore.onReset(state, "Alice", 88L);

		assertFalse(transition.state().degraded());
		assertEquals(0, transition.state().consecutiveFailureCount());
		assertEquals("Planner state reset.", transition.lastVisibleResponse().text());
		assertEquals(2, transition.effects().size());
	}
}
