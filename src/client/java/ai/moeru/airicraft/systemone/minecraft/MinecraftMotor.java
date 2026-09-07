package ai.moeru.airicraft.systemone.minecraft;

import ai.moeru.airicraft.agent.baritone.LiveBaritoneFacade;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.systemone.TaskKernel;
import ai.moeru.airicraft.systemone.voxel.VoxelCommand;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelCommand.*;

/** One owned command, with explicit stop acknowledgement after Baritone and interaction release. */
final class MinecraftMotor {
	private final LiveBaritoneFacade pathing = new LiveBaritoneFacade();
	private final Map<Settings.Setting<?>, Object> savedSettings = new HashMap<>();
	private Start<VoxelCommand> active;
	private long started;
	private Vec3d lastPosition;
	private double travelled;
	private int navigationIdleTicks;
	private Outcome finishing;
	private boolean stopping;
	private boolean breaking;
	private boolean placed;
	private MinecraftCrafting crafting;
	private MinecraftSmelting smelting;
	private MinecraftEdgePlacement edgePlacement;

	void apply(Effect<VoxelCommand> effect, MinecraftClient client, long tick) {
		if (effect instanceof Stop<VoxelCommand> stop) {
			if (active == null || !active.token().equals(stop.token())) throw new IllegalStateException("Stopping an unowned command");
			stopping = true;
			requestRelease(client);
			return;
		}
		if (active != null || pathing.processActive()) throw new IllegalStateException("Motor already owned");
		active = (Start<VoxelCommand>) effect;
		started = tick;
		lastPosition = client.player.getPos(); travelled = 0;
		navigationIdleTicks = 0;
		placed = false;
		if (active.command() instanceof Craft craft) crafting = new MinecraftCrafting(craft, tick);
		if (active.command() instanceof StartSmelt || active.command() instanceof CollectSmelt) smelting = new MinecraftSmelting(active.command(), tick);
		if (active.command() instanceof EdgePlace edge) edgePlacement = new MinecraftEdgePlacement(edge.placement(), client.player, tick);
		if (active.command() instanceof Navigate move) {
			var settings = BaritoneAPI.getSettings();
			set(settings.allowBreak, false); set(settings.allowBreakAnyway, java.util.List.of());
			set(settings.allowPlace, false); set(settings.allowInventory, false);
			set(settings.allowParkour, false); set(settings.allowParkourPlace, false);
			set(settings.allowWaterBucketFall, false); set(settings.avoidance, false);
			// Keep unassisted descent within the height we can jump back up.
			set(settings.maxFallHeightNoWater, 1);
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
			if (crafting != null && !crafting.release(client)) return Optional.empty();
			if (smelting != null && !smelting.release(client)) return Optional.empty();
			if (edgePlacement != null && !edgePlacement.release(client, tick)) return Optional.empty();
			var result = stopping ? new Released(active.token()) : new Finished(active.token(), finishing);
			BaritoneAPI.getProvider().getPrimaryBaritone().getInputOverrideHandler().clearAllKeys();
			restoreSettings();
			active = null; stopping = false; finishing = null; breaking = false; crafting = null; smelting = null; edgePlacement = null;
			return Optional.of(result);
		}
		if (active.command() instanceof Look look) {
			client.player.setYaw(look.yaw()); client.player.setPitch(look.pitch());
			finish(client, Outcome.success("look_applied"));
		}
		else if (active.command() instanceof Navigate move) {
			travelled += client.player.getPos().distanceTo(lastPosition);
			lastPosition = client.player.getPos();
			var destination = new BlockPos(move.stance().x(), move.stance().y(), move.stance().z());
			if (client.player.isOnGround() && client.player.getBlockPos().equals(destination) && client.player.squaredDistanceTo(Vec3d.ofBottomCenter(destination)) < 0.5) {
				finish(client, Outcome.success("stance_reached"));
			}
			else if (tick - started > move.maxTicks() || travelled > move.maxTravel()) {
				finish(client, Outcome.failure("navigation_budget_exhausted"));
			}
			else if (pathing.processActive()) navigationIdleTicks = 0;
			// Baritone can finish its path as the player enters the goal cell, before landing.
			else if (++navigationIdleTicks > 10) finish(client, Outcome.failure("observed_route_unavailable"));
		}
		else if (active.command() instanceof Break target) tickBreak(client, target, tick);
		else if (active.command() instanceof Place target) tickPlace(client, target, tick);
		else if (edgePlacement != null) edgePlacement.tick(client, tick).ifPresent(outcome -> finish(client, outcome));
		else if (crafting != null) crafting.tick(client, tick).ifPresent(outcome -> finish(client, outcome));
		else if (smelting != null) smelting.tick(client, tick).ifPresent(outcome -> finish(client, outcome));
		return Optional.empty();
	}

