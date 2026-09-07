package ai.moeru.airicraft.systemone.minecraft;

import ai.moeru.airicraft.systemone.TaskKernel;
import ai.moeru.airicraft.systemone.voxel.StoneAcquisition;
import ai.moeru.airicraft.systemone.voxel.VoxelObservation.Pos;
import net.minecraft.client.MinecraftClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

import static ai.moeru.airicraft.systemone.TaskKernel.*;

/** Temporary replacement entrance while callers migrate off the legacy execution runtime. */
public final class SystemOneHost {
	private final TaskKernel<StoneAcquisition.Task, StoneAcquisition.World, StoneAcquisition.Command> kernel =
		new TaskKernel<>(new StoneAcquisition(), new Limits(16, 16, 72000, 2000));
	private final MinecraftSensor sensor = new MinecraftSensor();
	private final MinecraftMotor motor = new MinecraftMotor();
	private State<StoneAcquisition.Task> state;
	private String cancellation;
	private long sequence;

	public String start(String item, int count, MinecraftClient client, long tick) {
		if (state != null && state.outcome().isEmpty()) throw new IllegalStateException("A System 1 mission is already active");
		if (!item.equals("minecraft:cobblestone")) throw new IllegalArgumentException("System 1 method not yet implemented: " + item);
		if (count < 1) throw new IllegalArgumentException("Positive quantity required");
		if (client.world == null || client.player == null) throw new IllegalStateException("World not loaded");
		sensor.clear(); cancellation = null;
		state = kernel.begin(client.world.getRegistryKey().getValue() + ":" + tick, "system-one-" + (++sequence),
			StoneAcquisition.Task.begin(count, new Pos(client.player.getBlockX(), client.player.getBlockY(), client.player.getBlockZ())), tick);
		return state.run();
	}

	public void tick(MinecraftClient client, long tick, BiConsumer<String, Map<String, Object>> trace) {
		if (state == null || state.outcome().isPresent()) return;
		List<Feedback> feedback = motor.tick(client, tick).map(List::of).orElseGet(List::of);
		if (client.world == null || client.player == null) cancellation = "world_left";
		StoneAcquisition.World observation = client.world == null || client.player == null ? null : sensor.observe(client, tick);
		var step = kernel.advance(state, observation, feedback, tick, Optional.ofNullable(cancellation));
		state = step.state();
		for (var event : step.events()) trace.accept("system_one." + event.type(), Map.of("run", state.run(), "task", event.task(), "detail", event.detail()));
		for (var effect : step.effects()) {
			trace.accept("system_one.motor_effect", Map.of("run", state.run(), "effect", effect.toString()));
			motor.apply(effect, client, tick);
		}
	}
	public void cancel(String reason) { cancellation = reason; }
	public boolean busy() { return state != null && state.outcome().isEmpty(); }
	public Optional<String> failure(String id) {
		if (state == null || !state.run().equals(id)) return Optional.of("System 1 run disappeared: " + id);
		return state.outcome().map(outcome -> outcome.kind() + ": " + outcome.evidence());
	}
	public Map<String, Object> status() {
		return state == null ? Map.of("runtime", "system_one", "state", "IDLE")
			: Map.of("runtime", "system_one", "run", state.run(), "state", state.outcome().map(o -> o.kind().name()).orElse("RUNNING"),
				"taskStack", state.stack().toString(), "outcome", state.outcome().map(Outcome::evidence).orElse(""),
				"motor", motor.status(), "knowledgeVersion", "local-stone-v1", "policyVersion", "observed-excavation-v1");
	}
}
