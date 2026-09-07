package ai.moeru.airicraft.systemone.minecraft;

import ai.moeru.airicraft.agent.baritone.LiveBaritoneFacade;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.systemone.TaskKernel;
import ai.moeru.airicraft.systemone.voxel.StoneAcquisition;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.StoneAcquisition.*;

/** One owned command, with explicit stop acknowledgement after Baritone and interaction release. */
final class MinecraftMotor {
	private final LiveBaritoneFacade pathing = new LiveBaritoneFacade();
	private final CameraController camera = new CameraController();
	private final Map<Settings.Setting<?>, Object> savedSettings = new HashMap<>();
	private Start<Command> active;
	private long started;
	private Vec3d lastPosition;
	private double travelled;
	private Outcome finishing;
	private boolean stopping;
	private boolean breaking;

	void apply(Effect<Command> effect, MinecraftClient client, long tick) {
		if (effect instanceof Stop<Command> stop) {
			if (active == null || !active.token().equals(stop.token())) throw new IllegalStateException("Stopping an unowned command");
			stopping = true;
			requestRelease(client);
			return;
		}
		if (active != null || pathing.processActive()) throw new IllegalStateException("Motor already owned");
		active = (Start<Command>) effect;
		started = tick;
		lastPosition = client.player.getPos(); travelled = 0;
		if (active.command() instanceof Navigate move) {
			var settings = BaritoneAPI.getSettings();
			set(settings.allowBreak, false); set(settings.allowBreakAnyway, java.util.List.of());
			set(settings.allowPlace, false); set(settings.allowInventory, false);
			set(settings.allowParkour, false); set(settings.allowParkourPlace, false);
			set(settings.allowWaterBucketFall, false); set(settings.avoidance, false);
			set(settings.allowSprint, false);
			set(settings.autoTool, false);
			set(settings.simplifyUnloadedYCoord, false);
			pathing.startNavigate(new GoalPosition(move.stance().x(), move.stance().y(), move.stance().z(), false));
		}
	}

	Optional<Feedback> tick(MinecraftClient client, long tick) {
		if (active == null) return Optional.empty();
		if (client.player == null || client.world == null || client.interactionManager == null) {
			stopping = true;
			requestRelease(client);
		}
		if (stopping || finishing != null) {
			if (pathing.processActive() || pathing.cancellationPending()) return Optional.empty();
			var result = stopping ? new Released(active.token()) : new Finished(active.token(), finishing);
			BaritoneAPI.getProvider().getPrimaryBaritone().getInputOverrideHandler().clearAllKeys();
			restoreSettings();
			active = null; stopping = false; finishing = null; breaking = false;
			return Optional.of(result);
		}
		if (active.command() instanceof Look look) {
			client.player.setYaw(look.yaw()); client.player.setPitch(look.pitch());
			finish(client, Outcome.success("look_applied"));
		}
		else if (active.command() instanceof Navigate move) {
			travelled += client.player.getPos().distanceTo(lastPosition);
			lastPosition = client.player.getPos();
			if (client.player.squaredDistanceTo(Vec3d.ofBottomCenter(new BlockPos(move.stance().x(), move.stance().y(), move.stance().z()))) < 0.5) {
				finish(client, Outcome.success("stance_reached"));
			}
			else if (tick - started > move.maxTicks() || travelled > move.maxTravel()) {
				finish(client, Outcome.failure("navigation_budget_exhausted"));
			}
			else if (tick - started > 10 && !pathing.processActive()) finish(client, Outcome.failure("observed_route_unavailable"));
		}
		else if (active.command() instanceof Break target) tickBreak(client, target, tick);
		return Optional.empty();
	}

	private void tickBreak(MinecraftClient client, Break target, long tick) {
		BlockPos pos = new BlockPos(target.target().x(), target.target().y(), target.target().z());
		if (tick - started > 200) { finish(client, Outcome.failure("break_budget_exhausted")); return; }
		var eye = client.player.getEyePos();
		var center = Vec3d.ofCenter(pos);
		if (eye.squaredDistanceTo(center) > 4.5 * 4.5) { finish(client, Outcome.failure("target_out_of_reach")); return; }
		camera.lookAtNow(client, center);
		var hit = client.world.raycast(new RaycastContext(eye, center, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, client.player));
		if (hit.getType() == HitResult.Type.MISS || !hit.getBlockPos().equals(pos)) {
			finish(client, breaking ? Outcome.success("target_no_longer_occludes_ray") : Outcome.failure("target_occluded"));
			return;
		}
		var state = client.world.getBlockState(pos); // First ray hit only; no hidden target-state lookup.
		if (!Registries.BLOCK.getId(state.getBlock()).toString().equals(target.expectedBlock())) {
			finish(client, Outcome.failure("observed_target_changed")); return;
		}
		if (pos.equals(client.player.getBlockPos().down())) { finish(client, Outcome.failure("standing_on_target")); return; }
		if (!breaking) {
			int bestSlot = -1;
			float bestSpeed = -1;
			for (int slot = 0; slot < 9; slot++) {
				var tool = client.player.getInventory().getStack(slot);
				if (state.isToolRequired() && !tool.isSuitableFor(state)) continue;
				float speed = tool.getMiningSpeedMultiplier(state);
				if (speed > bestSpeed || (speed == bestSpeed && tool.isEmpty())) { bestSlot = slot; bestSpeed = speed; }
			}
			if (bestSlot < 0) { finish(client, Outcome.failure("suitable_hotbar_tool_missing")); return; }
			client.player.getInventory().setSelectedSlot(bestSlot);
			breaking = client.interactionManager.attackBlock(pos, hit.getSide());
			if (!breaking) { finish(client, Outcome.failure("break_rejected")); return; }
		}
		client.interactionManager.updateBlockBreakingProgress(pos, hit.getSide());
		client.player.swingHand(Hand.MAIN_HAND);
	}

	private void finish(MinecraftClient client, Outcome result) { finishing = result; requestRelease(client); }
	Map<String, Object> status() {
		return Map.of("command", active == null ? "" : active.toString(), "stopping", stopping,
			"finishing", finishing == null ? "" : finishing.toString(), "pathingActive", pathing.processActive(),
			"releasePending", pathing.cancellationPending());
	}
	private void requestRelease(MinecraftClient client) {
		pathing.cancel();
		if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
	}
	private <V> void set(Settings.Setting<V> setting, V value) { savedSettings.put(setting, setting.value); setting.value = value; }
	@SuppressWarnings({"rawtypes", "unchecked"})
	private void restoreSettings() { savedSettings.forEach((setting, value) -> ((Settings.Setting) setting).value = value); savedSettings.clear(); }
}
