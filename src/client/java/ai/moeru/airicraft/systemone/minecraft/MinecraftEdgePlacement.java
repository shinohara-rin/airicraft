package ai.moeru.airicraft.systemone.minecraft;

import ai.moeru.airicraft.systemone.TaskKernel.Outcome;
import ai.moeru.airicraft.systemone.voxel.EdgePlacement;
import ai.moeru.airicraft.systemone.voxel.VoxelCommand.Place;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import java.util.Optional;

/** Crouched approach and interaction owned entirely by the current motor command. */
final class MinecraftEdgePlacement {
	private final Place placement;
	private final ClientPlayerEntity owner;
	private final long started;
	private boolean interacted, observed;
	private long releaseStarted = -1;
	MinecraftEdgePlacement(Place placement, ClientPlayerEntity owner, long started) { this.placement = placement; this.owner = owner; this.started = started; }
	Optional<Outcome> tick(MinecraftClient client, long tick) {
		var player = client.player;
		if (player != owner || !player.isAlive()) return Optional.of(Outcome.failure("edge_player_changed"));
		if (tick - started > 160) return Optional.of(Outcome.failure("edge_placement_budget_exhausted"));
		var support = pos(placement.support()); var destination = pos(placement.destination());
		if (interacted) {
			stop(client, true);
			var hit = MinecraftInteractions.hit(client, destination);
			if (hit.isPresent() && Registries.BLOCK.getId(client.world.getBlockState(destination).getBlock()).toString().equals(placement.expectedPlacedBlock())) {
				observed = true; return Optional.of(Outcome.success("edge_placed_block_observed"));
			}
			return Optional.empty();
		}
		if (!player.isOnGround() || Math.abs(player.getY() - support.getY() - 1) > .1
			|| !EdgePlacement.withinSupportEnvelope(placement, player.getX(), player.getZ())) return Optional.of(Outcome.failure("edge_support_lost"));
		var anchor = MinecraftInteractions.hit(client, support);
		if (anchor.isEmpty() || !Registries.BLOCK.getId(client.world.getBlockState(support).getBlock()).toString().equals(placement.expectedSupport())
			|| !ObservedTerrain.get(destination).isAir()) return Optional.of(Outcome.failure("edge_observed_geometry_changed"));
		var target = EdgePlacement.edge(placement);
		if (EdgePlacement.distance(player.getX(), player.getZ(), target) > .025) {
			walk(client, target); return Optional.empty();
		}
		stop(client, true);
		var face = net.minecraft.util.math.Direction.valueOf(placement.face().name());
		var hit = MinecraftInteractions.hit(client, support, face);
		if (hit.isEmpty()) return Optional.of(Outcome.failure("edge_face_unreachable"));
		if (!MinecraftInteractions.selectItem(client, placement.item())) return Optional.of(Outcome.failure("placement_item_unavailable"));
		client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit.get()); interacted = true;
		return Optional.empty();
	}
	boolean release(MinecraftClient client, long tick) {
		if (client.player != owner || !owner.isAlive() || client.world == null || observed) { stop(client, false); return true; }
		if (releaseStarted < 0) releaseStarted = tick;
		var center = EdgePlacement.center(placement.support());
		if (owner.isOnGround() && EdgePlacement.distance(owner.getX(), owner.getZ(), center) <= .2) { stop(client, false); return true; }
		if (tick - releaseStarted >= 40 || !owner.isOnGround()) { stop(client, false); return true; }
		walk(client, center); return false;
	}
	String status() { return observed ? "placed" : releaseStarted >= 0 ? "returning_to_support" : interacted ? "waiting_for_placement" : "approaching_edge"; }
	private static void walk(MinecraftClient client, EdgePlacement.Point target) {
		stop(client, true);
		client.player.setYaw((float) Math.toDegrees(Math.atan2(-(target.x() - client.player.getX()), target.z() - client.player.getZ())));
		client.player.setPitch(65);
		// Wait until the crouch input has reached the player before moving toward the edge.
		if (client.player.isSneaking()) client.options.forwardKey.setPressed(true);
	}
	private static void stop(MinecraftClient client, boolean crouch) {
		client.options.forwardKey.setPressed(false); client.options.backKey.setPressed(false);
		client.options.leftKey.setPressed(false); client.options.rightKey.setPressed(false);
		client.options.jumpKey.setPressed(false); client.options.sprintKey.setPressed(false); client.options.sneakKey.setPressed(crouch);
	}
	private static BlockPos pos(ai.moeru.airicraft.systemone.voxel.VoxelObservation.Pos pos) { return new BlockPos(pos.x(), pos.y(), pos.z()); }
}
