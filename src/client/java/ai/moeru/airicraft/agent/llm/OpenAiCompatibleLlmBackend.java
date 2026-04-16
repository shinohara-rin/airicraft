package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.tasks.AskUserStepArgs;
import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.events.EventPolicyChanges;
import ai.moeru.airicraft.agent.events.EventPolicyMatch;
import ai.moeru.airicraft.agent.events.EventPolicyRuleUpsert;
import ai.moeru.airicraft.agent.job.ActiveJobProposal;
import ai.moeru.airicraft.agent.job.ActiveJobType;
import ai.moeru.airicraft.agent.observability.AgentObservability;
import ai.moeru.airicraft.agent.observability.NoopObservability;
import ai.moeru.airicraft.agent.observability.TraceSanitizer;
import ai.moeru.airicraft.agent.tasks.CollectResourceStepArgs;
import ai.moeru.airicraft.agent.tasks.CraftRecipeStepArgs;
import ai.moeru.airicraft.agent.tasks.DropItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.EvidenceKind;
import ai.moeru.airicraft.agent.tasks.EvidenceRequirement;
import ai.moeru.airicraft.agent.tasks.FinishStepArgs;
import ai.moeru.airicraft.agent.tasks.LedgerStep;
import ai.moeru.airicraft.agent.tasks.LedgerStepKind;
import ai.moeru.airicraft.agent.tasks.LedgerStepPayload;
import ai.moeru.airicraft.agent.tasks.LedgerStepStatus;
import ai.moeru.airicraft.agent.tasks.MissionType;
import ai.moeru.airicraft.agent.tasks.NavigateToBlockKindStepArgs;
import ai.moeru.airicraft.agent.tasks.OpenContainerStepArgs;
import ai.moeru.airicraft.agent.tasks.PlaceBlockStepArgs;
import ai.moeru.airicraft.agent.tasks.TaskLedger;
import ai.moeru.airicraft.agent.tasks.TaskResourceKind;
import ai.moeru.airicraft.agent.tasks.TaskSpec;
import ai.moeru.airicraft.agent.tasks.TaskType;
import ai.moeru.airicraft.agent.tasks.TransferItemsStepArgs;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.opentelemetry.context.Context;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

public final class OpenAiCompatibleLlmBackend implements LlmBackend {
	private final AgentConfig.LlmConfig config;
	private final OpenAiCompatibleChatClient chatClient;
	private final AgentObservability observability;
	private final Deque<Object> injectedOutcomes = new ArrayDeque<>();

