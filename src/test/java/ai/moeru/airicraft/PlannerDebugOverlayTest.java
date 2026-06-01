package ai.moeru.airicraft;

import ai.moeru.airicraft.agent.AgentRuntimeSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerContextDebugSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerConversationDebugKind;
import ai.moeru.airicraft.agent.llm.PlannerConversationDebugMessage;
import ai.moeru.airicraft.agent.llm.PlannerConversationDebugSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerOrchestratorDebugSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerRequest;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerDebugOverlayTest {
	@Test
	void formatStateLinesShowsPendingCoalesceState() {
		List<String> lines = PlannerDebugOverlay.formatStateLines(
			true,
			runtimeSnapshot(SessionMode.OUT_OF_WORLD),
			false,
			true,
			false,
			plannerSnapshot(
				new PlannerRequest(120L, 2_000L, SessionMode.OUT_OF_WORLD, "Alice", null, "Alice", "hello", null),
				new PlannerContextDebugSnapshot(65_536, true, 8, 1, 1, 0, 3, 42L, 1_000L, false, false, null, null, null),
				true,
				1_020L,
				20L
			),
			1_005L
		);

		assertTrue(lines.contains("enabled: true"));
		assertTrue(lines.contains("coalescePending: true"));
		assertTrue(lines.contains("coalesceWindowMs: 20"));
		assertTrue(lines.contains("coalesceRemainingMs: 15"));
		assertTrue(lines.contains("queuedTriggerCount: 3"));
	}

	@Test
	void formatStateLinesHandleUnknownNestedStateWithoutCrashing() {
		List<String> lines = PlannerDebugOverlay.formatStateLines(
			true,
			runtimeSnapshot(null),
			true,
			false,
			false,
			new PlannerOrchestratorDebugSnapshot(
				false,
				null,
				false,
				false,
				false,
				false,
				false,
				false,
				null,
				null,
				new PlannerContextDebugSnapshot(0, false, 0, 0, 0, 0, 0, 0L, 0L, false, false, null, null, null),
				0L,
				null,
				0,
				0L,
				0L,
				false,
				0L,
				false,
				0L,
				0L
			),
			0L
		);

		assertTrue(lines.contains("sessionState: -"));
		assertTrue(lines.contains("plannerVisionMode: -"));
		assertTrue(lines.contains("baseRequest: none"));
		assertTrue(lines.contains("lastCompactionResult: none"));
		assertTrue(lines.contains("lastObservedUsage: unknown"));
		assertTrue(lines.contains("activeCheckpoint: none"));
		assertTrue(lines.contains("ambientContext: none"));
	}

	@Test
	void overlayModeTransitionsAreStable() {
		PlannerDebugOverlay overlay = new PlannerDebugOverlay();

		assertEquals(PlannerDebugOverlayMode.OFF, overlay.mode());
		assertFalse(overlay.enabled());

		overlay.setMode(PlannerDebugOverlayMode.STATES);
		assertEquals(PlannerDebugOverlayMode.STATES, overlay.mode());
		assertTrue(overlay.enabled());

		overlay.setMode(PlannerDebugOverlayMode.CONVERSATION);
		assertEquals(PlannerDebugOverlayMode.CONVERSATION, overlay.mode());
		assertTrue(overlay.enabled());

		overlay.setMode(PlannerDebugOverlayMode.OFF);
		assertEquals(PlannerDebugOverlayMode.OFF, overlay.mode());
		assertFalse(overlay.enabled());
	}

	@Test
	void conversationLayoutWrapsTextAndKeepsNewestAtBottom() {
		PlannerConversationDebugSnapshot snapshot = new PlannerConversationDebugSnapshot(
			7L,
			"TOOL_FOLLOW_UP",
			2,
			List.of(
				message("system", PlannerConversationDebugKind.SYSTEM, "System prompt with a long explanation that should wrap across multiple lines for the pane."),
				message("assistant", PlannerConversationDebugKind.ASSISTANT_TURN, "Final assistant answer.")
			)
		);

		PlannerDebugOverlay.ConversationPaneLayout layout = PlannerDebugOverlay.layoutConversationPane(
			snapshot,
			800,
			600,
			text -> text.length() * 6,
			10,
			0,
			true,
			null
		);

		assertEquals(2, layout.cards().size());
		assertTrue(layout.cards().get(0).bodyLines().size() > 1);
		assertEquals(layout.maxScroll(), layout.scrollTop());
		PlannerDebugOverlay.ConversationCardLayout newest = layout.cards().get(layout.cards().size() - 1);
		assertEquals(layout.contentHeight(), newest.contentTop() + newest.height());
	}

	@Test
	void conversationScrollConsumesOnlyWhenPointerIsInsideScrollablePane() {
		PlannerConversationDebugSnapshot snapshot = new PlannerConversationDebugSnapshot(
			4L,
			"PLANNER_REQUEST",
			1,
			List.of(
				message("system", PlannerConversationDebugKind.SYSTEM, "x ".repeat(200)),
				message("user", PlannerConversationDebugKind.USER_TURN, "y ".repeat(200)),
				message("assistant", PlannerConversationDebugKind.ASSISTANT_TURN, "z ".repeat(200))
			)
		);
		PlannerDebugOverlay.ConversationPaneLayout layout = PlannerDebugOverlay.layoutConversationPane(
			snapshot,
			800,
			320,
			text -> text.length() * 6,
			10,
			0,
			true,
			null
		);

		PlannerDebugOverlay.ConversationScrollUpdate ignored = PlannerDebugOverlay.scrollConversation(
			layout,
			layout.scrollTop(),
			layout.viewportBounds().left() - 5,
			layout.viewportBounds().top() + 5,
			1.0D
		);
		assertFalse(ignored.consumed());

		PlannerDebugOverlay.ConversationScrollUpdate consumed = PlannerDebugOverlay.scrollConversation(
			layout,
			layout.scrollTop(),
			layout.viewportBounds().left() + 5,
			layout.viewportBounds().top() + 5,
			1.0D
		);
		assertTrue(consumed.consumed());
		assertTrue(consumed.scrollTop() < layout.scrollTop());
		assertFalse(consumed.pinnedToBottom());
	}

	@Test
	void conversationFooterShowsSpinnerWhilePlannerIsInFlight() {
		String footer = PlannerDebugOverlay.formatConversationFooter(
			plannerSnapshot(
				new PlannerRequest(120L, 2_000L, SessionMode.OUT_OF_WORLD, "Alice", null, "Alice", "hello", null),
				new PlannerContextDebugSnapshot(65_536, false, 8, 1, 1, 0, 0, 42L, 1_000L, false, false, null, null, null),
				false,
				-1L,
				0L,
				true,
				true,
				false,
				false
			),
			400L
		);

		assertEquals("-", footer.substring(0, 1));
		assertTrue(footer.contains("waiting for planner"));
	}

	@Test
	void conversationLayoutTitleTracksCurrentGenerationWhenPinnedToBottom() {
		PlannerDebugOverlay.ConversationPaneLayout first = PlannerDebugOverlay.layoutConversationPane(
			new PlannerConversationDebugSnapshot(
				1L,
				"PLANNER_REQUEST",
				1,
				List.of(message("user", PlannerConversationDebugKind.USER_TURN, "first request"))
			),
			800,
			600,
			text -> text.length() * 6,
			10,
			0,
			true,
			null
		);

		PlannerDebugOverlay.ConversationPaneLayout second = PlannerDebugOverlay.layoutConversationPane(
			new PlannerConversationDebugSnapshot(
				2L,
				"TOOL_FOLLOW_UP",
				1,
				List.of(message("tool", PlannerConversationDebugKind.TOOL_RESULT, "second tool result"))
			),
			800,
			600,
			text -> text.length() * 6,
			10,
			first.scrollTop(),
			true,
			null
		);

		assertTrue(second.title().contains("g2"));
		assertTrue(second.title().contains("tool_follow_up"));
		assertEquals(second.maxScroll(), second.scrollTop());
	}

	@Test
	void conversationLayoutReservesFooterSpaceWhenStatusLinePresent() {
		PlannerConversationDebugSnapshot snapshot = new PlannerConversationDebugSnapshot(
			7L,
			"PLANNER_REQUEST",
			1,
			List.of(message("assistant", PlannerConversationDebugKind.ASSISTANT_TURN, "Final assistant answer."))
		);

		PlannerDebugOverlay.ConversationPaneLayout withFooter = PlannerDebugOverlay.layoutConversationPane(
			snapshot,
			800,
			600,
			text -> text.length() * 6,
			10,
			0,
			true,
			"| waiting for planner"
		);
		PlannerDebugOverlay.ConversationPaneLayout withoutFooter = PlannerDebugOverlay.layoutConversationPane(
			snapshot,
			800,
			600,
			text -> text.length() * 6,
			10,
			0,
			true,
			null
		);

		assertEquals("| waiting for planner", withFooter.footerLine());
		assertTrue(withFooter.viewportBounds().height() < withoutFooter.viewportBounds().height());
	}

	private static AgentRuntimeSnapshot runtimeSnapshot(SessionMode mode) {
		return new AgentRuntimeSnapshot(
			true,
			120L,
			new SessionSnapshot(mode, true, false, null, false, 0, 120L),
			null,
			null,
			null,
			null
		);
	}

	private static PlannerOrchestratorDebugSnapshot plannerSnapshot(
		PlannerRequest request,
		PlannerContextDebugSnapshot context,
		boolean coalescePending,
		long coalesceReadyAtMs,
		long coalesceWindowMs
	) {
		return plannerSnapshot(request, context, coalescePending, coalesceReadyAtMs, coalesceWindowMs, false, false, false, false);
	}

	private static PlannerOrchestratorDebugSnapshot plannerSnapshot(
		PlannerRequest request,
		PlannerContextDebugSnapshot context,
		boolean coalescePending,
		long coalesceReadyAtMs,
		long coalesceWindowMs,
		boolean inFlight,
		boolean plannerInFlight,
		boolean toolInFlight,
		boolean captureInFlight
	) {
		return new PlannerOrchestratorDebugSnapshot(
			true,
			"native_tool_image",
			inFlight,
			plannerInFlight,
			false,
			captureInFlight,
			toolInFlight,
			false,
			request,
			null,
			context,
			7L,
			"TOOL_WAIT",
			2,
			9L,
			1L,
			false,
			0L,
			coalescePending,
			coalesceReadyAtMs,
			coalesceWindowMs
		);
	}

	private static PlannerConversationDebugMessage message(String role, PlannerConversationDebugKind kind, String text) {
		return new PlannerConversationDebugMessage(role, kind, text, 1L, "PLANNER_REQUEST", 1, false);
	}
}
