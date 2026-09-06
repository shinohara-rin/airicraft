package ai.moeru.airicraft.systemone;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Deterministic task lifetimes. T, O and C are domain values, never engine handles. */
public final class TaskKernel<T, O, C> {

	@FunctionalInterface
	public interface Domain<T, O, C> {
		Decision<T, C> decide(View<T> task, O observation);
	}

	public record Limits(int transitionsPerTick, int maxDepth, long maxTicks, long maxCommands) {
		public Limits {
			if (transitionsPerTick < 1 || maxDepth < 1 || maxTicks < 1 || maxCommands < 1) {
				throw new IllegalArgumentException("Task limits must be positive");
			}
		}
	}

	public record Token(String session, String run, long task, long attempt) {}
	public enum ResultKind { SUCCEEDED, FAILED, CANCELLED }
	public record Outcome(ResultKind kind, String evidence) {
		public Outcome { Objects.requireNonNull(kind); Objects.requireNonNull(evidence); }
		public static Outcome success(String evidence) { return new Outcome(ResultKind.SUCCEEDED, evidence); }
		public static Outcome failure(String evidence) { return new Outcome(ResultKind.FAILED, evidence); }
		public static Outcome cancelled(String reason) { return new Outcome(ResultKind.CANCELLED, reason); }
	}

	public sealed interface Feedback permits Finished, Released { Token token(); }
	/** Finished means the adapter has also released all physical control. */
	public record Finished(Token token, Outcome outcome) implements Feedback {}
	public record Released(Token token) implements Feedback {}

	public sealed interface Effect<C> permits Start, Stop {}
	public record Start<C>(Token token, C command) implements Effect<C> {}
	public record Stop<C>(Token token) implements Effect<C> {}

	public sealed interface Decision<T, C> permits Keep, Execute, Child, Sleep, Complete {}
	public record Keep<T, C>() implements Decision<T, C> {}
	public record Execute<T, C>(T continuation, C command) implements Decision<T, C> {}
	public record Child<T, C>(T continuation, T child, String reason) implements Decision<T, C> {}
	public record Sleep<T, C>(T continuation, long wakeTick) implements Decision<T, C> {}
	public record Complete<T, C>(Outcome outcome) implements Decision<T, C> {}

	public sealed interface Phase<T> permits Ready, Acting, Releasing, WaitingChild, Sleeping {}
	public record Ready<T>() implements Phase<T> {}
	public record Acting<T>(Token token) implements Phase<T> {}
	public record Releasing<T>(Token token, AfterRelease<T> next) implements Phase<T> {}
	public record WaitingChild<T>(long childId, String reason) implements Phase<T> {}
	public record Sleeping<T>(long wakeTick) implements Phase<T> {}
	public sealed interface AfterRelease<T> permits PushChild, EndTask, EndRun {}
	public record PushChild<T>(T child, String reason) implements AfterRelease<T> {}
	public record EndTask<T>(Outcome outcome) implements AfterRelease<T> {}
	public record EndRun<T>(Outcome outcome) implements AfterRelease<T> {}

	public record Frame<T>(long id, T task, Phase<T> phase,
		Optional<Outcome> commandResult, Optional<Outcome> childResult) {
		public Frame {
			Objects.requireNonNull(task); Objects.requireNonNull(phase);
			Objects.requireNonNull(commandResult); Objects.requireNonNull(childResult);
		}
	}
	public record View<T>(long id, T task, boolean acting, long tick,
		Optional<Outcome> commandResult, Optional<Outcome> childResult) {}

	/** A stack represents a single active branch of a task tree, root first. */
	public record State<T>(String session, String run, List<Frame<T>> stack, long nextTask,
		long nextAttempt, long deadline, long lastTick, Optional<Outcome> outcome) {
		public State {
			Objects.requireNonNull(session); Objects.requireNonNull(run); Objects.requireNonNull(outcome);
			stack = List.copyOf(stack);
			if (stack.isEmpty() != outcome.isPresent()) {
				throw new IllegalArgumentException("A run has an active branch or a terminal outcome");
			}
			for (int i = 0; i + 1 < stack.size(); i++) {
				if (!(stack.get(i).phase() instanceof WaitingChild<T> waiting)
					|| waiting.childId() != stack.get(i + 1).id()) {
					throw new IllegalArgumentException("Only the leaf may own execution");
				}
			}
		}
	}
	public record Event(long tick, long task, String type, String detail) {}
	public record Step<T, C>(State<T> state, List<Effect<C>> effects, List<Event> events) {
		public Step { effects = List.copyOf(effects); events = List.copyOf(events); }
	}