	public OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig config) {
		this(config, NoopObservability.INSTANCE);
	}

	public OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig config, AgentObservability observability) {
		this.config = Objects.requireNonNull(config, "config");
		this.observability = Objects.requireNonNull(observability, "observability");
		this.chatClient = new OpenAiCompatibleChatClient(config, observability);
	}

	@Override
	public synchronized LlmCallResult<PlannerResponse> generate(LlmConversation conversation) throws LlmBackendException {
		Objects.requireNonNull(conversation, "conversation");

		Object injected = injectedOutcomes.pollFirst();
		if (injected instanceof PlannerResponse plannerResponse) {
			observability.recordLlmResponse(Context.current(), null, config.model(), LlmUsageSnapshot.unknown(), plannerResponse);
			return LlmCallResult.of(plannerResponse, LlmUsageSnapshot.unknown(), null, config.model());
		}
		if (injected instanceof TimeoutException timeoutException) {
			observability.recordFailure(Context.current(), LlmFailureType.TIMEOUT.name(), timeoutException.getMessage(), timeoutException);
			throw new LlmBackendException(LlmFailureType.TIMEOUT, timeoutException.getMessage(), timeoutException);
		}

		LlmCallResult<String> rawResponse = chatClient.complete(conversation);
		PlannerResponse plannerResponse = parsePlannerResponse(rawResponse.payload());
		observability.recordLlmResponse(Context.current(), rawResponse.statusCode(), rawResponse.responseModel(), rawResponse.usage(), plannerResponse);
		return LlmCallResult.of(plannerResponse, rawResponse.usage(), rawResponse.statusCode(), rawResponse.responseModel());
	}

	@Override
	public synchronized void injectMockResponse(PlannerResponse response) {
		injectedOutcomes.addLast(Objects.requireNonNull(response, "response"));
	}

	@Override
	public synchronized void injectTimeout() {
		injectedOutcomes.addLast(new TimeoutException("Injected LLM timeout"));
	}

	@Override
	public boolean isConfigured() {
		return config.isConfigured();
	}

	private PlannerResponse parsePlannerResponse(String responseBody) throws LlmBackendException {
		try {
			JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
			JsonArray choices = root.getAsJsonArray("choices");
			if (choices == null || choices.isEmpty()) {
				throw new JsonParseException("Missing choices");
			}

			JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
			if (message == null) {
				throw new JsonParseException("Missing message");
			}

			JsonElement rawAssistantContent = OpenAiCompatibleMessageContent.rawContentForReplay(message.get("content"));
			String visibleText = OpenAiCompatibleMessageContent.extractVisibleText(message.get("content"));
			JsonObject payload = OpenAiCompatibleMessageContent.extractJsonObject(message.get("content"))
				.filter(OpenAiCompatibleLlmBackend::looksLikePlannerPayload)
				.orElse(null);
			if (payload == null) {
				Airicraft.LOGGER.info("LLM returned plain text instead of JSON, treating as reply_only content={}", summarizeForLog(visibleText));
				return new PlannerResponse(visibleText.strip(), new PlannerIntent("reply_only", null, null), null, null, rawAssistantContent);
			}
			String replyText = getString(payload, "replyText").orElse("");
			JsonObject intentObject = payload.has("intent") && payload.get("intent").isJsonObject()
				? payload.getAsJsonObject("intent")
				: new JsonObject();
			JsonObject toolRequestObject = payload.has("toolRequest") && payload.get("toolRequest").isJsonObject()
				? payload.getAsJsonObject("toolRequest")
				: null;
			JsonObject eventPolicyObject = payload.has("eventPolicyChanges") && payload.get("eventPolicyChanges").isJsonObject()
				? payload.getAsJsonObject("eventPolicyChanges")
				: null;
			ActiveJobProposal activeJob = parseActiveJobProposal(intentObject, "activeJob");
			String intentType = canonicalIntentType(getString(intentObject, "type"), activeJob);

			PlannerIntent intent = new PlannerIntent(
				intentType,
				getString(intentObject, "goalType")
					.map(value -> ai.moeru.airicraft.agent.goals.GoalType.valueOf(value.toUpperCase(Locale.ROOT)))
					.orElse(null),
				getString(intentObject, "targetPlayer").orElse(null),
				parseGoalPosition(intentObject, "position"),
				parseGoalMineSpec(intentObject, "mineSpec"),
				parseTaskSpec(intentObject, "taskSpec"),
				parseTaskLedger(intentObject, "taskLedger"),
				activeJob
			);
			PlannerToolRequest toolRequest = toolRequestObject == null
				? null
				: new PlannerToolRequest(
					getString(toolRequestObject, "type").orElse(null),
					getString(toolRequestObject, "prompt").orElse(null)
				);
			EventPolicyChanges eventPolicyChanges = parseEventPolicyChanges(eventPolicyObject);
			Airicraft.LOGGER.info(
				"Planner parsed response intentType={} goalType={} targetPlayer={} replyText={} toolRequestType={} toolPrompt={} policyChangeCount={}",
				intent.type(),
				intent.goalType(),
				intent.targetPlayer(),
				summarizeForLog(replyText),
				toolRequest == null ? null : toolRequest.type(),
				toolRequest == null ? null : summarizeForLog(toolRequest.prompt()),
				eventPolicyChanges == null ? 0 : eventPolicyChanges.upserts().size()
			);
			return new PlannerResponse(replyText, intent, toolRequest, eventPolicyChanges, rawAssistantContent);
		}
		catch (IllegalArgumentException | JsonParseException exception) {
			Airicraft.LOGGER.warn("Failed to parse planner response summary={}", TraceSanitizer.summarizeChatResponseForLog(responseBody), exception);
			observability.recordFailure(Context.current(), LlmFailureType.PARSE_ERROR.name(), "Failed to parse planner response", exception);
			throw new LlmBackendException(LlmFailureType.PARSE_ERROR, "Failed to parse planner response", exception);
		}
	}

	private static EventPolicyChanges parseEventPolicyChanges(JsonObject object) {
		if (object == null) {
			return null;
		}

		boolean clearAll = getBoolean(object, "clearAll").orElse(false);
		List<String> removeRuleIds = getStringArray(object, "removeRuleIds").orElse(List.of());
		List<EventPolicyRuleUpsert> upserts = new ArrayList<>();
		JsonArray upsertArray = object.has("upserts") && object.get("upserts").isJsonArray()
			? object.getAsJsonArray("upserts")
			: null;
		if (upsertArray != null) {
			for (JsonElement element : upsertArray) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject upsertObject = element.getAsJsonObject();
				JsonObject matchObject = upsertObject.has("match") && upsertObject.get("match").isJsonObject()
					? upsertObject.getAsJsonObject("match")
					: null;
				upserts.add(new EventPolicyRuleUpsert(
					getString(upsertObject, "ruleId").orElse(null),
					getString(upsertObject, "effect").orElse(null),
					parseEventPolicyMatch(matchObject),
					getString(upsertObject, "reason").orElse(null)
				));
			}
		}
		return new EventPolicyChanges(clearAll, removeRuleIds, upserts);
	}

	private static EventPolicyMatch parseEventPolicyMatch(JsonObject object) {
		if (object == null) {
			return null;
		}
		return new EventPolicyMatch(
			getString(object, "eventType").orElse(null),
			getString(object, "player").orElse(null),
			getString(object, "speaker").orElse(null),
			getString(object, "actor").orElse(null),
			getString(object, "itemId").orElse(null),
			getString(object, "damageTypeId").orElse(null),
			getString(object, "attackerName").orElse(null)
		);
	}

	private static Optional<String> getString(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || object.get(fieldName).isJsonNull() || !object.get(fieldName).isJsonPrimitive()) {
			return Optional.empty();
		}
		try {
			String value = object.get(fieldName).getAsString();
			return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
		}
		catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	private static GoalPosition parseGoalPosition(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || !object.get(fieldName).isJsonObject()) {
			return null;
		}
		JsonObject positionObject = object.getAsJsonObject(fieldName);
		Optional<Integer> x = getInt(positionObject, "x");
		Optional<Integer> y = getInt(positionObject, "y");
		Optional<Integer> z = getInt(positionObject, "z");
		if (x.isEmpty() || y.isEmpty() || z.isEmpty()) {
			return null;
		}
		return new GoalPosition(x.get(), y.get(), z.get(), getBoolean(positionObject, "exactY").orElse(false));
	}

	private static GoalMineSpec parseGoalMineSpec(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || !object.get(fieldName).isJsonObject()) {
			return null;
		}
		JsonObject mineSpecObject = object.getAsJsonObject(fieldName);
		Optional<List<String>> blockIds = getStringArray(mineSpecObject, "blockIds");
		Optional<Integer> quantity = getInt(mineSpecObject, "quantity");
		if (blockIds.isEmpty() || quantity.isEmpty()) {
			return null;
		}
		try {
			return new GoalMineSpec(blockIds.get(), quantity.get());
		}
		catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private static TaskSpec parseTaskSpec(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || !object.get(fieldName).isJsonObject()) {
			return null;
		}
		JsonObject taskSpecObject = object.getAsJsonObject(fieldName);
		Optional<TaskType> taskType = getString(taskSpecObject, "type").flatMap(OpenAiCompatibleLlmBackend::parseTaskType);
		Optional<TaskResourceKind> resourceKind = getString(taskSpecObject, "resourceKind").flatMap(OpenAiCompatibleLlmBackend::parseTaskResourceKind);
		Optional<Integer> quantity = getInt(taskSpecObject, "quantity");
		if (taskType.isEmpty() || resourceKind.isEmpty() || quantity.isEmpty()) {
			return null;
		}
		try {
			return new TaskSpec(taskType.get(), resourceKind.get(), quantity.get());
		}
		catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private static String canonicalIntentType(Optional<String> wireType, ActiveJobProposal activeJob) {
		if (wireType.isEmpty() || wireType.get().isBlank()) {
			return activeJob == null ? "none" : "job_update";
		}
		String normalized = wireType.get().toLowerCase(Locale.ROOT);
		if (activeJob != null && !isKnownIntentType(normalized)) {
			return "job_update";
		}
		return normalized;
	}

	private static boolean isKnownIntentType(String intentType) {
		return switch (intentType) {
			case "set_goal",
				"clear_goal",
				"job_update",
				"mission_update",
				"submit_task",
				"cancel_task",
				"reply_only",
				"ask_clarification",
				"acknowledge_failure",
				"none" -> true;
			default -> false;
		};
	}

	private static ActiveJobProposal parseActiveJobProposal(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || !object.get(fieldName).isJsonObject()) {
			return null;
		}
		JsonObject jobObject = object.getAsJsonObject(fieldName);
		Optional<ActiveJobType> type = getString(jobObject, "type").flatMap(OpenAiCompatibleLlmBackend::parseActiveJobType);
		if (type.isEmpty()) {
			return null;
		}
		return switch (type.get()) {
			case FOLLOW_PLAYER -> getString(jobObject, "targetPlayer")
				.map(ActiveJobProposal::followPlayer)
				.orElse(null);
			case NAVIGATE_TO -> Optional.ofNullable(parseGoalPosition(jobObject, "position"))
				.map(ActiveJobProposal::navigateTo)
				.orElse(null);
			case MINE_BLOCKS -> Optional.ofNullable(parseGoalMineSpec(jobObject, "mineSpec"))
				.map(ActiveJobProposal::mineBlocks)
				.orElse(null);
			case COLLECT_RESOURCE -> parseActiveCollectResourceProposal(jobObject);
			case CRAFT_RECIPE -> parseActiveCraftRecipeProposal(jobObject);
			case ASK_USER -> {
				String prompt = getString(jobObject, "askPrompt").orElseGet(() -> getString(jobObject, "prompt").orElse(null));
				yield prompt == null ? null : ActiveJobProposal.askUser(prompt);
			}
			case IDLE -> null;
		};
	}

	private static ActiveJobProposal parseActiveCollectResourceProposal(JsonObject jobObject) {
		TaskSpec nestedTaskSpec = parseTaskSpec(jobObject, "taskSpec");
		if (nestedTaskSpec != null) {
			return ActiveJobProposal.collectResource(nestedTaskSpec);
		}
		Optional<TaskResourceKind> resourceKind = getString(jobObject, "resourceKind").flatMap(OpenAiCompatibleLlmBackend::parseTaskResourceKind);
		Optional<Integer> quantity = getInt(jobObject, "quantity");
		if (resourceKind.isEmpty() || quantity.isEmpty()) {
			return null;
		}
		return ActiveJobProposal.collectResource(new TaskSpec(TaskType.COLLECT_RESOURCE, resourceKind.get(), quantity.get()));
	}

	private static ActiveJobProposal parseActiveCraftRecipeProposal(JsonObject jobObject) {
		CraftRecipeStepArgs nestedCraftRecipe = parseCraftRecipeStepArgs(jobObject, "craftRecipe");
		if (nestedCraftRecipe != null) {
			return ActiveJobProposal.craftRecipe(nestedCraftRecipe);
		}
		Optional<String> recipeId = getString(jobObject, "itemId").or(() -> getString(jobObject, "recipeId"));
		Optional<Integer> quantity = getInt(jobObject, "quantity");
		if (recipeId.isEmpty() || quantity.isEmpty()) {
			return null;
		}
		return ActiveJobProposal.craftRecipe(new CraftRecipeStepArgs(recipeId.get(), quantity.get()));
	}

	private static TaskLedger parseTaskLedger(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || !object.get(fieldName).isJsonObject()) {
			return null;
		}
		JsonObject ledgerObject = object.getAsJsonObject(fieldName);
		Optional<String> missionId = getString(ledgerObject, "missionId");
		Optional<MissionType> missionType = getString(ledgerObject, "missionType").flatMap(OpenAiCompatibleLlmBackend::parseMissionType);
		Optional<String> goalText = getString(ledgerObject, "goalText");
		Optional<List<LedgerStep>> steps = parseLedgerSteps(ledgerObject, "steps");
		if (missionId.isEmpty() || missionType.isEmpty() || goalText.isEmpty() || steps.isEmpty()) {
			return null;
		}
		return new TaskLedger(
			missionId.get(),
			missionType.get(),
			goalText.get(),
			steps.get(),
			getString(ledgerObject, "activeStepId").orElse(null),
			parseEvidenceRequirements(ledgerObject, "completionCriteria").orElse(List.of()),
			getString(ledgerObject, "replanReason").orElse(null),
			getString(ledgerObject, "plannerNotes").orElse(null)
		);
	}

	private static Optional<List<LedgerStep>> parseLedgerSteps(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || !object.get(fieldName).isJsonArray()) {
			return Optional.empty();
		}
		JsonArray array = object.getAsJsonArray(fieldName);
		ArrayList<LedgerStep> steps = new ArrayList<>(array.size());
		for (int index = 0; index < array.size(); index++) {
			if (!array.get(index).isJsonObject()) {
				return Optional.empty();
			}
			LedgerStep step = parseLedgerStep(array.get(index).getAsJsonObject());
			if (step == null) {
				return Optional.empty();
			}
			steps.add(step);
		}
		return Optional.of(List.copyOf(steps));
	}

	private static LedgerStep parseLedgerStep(JsonObject stepObject) {
		Optional<String> id = getString(stepObject, "id");
		Optional<LedgerStepKind> kind = getString(stepObject, "kind").flatMap(OpenAiCompatibleLlmBackend::parseLedgerStepKind);
		Optional<LedgerStepStatus> status = getString(stepObject, "status").flatMap(OpenAiCompatibleLlmBackend::parseLedgerStepStatus);
		Optional<Integer> retryBudget = getInt(stepObject, "retryBudget");
		if (id.isEmpty() || kind.isEmpty() || status.isEmpty() || retryBudget.isEmpty()) {
			return null;
		}
		LedgerStepPayload args = parseLedgerStepPayload(stepObject, "args");
		if (args == null) {
			return null;
		}
		return new LedgerStep(
			id.get(),
			kind.get(),
			args,
			getStringArray(stepObject, "dependsOn").orElse(List.of()),
			status.get(),
			parseEvidenceRequirements(stepObject, "expectedEvidence").orElse(List.of()),
			retryBudget.get(),
			getString(stepObject, "notes").orElse(null)
		);
	}

	private static LedgerStepPayload parseLedgerStepPayload(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || !object.get(fieldName).isJsonObject()) {
			return null;
		}
		JsonObject argsObject = object.getAsJsonObject(fieldName);
		return new LedgerStepPayload(
			parseCollectResourceStepArgs(argsObject, "collectResource"),
			parseGoalPosition(argsObject, "navigateToPosition"),
			parseNavigateToBlockKindStepArgs(argsObject, "navigateToBlockKind"),
			parseGoalMineSpec(argsObject, "mineBlocks"),
			parseCraftRecipeStepArgs(argsObject, "craftRecipe"),
			parseOpenContainerStepArgs(argsObject, "openContainer"),
			parseTransferItemsStepArgs(argsObject, "transferItems"),
			parsePlaceBlockStepArgs(argsObject, "placeBlock"),
			parseDropItemsStepArgs(argsObject, "dropItems"),
			parseAskUserStepArgs(argsObject, "askUser"),
			parseFinishStepArgs(argsObject, "finish")
		);
	}

	private static CollectResourceStepArgs parseCollectResourceStepArgs(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || !object.get(fieldName).isJsonObject()) {
			return null;
		}
		JsonObject argsObject = object.getAsJsonObject(fieldName);
		Optional<TaskResourceKind> resourceKind = getString(argsObject, "resourceKind").flatMap(OpenAiCompatibleLlmBackend::parseTaskResourceKind);
		Optional<Integer> quantity = getInt(argsObject, "quantity");
		if (resourceKind.isEmpty() || quantity.isEmpty()) {
			return null;
		}
		return new CollectResourceStepArgs(resourceKind.get(), quantity.get(), getString(argsObject, "deliveryPolicy").orElse(null));
	}

	private static NavigateToBlockKindStepArgs parseNavigateToBlockKindStepArgs(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || !object.get(fieldName).isJsonObject()) {
			return null;
		}
		JsonObject argsObject = object.getAsJsonObject(fieldName);
		Optional<List<String>> blockIds = getStringArray(argsObject, "blockIds");
		return blockIds.map(NavigateToBlockKindStepArgs::new).orElse(null);
	}

	private static CraftRecipeStepArgs parseCraftRecipeStepArgs(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || !object.get(fieldName).isJsonObject()) {
			return null;
		}
		JsonObject argsObject = object.getAsJsonObject(fieldName);
		Optional<String> recipeId = getString(argsObject, "itemId").or(() -> getString(argsObject, "recipeId"));
		Optional<Integer> quantity = getInt(argsObject, "quantity");
		return recipeId.isPresent() && quantity.isPresent() ? new CraftRecipeStepArgs(recipeId.get(), quantity.get()) : null;
	}

	private static OpenContainerStepArgs parseOpenContainerStepArgs(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || !object.get(fieldName).isJsonObject()) {
			return null;
		}
		return new OpenContainerStepArgs(getString(object.getAsJsonObject(fieldName), "containerRef").orElse(null));
	}

	private static TransferItemsStepArgs parseTransferItemsStepArgs(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || !object.get(fieldName).isJsonObject()) {
			return null;
		}
		JsonObject argsObject = object.getAsJsonObject(fieldName);
		Optional<String> direction = getString(argsObject, "direction");
		Optional<Integer> quantity = getInt(argsObject, "quantity");
		if (direction.isEmpty() || quantity.isEmpty()) {
			return null;
		}
		return new TransferItemsStepArgs(direction.get(), getStringArray(argsObject, "itemFilters").orElse(List.of()), quantity.get(), getString(argsObject, "containerRef").orElse(null));
	}

	private static PlaceBlockStepArgs parsePlaceBlockStepArgs(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || !object.get(fieldName).isJsonObject()) {
			return null;
		}
		JsonObject argsObject = object.getAsJsonObject(fieldName);
		return new PlaceBlockStepArgs(getString(argsObject, "itemId").orElse(null), parseGoalPosition(argsObject, "position"));
	}

	private static DropItemsStepArgs parseDropItemsStepArgs(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || !object.get(fieldName).isJsonObject()) {
			return null;
		}
		JsonObject argsObject = object.getAsJsonObject(fieldName);
		Optional<Integer> quantity = getInt(argsObject, "quantity");
		return quantity.map(value -> new DropItemsStepArgs(getStringArray(argsObject, "itemFilters").orElse(List.of()), value)).orElse(null);
	}

	private static AskUserStepArgs parseAskUserStepArgs(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || !object.get(fieldName).isJsonObject()) {
			return null;
		}
		return new AskUserStepArgs(getString(object.getAsJsonObject(fieldName), "prompt").orElse(null));
	}

	private static FinishStepArgs parseFinishStepArgs(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || !object.get(fieldName).isJsonObject()) {
			return null;
		}
		return new FinishStepArgs(getString(object.getAsJsonObject(fieldName), "reason").orElse(null));
	}

	private static Optional<List<EvidenceRequirement>> parseEvidenceRequirements(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || !object.get(fieldName).isJsonArray()) {
			return Optional.empty();
		}
		JsonArray array = object.getAsJsonArray(fieldName);
		ArrayList<EvidenceRequirement> requirements = new ArrayList<>(array.size());
		for (int index = 0; index < array.size(); index++) {
			if (!array.get(index).isJsonObject()) {
				return Optional.empty();
			}
			JsonObject requirementObject = array.get(index).getAsJsonObject();
			Optional<EvidenceKind> type = getString(requirementObject, "type").flatMap(OpenAiCompatibleLlmBackend::parseEvidenceKind);
			if (type.isEmpty()) {
				return Optional.empty();
			}
			requirements.add(new EvidenceRequirement(
				type.get(),
				getString(requirementObject, "resourceKind").flatMap(OpenAiCompatibleLlmBackend::parseTaskResourceKind).orElse(null),
				getInt(requirementObject, "quantity").orElse(null),
				getString(requirementObject, "stepId").orElse(null),
				getString(requirementObject, "itemId").orElse(null),
				getString(requirementObject, "detail").orElse(null)
			));
		}
		return Optional.of(List.copyOf(requirements));
	}

	private static Optional<TaskType> parseTaskType(String value) {
		try {
			return Optional.of(TaskType.valueOf(value.toUpperCase(Locale.ROOT)));
		}
		catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	private static Optional<ActiveJobType> parseActiveJobType(String value) {
		try {
			return Optional.of(ActiveJobType.valueOf(value.toUpperCase(Locale.ROOT)));
		}
		catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	private static Optional<MissionType> parseMissionType(String value) {
		try {
			return Optional.of(MissionType.valueOf(value.toUpperCase(Locale.ROOT)));
		}
		catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	private static Optional<LedgerStepKind> parseLedgerStepKind(String value) {
		try {
			return Optional.of(LedgerStepKind.valueOf(value.toUpperCase(Locale.ROOT)));
		}
		catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	private static Optional<LedgerStepStatus> parseLedgerStepStatus(String value) {
		try {
			return Optional.of(LedgerStepStatus.valueOf(value.toUpperCase(Locale.ROOT)));
		}
		catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	private static Optional<EvidenceKind> parseEvidenceKind(String value) {
		try {
			return Optional.of(EvidenceKind.valueOf(value.toUpperCase(Locale.ROOT)));
		}
		catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	private static Optional<TaskResourceKind> parseTaskResourceKind(String value) {
		try {
			return Optional.of(TaskResourceKind.valueOf(value.toUpperCase(Locale.ROOT)));
		}
		catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	private static Optional<Integer> getInt(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || object.get(fieldName).isJsonNull() || !object.get(fieldName).isJsonPrimitive()) {
			return Optional.empty();
		}
		try {
			return Optional.of(object.get(fieldName).getAsInt());
		}
		catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	private static Optional<Long> getLong(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || object.get(fieldName).isJsonNull() || !object.get(fieldName).isJsonPrimitive()) {
			return Optional.empty();
		}
		try {
			return Optional.of(object.get(fieldName).getAsLong());
		}
		catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	private static Optional<Boolean> getBoolean(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || object.get(fieldName).isJsonNull() || !object.get(fieldName).isJsonPrimitive()) {
			return Optional.empty();
		}
		try {
			return Optional.of(object.get(fieldName).getAsBoolean());
		}
		catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	private static Optional<List<String>> getStringArray(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || object.get(fieldName).isJsonNull() || !object.get(fieldName).isJsonArray()) {
			return Optional.empty();
		}
		JsonArray array = object.getAsJsonArray(fieldName);
		ArrayList<String> values = new ArrayList<>(array.size());
		for (int index = 0; index < array.size(); index++) {
			if (!array.get(index).isJsonPrimitive()) {
				return Optional.empty();
			}
			try {
				values.add(array.get(index).getAsString());
			}
			catch (RuntimeException exception) {
				return Optional.empty();
			}
		}
		return Optional.of(values);
	}

	static String stripMarkdownCodeFences(String text) {
		String trimmed = text.strip();
		if (trimmed.startsWith("```")) {
			int firstNewline = trimmed.indexOf('\n');
			if (firstNewline >= 0) {
				trimmed = trimmed.substring(firstNewline + 1);
			}
			if (trimmed.endsWith("```")) {
				trimmed = trimmed.substring(0, trimmed.length() - 3);
			}
			return trimmed.strip();
		}
		return text;
	}

	private static boolean looksLikePlannerPayload(JsonObject object) {
		return object.has("replyText")
			|| object.has("intent")
			|| object.has("toolRequest")
			|| object.has("eventPolicyChanges");
	}

	private static String summarizeForLog(String text) {
		return OpenAiCompatibleChatClient.summarizeForLog(text);
	}
}
