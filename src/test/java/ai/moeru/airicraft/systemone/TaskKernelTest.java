package ai.moeru.airicraft.systemone;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskKernelTest {
	private static final Limits LIMITS = new Limits(16, 8, 100, 20);

	/** A battery-powered sample collector: no coordinates, items, recipes, or Minecraft types. */
	private static final Domain<String, String, String> COLLECTOR = (task, observation) -> {
		if (observation.equals("sample_collected")) return new Complete<>(Outcome.success("sample observed"));
		if (task.task().equals("charge")) {
			if (observation.equals("charged")) return new Complete<>(Outcome.success("battery observed full"));
			return task.acting() ? new Keep<>() : new Execute<>("charge", "dock_and_charge");
		}
		if (observation.equals("low_battery")) return new Child<>("return_to_sample", "charge", "battery low");
		return task.acting() ? new Keep<>() : new Execute<>(task.task(), task.task());
	};

	@Test
	void interruptsOnlyAfterReleaseAndResumesTheOriginalPurpose() {
		var kernel = new TaskKernel<>(COLLECTOR, LIMITS);
		var moving = kernel.advance(kernel.begin("world", "run", "collect_sample", 0), "healthy", List.of(), 1);
		var travel = start(moving);
		var interrupt = kernel.advance(moving.state(), "low_battery", List.of(), 2);
		assertEquals(List.of(new Stop<String>(travel.token())), interrupt.effects());
		assertEquals(1, interrupt.state().stack().size(), "repair cannot start while travel owns control");
		assertTrue(kernel.advance(interrupt.state(), "low_battery", List.of(), 3).effects().isEmpty());

		var charging = kernel.advance(interrupt.state(), "low_battery", List.of(new Released(travel.token())), 4);
		assertEquals("dock_and_charge", start(charging).command());
		assertEquals(2, charging.state().stack().size());
		assertEquals("return_to_sample", charging.state().stack().getFirst().task());

		var returning = kernel.advance(charging.state(), "charged",
			List.of(new Finished(start(charging).token(), Outcome.success("docked"))), 5);
		assertEquals("return_to_sample", start(returning).command());
		assertEquals(1, returning.state().stack().size());
		assertEquals(travel.token().task(), start(returning).token().task(), "same parent task resumes");
		var completed = kernel.advance(returning.state(), "sample_collected",
			List.of(new Finished(start(returning).token(), Outcome.success("arrived"))), 6);
		assertEquals(Outcome.success("sample observed"), completed.state().outcome().orElseThrow());
	}

	@Test
	void staleAndDuplicateFeedbackCannotFinishANewerCommand() {
		var kernel = new TaskKernel<>(COLLECTOR, LIMITS);
		var first = kernel.advance(kernel.begin("s", "r", "collect_sample", 0), "healthy", List.of(), 1);
		var feedback = new Finished(start(first).token(), Outcome.success("arrived"));
		var next = kernel.advance(first.state(), "healthy", List.of(feedback, feedback), 2);
		assertNotEquals(start(first).token(), start(next).token());
		var stale = kernel.advance(next.state(), "healthy", List.of(feedback,
			new Finished(new Token("other_world", "r", 1, 2), Outcome.success("wrong world"))), 3);
		assertTrue(stale.effects().isEmpty());
		assertEquals(next.state().stack(), stale.state().stack());
		assertEquals(2, stale.events().stream().filter(event -> event.type().equals("feedback_ignored")).count());
	}

	@Test
	void terminalReleaseIsDistinctFromAnInterruptionOrActiveWork() {
		var kernel = new TaskKernel<>(COLLECTOR, LIMITS);
		var active = kernel.advance(kernel.begin("s", "r", "collect_sample", 0), "healthy", List.of(), 1);
		assertFalse(active.state().ending());
		assertFalse(kernel.advance(active.state(), "low_battery", List.of(), 2).state().ending());
		var finishing = kernel.advance(active.state(), "sample_collected", List.of(), 2);
		assertTrue(finishing.state().ending());
		assertTrue(finishing.state().outcome().isEmpty(), "success still waits for physical release");
		var finished = kernel.advance(finishing.state(), "sample_collected", List.of(new Released(start(active).token())), 3);
		assertEquals(Outcome.success("sample observed"), finished.state().outcome().orElseThrow());
		assertTrue(finished.state().ending());
		var interruption = kernel.advance(active.state(), "low_battery", List.of(), 2);
		var child = kernel.advance(interruption.state(), "low_battery", List.of(new Released(start(active).token())), 3);
		assertFalse(kernel.advance(child.state(), "charged", List.of(), 4).state().ending(), "finishing a child does not finish the run");
		assertTrue(kernel.advance(child.state(), "charged", List.of(), 4, Optional.of("cancelled")).state().ending());
	}

	@Test
	void rootObservationCompletesThroughAnActingChildButStillWaitsForRelease() {
		Domain<String, Boolean, String> domain = new Domain<>() {
			@Override public Optional<Outcome> completion(String root, Boolean observed) {
				assertEquals("mission", root);
				return observed ? Optional.of(Outcome.success("goal observed")) : Optional.empty();
			}
			@Override public Decision<String, String> decide(View<String> task, Boolean observed) {
				if (task.task().equals("mission")) return new Child<>("mission", "prerequisite", "prepare");
				return task.acting() ? new Keep<>() : new Execute<>(task.task(), "collect");
			}
		};
		var kernel = new TaskKernel<>(domain, LIMITS);
		var child = kernel.advance(kernel.begin("s", "r", "mission", 0), false, List.of(), 1);
		assertEquals(2, child.state().stack().size());
		var token = start(child).token();
		var ending = kernel.advance(child.state(), true, List.of(), 2);
		assertTrue(ending.state().ending());
		assertTrue(ending.state().outcome().isEmpty());
		assertEquals(List.of(new Stop<String>(token)), ending.effects());
		assertTrue(kernel.advance(ending.state(), true, List.of(), 3).effects().isEmpty());
		var done = kernel.advance(ending.state(), true, List.of(new Released(token)), 4);
		assertEquals(Outcome.success("goal observed"), done.state().outcome().orElseThrow());
		assertEquals(2, done.events().stream().filter(event -> event.type().equals("task_ended")).count());
		assertTrue(done.effects().isEmpty());
		var cancelled = kernel.advance(child.state(), true, List.of(), 2, Optional.of("user_cancelled"));
		var awaiting = kernel.advance(cancelled.state(), true, List.of(), 3);
		assertEquals(Outcome.cancelled("user_cancelled"), kernel.advance(awaiting.state(), true,
			List.of(new Released(token)), 4).state().outcome().orElseThrow());
	}

	@Test
	void cancellationDuringRepairReleaseCancelsTheRunWithoutStartingTheRepair() {
		var kernel = new TaskKernel<>(COLLECTOR, LIMITS);
		var first = kernel.advance(kernel.begin("s", "r", "collect_sample", 0), "healthy", List.of(), 1);
		var releasing = kernel.advance(first.state(), "low_battery", List.of(), 2);
		var cancelled = kernel.advance(releasing.state(), "low_battery", List.of(), 3, Optional.of("user_cancelled"));
		assertTrue(cancelled.state().outcome().isEmpty(), "cancellation is pending until physical release");
		assertTrue(cancelled.effects().isEmpty(), "do not repeat stop or start a child");
		var released = kernel.advance(cancelled.state(), "healthy", List.of(new Released(start(first).token())), 4);
		assertEquals(Outcome.cancelled("user_cancelled"), released.state().outcome().orElseThrow());
		assertTrue(released.effects().isEmpty());
	}

	@Test
	void aFailedChildCanSelectAnAlternativeInsteadOfFailingTheMission() {
		Domain<String, String, String> domain = (view, observation) -> switch (view.task()) {
			case "mission" -> new Child<>("after_primary", "primary", "first method");
			case "primary" -> new Complete<>(Outcome.failure("unavailable"));
			case "after_primary" -> {
				assertEquals(ResultKind.FAILED, view.childResult().orElseThrow().kind());
				yield new Child<>("after_alternative", "alternative", "recover");
			}
			case "alternative" -> new Complete<>(Outcome.success("found alternative"));
			case "after_alternative" -> new Complete<>(view.childResult().orElseThrow());
			default -> throw new AssertionError(view.task());
		};
		var kernel = new TaskKernel<>(domain, LIMITS);
		var result = kernel.advance(kernel.begin("s", "r", "mission", 0), "", List.of(), 1);
		assertEquals(Outcome.success("found alternative"), result.state().outcome().orElseThrow());
	}

	@Test
	void passiveWaitingDoesNotRepeatCommandsOrClaimCompletion() {
		Domain<String, String, String> domain = (task, observation) -> observation.equals("ready")
			? new Complete<>(Outcome.success("external outcome observed")) : new Sleep<>(task.task(), task.tick() + 5);
		var kernel = new TaskKernel<>(domain, LIMITS);
		var first = kernel.advance(kernel.begin("s", "r", "waiting", 0), "pending", List.of(), 1);
		assertTrue(kernel.advance(first.state(), "pending", List.of(), 2).effects().isEmpty());
		assertEquals(ResultKind.SUCCEEDED,
			kernel.advance(first.state(), "ready", List.of(), 6).state().outcome().orElseThrow().kind());
	}

	@Test
	void aCommandSuccessIsNotGoalSuccess() {
		var kernel = new TaskKernel<>(COLLECTOR, LIMITS);
		var first = kernel.advance(kernel.begin("s", "r", "collect_sample", 0), "healthy", List.of(), 1);
		var next = kernel.advance(first.state(), "healthy",
			List.of(new Finished(start(first).token(), Outcome.success("path ended"))), 2);
		assertTrue(next.state().outcome().isEmpty());
	}

	@Test
	void deadlineStopsTheOwnerButDoesNotInventAReleaseAcknowledgement() {
		var kernel = new TaskKernel<>(COLLECTOR, new Limits(16, 8, 3, 20));
		var first = kernel.advance(kernel.begin("s", "r", "collect_sample", 0), "healthy", List.of(), 1);
		var expired = kernel.advance(first.state(), "healthy", List.of(), 3);
		assertEquals(List.of(new Stop<String>(start(first).token())), expired.effects());
		var waiting = kernel.advance(expired.state(), "healthy", List.of(), 40);
		assertTrue(waiting.effects().isEmpty());
		assertTrue(waiting.state().outcome().isEmpty());
		var released = kernel.advance(waiting.state(), "healthy", List.of(new Released(start(first).token())), 41);
		assertEquals(Outcome.failure("tick_budget_exhausted"), released.state().outcome().orElseThrow());
	}

	@Test
	void recursiveDependenciesAndRepeatedCommandsAreBounded() {
		Domain<String, String, String> cyclic = (task, observation) -> new Child<>(task.task(), task.task(), "cycle");
		var kernel = new TaskKernel<>(cyclic, new Limits(3, 2, 3, 1));
		var first = kernel.advance(kernel.begin("s", "r", "cycle", 0), "", List.of(), 1);
		assertTrue(first.state().stack().size() <= 2);
		assertEquals(Outcome.failure("tick_budget_exhausted"),
			kernel.advance(first.state(), "", List.of(), 3).state().outcome().orElseThrow());
		var bounded = new TaskKernel<>(COLLECTOR, new Limits(16, 8, 100, 1));
		var command = bounded.advance(bounded.begin("s", "r", "collect_sample", 0), "healthy", List.of(), 1);
		var exhausted = bounded.advance(command.state(), "healthy", List.of(new Finished(start(command).token(), Outcome.success("done"))), 2);
		assertEquals(Outcome.failure("command_budget_exhausted"), exhausted.state().outcome().orElseThrow());
	}

	@Test
	void recordedInputsReplayTheSameStatesEffectsAndEvents() {
		var kernel = new TaskKernel<>(COLLECTOR, LIMITS);
		var initial = kernel.begin("world", "recorded_run", "collect_sample", 0);
		var inputs = List.of(
			new Input("healthy", List.of(), 1),
			new Input("low_battery", List.of(), 2),
			new Input("low_battery", List.of(new Released(new Token("world", "recorded_run", 1, 1))), 3),
			new Input("charged", List.of(new Finished(new Token("world", "recorded_run", 2, 2), Outcome.success("charged"))), 4),
			new Input("sample_collected", List.of(new Finished(new Token("world", "recorded_run", 1, 3), Outcome.success("arrived"))), 5)
		);
		List<Step<String, String>> recording = new ArrayList<>();
		var state = initial;
		for (Input input : inputs) {
			var step = kernel.advance(state, input.observation(), input.feedback(), input.tick());
			recording.add(step); state = step.state();
		}
		state = initial;
		for (int i = 0; i < inputs.size(); i++) {
			var input = inputs.get(i);
			var replay = kernel.advance(state, input.observation(), input.feedback(), input.tick());
			assertEquals(recording.get(i), replay);
			state = replay.state();
		}
		assertEquals(ResultKind.SUCCEEDED, state.outcome().orElseThrow().kind());
	}

	@Test
	void domainCannotIssueAnotherCommandWhileActing() {
		Domain<String, String, String> invalid = (task, observation) -> new Execute<>("task", "command");
		var kernel = new TaskKernel<>(invalid, LIMITS);
		var first = kernel.advance(kernel.begin("s", "r", "task", 0), "", List.of(), 1);
		assertThrows(IllegalStateException.class, () -> kernel.advance(first.state(), "", List.of(), 2));
	}

	private record Input(String observation, List<Feedback> feedback, long tick) {}
	@SuppressWarnings("unchecked")
	private static Start<String> start(Step<String, String> step) {
		assertEquals(1, step.effects().size());
		return (Start<String>) step.effects().getFirst();
	}
}
