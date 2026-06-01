package ai.moeru.airicraft.agent.tasks;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.recipe.display.FurnaceRecipeDisplay;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.registry.Registries;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class SmeltingPlannerService {
	private static final int STATION_SEARCH_RADIUS = 10;
	private static final int STATION_SEARCH_VERTICAL_RADIUS = 4;
	private static final int DEFAULT_COOK_TIME_TICKS = 200;

	public String checkSmeltables(MinecraftClient client, SmeltingProcessManager manager, long tick) {
		Objects.requireNonNull(manager, "manager");
		ClientPlayerEntity player = client == null ? null : client.player;
		ClientWorld world = client == null ? null : client.world;
		if (player == null || world == null) {
			manager.registerOptions(List.of());
			return "Tool result for check_smeltables: SMELTING_UNAVAILABLE world_not_loaded";
		}

		List<SmeltableInput> inputs = smeltableInputs(player, world);
		List<SmeltingStationCandidate> candidates = manager.rankCandidates(stationCandidates(client, manager, tick));
		List<SmeltingOption> options = buildOptions(inputs, candidates, observeStations(client), manager, tick);
		manager.registerOptions(options);
		return renderCheckSmeltables(options, candidates);
	}

	public String inspectSmelting(MinecraftClient client, SmeltingProcessManager manager, long tick) {
		Objects.requireNonNull(manager, "manager");
		StringBuilder builder = new StringBuilder(manager.inspectSummary());
		List<SmeltingStationObservation> observations = observeStations(client);
		if (observations.isEmpty()) {
			return builder.append("\nnearbyFurnaces=0").toString();
		}
		builder.append("\nnearbyFurnaces=").append(observations.size());
		for (SmeltingStationObservation observation : observations) {
			SmeltingStationState state = manager.classify(observation, tick);
			builder.append("\nstation=")
				.append(observation.key().compact())
				.append(" kind=")
				.append(observation.kind().name())
				.append(" state=")
				.append(state.name())
				.append(" slots=")
				.append(slotSummary(observation.slots()));
			if (state == SmeltingStationState.OCCUPIED && observation.slots().outputCount() > 0) {
				builder.append(" untrackedReadyOutput=true");
			}
		}
		return builder.toString();
	}

	public List<SmeltingOutputReadyEvent> pollTrackedOutputReady(MinecraftClient client, SmeltingProcessManager manager, long tick) {
		Objects.requireNonNull(manager, "manager");
		if (!manager.hasTrackedProcesses()) {
			return List.of();
		}
		ArrayList<SmeltingStationObservation> observations = new ArrayList<>();
		for (SmeltingStationKey key : manager.trackedStationKeys()) {
			observeTrackedStation(client, key).ifPresent(observations::add);
		}
		return manager.markReadyOutputs(observations, tick);
	}

	public SmeltingActionResult startSmelting(MinecraftClient client, SmeltingProcessManager manager, SmeltItemsStepArgs request, long tick) {
		Objects.requireNonNull(manager, "manager");
		Objects.requireNonNull(request, "request");
		SmeltingOption option = manager.registeredOption(request.optionId());
		if (option == null) {
			return SmeltingActionResult.failed("option_not_found", "Unknown smelting optionId: " + request.optionId());
		}
		if (request.inputQuantity() > option.maxInputQuantity()) {
			return SmeltingActionResult.failed("insufficient_input", "Requested inputQuantity exceeds available input items.");
		}
		SmeltingStationObservation latest = refreshObservation(client, option.stationObservation())
			.orElse(option.stationObservation());
		return manager.startProcess(request, latest, tick);
	}

	public SmeltingActionResult collectSmelted(MinecraftClient client, SmeltingProcessManager manager, CollectSmeltedItemsStepArgs request, long tick) {
		Objects.requireNonNull(manager, "manager");
		Objects.requireNonNull(request, "request");
		SmeltingStationKey key = request.processId() == null
			? manager.confirmationStationKey(request.confirmationToken())
			: manager.processStationKey(request.processId());
		if (request.processId() != null && key != null) {
			return SmeltingActionResult.accepted(request.processId(), "accepted processId=" + request.processId());
		}
		if (key != null) {
			SmeltingStationObservation observation = refreshObservation(client, key).orElse(null);
			if (observation == null) {
				return SmeltingActionResult.failed("station_unavailable", "Smelting station is no longer loaded.");
			}
			return manager.collectUntrackedOutput(request, observation, tick);
		}
		List<SmeltingStationObservation> observations = observeStations(client).stream()
			.filter(observation -> observation.slots().outputCount() > 0)
			.sorted(Comparator.comparingDouble(SmeltingStationObservation::distance))
			.toList();
		if (observations.isEmpty()) {
			return SmeltingActionResult.failed("nothing_to_collect", "No nearby smelted output is visible.");
		}
		return manager.collectUntrackedOutput(request, observations.get(0), tick);
	}

	Optional<SmeltingStationObservation> refreshObservation(MinecraftClient client, SmeltingStationObservation previous) {
		if (previous == null || previous.key() == null) {
			return Optional.empty();
		}
		return refreshObservation(client, previous.key());
	}

	Optional<SmeltingStationObservation> refreshObservation(MinecraftClient client, SmeltingStationKey key) {
		if (key == null) {
			return Optional.empty();
		}
		return observeStations(client).stream()
			.filter(observation -> key.equals(observation.key()))
				.findFirst();
	}

	private Optional<SmeltingStationObservation> observeTrackedStation(MinecraftClient client, SmeltingStationKey key) {
		ClientPlayerEntity player = client == null ? null : client.player;
		ClientWorld world = client == null ? null : client.world;
		if (player == null || world == null || key == null) {
			return Optional.empty();
		}
		if (key.dimensionId() != null && key.dimensionId().contains("#open_screen")) {
			return observeStations(client).stream()
				.filter(observation -> key.equals(observation.key()))
				.findFirst();
		}
		if (!Objects.equals(world.getRegistryKey().getValue().toString(), key.dimensionId())) {
			return Optional.empty();
		}
		BlockPos pos = new BlockPos(key.x(), key.y(), key.z());
		if (!world.isChunkLoaded(pos)) {
			return Optional.empty();
		}
		SmeltingStationKind kind = stationKind(world.getBlockState(pos)).orElse(null);
		if (kind == null) {
			return Optional.empty();
		}
		BlockEntity blockEntity = world.getBlockEntity(pos);
		if (!(blockEntity instanceof Inventory inventory)) {
			return Optional.empty();
		}
		return Optional.of(new SmeltingStationObservation(
			key,
			kind,
			slotSnapshot(inventory, world.getBlockState(pos)),
			false,
			player.squaredDistanceTo(Vec3d.ofCenter(pos))
		));
	}

	List<SmeltingStationObservation> observeStations(MinecraftClient client) {
		ClientPlayerEntity player = client == null ? null : client.player;
		ClientWorld world = client == null ? null : client.world;
		if (player == null || world == null) {
			return List.of();
		}
		ArrayList<SmeltingStationObservation> observations = new ArrayList<>();
		if (player.currentScreenHandler instanceof AbstractFurnaceScreenHandler furnaceHandler) {
			observations.add(openScreenObservation(player, world, furnaceHandler));
		}
		BlockPos origin = player.getBlockPos();
		for (int dx = -STATION_SEARCH_RADIUS; dx <= STATION_SEARCH_RADIUS; dx++) {
			for (int dy = -STATION_SEARCH_VERTICAL_RADIUS; dy <= STATION_SEARCH_VERTICAL_RADIUS; dy++) {
				for (int dz = -STATION_SEARCH_RADIUS; dz <= STATION_SEARCH_RADIUS; dz++) {
					BlockPos pos = origin.add(dx, dy, dz);
					if (!world.isChunkLoaded(pos)) {
						continue;
					}
					SmeltingStationKind kind = stationKind(world.getBlockState(pos)).orElse(null);
					if (kind == null) {
						continue;
					}
					BlockEntity blockEntity = world.getBlockEntity(pos);
					if (!(blockEntity instanceof Inventory inventory)) {
						continue;
					}
					observations.add(new SmeltingStationObservation(
						stationKey(world, pos),
						kind,
						slotSnapshot(inventory, world.getBlockState(pos)),
						false,
						player.squaredDistanceTo(Vec3d.ofCenter(pos))
					));
				}
			}
		}
		return List.copyOf(observations);
	}

	private List<SmeltingStationCandidate> stationCandidates(MinecraftClient client, SmeltingProcessManager manager, long tick) {
		ClientPlayerEntity player = client == null ? null : client.player;
		ClientWorld world = client == null ? null : client.world;
		if (player == null || world == null) {
			return List.of();
		}
		ArrayList<SmeltingStationCandidate> candidates = new ArrayList<>();
		for (SmeltingStationObservation observation : observeStations(client)) {
			SmeltingStationState state = manager.classify(observation, tick);
			candidates.add(new SmeltingStationCandidate(
				observation.openScreen() ? SmeltingStationSource.OPEN_SCREEN : SmeltingStationSource.NEARBY_EXISTING,
				state,
				observation.kind(),
				observation.key(),
				observation.distance(),
				state == SmeltingStationState.OCCUPIED || state == SmeltingStationState.STALE
			));
		}
		chooseFurnacePlacement(client, player).ifPresent(pos -> candidates.add(new SmeltingStationCandidate(
			SmeltingStationSource.PLACE_FROM_INVENTORY,
			SmeltingStationState.EMPTY,
			SmeltingStationKind.FURNACE,
			stationKey(world, pos),
			player.squaredDistanceTo(Vec3d.ofCenter(pos)),
			false
		)));
		return List.copyOf(candidates);
	}

	private List<SmeltingOption> buildOptions(
		List<SmeltableInput> inputs,
		List<SmeltingStationCandidate> candidates,
		List<SmeltingStationObservation> observations,
		SmeltingProcessManager manager,
		long tick
	) {
		if (inputs.isEmpty() || candidates.isEmpty()) {
			return List.of();
		}
		Map<SmeltingStationKey, SmeltingStationObservation> observationsByKey = new LinkedHashMap<>();
		for (SmeltingStationObservation observation : observations) {
			observationsByKey.putIfAbsent(observation.key(), observation);
		}
		ArrayList<SmeltingOption> options = new ArrayList<>();
		int candidateIndex = 0;
		for (SmeltingStationCandidate candidate : candidates) {
			candidateIndex++;
			SmeltingStationObservation observation = observationsByKey.get(candidate.key());
			if (observation == null) {
				observation = new SmeltingStationObservation(
					candidate.key(),
					candidate.kind(),
					new SmeltingSlotSnapshot(null, 0, null, 0, null, 0, 0, DEFAULT_COOK_TIME_TICKS, false),
					false,
					candidate.distance()
				);
			}
			for (SmeltableInput input : inputs) {
				String optionId = "smelt:" + optionSegment(input.inputItemId()) + "_to_" + optionSegment(input.outputItemId())
					+ ":" + sourceSegment(candidate.source()) + "-" + candidateIndex;
				options.add(new SmeltingOption(
					optionId,
					input.inputItemId(),
					input.outputItemId(),
					input.outputCount(),
					input.availableCount(),
					input.cookTimeTicks(),
					candidate,
					observation
				));
			}
		}
		return List.copyOf(options);
	}

	private List<SmeltableInput> smeltableInputs(ClientPlayerEntity player, ClientWorld world) {
		Map<Item, Integer> inventoryCounts = inventoryCounts(player);
		if (inventoryCounts.isEmpty()) {
			return List.of();
		}
		Map<String, SmeltableInput> inputs = new LinkedHashMap<>();
		var context = SlotDisplayContexts.createParameters(world);
		for (RecipeResultCollection collection : player.getRecipeBook().getOrderedResults()) {
			for (RecipeDisplayEntry entry : collection.getAllRecipes()) {
				if (!(entry.display() instanceof FurnaceRecipeDisplay display)) {
					continue;
				}
				ItemStack result = display.result().getFirst(context);
				if (result.isEmpty()) {
					continue;
				}
				for (ItemStack ingredient : display.ingredient().getStacks(context)) {
					if (ingredient.isEmpty()) {
						continue;
					}
					int available = inventoryCounts.getOrDefault(ingredient.getItem(), 0);
					if (available <= 0) {
						continue;
					}
					String inputItemId = itemId(ingredient);
					inputs.putIfAbsent(inputItemId, new SmeltableInput(
						inputItemId,
						itemId(result),
						result.getCount(),
						available,
						display.duration() <= 0 ? DEFAULT_COOK_TIME_TICKS : display.duration()
					));
				}
			}
		}
		return List.copyOf(inputs.values());
	}

	private static SmeltingStationObservation openScreenObservation(ClientPlayerEntity player, ClientWorld world, AbstractFurnaceScreenHandler handler) {
		SmeltingStationKind kind = switch (handler) {
			case net.minecraft.screen.BlastFurnaceScreenHandler ignored -> SmeltingStationKind.BLAST_FURNACE;
			case net.minecraft.screen.SmokerScreenHandler ignored -> SmeltingStationKind.SMOKER;
			default -> SmeltingStationKind.FURNACE;
		};
		return new SmeltingStationObservation(
			new SmeltingStationKey(world.getRegistryKey().getValue() + "#open_screen", handler.syncId, 0, 0),
			kind,
			new SmeltingSlotSnapshot(
				itemId(handler.getSlot(0).getStack()),
				handler.getSlot(0).getStack().getCount(),
				itemId(handler.getSlot(1).getStack()),
				handler.getSlot(1).getStack().getCount(),
				itemId(handler.getSlot(2).getStack()),
				handler.getSlot(2).getStack().getCount(),
				0,
				DEFAULT_COOK_TIME_TICKS,
				handler.isBurning()
			),
			true,
			0.0D
		);
	}

	private static SmeltingSlotSnapshot slotSnapshot(Inventory inventory, BlockState state) {
		ItemStack input = inventory.getStack(0);
		ItemStack fuel = inventory.getStack(1);
		ItemStack output = inventory.getStack(2);
		boolean burning = state.contains(Properties.LIT) && state.get(Properties.LIT);
		return new SmeltingSlotSnapshot(
			itemId(input),
			input.getCount(),
			itemId(fuel),
			fuel.getCount(),
			itemId(output),
			output.getCount(),
			0,
			DEFAULT_COOK_TIME_TICKS,
			burning
		);
	}

	private static Optional<SmeltingStationKind> stationKind(BlockState state) {
		if (state.isOf(Blocks.FURNACE)) {
			return Optional.of(SmeltingStationKind.FURNACE);
		}
		if (state.isOf(Blocks.BLAST_FURNACE)) {
			return Optional.of(SmeltingStationKind.BLAST_FURNACE);
		}
		if (state.isOf(Blocks.SMOKER)) {
			return Optional.of(SmeltingStationKind.SMOKER);
		}
		return Optional.empty();
	}

	private static Optional<BlockPos> chooseFurnacePlacement(MinecraftClient client, ClientPlayerEntity player) {
		if (!hasFurnaceItem(player.currentScreenHandler)) {
			return Optional.empty();
		}
		BlockPos origin = player.getBlockPos();
		for (Direction direction : Direction.Type.HORIZONTAL) {
			BlockPos candidate = origin.offset(direction);
			if (canPlaceAt(client, candidate)) {
				return Optional.of(candidate.toImmutable());
			}
		}
		return Optional.empty();
	}

	private static boolean hasFurnaceItem(ScreenHandler handler) {
		if (!(handler instanceof PlayerScreenHandler)) {
			return false;
		}
		for (int slot = PlayerScreenHandler.INVENTORY_START; slot < PlayerScreenHandler.HOTBAR_END; slot++) {
			ItemStack stack = handler.getSlot(slot).getStack();
			if (!stack.isEmpty() && stack.isOf(Items.FURNACE)) {
				return true;
			}
		}
		return false;
	}

	private static boolean canPlaceAt(MinecraftClient client, BlockPos pos) {
		if (client == null || client.world == null || !client.world.isChunkLoaded(pos) || !client.world.isChunkLoaded(pos.down())) {
			return false;
		}
		BlockState target = client.world.getBlockState(pos);
		BlockState support = client.world.getBlockState(pos.down());
		return (target.isAir() || target.isReplaceable())
			&& support.isSideSolidFullSquare(client.world, pos.down(), Direction.UP);
	}

	private static Map<Item, Integer> inventoryCounts(ClientPlayerEntity player) {
		LinkedHashMap<Item, Integer> counts = new LinkedHashMap<>();
		for (int slot = 0; slot < player.getInventory().size(); slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (!stack.isEmpty()) {
				counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
			}
		}
		return Map.copyOf(counts);
	}

	private static SmeltingStationKey stationKey(ClientWorld world, BlockPos pos) {
		return new SmeltingStationKey(world.getRegistryKey().getValue().toString(), pos.getX(), pos.getY(), pos.getZ());
	}

	private static String renderCheckSmeltables(List<SmeltingOption> options, List<SmeltingStationCandidate> candidates) {
		StringBuilder builder = new StringBuilder("Tool result for check_smeltables: options=").append(options.size());
		if (options.isEmpty()) {
			return builder.append(" candidates=").append(candidates.size()).toString();
		}
		for (SmeltingOption option : options) {
			builder.append("\noptionId=")
				.append(option.optionId())
				.append(" input=")
				.append(option.inputItemId())
				.append(" maxInputQuantity=")
				.append(option.maxInputQuantity())
				.append(" output=")
				.append(option.outputItemId())
				.append("x")
				.append(option.outputCount())
				.append(" stationState=")
				.append(option.stationCandidate().state().name())
				.append(" stationSource=")
				.append(option.stationCandidate().source().name())
				.append(" confirmationRequired=")
				.append(option.stationCandidate().confirmationRequired());
		}
		return builder.toString();
	}

	private static String slotSummary(SmeltingSlotSnapshot slots) {
		return "input=" + itemSummary(slots.inputItemId(), slots.inputCount())
			+ " fuel=" + itemSummary(slots.fuelItemId(), slots.fuelCount())
			+ " output=" + itemSummary(slots.outputItemId(), slots.outputCount());
	}

	private static String itemSummary(String itemId, int count) {
		return itemId == null || count <= 0 ? "empty" : itemId + "x" + count;
	}

	private static String itemId(ItemStack stack) {
		return stack == null || stack.isEmpty() ? null : Registries.ITEM.getId(stack.getItem()).toString();
	}

	private static String optionSegment(String itemId) {
		return itemId == null ? "unknown" : itemId.toLowerCase(Locale.ROOT).replace(':', '_').replace('/', '_');
	}

	private static String sourceSegment(SmeltingStationSource source) {
		return switch (source) {
			case OPEN_SCREEN -> "open";
			case NEARBY_EXISTING -> "nearby";
			case PLACE_FROM_INVENTORY -> "carried_furnace";
		};
	}

	private record SmeltableInput(
		String inputItemId,
		String outputItemId,
		int outputCount,
		int availableCount,
		int cookTimeTicks
	) {
	}
}