	private final Domain<T, O, C> domain;
	private final Limits limits;

	public TaskKernel(Domain<T, O, C> domain, Limits limits) {
		this.domain = Objects.requireNonNull(domain);
		this.limits = Objects.requireNonNull(limits);
	}

	public State<T> begin(String session, String run, T goal, long tick) {
		return new State<>(session, run, List.of(ready(1, goal, Optional.empty(), Optional.empty())),
			2, 1, Math.addExact(tick, limits.maxTicks()), tick, Optional.empty());
	}

	public Step<T, C> advance(State<T> state, O observation, List<Feedback> feedback, long tick) {
		return advance(state, observation, feedback, tick, Optional.empty());
	}

	/** Cancellation is idempotent and cannot release a command without adapter acknowledgement. */
	public Step<T, C> advance(State<T> state, O observation, List<Feedback> feedback,
		long tick, Optional<String> cancellation) {
		if (tick < state.lastTick()) throw new IllegalArgumentException("Ticks must be monotonic");
		if (state.outcome().isPresent()) return new Step<>(state, List.of(), List.of());
		var turn = new Turn(state, tick);
		for (Feedback result : feedback) turn.accept(result);
		if (turn.done.isPresent()) return turn.finish();
		if (cancellation.isPresent() || tick >= state.deadline()) {
			turn.endRun(cancellation.map(Outcome::cancelled).orElseGet(() -> Outcome.failure("tick_budget_exhausted")));
			return turn.finish();
		}
		for (int i = 0; i < limits.transitionsPerTick() && turn.done.isEmpty(); i++) {
			Frame<T> frame = turn.leaf();
			if (frame.phase() instanceof Releasing<T>) break;
			if (frame.phase() instanceof Sleeping<T> sleeping && tick < sleeping.wakeTick()) break;
			boolean acting = frame.phase() instanceof Acting<T>;
			Decision<T, C> decision = domain.decide(new View<>(frame.id(), frame.task(), acting, tick,
				frame.commandResult(), frame.childResult()), observation);
			if (decision instanceof Keep<T, C>) break;
			if (decision instanceof Execute<T, C> execute) {
				if (acting) throw new IllegalStateException("Must release the current command before replacing it");
				if (turn.nextAttempt > limits.maxCommands()) {
					turn.endRun(Outcome.failure("command_budget_exhausted"));
					break;
				}
				Token token = new Token(state.session(), state.run(), frame.id(), turn.nextAttempt++);
				turn.replace(new Frame<>(frame.id(), execute.continuation(), new Acting<>(token), Optional.empty(), Optional.empty()));
				turn.effects.add(new Start<>(token, Objects.requireNonNull(execute.command())));
				turn.event("command_started", Long.toString(token.attempt()));
				break;
			}
			if (decision instanceof Child<T, C> child) {
				turn.replace(new Frame<>(frame.id(), child.continuation(), frame.phase(), Optional.empty(), Optional.empty()));
				turn.afterRelease(new PushChild<>(child.child(), child.reason()));
			}
			else if (decision instanceof Complete<T, C> complete) turn.afterRelease(new EndTask<>(complete.outcome()));
			else if (decision instanceof Sleep<T, C> sleep) {
				if (acting) throw new IllegalStateException("Cannot sleep while owning a command");
				if (sleep.wakeTick() <= tick) throw new IllegalArgumentException("Sleep must advance time");
				turn.replace(new Frame<>(frame.id(), sleep.continuation(), new Sleeping<>(sleep.wakeTick()), Optional.empty(), Optional.empty()));
				turn.event("task_waiting", Long.toString(sleep.wakeTick()));
				break;
			}
		}
		return turn.finish();
	}

	private static <T> Frame<T> ready(long id, T task, Optional<Outcome> command, Optional<Outcome> child) {
		return new Frame<>(id, task, new Ready<>(), command, child);
	}

