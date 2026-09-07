package ai.moeru.airicraft.evaluator;

import ai.moeru.airicraft.systemone.TaskKernel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static org.junit.jupiter.api.Assertions.*;

class SystemOneFeedbackFaultTest {
	@Test void delayedCompletionRetainsOwnershipAndLateDuplicateCannotCompleteTheNextAttempt() {
		var delivery = new SystemOneFeedbackFault();
		var kernel = new TaskKernel<Integer, Integer, String>((view, observation) -> view.acting() ? new Keep<>()
			: view.task() == 3 ? new Complete<>(Outcome.success("three actions")) : new Execute<>(view.task() + 1, "work"),
			new Limits(16, 4, 100, 10));
		var state = kernel.begin("session", "run", 0, 0);
		Token owner = null;
		long finishAt = 0;
		var starts = new ArrayList<Long>();
		int ignored = 0;
		for (long tick = 1; tick <= 60 && state.outcome().isEmpty(); tick++) {
			List<Feedback> received = List.of();
			if (owner != null && tick == finishAt) {
				received = List.of(new Finished(owner, Outcome.success("motor released")));
				owner = null;
			}
			var step = kernel.advance(state, 0, delivery.apply(tick, received), tick);
			state = step.state();
			ignored += (int) step.events().stream().filter(event -> event.type().equals("feedback_ignored")).count();
			for (var effect : step.effects()) if (effect instanceof Start<String> start) {
				assertNull(owner, "a new command requires physical release of its predecessor");
				owner = start.token(); finishAt = tick + 7; starts.add(tick);
			}
		}
		assertEquals(List.of(1L, 28L, 35L), starts);
		assertEquals(1, ignored);
		assertEquals(ResultKind.SUCCEEDED, state.outcome().orElseThrow().kind());
	}

	@Test void releaseAcknowledgementsAndSubsequentCompletionsPassThrough() {
		var delivery = new SystemOneFeedbackFault();
		var first = new Token("session", "run", 1, 1);
		var second = new Token("session", "run", 2, 2);
		var held = new Finished(first, Outcome.success("first"));
		assertTrue(delivery.apply(1L, List.of(held)).isEmpty());
		var release = new Released(first);
		var completion = new Finished(second, Outcome.success("second"));
		assertEquals(List.of(release, completion), delivery.apply(2L, List.of(release, completion)));
		assertTrue(delivery.apply(20L, List.of()).isEmpty());
		assertEquals(List.of(held), delivery.apply(21L, List.of()));
		assertTrue(delivery.apply(25L, List.of()).isEmpty());
		assertEquals(List.of(held), delivery.apply(26L, List.of()));
		assertTrue(delivery.apply(27L, List.of()).isEmpty());
	}
}
