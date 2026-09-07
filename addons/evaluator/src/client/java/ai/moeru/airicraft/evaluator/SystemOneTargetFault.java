package ai.moeru.airicraft.evaluator;

import ai.moeru.airicraft.AiricraftClient;
import ai.moeru.airicraft.systemone.TaskKernel.Token;
import ai.moeru.airicraft.systemone.voxel.VoxelObservation.Pos;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** One evaluator-only world change after an actual observed-block command has started. */
final class SystemOneTargetFault {
	private long cursor;
	private CompletableFuture<Void> intervention;

	void tick(MinecraftClient client, long tick, Path output) {
		if (intervention != null) { if (intervention.isDone()) intervention.join(); return; }
		if (client.getServer() == null || client.player == null) return;
		var events = AiricraftClient.runtimeController().agentRuntime().recentEvents(cursor);
		cursor = events.latestSeqNo();
		for (var event : events.events()) {
			var data = event.payload();
			if (!event.type().equals("system_one.motor_effect") || !"Break".equals(data.get("commandType"))
				|| !(data.get("targetPosition") instanceof Pos target) || !(data.get("commandToken") instanceof Token token)
				|| !(data.get("targetBlock") instanceof String expected) || !Set.of("minecraft:grass_block","minecraft:dirt","minecraft:stone").contains(expected)) continue;
			var server = client.getServer(); var playerId = client.player.getUuid();
			intervention = CompletableFuture.runAsync(() -> {
				var player = server.getPlayerManager().getPlayer(playerId);
				if (player == null) throw new IllegalStateException("Target-change fixture player unavailable");
				var world = player.getWorld(); var pos = new BlockPos(target.x(),target.y(),target.z());
				if (!Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).toString().equals(expected))
					throw new IllegalStateException("Target changed before fixture injection");
				if (!world.setBlockState(pos,Blocks.BEDROCK.getDefaultState(),3)) throw new IllegalStateException("Target replacement failed");
				try {
					var evidence = Map.of("kind","target_replaced","issuedAtRuntimeTick",event.tick(),"requestedAtEvaluatorTick",tick,
						"commandToken",token,"target",target,"expectedBlock",expected,"replacementBlock","minecraft:bedrock","serverWorldTime",world.getTime());
					Files.writeString(output.resolve("fixture-target-change.json"),new com.google.gson.Gson().toJson(evidence));
				} catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
			},server);
			return;
		}
	}
}