	/** Mutation is confined to one invocation; only immutable values leave the kernel. */
	private final class Turn {
		final State<T> previous;
		final long tick;
		final ArrayList<Frame<T>> stack;
		final ArrayList<Effect<C>> effects = new ArrayList<>();
		final ArrayList<Event> events = new ArrayList<>();
		long nextTask;
		long nextAttempt;
		Optional<Outcome> done = Optional.empty();

		Turn(State<T> previous, long tick) {
			this.previous = previous; this.tick = tick;
			stack = new ArrayList<>(previous.stack());
			nextTask = previous.nextTask(); nextAttempt = previous.nextAttempt();
		}
		Frame<T> leaf() { return stack.getLast(); }
		void replace(Frame<T> frame) { stack.set(stack.size() - 1, frame); }
		void event(String type, String detail) { events.add(new Event(tick, stack.isEmpty() ? 1 : leaf().id(), type, detail)); }

		void accept(Feedback feedback) {
			if (done.isPresent()) return;
			Frame<T> frame = leaf();
			if (frame.phase() instanceof Acting<T> acting && acting.token().equals(feedback.token())
				&& feedback instanceof Finished finished) {
				replace(ready(frame.id(), frame.task(), Optional.of(finished.outcome()), Optional.empty()));
				event("command_finished", finished.outcome().kind() + ":" + finished.outcome().evidence());
			}
			else if (frame.phase() instanceof Releasing<T> releasing && releasing.token().equals(feedback.token())) {
				event("command_released", Long.toString(feedback.token().attempt()));
				replace(ready(frame.id(), frame.task(), Optional.empty(), Optional.empty()));
				apply(releasing.next());
			}
			else event("feedback_ignored", feedback.token().toString());
		}

		void afterRelease(AfterRelease<T> next) {
			Frame<T> frame = leaf();
			if (frame.phase() instanceof Acting<T> acting) {
				replace(new Frame<>(frame.id(), frame.task(), new Releasing<>(acting.token(), next), Optional.empty(), Optional.empty()));
				effects.add(new Stop<>(acting.token()));
				event("release_requested", next instanceof PushChild<T> child ? child.reason() : "task_ending");
			}
			else apply(next);
		}

		void apply(AfterRelease<T> next) {
			if (next instanceof PushChild<T> child) {
				if (stack.size() >= limits.maxDepth()) {
					completeTask(Outcome.failure("task_depth_exhausted"));
					return;
				}
				Frame<T> parent = leaf();
				long id = nextTask++;
				replace(new Frame<>(parent.id(), parent.task(), new WaitingChild<>(id, child.reason()), Optional.empty(), Optional.empty()));
				event("task_suspended", child.reason());
				stack.add(ready(id, child.child(), Optional.empty(), Optional.empty()));
				event("task_started", "parent=" + parent.id() + " reason=" + child.reason());
			}
			else if (next instanceof EndTask<T> end) completeTask(end.outcome());
			else if (next instanceof EndRun<T> end) {
				while (!stack.isEmpty()) { event("task_ended", end.outcome().kind() + ":" + end.outcome().evidence()); stack.removeLast(); }
				done = Optional.of(end.outcome());
			}
		}

		void completeTask(Outcome outcome) {
			event("task_ended", outcome.kind() + ":" + outcome.evidence());
			stack.removeLast();
			if (stack.isEmpty()) done = Optional.of(outcome);
			else {
				Frame<T> parent = leaf();
				replace(ready(parent.id(), parent.task(), Optional.empty(), Optional.of(outcome)));
				event("task_resumed", outcome.kind() + ":" + outcome.evidence());
			}
		}

		void endRun(Outcome outcome) {
			if (leaf().phase() instanceof Releasing<T> releasing) {
				Frame<T> frame = leaf();
				replace(new Frame<>(frame.id(), frame.task(), new Releasing<>(releasing.token(), new EndRun<>(outcome)), Optional.empty(), Optional.empty()));
			}
			else afterRelease(new EndRun<>(outcome));
		}

		Step<T, C> finish() {
			return new Step<>(new State<>(previous.session(), previous.run(), stack, nextTask, nextAttempt,
				previous.deadline(), tick, done), effects, events);
		}
	}
}
