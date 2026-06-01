package ai.moeru.airicraft.agent.tasks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class SmeltingProcessManager {
	private static final long CONFIRMATION_TTL_TICKS = 20L * 60L;

	private final Map<SmeltingStationKey, TrackedProcess> processesByStation = new HashMap<>();
	private final Map<String, TrackedProcess> processesById = new HashMap<>();
	private final Map<String, Confirmation> confirmations = new HashMap<>();
	private final Map<String, SmeltingOption> optionsById = new HashMap<>();

	public void registerOptions(List<SmeltingOption> options) {
		optionsById.clear();
		if (options == null) {
			return;
		}
		for (SmeltingOption option : options) {
			if (option != null) {
				optionsById.put(option.optionId(), option);
			}
		}
	}

	public List<SmeltingOption> registeredOptions() {
		return List.copyOf(optionsById.values());
	}

	public SmeltingOption registeredOption(String optionId) {
		if (optionId == null || optionId.isBlank()) {
			return null;
		}
		return optionsById.get(optionId.trim());
	}

	public SmeltingStationKey processStationKey(String processId) {
		if (processId == null || processId.isBlank()) {
			return null;
		}
		TrackedProcess process = processesById.get(processId.trim());
		return process == null ? null : process.stationKey();
	}

	public SmeltingStationKey confirmationStationKey(String confirmationToken) {
		if (confirmationToken == null || confirmationToken.isBlank()) {
			return null;
		}
		Confirmation confirmation = confirmations.get(confirmationToken.trim());
		return confirmation == null ? null : confirmation.stationKey();
	}

	public SmeltingActionResult startRegisteredProcess(SmeltItemsStepArgs request, long tick) {
		Objects.requireNonNull(request, "request");
		SmeltingOption option = registeredOption(request.optionId());
		if (option == null) {
			return SmeltingActionResult.failed("option_not_found", "Unknown smelting optionId: " + request.optionId());
		}
		if (request.inputQuantity() > option.maxInputQuantity()) {
			return SmeltingActionResult.failed("insufficient_input", "Requested inputQuantity exceeds the registered option inventory count.");
		}
		return startProcess(request, option.stationObservation(), tick);
	}

	public SmeltingActionResult startProcess(SmeltItemsStepArgs request, SmeltingStationObservation observation, long tick) {
		Objects.requireNonNull(request, "request");
		SmeltingStationState state = classify(observation, tick);
		boolean unsafeStation = state == SmeltingStationState.OCCUPIED || state == SmeltingStationState.STALE;
		if (unsafeStation) {
			SmeltingActionResult confirmationFailure = consumeConfirmation(
				request.confirmationToken(),
				"smelt_items",
				request.optionId(),
				request.inputQuantity(),
				observation,
				tick
			);
			if (confirmationFailure != null) {
				return confirmationFailure;
			}
		}
		if (unsafeStation && request.confirmationToken() == null) {
			String token = createConfirmation("smelt_items", request.optionId(), request.inputQuantity(), observation, tick);
			return SmeltingActionResult.confirmationRequired(
				token,
				"confirmationRequired state=" + state.name() + " station=" + stationText(observation) + " slots=" + slotSummary(observation)
			);
		}
		String processId = "smelt-process-" + UUID.randomUUID();
		TrackedProcess process = new TrackedProcess(
			processId,
			observation.key(),
			observation.slots().fingerprint(),
			request.optionId(),
			request.inputQuantity(),
			tick
		);
		processesByStation.put(observation.key(), process);
		processesById.put(processId, process);
		return SmeltingActionResult.accepted(
			processId,
			"accepted processId=" + processId + " station=" + stationText(observation)
		);
	}

	public SmeltingActionResult collectUntrackedOutput(CollectSmeltedItemsStepArgs request, SmeltingStationObservation observation, long tick) {
		Objects.requireNonNull(request, "request");
		if (request.processId() != null && processesById.containsKey(request.processId())) {
			return SmeltingActionResult.accepted(request.processId(), "accepted processId=" + request.processId());
		}
		SmeltingStationState state = classify(observation, tick);
		boolean unsafeStation = state == SmeltingStationState.OCCUPIED || state == SmeltingStationState.STALE;
		if (unsafeStation) {
			SmeltingActionResult confirmationFailure = consumeConfirmation(
				request.confirmationToken(),
				"collect_smelted_items",
				request.processId(),
				0,
				observation,
				tick
			);
			if (confirmationFailure != null) {
				return confirmationFailure;
			}
		}
		if (unsafeStation && request.confirmationToken() == null) {
			String token = createConfirmation("collect_smelted_items", request.processId(), 0, observation, tick);
			return SmeltingActionResult.confirmationRequired(
				token,
				"confirmationRequired state=" + state.name() + " station=" + stationText(observation) + " slots=" + slotSummary(observation)
			);
		}
		return SmeltingActionResult.failed("nothing_to_collect", "No tracked or occupied smelting output is available.");
	}

	public SmeltingStationState classify(SmeltingStationObservation observation, long tick) {
		if (observation == null || observation.key() == null || observation.slots() == null) {
			return SmeltingStationState.STALE;
		}
		TrackedProcess process = processesByStation.get(observation.key());
		if (process != null) {
			if (Objects.equals(process.slotFingerprint(), observation.slots().fingerprint())) {
				return SmeltingStationState.AIRICRAFT_OWNED;
			}
			return SmeltingStationState.STALE;
		}
		return observation.slots().empty() ? SmeltingStationState.EMPTY : SmeltingStationState.OCCUPIED;
	}

	public List<SmeltingStationCandidate> rankCandidates(List<SmeltingStationCandidate> candidates) {
		if (candidates == null || candidates.isEmpty()) {
			return List.of();
		}
		ArrayList<SmeltingStationCandidate> ranked = new ArrayList<>(candidates);
		ranked.sort(Comparator
			.comparingInt((SmeltingStationCandidate candidate) -> sourceRank(candidate.source()))
			.thenComparingInt(candidate -> stateRank(candidate.state()))
			.thenComparingDouble(SmeltingStationCandidate::distance));
		return List.copyOf(ranked);
	}

	public String inspectSummary() {
		if (processesById.isEmpty()) {
			return "Tool result for inspect_smelting: processes=0";
		}
		StringBuilder builder = new StringBuilder("Tool result for inspect_smelting: processes=").append(processesById.size());
		for (TrackedProcess process : processesById.values()) {
			builder.append("\nprocessId=")
				.append(process.processId())
				.append(" optionId=")
				.append(process.optionId())
				.append(" station=")
				.append(process.stationKey().compact())
				.append(" inputQuantity=")
				.append(process.inputQuantity());
		}
		return builder.toString();
	}

	public boolean cancel(String processId) {
		if (processId == null || processId.isBlank()) {
			return false;
		}
		TrackedProcess process = processesById.remove(processId.trim());
		if (process == null) {
			return false;
		}
		processesByStation.remove(process.stationKey());
		return true;
	}

	private SmeltingActionResult consumeConfirmation(
		String token,
		String actionKind,
		String requestedId,
		int quantity,
		SmeltingStationObservation observation,
		long tick
	) {
		if (token == null) {
			return null;
		}
		Confirmation confirmation = confirmations.remove(token);
		if (confirmation == null
			|| tick > confirmation.expiresAtTick()
			|| !Objects.equals(confirmation.actionKind(), actionKind)
			|| !Objects.equals(confirmation.requestedId(), requestedId)
			|| confirmation.quantity() != quantity
			|| observation == null
			|| !Objects.equals(confirmation.stationKey(), observation.key())
			|| !Objects.equals(confirmation.slotFingerprint(), observation.slots().fingerprint())) {
			return SmeltingActionResult.failed("invalid_confirmation_token", "Confirmation token is invalid, expired, or no longer matches furnace contents.");
		}
		return null;
	}

	private String createConfirmation(String actionKind, String requestedId, int quantity, SmeltingStationObservation observation, long tick) {
		String token = "smelt-confirm-" + UUID.randomUUID();
		confirmations.put(token, new Confirmation(
			token,
			actionKind,
			requestedId,
			quantity,
			observation.key(),
			observation.slots().fingerprint(),
			tick + CONFIRMATION_TTL_TICKS
		));
		return token;
	}

	private static int sourceRank(SmeltingStationSource source) {
		return switch (source) {
			case OPEN_SCREEN -> 0;
			case NEARBY_EXISTING -> 1;
			case PLACE_FROM_INVENTORY -> 2;
		};
	}

	private static int stateRank(SmeltingStationState state) {
		return switch (state) {
			case EMPTY -> 0;
			case AIRICRAFT_OWNED -> 1;
			case OCCUPIED -> 2;
			case STALE -> 3;
		};
	}

	private static String stationText(SmeltingStationObservation observation) {
		return observation == null || observation.key() == null ? "unknown" : observation.key().compact();
	}

	private static String slotSummary(SmeltingStationObservation observation) {
		if (observation == null || observation.slots() == null) {
			return "unknown";
		}
		SmeltingSlotSnapshot slots = observation.slots();
		return "input=" + itemSummary(slots.inputItemId(), slots.inputCount())
			+ " fuel=" + itemSummary(slots.fuelItemId(), slots.fuelCount())
			+ " output=" + itemSummary(slots.outputItemId(), slots.outputCount());
	}

	private static String itemSummary(String itemId, int count) {
		return itemId == null || count <= 0 ? "empty" : itemId + "x" + count;
	}

	private record TrackedProcess(
		String processId,
		SmeltingStationKey stationKey,
		String slotFingerprint,
		String optionId,
		int inputQuantity,
		long startedTick
	) {
	}

	private record Confirmation(
		String token,
		String actionKind,
		String requestedId,
		int quantity,
		SmeltingStationKey stationKey,
		String slotFingerprint,
		long expiresAtTick
	) {
	}
}
