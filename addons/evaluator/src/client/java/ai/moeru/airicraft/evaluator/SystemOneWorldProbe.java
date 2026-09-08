package ai.moeru.airicraft.evaluator;

import ai.moeru.airicraft.systemone.DecisionTraceWriter;
import ai.moeru.airicraft.systemone.minecraft.ObservedTerrain;
import ai.moeru.airicraft.systemone.minecraft.SystemOneHost;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Evaluator-only transition before the scenario goal, using a separately recorded real host. */
final class SystemOneWorldProbe {
	private enum Phase { START, ACTIVE, CHANGED, RESTORING, DONE }
	private Phase phase = Phase.START;
	private final SystemOneHost host = new SystemOneHost();
	private final List<Map<String,Object>> events = new ArrayList<>();
	private DecisionTraceWriter writer;
	private ClientWorld original;
	private BlockPos origin;
	private CompletableFuture<Void> transition;
	private long deadline, changedAt = -1, settledAfter;
	private int oldTerrainCells;
	private ObservedTerrain.View capturedTerrain;
	private BlockPos sample;
	private boolean oldViewInvalidated, heldFinished;
	private boolean cancelledWithoutHelp;
	private Map<String,Object> terminal;

	boolean ready(MinecraftClient client, long tick, Path output) {
		if (phase == Phase.DONE) return true;
		if (phase == Phase.START) {
			original = client.world; origin = client.player.getBlockPos(); deadline = tick + 600;
			writer = new DecisionTraceWriter(output.resolve("system-one-world-probe-decisions.jsonl.gz"));
			host.recordDecisions(writer::accept); host.start("minecraft:cobblestone",64,client,tick,(at,replies) -> replies.stream().filter(reply -> {
				if (phase == Phase.CHANGED && reply instanceof ai.moeru.airicraft.systemone.TaskKernel.Finished) { heldFinished = true; return false; }
				return true;
			}).toList());
			phase = Phase.ACTIVE; return false;
		}
		if (tick > deadline) throw new IllegalStateException("World transition probe timed out");
		if (phase == Phase.RESTORING) {
			if (!transition.isDone() || client.world == null || !client.world.getRegistryKey().equals(original.getRegistryKey()) || client.player == null || client.player.getBlockY() != origin.getY()) return false;
			transition.join();
			if (settledAfter == 0) settledAfter = tick + 40;
			if (tick < settledAfter || !writer.finished()) return false;
			write(output.resolve("system-one-world-probe-return-view.json"),Map.of("tick",tick,"feet",origin.toShortString(),
				"chunkAvailable",client.world.getChunkManager().getChunk(origin.getX() >> 4,origin.getZ() >> 4,net.minecraft.world.chunk.ChunkStatus.FULL,false) != null,
				"light",client.world.getLightLevel(origin),"sourceDimension",client.world.getRegistryKey().getValue().toString()));
			var motor = (Map<?,?>)terminal.get("motor");
			boolean released = "".equals(motor.get("command")) && Boolean.FALSE.equals(motor.get("pathingActive")) && Boolean.FALSE.equals(motor.get("releasePending"));
			boolean passed = cancelledWithoutHelp && released && oldViewInvalidated && oldTerrainCells == 0 && Boolean.TRUE.equals(writer.status().get("complete"));
			write(output.resolve("system-one-world-probe.json"),Map.of("passed",passed,"transitionObservedAt",changedAt,"cancelledWithoutHelp",cancelledWithoutHelp,"terrainCellsAfterInvalidation",oldTerrainCells,"capturedViewInvalidated",oldViewInvalidated,"finishedReceiptWithheld",heldFinished,"terminal",terminal,"recording",writer.status(),"events",events));
			if (!passed) throw new IllegalStateException("World transition retained an old mission, motor, or terrain; see system-one-world-probe.json");
			ObservedTerrain.clear(); phase = Phase.DONE; return true;
		}
		if (phase == Phase.ACTIVE && transition != null && client.world != null && client.world != original) {
			transition.join(); changedAt = tick; phase = Phase.CHANGED;
			oldViewInvalidated = capturedTerrain.get(sample.getX(),sample.getY(),sample.getZ()).isOf(Blocks.BARRIER)
				&& !capturedTerrain.knownColumn(sample.getX(),sample.getZ()) && capturedTerrain.blocks().isEmpty();
		}
		host.tick(client,tick,(type,payload) -> events.add(Map.of("tick",tick,"type",type,"payload",payload)));
		if (phase == Phase.ACTIVE && transition == null && String.valueOf(((Map<?,?>)host.status().get("motor")).get("command")).contains("Break[")) {
			capturedTerrain = ObservedTerrain.capture(); sample = capturedTerrain.blocks().keySet().iterator().next();
			transition = teleport(client,true);
		}
		if (phase == Phase.CHANGED) {
			if (host.busy() && tick == changedAt + 20) host.cancel("world_probe_cleanup");
			if (!host.busy()) {
				terminal = host.status(); cancelledWithoutHelp = "CANCELLED".equals(terminal.get("state")) && Set.of("world_changed","world_left").contains(terminal.get("outcome"));
				oldTerrainCells = ObservedTerrain.capture().blocks().size();
				writer.close();
				write(output.resolve("system-one-world-probe-transition.json"),Map.of("transitionObservedAt",changedAt,"sourceDimension",original.getRegistryKey().getValue().toString(),"destinationDimension",client.world.getRegistryKey().getValue().toString(),"cancelledWithoutHelp",cancelledWithoutHelp,"terrainCellsAfterInvalidation",oldTerrainCells,"capturedViewInvalidated",oldViewInvalidated,"finishedReceiptWithheld",heldFinished,"terminal",terminal));
				transition = teleport(client,false); phase = Phase.RESTORING; deadline = tick + 600;
			}
		}
		return false;
	}
	private CompletableFuture<Void> teleport(MinecraftClient client, boolean outward) {
		var server=client.getServer(); var id=client.player.getUuid(); var key=outward ? World.NETHER : original.getRegistryKey();
		return CompletableFuture.runAsync(() -> {
			var world=server.getWorld(key); var player=server.getPlayerManager().getPlayer(id);
			if (world==null || player==null) throw new IllegalStateException("World probe destination unavailable");
			var destination=outward ? new BlockPos(origin.getX(),120,origin.getZ()) : origin;
			if (outward) for(int x=-3;x<=3;x++) for(int z=-3;z<=3;z++) for(int y=-1;y<=3;y++) {
				world.setBlockState(destination.add(x,y,z),(y==-1 ? Blocks.BEDROCK : Blocks.AIR).getDefaultState(),3);
			}
			if (!player.teleport(world,destination.getX()+.5,destination.getY(),destination.getZ()+.5,Set.<PositionFlag>of(),0,45,true)) throw new IllegalStateException("World probe teleport failed");
		},server);
	}
	private static void write(Path path,Object value) {
		try { Files.writeString(path,new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(value)); }
		catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
	}
}
