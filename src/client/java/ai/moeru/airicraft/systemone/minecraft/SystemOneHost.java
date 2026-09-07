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
	private String cancellation;
	private long sequence;
	private long recordingSequence;
	private StoneAcquisition.World recordedObservation;
	private Consumer<Object> decisionRecorder = ignored -> {};

	public String start(String item, int count, MinecraftClient client, long tick) {
		if (state != null && state.outcome().isEmpty()) throw new IllegalStateException("A System 1 mission is already active");
		if (count < 1) throw new IllegalArgumentException("Positive quantity required");
		if (client.world == null || client.player == null) throw new IllegalStateException("World not loaded");
		sensor.clear(); cancellation = null;
		recordingSequence = 0; recordedObservation = null;
		knowledge = MinecraftProductionKnowledge.capture(client);
		kernel = new TaskKernel<>(new ProductionDomain(knowledge), StoneTape.LIMITS);
		state = kernel.begin(client.world.getRegistryKey().getValue() + ":" + tick, "system-one-" + (++sequence),
			ProductionDomain.Acquire.root(item, count), tick);
		decisionRecorder.accept(ProductionTape.header(state, knowledge));
		return state.run();
	}

	public void tick(MinecraftClient client, long tick, BiConsumer<String, Map<String, Object>> trace) {
		if (state == null || state.outcome().isPresent()) return;
		List<Feedback> feedback = motor.tick(client, tick).map(List::of).orElseGet(List::of);
		if (client.world == null || client.player == null) cancellation = "world_left";
		StoneAcquisition.World observation = client.world == null || client.player == null ? null : sensor.observe(client, tick);
		var step = kernel.advance(state, observation, feedback, tick, Optional.ofNullable(cancellation));
		decisionRecorder.accept(StoneTape.turn(++recordingSequence, recordedObservation, observation, feedback, cancellation, step));
		recordedObservation = observation;
		state = step.state();
		for (var event : step.events()) trace.accept("system_one." + event.type(), Map.of("run", state.run(), "task", event.task(), "detail", event.detail()));
		for (var effect : step.effects()) {
			trace.accept("system_one.motor_effect", Map.of("run", state.run(), "effect", effect.toString()));
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
	public Optional<String> failure(String id) {
		if (state == null || !state.run().equals(id)) return Optional.of("System 1 run disappeared: " + id);
		return state.outcome().map(outcome -> outcome.kind() + ": " + outcome.evidence());
	}
	public Map<String, Object> status() {
		return state == null ? Map.of("runtime", "system_one", "state", "IDLE")
			: Map.of("runtime", "system_one", "run", state.run(), "state", state.outcome().map(o -> o.kind().name()).orElse("RUNNING"),
				"taskStack", state.stack().toString(), "outcome", state.outcome().map(Outcome::evidence).orElse(""),
				"motor", motor.status(), "knowledgeVersion", knowledge.version(), "methodVersion", ProductionTape.METHOD_VERSION,
				"policyVersion", "observed-excavation-v7", "motorVersion", "production-motor-v7");
	}
}
