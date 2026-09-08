package ai.moeru.airicraft.systemone.minecraft;

import ai.moeru.airicraft.systemone.TaskKernel;
import ai.moeru.airicraft.systemone.voxel.VoxelCommand;
import ai.moeru.airicraft.systemone.voxel.StoneAcquisition;
import ai.moeru.airicraft.systemone.voxel.StoneTape;
import ai.moeru.airicraft.systemone.voxel.ProductionDomain;
import ai.moeru.airicraft.systemone.voxel.ProductionKnowledge;
import ai.moeru.airicraft.systemone.voxel.ProductionTape;
import ai.moeru.airicraft.systemone.voxel.VoxelObservation.Pos;
import net.minecraft.client.MinecraftClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static ai.moeru.airicraft.systemone.TaskKernel.*;

/** Temporary replacement entrance while callers migrate off the legacy execution runtime. */
public final class SystemOneHost {
	private TaskKernel<ProductionDomain.Task, StoneAcquisition.World, VoxelCommand> kernel;
	private ProductionKnowledge knowledge;
	private final MinecraftSensor sensor = new MinecraftSensor();
	private final MinecraftMotor motor = new MinecraftMotor();
	private State<ProductionDomain.Task> state;
	private net.minecraft.client.world.ClientWorld missionWorld;
	private String cancellation;
	private long sequence;
	private long recordingSequence;
	private StoneAcquisition.World recordedObservation;
	private List<ProductionDomain.Task> retentionTasks = List.of();
	private java.util.Set<Pos> retained = java.util.Set.of();
	private Consumer<Object> decisionRecorder = ignored -> {};
	private java.util.function.BiFunction<Long, List<Feedback>, List<Feedback>> feedbackDelivery;

	public String start(String item, int count, MinecraftClient client, long tick) {
		return start(item, count, client, tick, (at, received) -> received);
	}
	/** The evaluator can fault delivery without altering motor execution or command identity. */
	public String start(String item, int count, MinecraftClient client, long tick,
		java.util.function.BiFunction<Long, List<Feedback>, List<Feedback>> delivery) {
		if (state != null && state.outcome().isEmpty()) throw new IllegalStateException("A System 1 mission is already active");
		if (count < 1) throw new IllegalArgumentException("Positive quantity required");
		if (client.world == null || client.player == null || client.interactionManager == null) throw new IllegalStateException("World not loaded");
		feedbackDelivery = java.util.Objects.requireNonNull(delivery);
		missionWorld = client.world;
		sensor.clear(); cancellation = null; retentionTasks = List.of(); retained = java.util.Set.of();
		recordingSequence = 0; recordedObservation = null;
		knowledge = MinecraftProductionKnowledge.capture(client);
		kernel = new TaskKernel<>(new ProductionDomain(knowledge), StoneTape.LIMITS);
		state = kernel.begin(client.world.getRegistryKey().getValue() + ":" + tick, "system-one-" + (++sequence),
			new ProductionDomain.Mission(item, count, 0, 0), tick);
		decisionRecorder.accept(ProductionTape.header(state, knowledge));
		return state.run();
	}

	public void tick(MinecraftClient client, long tick, BiConsumer<String, Map<String, Object>> trace) {
		if (state == null || state.outcome().isPresent()) return;
		boolean worldInvalid = client.world != missionWorld || client.player == null || client.interactionManager == null;
		if (worldInvalid) {
			if (cancellation == null) cancellation = client.world == null || client.player == null || client.interactionManager == null ? "world_left" : "world_changed";
			sensor.clear();
		}
		List<Feedback> received = motor.tick(client, tick).map(List::of).orElseGet(List::of);
		for (var reply : received) trace.accept("system_one.motor_feedback_received", Map.of(
			"run", state.run(), "token", reply.token().toString(), "kind", reply.getClass().getSimpleName(),
			"detail", reply instanceof Finished finished ? finished.outcome().toString() : "released"));
		List<Feedback> feedback = List.copyOf(feedbackDelivery.apply(tick, received));
		var tasks = state.stack().stream().map(Frame::task).toList();
		if (!tasks.equals(retentionTasks)) { retained = ProductionDomain.retainedCells(tasks); retentionTasks = tasks; }
		StoneAcquisition.World observation = worldInvalid ? null : sensor.observe(client, tick, retained);
		var step = kernel.advance(state, observation, feedback, tick, Optional.ofNullable(cancellation));
		decisionRecorder.accept(ProductionTape.turn(++recordingSequence, recordedObservation, observation, feedback, cancellation, state, step));
		recordedObservation = observation;
		state = step.state();
		for (var event : step.events()) trace.accept("system_one." + event.type(), Map.of("run", state.run(), "task", event.task(), "detail", event.detail()));
		for (var effect : step.effects()) {
			var payload = new java.util.LinkedHashMap<String, Object>();
			payload.put("run", state.run()); payload.put("effect", effect.toString());
			if (effect instanceof Start<VoxelCommand> start) {
				payload.put("commandType", start.command().getClass().getSimpleName());
				payload.put("commandToken", start.token());
				if (start.command() instanceof VoxelCommand.Break broken) {
					payload.put("targetBlock", broken.expectedBlock()); payload.put("targetPosition", broken.target());
				}
				if (state.stack().getLast().task() instanceof ProductionDomain.Explore explore) {
					payload.put("searchArea", explore.search().areas().size());
					payload.put("searchOrigin", explore.search().origin());
					payload.put("searchSteps", explore.search().steps());
				}
			}
			trace.accept("system_one.motor_effect", payload);
			motor.apply(effect, client, tick);
		}
	}
	public void cancel(String reason) { cancellation = reason; }
	public void finishEvaluation() {
		// The evaluator can observe inventory success while crafting is still draining its grid.
		if (state != null && !state.ending()) cancel("evaluation_finished");
	}
	public void recordDecisions(Consumer<Object> recorder) { decisionRecorder = java.util.Objects.requireNonNull(recorder); }
	public boolean busy() { return state != null && state.outcome().isEmpty(); }
	public boolean succeeded(String id) { return state != null && state.run().equals(id) && state.outcome().filter(outcome -> outcome.kind() == ResultKind.SUCCEEDED).isPresent(); }
	public Optional<String> failure(String id) {
		if (state == null || !state.run().equals(id)) return Optional.of("System 1 run disappeared: " + id);
		return state.outcome().map(outcome -> outcome.kind() + ": " + outcome.evidence());
	}
	public Map<String, Object> status() {
		return state == null ? Map.of("runtime", "system_one", "state", "IDLE")
			: Map.of("runtime", "system_one", "run", state.run(), "state", state.outcome().map(o -> o.kind().name()).orElse("RUNNING"),
				"taskStack", state.stack().toString(), "outcome", state.outcome().map(Outcome::evidence).orElse(""),
				"motor", motor.status(), "knowledgeVersion", knowledge.version(), "methodVersion", ProductionTape.METHOD_VERSION,
				"policyVersion", "observed-survival-v1", "motorVersion", "production-motor-v13");
	}
}
