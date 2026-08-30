package ai.moeru.airicraft.agent.debug;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDebugRecorderCursorTest {
	@Test
	void cursorImmediatelyBeforeOldestRetainedEntryIsNotTruncated() {
		AgentDebugRecorder recorder = new AgentDebugRecorder(2, 1);
		recorder.recordChatAttempt(1L, "one", "test", false);
		recorder.recordChatAttempt(2L, "two", "test", false);
		recorder.recordChatAttempt(3L, "three", "test", false);

		assertFalse(recorder.queryTimeline(1L).truncated());
		assertTrue(recorder.queryTimeline(0L).truncated());
	}
}
