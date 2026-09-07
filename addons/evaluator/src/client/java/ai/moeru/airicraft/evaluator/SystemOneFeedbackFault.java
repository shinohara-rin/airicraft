package ai.moeru.airicraft.evaluator;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import ai.moeru.airicraft.systemone.TaskKernel.Feedback;
import ai.moeru.airicraft.systemone.TaskKernel.Finished;

/** Evaluator-only delivery fault. Never changes the actual motor or its release protocol. */
final class SystemOneFeedbackFault implements BiFunction<Long, List<Feedback>, List<Feedback>> {
	private record Delivery(long tick, Feedback feedback) {}
	private final List<Delivery> pending = new ArrayList<>();
	private boolean injected;

	@Override public List<Feedback> apply(Long tick, List<Feedback> incoming) {
		var delivered = new ArrayList<Feedback>();
		for (var feedback : incoming) {
			if (!injected && feedback instanceof Finished) {
				pending.add(new Delivery(tick + 20, feedback));
				pending.add(new Delivery(tick + 25, feedback));
				injected = true;
			} else delivered.add(feedback);
		}
		pending.removeIf(delivery -> {
			if (delivery.tick() > tick) return false;
			delivered.add(delivery.feedback());
			return true;
		});
		return List.copyOf(delivered);
	}
}
