package ai.moeru.airicraft.evaluator;

import ai.moeru.airicraft.systemone.minecraft.ObservedTerrain;
import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.PathCalculationResult;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.pathing.movement.Moves;
import baritone.pathing.movement.movements.MovementTraverse;
import baritone.pathing.movement.movements.MovementFall;
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.Favoring;
import baritone.utils.pathing.MutableMoveResult;
import com.google.gson.GsonBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Live adapter contract test. Its synthetic observations are cleared before the autonomous goal starts. */
final class SystemOneTerrainProbe {
	private enum Phase { START, EMPTY_WORLD, SOLID_WORLD, RESTORE, DONE }
	private Phase phase = Phase.START;
	private CompletableFuture<Void> mutation;
	private Map<BlockPos, BlockState> pending;
	private BlockPos feet;
	private Snapshot first;
	private Snapshot second;
	private long deadline;

	boolean ready(MinecraftClient client, long tick, Path output) {
		if (phase == Phase.DONE) return true;
		if (phase == Phase.START) {
			require(ObservedTerrain.ENABLED, "System 1 terrain hooks are disabled");
			feet = client.player.getBlockPos();
			Map<BlockPos, BlockState> known = new HashMap<>();
			for (int x = -1; x <= 4; x++) for (int y = -1; y <= 2; y++) {
				known.put(feet.add(x, y, 0), y == -1 ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
			}
			ObservedTerrain.publish(known);
			change(client, Blocks.AIR.getDefaultState(), tick);
			phase = Phase.EMPTY_WORLD;
			return false;
		}
		if (!mutation.isDone() || pending.entrySet().stream().anyMatch(e -> !client.world.getBlockState(e.getKey()).equals(e.getValue()))) {
			require(tick <= deadline, "Terrain probe world update timed out");
			return false;
		}
		mutation.join();
		if (phase == Phase.EMPTY_WORLD) {
			first = inspect();
			change(client, Blocks.STONE.getDefaultState(), tick);
			phase = Phase.SOLID_WORLD;
		}
		else if (phase == Phase.SOLID_WORLD) {
			second = inspect();
			change(client, Blocks.AIR.getDefaultState(), tick);
			phase = Phase.RESTORE;
		}
		else {
			ObservedTerrain.clear();
			boolean equal = first.equals(second);
			try {
				Files.writeString(output.resolve("system-one-terrain-probe.json"), new GsonBuilder().setPrettyPrinting().create().toJson(
					Map.of("equal", equal, "hiddenCellsChanged", pending.size(), "emptyWorld", first, "solidWorld", second)));
			}
			catch (IOException exception) { throw new IllegalStateException("Cannot save terrain probe", exception); }
			require(equal, "Hidden world layout changed navigation results");
			phase = Phase.DONE;
		}
		return phase == Phase.DONE;
	}

	private void change(MinecraftClient client, BlockState state, long tick) {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		for (int x = -2; x <= 5; x++) for (int z = -2; z <= 2; z++) for (int y = 0; y <= 2; y++) {
			if (z != 0 || x < -1 || x > 4) blocks.put(feet.add(x, y, z), state);
		}
		pending = Map.copyOf(blocks);
		deadline = tick + 200;
		var worldKey = client.world.getRegistryKey();
		var server = client.getServer();
		mutation = CompletableFuture.runAsync(() -> {
			var world = server.getWorld(worldKey);
			blocks.forEach((pos, value) -> world.setBlockState(pos, value, 3));
		}, server);
	}

	private Snapshot inspect() {
		var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
		var ctx = baritone.getPlayerContext();
		var unknown = feet.south();
		var settings = BaritoneAPI.getSettings();
		boolean allowBreak = settings.allowBreak.value, allowPlace = settings.allowPlace.value, parkour = settings.allowParkour.value;
		boolean sprint = settings.allowSprint.value, water = settings.allowWaterBucketFall.value;
		try {
			settings.allowBreak.value = false; settings.allowPlace.value = false; settings.allowParkour.value = false;
			settings.allowSprint.value = false; settings.allowWaterBucketFall.value = false;
			for (boolean threaded : new boolean[]{false, true}) {
				var bsi = new BlockStateInterface(ctx, threaded);
				require(bsi.get0(unknown).isOf(Blocks.BARRIER), "Hidden terrain escaped get0");
				require(!bsi.isLoaded(unknown.getX(), unknown.getZ()) && !bsi.worldContainsLoadedChunk(unknown.getX(), unknown.getZ()), "Hidden column escaped loaded checks");
				require(bsi.get0(feet).isAir(), "Observed air became blocked");
			}
			require(!MovementHelper.fullyPassable(ctx, unknown), "Live-world passability escaped observations");
			require(!MovementHelper.canWalkOn(ctx, unknown), "Unknown terrain became footing");
			require(VecUtils.calculateBlockCenter(ctx.world(), unknown).equals(Vec3d.ofCenter(unknown)), "Aim inspected hidden shape");
			var movement = new MovementTraverse(baritone, new BetterBlockPos(feet), new BetterBlockPos(unknown));
			require(movement.updateState(new MovementState()).getStatus() == MovementStatus.UNREACHABLE, "Blocked route started preparation instead of failing");
			var placement = new MovementState();
			require(MovementHelper.attemptToPlaceABlock(placement, baritone, unknown, false, false) == MovementHelper.PlaceResult.NO_OPTION
				&& placement.getStatus() == MovementStatus.UNREACHABLE, "Navigation attempted implicit placement");
			var input = baritone.getInputOverrideHandler();
			try {
				input.setInputForceState(Input.CLICK_LEFT, true); input.setInputForceState(Input.CLICK_RIGHT, true);
				input.setInputForceState(Input.MOVE_FORWARD, true);
				require(!input.isInputForcedDown(Input.CLICK_LEFT) && !input.isInputForcedDown(Input.CLICK_RIGHT), "Baritone gained interaction authority");
				require(input.isInputForcedDown(Input.MOVE_FORWARD), "Navigation lost locomotion authority");
			}
			finally { input.clearAllKeys(); }
			verifyNoBucketSelection();
			var context = new CalculationContext(baritone, true);
			var costs = new ArrayList<String>();
			for (int x = -1; x <= 4; x++) for (var move : Moves.values()) {
				var result = new MutableMoveResult();
				move.apply(context, feet.getX() + x, feet.getY(), feet.getZ(), result);
				costs.add(x + ":" + move.name() + ":" + (result.x - feet.getX()) + "," + (result.y - feet.getY()) + "," + (result.z - feet.getZ()) + ":" + result.cost);
			}
			require(Moves.TRAVERSE_EAST.cost(context, feet.getX(), feet.getY(), feet.getZ()) < ActionCosts.COST_INF, "Known route was blocked");
			require(Moves.TRAVERSE_SOUTH.cost(context, feet.getX(), feet.getY(), feet.getZ()) >= ActionCosts.COST_INF, "Unknown route was traversable");
			var goal = new GoalBlock(feet.east(3));
			var search = new AStarPathFinder(new BetterBlockPos(feet), feet.getX(), feet.getY(), feet.getZ(), goal, new Favoring(null, context), context);
			var result = search.calculate(200, 200);
			require(result.getType() == PathCalculationResult.Type.SUCCESS_TO_GOAL, "Known route did not reach goal");
			var path = result.getPath().orElseThrow();
			return new Snapshot(costs, path.positions().stream().map(pos -> pos.subtract(feet).toShortString()).toList(),
				path.movements().stream().mapToDouble(move -> move.getCost()).sum());
		}
		finally {
			settings.allowBreak.value = allowBreak; settings.allowPlace.value = allowPlace; settings.allowParkour.value = parkour;
			settings.allowSprint.value = sprint; settings.allowWaterBucketFall.value = water;
		}
	}
	private void verifyNoBucketSelection() {
		var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
		var inventory = baritone.getPlayerContext().player().getInventory();
		int selected = inventory.getSelectedSlot();
		int bucketSlot = selected == 8 ? 7 : 8;
		var original = inventory.getStack(bucketSlot);
		var terrain = ObservedTerrain.capture();
		try {
			inventory.setStack(bucketSlot, new ItemStack(Items.BUCKET));
			var water = new HashMap<>(terrain.blocks());
			water.put(feet, Blocks.WATER.getDefaultState());
			ObservedTerrain.publish(water);
			var fall = new MovementFall(baritone, new BetterBlockPos(feet.up(3)), new BetterBlockPos(feet));
			var state = fall.updateState(new MovementState().setStatus(MovementStatus.WAITING));
			require(inventory.getSelectedSlot() == selected && !state.getInputStates().getOrDefault(Input.CLICK_RIGHT, false),
				"Falling tried to collect water or select a bucket");
		}
		finally {
			inventory.setSelectedSlot(selected); inventory.setStack(bucketSlot, original);
			ObservedTerrain.publish(terrain.blocks());
		}
	}
	private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
	private record Snapshot(List<String> movementCosts, List<String> path, double pathCost) {}
}
