package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.session.SessionMode;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerConversationProjectorTest {
	@Test
	void appendsToolExchangesInOpenAiReplayOrder() {
		PlannerTurnJournal journal = new PlannerTurnJournal(fixedClock(), 64);
		PlannerConversationProjector projector = new PlannerConversationProjector(48);
		PlannerRequest request = requestAt(10L, 1_000L, "@agent make torches");
		LlmConversation base = LlmConversation.of(List.of(
			LlmChatMessage.system("system"),
			LlmChatMessage.user("[chat][Alice] @agent make torches", LlmMessageKind.USER_TURN)
		));
		PlannerContextSnapshot snapshot = snapshot(request, base);
		PlannerToolCall craftables = toolCall("call_craft", "check_craftables", "prompt", "torch ingredients");
		PlannerToolCall recipes = toolCall("call_recipe", "search_recipes", "query", "torch");

		journal.recordToolExchange(
			1L,
			snapshot,
			null,
			craftables,
			"Tool result for check_craftables: Available 2x2 crafts: torch",
			false
		);
		LlmConversation replay = projector.appendToolExchanges(base, journal.toolExchanges(1L))
			.withAppended(LlmChatMessage.assistantToolCall("", recipes))
			.withAppended(LlmChatMessage.tool(recipes.id(), "Tool result for search_recipes: query=torch"));

		List<LlmChatMessage> tail = replay.messages().subList(replay.messages().size() - 4, replay.messages().size());
		assertEquals("assistant", tail.get(0).role());
		assertEquals("check_craftables", tail.get(0).toolCalls().getFirst().name());
		assertEquals("tool", tail.get(1).role());
		assertTrue(tail.get(1).content().contains("Available 2x2 crafts"));
		assertEquals("assistant", tail.get(2).role());
		assertEquals("search_recipes", tail.get(2).toolCalls().getFirst().name());
		assertEquals("tool", tail.get(3).role());
		assertTrue(tail.get(3).content().contains("query=torch"));
	}

	@Test
	void projectedSnapshotDropsSupersededDebugCards() {
		PlannerTurnJournal journal = new PlannerTurnJournal(fixedClock(), 64);
		PlannerConversationProjector projector = new PlannerConversationProjector(48);
		LlmConversation first = LlmConversation.of(List.of(
			LlmChatMessage.system("system"),
			LlmChatMessage.user("[chat][Alice] first", LlmMessageKind.USER_TURN)
		));
		LlmConversation second = LlmConversation.of(List.of(
			LlmChatMessage.system("system"),
			LlmChatMessage.user("[chat][Alice] second", LlmMessageKind.USER_TURN)
		));
		journal.recordSubmission(1L, 1, PlannerSessionPhase.PLANNER_REQUEST, requestAt(10L, 1_000L, "first"), first);
		journal.recordDebugCard(new PlannerConversationDebugMessage(
			"assistant",
			PlannerConversationDebugKind.TASK,
			"Tool call: take_a_look",
			1L,
			PlannerSessionPhase.PLANNER_REQUEST.name(),
			1,
			false
		));
		journal.markSuperseded(1L);
		journal.recordSubmission(2L, 1, PlannerSessionPhase.PLANNER_REQUEST, requestAt(11L, 1_100L, "second"), second);

		PlannerConversationDebugSnapshot projected = projector.projectedSnapshot(journal);

		assertFalse(projected.messages().stream().anyMatch(message -> message.text().contains("take_a_look")));
		assertTrue(projected.messages().stream().anyMatch(message -> message.text().contains("second")));
	}

	private static PlannerContextSnapshot snapshot(PlannerRequest request, LlmConversation conversation) {
		return new PlannerContextSnapshot(
			request,
			PlannerSnapshotMode.TRIGGERED,
			PlannerTriggerBatch.of(List.of()),
			conversation,
			0L,
			0L,
			PlannerAmbientContext.fromRequest(request),
			-1L
		);
	}

	private static PlannerRequest requestAt(long tick, long timestampMs, String message) {
		return new PlannerRequest(tick, timestampMs, SessionMode.OUT_OF_WORLD, "Alice", null, "Alice", message, null);
	}

	private static PlannerToolCall toolCall(String id, String name, String argumentName, String argumentValue) {
		JsonObject arguments = new JsonObject();
		arguments.addProperty(argumentName, argumentValue);
		return new PlannerToolCall(id, name, arguments, null, null);
	}

	private static Clock fixedClock() {
		return Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneId.of("Asia/Taipei"));
	}
}