	private void tickBreak(MinecraftClient client, Break target, long tick) {
		BlockPos pos = new BlockPos(target.target().x(), target.target().y(), target.target().z());
		if (tick - started > 200) { finish(client, Outcome.failure("break_budget_exhausted")); return; }
		var visible = MinecraftInteractions.hit(client, pos);
		if (visible.isEmpty()) {
			finish(client, breaking ? Outcome.success("target_no_longer_occludes_ray") : Outcome.failure("target_occluded"));
			return;
		}
		var hit = visible.get();
		var state = client.world.getBlockState(pos); // First ray hit only; no hidden target-state lookup.
		if (!Registries.BLOCK.getId(state.getBlock()).toString().equals(target.expectedBlock())) {
			finish(client, Outcome.failure("observed_target_changed")); return;
		}
		if (pos.equals(client.player.getBlockPos().down())) { finish(client, Outcome.failure("standing_on_target")); return; }
		if (!breaking) {
			int bestSlot = -1;
			float bestSpeed = -1;
			for (int slot = 0; slot < 36; slot++) {
				var tool = client.player.getInventory().getStack(slot);
				if (state.isToolRequired() && !tool.isSuitableFor(state)) continue;
				float speed = tool.getMiningSpeedMultiplier(state);
				if (speed > bestSpeed || (speed == bestSpeed && tool.isEmpty() && !client.player.getInventory().getStack(bestSlot).isEmpty())) { bestSlot = slot; bestSpeed = speed; }
			}
			if (bestSlot < 0) { finish(client, Outcome.failure("suitable_hotbar_tool_missing")); return; }
			if (!MinecraftInteractions.selectSlot(client, bestSlot)) { finish(client, Outcome.failure("tool_selection_blocked")); return; }
			breaking = client.interactionManager.attackBlock(pos, hit.getSide());
			if (!breaking) { finish(client, Outcome.failure("break_rejected")); return; }
		}
		client.interactionManager.updateBlockBreakingProgress(pos, hit.getSide());
		client.player.swingHand(Hand.MAIN_HAND);
	}
	private void tickPlace(MinecraftClient client, Place target, long tick) {
		var support = new BlockPos(target.support().x(), target.support().y(), target.support().z());
		var destination = new BlockPos(target.destination().x(), target.destination().y(), target.destination().z());
		var face = net.minecraft.util.math.Direction.valueOf(target.face().name());
		if (tick - started > 60) { finish(client, Outcome.failure("placement_timeout")); return; }
		if (placed) {
			var hit = MinecraftInteractions.hit(client, destination);
			if (hit.isPresent() && Registries.BLOCK.getId(client.world.getBlockState(destination).getBlock()).toString().equals(target.expectedPlacedBlock())) finish(client, Outcome.success("placed_block_observed"));
			return;
		}
		var hit = MinecraftInteractions.hit(client, support, face);
		if (hit.isEmpty() || client.player.getBoundingBox().intersects(new net.minecraft.util.math.Box(destination))
			|| !Registries.BLOCK.getId(client.world.getBlockState(support).getBlock()).toString().equals(target.expectedSupport())) {
			finish(client, Outcome.failure("placement_support_changed")); return;
		}
		if (!MinecraftInteractions.selectItem(client, target.item())) { finish(client, Outcome.failure("placement_item_unavailable")); return; }
		client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit.get());
		placed = true;
	}

	private void finish(MinecraftClient client, Outcome result) { finishing = result; requestRelease(client); }
	Map<String, Object> status() {
		return Map.of("command", active == null ? "" : active.toString(), "stopping", stopping,
			"finishing", finishing == null ? "" : finishing.toString(), "pathingActive", pathing.processActive(),
			"releasePending", pathing.cancellationPending(), "crafting", crafting == null ? "" : crafting.status(), "smelting", smelting == null ? "" : smelting.status(), "edgePlacement", edgePlacement == null ? "" : edgePlacement.status());
	}
	private void requestRelease(MinecraftClient client) {
		pathing.cancel();
		if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
	}
	private <V> void set(Settings.Setting<V> setting, V value) { savedSettings.put(setting, setting.value); setting.value = value; }
	@SuppressWarnings({"rawtypes", "unchecked"})
	private void restoreSettings() { savedSettings.forEach((setting, value) -> ((Settings.Setting) setting).value = value); savedSettings.clear(); }
}
