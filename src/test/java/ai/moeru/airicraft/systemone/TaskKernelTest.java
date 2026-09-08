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
	void keepingUpdatedDomainStatePreservesOwnershipFeedbackAndBudgets() {
		Domain<String, String, String> domain = (view, observation) -> observation.equals("start")
			? new Execute<>(view.task(), "move") : new Keep<>(observation);
		var kernel = new TaskKernel<>(domain, LIMITS);
		var started = kernel.advance(kernel.begin("s", "r", "initial", 0), "start", List.of(), 1);
		var refreshed = kernel.advance(started.state(), "observed", List.of(), 2);
		assertEquals("observed", refreshed.state().stack().getFirst().task());
		assertEquals(started.state().stack().getFirst().phase(), refreshed.state().stack().getFirst().phase());
		assertEquals(started.state().nextAttempt(), refreshed.state().nextAttempt());
		assertEquals(started.state().deadline(), refreshed.state().deadline());
		assertTrue(refreshed.effects().isEmpty());
		var outcome = Outcome.success("arrived");
		var finished = kernel.advance(refreshed.state(), "arrived_state", List.of(new Finished(start(started).token(), outcome)), 3);
		assertEquals("arrived_state", finished.state().stack().getFirst().task());
		assertInstanceOf(Ready.class, finished.state().stack().getFirst().phase());
		assertEquals(Optional.of(outcome), finished.state().stack().getFirst().commandResult());
		assertTrue(finished.effects().isEmpty());
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
				return root.equals("mission") && observed ? Optional.of(Outcome.success("goal observed")) : Optional.empty();
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

	@Test void anObservedReadyChildSettlesBeforeMaintenanceAndDoesNotStartParentWork() {
		Domain<String,Boolean,String> domain=new Domain<>() {
			@Override public Optional<Outcome> completion(String task,Boolean observed) {
				return task.equals("supply") && observed ? Optional.of(Outcome.success("supply observed")) : Optional.empty();
			}
			@Override public Optional<Interruption<String>> interrupt(List<View<String>> branch,Boolean observed) {
				return observed ? Optional.of(new Interruption<>(branch.getLast().task(),"repair","maintenance")) : Optional.empty();
			}
			@Override public Decision<String,String> decide(View<String> task,Boolean observed) {
				if(task.task().equals("mission") && task.childResult().isEmpty())return new Child<>("mission","supply","supply");
				return task.acting() ? new Keep<>() : new Execute<>(task.task(),task.task());
			}
		};
		var kernel=new TaskKernel<>(domain,LIMITS);
		var acting=kernel.advance(kernel.begin("s","r","mission",0),false,List.of(),1);var token=start(acting).token();
		var settled=kernel.advance(acting.state(),true,List.of(new Finished(token,Outcome.success("collected"))),2);
		assertEquals(1,settled.state().stack().size());assertTrue(settled.effects().isEmpty());
		assertEquals(Outcome.success("supply observed"),settled.state().stack().getFirst().childResult().orElseThrow());
		var repair=kernel.advance(settled.state(),true,List.of(),3);
		assertEquals("repair",start(repair).command());
	}

	@Test void anObservedAncestorReleasesItsActingDescendantBeforeParentMaintenance() {
		Domain<String,Boolean,String> domain=new Domain<>() {
			@Override public Optional<Outcome> completion(String task,Boolean observed) {
				return task.equals("supply") && observed ? Optional.of(Outcome.success("supply observed")) : Optional.empty();
			}
			@Override public Optional<Interruption<String>> interrupt(List<View<String>> branch,Boolean observed) {
				return observed ? Optional.of(new Interruption<>(branch.getLast().task(),"repair","maintenance")) : Optional.empty();
			}
			@Override public Decision<String,String> decide(View<String> task,Boolean observed) {
				if(task.task().equals("mission") && task.childResult().isEmpty())return new Child<>("mission","supply","supply");
				if(task.task().equals("supply"))return new Child<>("supply","collect","collect");
				return task.acting() ? new Keep<>() : new Execute<>(task.task(),task.task());
			}
		};
		var kernel=new TaskKernel<>(domain,LIMITS);
		var active=kernel.advance(kernel.begin("s","r","mission",0),false,List.of(),1);var token=start(active).token();
		var stopping=kernel.advance(active.state(),true,List.of(),2);
		assertEquals(List.of(new Stop<String>(token)),stopping.effects());
		assertTrue(stopping.events().stream().noneMatch(e->e.detail().equals("maintenance")));
		var waiting=kernel.advance(stopping.state(),true,List.of(),3);assertTrue(waiting.effects().isEmpty());
		var repair=kernel.advance(waiting.state(),true,List.of(new Released(token)),4);
		assertEquals("repair",start(repair).command());
		assertTrue(repair.state().stack().stream().noneMatch(f->f.task().equals("supply") || f.task().equals("collect")));
		assertTrue(repair.events().stream().anyMatch(e->e.task()==2 && e.type().equals("task_ended") && e.detail().equals("SUCCEEDED:supply observed")));
		assertTrue(repair.events().stream().anyMatch(e->e.task()==1 && e.type().equals("task_suspended") && e.detail().equals("maintenance")));
		var late=kernel.advance(repair.state(),false,List.of(new Finished(token,Outcome.success("late collection"))),5);
		assertTrue(late.effects().isEmpty());assertEquals(repair.state().stack(),late.state().stack());
		assertTrue(late.events().stream().anyMatch(e->e.type().equals("feedback_ignored")));
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
	private static final Domain<String, String, String> URGENT = new Domain<>() {
		@Override public Decision<String, String> decide(View<String> view, String observation) {
			if (view.acting()) return new Keep<>();
			if (view.task().equals("rescue") && view.commandResult().isPresent()) return new Complete<>(Outcome.success("safe"));
			if (view.task().equals("goal")) return new Child<>("goal", "work", "prerequisite");
			if (observation.equals("sleep")) return new Sleep<>(view.task(), 80);
			return new Execute<>(view.task(), view.task());
		}
		@Override public Optional<Interruption<String>> interrupt(List<View<String>> branch, String observation) {
			var leaf = branch.getLast();
			return observation.equals("danger") && !leaf.task().equals("rescue")
				? Optional.of(new Interruption<>("resume_" + leaf.task(), "rescue", "hazard observed")) : Optional.empty();
		}
		@Override public Optional<Revision<String>> reconsider(List<View<String>> branch, String observation) {
			if (observation.equals("danger") && !branch.getLast().task().equals("rescue")) fail("urgent interruption precedes ordinary revision");
			return Optional.empty();
		}
	};
	@Test void urgentWorkInterruptsAPassiveWaitWithoutLosingTheParentBranch() {
		var kernel = new TaskKernel<>(URGENT, LIMITS);
		var sleeping = kernel.advance(kernel.begin("s", "r", "goal", 0), "sleep", List.of(), 1);
		assertInstanceOf(Sleeping.class, sleeping.state().stack().getLast().phase());
		var rescue = kernel.advance(sleeping.state(), "danger", List.of(), 2);
		assertEquals("rescue", start(rescue).command());
		assertEquals(3, rescue.state().stack().size());
		assertEquals(sleeping.state().stack().getFirst(), rescue.state().stack().getFirst());
		assertEquals("resume_work", rescue.state().stack().get(1).task());
		var resumed = kernel.advance(rescue.state(), "safe", List.of(new Finished(start(rescue).token(), Outcome.success("safe"))), 3);
		assertEquals("resume_work", start(resumed).command());
		assertEquals(sleeping.state().stack().getLast().id(), start(resumed).token().task());
		assertEquals(sleeping.state().deadline(), resumed.state().deadline());
	}
	@Test void urgentWorkWaitsForActiveReleaseAndIgnoresLateCompletion() {
		var kernel = new TaskKernel<>(URGENT, LIMITS);
		var working = kernel.advance(kernel.begin("s", "r", "goal", 0), "healthy", List.of(), 1);
		var old = start(working);
		var stopping = kernel.advance(working.state(), "danger", List.of(), 2);
		assertEquals(List.of(new Stop<String>(old.token())), stopping.effects());
		assertTrue(kernel.advance(stopping.state(), "danger", List.of(), 3).effects().isEmpty());
		var rescue = kernel.advance(stopping.state(), "danger", List.of(new Released(old.token())), 4);
		assertEquals("rescue", start(rescue).command());
		var stale = kernel.advance(rescue.state(), "danger", List.of(new Finished(old.token(), Outcome.success("late"))), 5);
		assertEquals(rescue.state().stack(), stale.state().stack());
		assertTrue(stale.effects().isEmpty());
		var cancelled = kernel.advance(stopping.state(), "danger", List.of(), 3, Optional.of("user stop"));
		var ended = kernel.advance(cancelled.state(), "danger", List.of(new Released(old.token())), 4);
		assertEquals(Outcome.cancelled("user stop"), ended.state().outcome().orElseThrow());
		assertTrue(ended.effects().isEmpty());
	}
	@Test void urgentInsertionConsumesATransitionFromTheExistingBudget() {
		var kernel = new TaskKernel<>(URGENT, new Limits(1, 8, 100, 20));
		var sleeping = kernel.advance(kernel.begin("s", "r", "work", 0), "sleep", List.of(), 1);
		var inserted = kernel.advance(sleeping.state(), "danger", List.of(), 2);
		assertTrue(inserted.effects().isEmpty());
		assertEquals(2, inserted.state().stack().size());
		assertEquals("rescue", start(kernel.advance(inserted.state(), "danger", List.of(), 3)).command());
	}
	private static final Domain<String, String, String> REVISABLE = new Domain<>() {
		@Override public Decision<String, String> decide(View<String> view, String observation) {
			if (view.task().equals("goal")) return new Child<>("goal", "dependency", "old method");
			if (view.task().equals("dependency")) return new Child<>("dependency", "old_work", "old prerequisite");
			if (view.acting()) return new Keep<>();
			if (observation.equals("waiting")) return new Sleep<>(view.task(), view.tick() + 20);
			return new Execute<>(view.task(), view.task());
		}
		@Override public Optional<Revision<String>> reconsider(List<View<String>> branch, String observation) {
			return observation.equals("alternative") ? Optional.of(new Revision<>(branch.getFirst().id(), "new_work", "new evidence")) : Optional.empty();
		}
	};
	@Test void ancestorRevisionWaitsForReleaseAndRetainsIdentityAndBudgets() {
		var kernel = new TaskKernel<>(REVISABLE, LIMITS);
		var first = kernel.advance(kernel.begin("s", "r", "goal", 0), "", List.of(), 1);
		var old = start(first);
		var revise = kernel.advance(first.state(), "alternative", List.of(), 2);
		assertEquals(List.of(new Stop<String>(old.token())), revise.effects());
		assertEquals(3, revise.state().stack().size());
		assertTrue(kernel.advance(revise.state(), "alternative", List.of(), 3).effects().isEmpty());
		var replaced = kernel.advance(revise.state(), "alternative", List.of(new Released(old.token())), 4);
		assertEquals("new_work", start(replaced).command());
		assertEquals(1, start(replaced).token().task());
		assertEquals(first.state().nextAttempt(), start(replaced).token().attempt());
		assertEquals(first.state().deadline(), replaced.state().deadline());
		assertEquals(first.state().nextTask(), replaced.state().nextTask());
		assertEquals(2, replaced.events().stream().filter(e -> e.type().equals("task_ended") && e.detail().startsWith("CANCELLED:")).count());
		var stale = kernel.advance(replaced.state(), "", List.of(new Finished(old.token(), Outcome.success("late"))), 5);
		assertEquals(replaced.state().stack(), stale.state().stack());
		assertTrue(stale.effects().isEmpty());
	}
	@Test void cancellationOverridesAPendingAncestorRevision() {
		var kernel = new TaskKernel<>(REVISABLE, LIMITS);
		var first = kernel.advance(kernel.begin("s", "r", "goal", 0), "", List.of(), 1);
		var revise = kernel.advance(first.state(), "alternative", List.of(), 2);
		var cancelled = kernel.advance(revise.state(), "alternative", List.of(), 3, Optional.of("user stop"));
		var ended = kernel.advance(cancelled.state(), "alternative", List.of(new Released(start(first).token())), 4);
		assertEquals(Outcome.cancelled("user stop"), ended.state().outcome().orElseThrow());
		assertTrue(ended.effects().isEmpty());
	}
	@Test void aSleepingPrerequisiteCanBeRevisedWithoutInventingARelease() {
		var kernel = new TaskKernel<>(REVISABLE, LIMITS);
		var waiting = kernel.advance(kernel.begin("s", "r", "goal", 0), "waiting", List.of(), 1);
		assertInstanceOf(Sleeping.class, waiting.state().stack().getLast().phase());
		var changed = kernel.advance(waiting.state(), "alternative", List.of(), 2);
		assertEquals("new_work", start(changed).command());
		assertEquals(1, changed.state().stack().size());
	}
	@Test void observingWaitingParentsPreservesOwnershipAndFeedsFreshStateToInterruptions() {
		record Observed(String work,int reading) {}
		var domain = new Domain<Observed,Integer,String>() {
			public List<Observed> observe(List<View<Observed>> branch,Integer input) {
				return branch.stream().map(v -> new Observed(v.task().work(),input)).toList();
			}
			public Optional<Interruption<Observed>> interrupt(List<View<Observed>> branch,Integer input) {
				if (branch.size()==2 && branch.getFirst().task().reading()==2) return Optional.of(new Interruption<>(new Observed("resume",input),new Observed("repair",input),"condition"));
				return Optional.empty();
			}
			public Decision<Observed,String> decide(View<Observed> view,Integer input) {
				if (view.task().work().equals("mission")) return new Child<>(view.task(),new Observed("work",input),"dependency");
				return view.acting() ? new Keep<>() : new Execute<>(view.task(),view.task().work());
			}
		};
		var kernel=new TaskKernel<>(domain,LIMITS);
		var first=kernel.advance(kernel.begin("s","r",new Observed("mission",0),0),1,List.of(),1);
		var running=(Start<String>)first.effects().getFirst();
		var stopped=kernel.advance(first.state(),2,List.of(),2);
		assertEquals(2,stopped.state().stack().getFirst().task().reading());
		assertEquals(List.of(new Stop<>(running.token())),stopped.effects());
		var pending=kernel.advance(stopped.state(),3,List.of(),3);
		assertEquals(3,pending.state().stack().getFirst().task().reading());
		assertEquals(stopped.state().stack().getLast().phase(),pending.state().stack().getLast().phase());
		assertEquals(first.state().deadline(),pending.state().deadline());
		assertEquals(first.state().nextAttempt(),pending.state().nextAttempt());
		assertTrue(pending.effects().isEmpty());
		var repaired=kernel.advance(pending.state(),4,List.of(new Released(running.token())),4);
		assertEquals("repair",((Start<?>)repaired.effects().getFirst()).command());
	}
	@Test void observingAPassiveWaitDoesNotWakeItOrConsumeACommand() {
		var domain=new Domain<Integer,Integer,String>() {
			public List<Integer> observe(List<View<Integer>> branch,Integer input) { return List.of(input); }
			public Decision<Integer,String> decide(View<Integer> view,Integer input) { return new Sleep<>(view.task(),50); }
		};
		var kernel=new TaskKernel<>(domain,LIMITS);
		var sleeping=kernel.advance(kernel.begin("s","r",0,0),1,List.of(),1);
		var observed=kernel.advance(sleeping.state(),2,List.of(),2);
		assertEquals(2,observed.state().stack().getFirst().task());
		assertEquals(sleeping.state().stack().getFirst().phase(),observed.state().stack().getFirst().phase());
		assertTrue(observed.effects().isEmpty()); assertEquals(sleeping.state().nextAttempt(),observed.state().nextAttempt());
	}
	@Test void observationCannotAddOrRemoveTaskFrames() {
		var domain=new Domain<String,String,String>() {
			public List<String> observe(List<View<String>> branch,String input) { return List.of(); }
			public Decision<String,String> decide(View<String> view,String input) { return new Keep<>(); }
		};
		var kernel=new TaskKernel<>(domain,LIMITS);
		assertThrows(IllegalArgumentException.class,() -> kernel.advance(kernel.begin("s","r","goal",0),"",List.of(),1));
	}
	@SuppressWarnings("unchecked")
	private static Start<String> start(Step<String, String> step) {
		assertEquals(1, step.effects().size());
		return (Start<String>) step.effects().getFirst();
	}
}
