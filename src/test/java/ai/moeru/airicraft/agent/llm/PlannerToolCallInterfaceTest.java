package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.AgentConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerToolCallInterfaceTest {
	@Test
	void normalPlannerRequestUsesToolsAndParsesPlaintext() throws Exception {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, """
			{
			  "choices": [
			    {
			      "message": {
			        "role": "assistant",
			        "content": "I can help with that."
			      }
			    }
			  ],
			  "usage": {
			    "prompt_tokens": 10,
			    "completion_tokens": 4,
			    "total_tokens": 14
			  }
			}
			""")) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(config(server.port()));

			LlmCallResult<PlannerResponse> result = backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Alice said just now: @agent hello", LlmMessageKind.USER_TURN)
			)));

			assertEquals("I can help with that.", result.payload().replyText());
			assertNull(result.payload().toolCall());
			JsonObject body = JsonParser.parseString(bodyRef.get()).getAsJsonObject();
			assertFalse(body.has("response_format"));
			assertEquals("auto", body.get("tool_choice").getAsString());
			JsonArray tools = body.getAsJsonArray("tools");
			assertNotNull(tools);
			assertTrue(tools.size() >= 10);
			JsonObject narrationSchema = tools.get(0).getAsJsonObject()
				.getAsJsonObject("function")
				.getAsJsonObject("parameters")
				.getAsJsonObject("properties")
				.getAsJsonObject("narration");
			assertEquals("string", narrationSchema.get("type").getAsString());
		}
	}

	@Test
	void parsesSingleToolCallWithNarration() throws Exception {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, """
			{
			  "choices": [
			    {
			      "message": {
			        "role": "assistant",
			        "content": null,
			        "tool_calls": [
			          {
			            "id": "call_inventory",
			            "type": "function",
			            "function": {
			              "name": "inspect_inventory",
			              "arguments": "{\\"narration\\":\\"I'm checking my inventory.\\",\\"prompt\\":\\"List current counts.\\"}"
			            }
			          }
			        ]
			      }
			    }
			  ],
			  "usage": {
			    "prompt_tokens": 10,
			    "completion_tokens": 4,
			    "total_tokens": 14
			  }
			}
			""")) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(config(server.port()));

			PlannerResponse response = backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Alice said just now: @agent inventory", LlmMessageKind.USER_TURN)
			))).payload();

			assertEquals("", response.replyText());
			assertEquals("inspect_inventory", response.toolCall().name());
			assertEquals("call_inventory", response.toolCall().id());
			assertEquals("I'm checking my inventory.", response.toolCall().narration());
			assertEquals("List current counts.", response.toolCall().arguments().get("prompt").getAsString());
		}
	}

	@Test
	void replayedRawToolCallUsesSynthesizedIdWhenProviderOmittedId() {
		JsonObject rawToolCall = JsonParser.parseString("""
			{
			  "type": "function",
			  "function": {
			    "name": "inspect_inventory",
			    "arguments": "{}"
			  }
			}
			""").getAsJsonObject();

		PlannerToolCall toolCall = PlannerToolCatalog.parseToolCall(rawToolCall);

		JsonObject replayed = PlannerToolCatalog.toOpenAiToolCalls(List.of(toolCall)).get(0).getAsJsonObject();
		assertEquals("call_planner_tool", toolCall.id());
		assertEquals(toolCall.id(), replayed.get("id").getAsString());
		assertEquals("inspect_inventory", replayed.getAsJsonObject("function").get("name").getAsString());
	}

	@Test
	void exposesAndParsesItemDropTools() {
		JsonArray tools = JsonParser.parseString(gson().toJson(PlannerToolCatalog.openAiTools())).getAsJsonArray();

		assertTrue(toolNames(tools).contains("drop_items"));
		assertTrue(toolNames(tools).contains("give_player"));
		PlannerToolCall dropCall = PlannerToolCatalog.parseToolCall(toolCall("drop_items", """
			{"itemId":"minecraft:oak_log","quantity":2}
			"""));
		PlannerToolCall giveCall = PlannerToolCatalog.parseToolCall(toolCall("give_player", """
			{"targetPlayer":"Alice","itemId":"minecraft:oak_log","quantity":2}
			"""));

		assertEquals("drop_items", dropCall.name());
		assertEquals("minecraft:oak_log", dropCall.arguments().get("itemId").getAsString());
		assertEquals(2, dropCall.arguments().get("quantity").getAsInt());
		assertEquals("give_player", giveCall.name());
		assertEquals("Alice", giveCall.arguments().get("targetPlayer").getAsString());
	}

	@Test
	void exposesAndParsesEntityInteractionTools() {
		JsonArray tools = JsonParser.parseString(gson().toJson(PlannerToolCatalog.openAiTools())).getAsJsonArray();

		assertTrue(toolNames(tools).contains("attack_entity"));
		assertTrue(toolNames(tools).contains("use_entity"));
		PlannerToolCall attackCall = PlannerToolCatalog.parseToolCall(toolCall("attack_entity", """
			{"entityTypeId":"minecraft:sheep","mode":"hit_once"}
			"""));
		PlannerToolCall useCall = PlannerToolCatalog.parseToolCall(toolCall("use_entity", """
			{"name":"Dinner","itemId":"minecraft:shears"}
			"""));

		assertEquals("attack_entity", attackCall.name());
		assertEquals("minecraft:sheep", attackCall.arguments().get("entityTypeId").getAsString());
		assertEquals("hit_once", attackCall.arguments().get("mode").getAsString());
		assertEquals("use_entity", useCall.name());
		assertEquals("Dinner", useCall.arguments().get("name").getAsString());
		assertEquals("minecraft:shears", useCall.arguments().get("itemId").getAsString());
	}

	@Test
	void entityInteractionToolSchemasRequireUuid() {
		JsonArray tools = JsonParser.parseString(gson().toJson(PlannerToolCatalog.openAiTools())).getAsJsonArray();
		JsonObject attackParameters = toolSchema(tools, "attack_entity");
		JsonObject useParameters = toolSchema(tools, "use_entity");

		assertRequiredUuid(attackParameters);
		assertRequiredUuid(useParameters);
	}

	@Test
	void exposesAndParsesNearbyEntityInspectionTool() {
		JsonArray tools = JsonParser.parseString(gson().toJson(PlannerToolCatalog.openAiTools())).getAsJsonArray();

		assertTrue(toolNames(tools).contains("inspect_nearby_entities"));
		PlannerToolCall inspectCall = PlannerToolCatalog.parseToolCall(toolCall("inspect_nearby_entities", """
			{"prompt":"List nearby mobs I can interact with."}
			"""));

		assertEquals("inspect_nearby_entities", inspectCall.name());
		assertEquals("List nearby mobs I can interact with.", inspectCall.arguments().get("prompt").getAsString());
	}

	@Test
	void takeALookAcceptsDirectionBlockOrPlayerTargets() {
		JsonArray tools = JsonParser.parseString(gson().toJson(PlannerToolCatalog.openAiTools())).getAsJsonArray();
		JsonObject parameters = toolSchema(tools, "take_a_look");

		assertTrue(parameters.getAsJsonObject("properties").has("direction"));
		assertTrue(parameters.getAsJsonObject("properties").has("x"));
		assertTrue(parameters.getAsJsonObject("properties").has("y"));
		assertTrue(parameters.getAsJsonObject("properties").has("z"));
		assertTrue(parameters.getAsJsonObject("properties").has("targetPlayer"));
		assertEquals("west", PlannerToolCatalog.parseToolCall(toolCall("take_a_look", """
			{"direction":"west"}
			""")).arguments().get("direction").getAsString());
		assertEquals(12, PlannerToolCatalog.parseToolCall(toolCall("take_a_look", """
			{"x":12,"y":64,"z":-8}
			""")).arguments().get("x").getAsInt());
		assertEquals("Alice", PlannerToolCatalog.parseToolCall(toolCall("take_a_look", """
			{"targetPlayer":"Alice"}
			""")).arguments().get("targetPlayer").getAsString());
	}

	@Test
	void takeALookRejectsConflictingOrPartialTargets() {
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("take_a_look", """
				{"direction":"west","targetPlayer":"Alice"}
				"""))
		);
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("take_a_look", """
				{"x":12,"z":-8}
				"""))
		);
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("take_a_look", """
				{"direction":"up"}
				"""))
		);
	}

	@Test
	void exposesCheckCraftablesInsteadOfInspectRecipes() {
		JsonArray tools = JsonParser.parseString(gson().toJson(PlannerToolCatalog.openAiTools())).getAsJsonArray();

		assertTrue(toolNames(tools).contains("check_craftables"));
		assertFalse(toolNames(tools).contains("inspect_recipes"));
		assertEquals("check_craftables", PlannerToolCatalog.parseToolCall(toolCall("check_craftables", "{}")).name());
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("inspect_recipes", "{}"))
			);
	}

	@Test
	void exposesAndParsesSmeltingTools() {
		JsonArray tools = JsonParser.parseString(gson().toJson(PlannerToolCatalog.openAiTools())).getAsJsonArray();

		assertTrue(toolNames(tools).contains("check_smeltables"));
		assertTrue(toolNames(tools).contains("smelt_items"));
		assertTrue(toolNames(tools).contains("inspect_smelting"));
		assertTrue(toolNames(tools).contains("collect_smelted_items"));
		assertTrue(toolNames(tools).contains("cancel_smelting"));
		assertEquals("check_smeltables", PlannerToolCatalog.parseToolCall(toolCall("check_smeltables", "{}")).name());
		assertEquals("inspect_smelting", PlannerToolCatalog.parseToolCall(toolCall("inspect_smelting", "{}")).name());

		PlannerToolCall smeltCall = PlannerToolCatalog.parseToolCall(toolCall("smelt_items", """
			{"optionId":"smelt:iron:nearby-1","inputQuantity":3,"fuelMode":"manual","fuelItemId":"minecraft:coal","fuelQuantity":1,"confirmationToken":"confirm-1"}
			"""));
		PlannerToolCall collectCall = PlannerToolCatalog.parseToolCall(toolCall("collect_smelted_items", """
			{"processId":"smelt-process-1","confirmationToken":"confirm-2"}
			"""));
		PlannerToolCall cancelCall = PlannerToolCatalog.parseToolCall(toolCall("cancel_smelting", """
			{"processId":"smelt-process-1"}
			"""));

		assertEquals("smelt_items", smeltCall.name());
		assertEquals("smelt:iron:nearby-1", smeltCall.arguments().get("optionId").getAsString());
		assertEquals(3, smeltCall.arguments().get("inputQuantity").getAsInt());
		assertEquals("manual", smeltCall.arguments().get("fuelMode").getAsString());
		assertEquals("minecraft:coal", smeltCall.arguments().get("fuelItemId").getAsString());
		assertEquals(1, smeltCall.arguments().get("fuelQuantity").getAsInt());
		assertEquals("confirm-1", smeltCall.arguments().get("confirmationToken").getAsString());
		assertEquals("collect_smelted_items", collectCall.name());
		assertEquals("smelt-process-1", collectCall.arguments().get("processId").getAsString());
		assertEquals("confirm-2", collectCall.arguments().get("confirmationToken").getAsString());
		assertEquals("cancel_smelting", cancelCall.name());
	}

	@Test
	void smeltingToolSchemasRequireExecutableOptionAndPositiveQuantity() {
		JsonArray tools = JsonParser.parseString(gson().toJson(PlannerToolCatalog.openAiTools())).getAsJsonArray();
		JsonObject smeltParameters = toolSchema(tools, "smelt_items");

		JsonArray required = smeltParameters.getAsJsonArray("required");
		assertNotNull(required);
		assertTrue(required.asList().stream().anyMatch(element -> "optionId".equals(element.getAsString())));
		assertTrue(required.asList().stream().anyMatch(element -> "inputQuantity".equals(element.getAsString())));
		assertTrue(smeltParameters.getAsJsonObject("properties").has("confirmationToken"));
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("smelt_items", """
				{"inputQuantity":1}
				"""))
		);
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("smelt_items", """
				{"optionId":"smelt:iron","inputQuantity":0}
				"""))
		);
	}

	@Test
	void providerToolsAreExposedAndParsed() throws Exception {
		PlannerToolRegistry registry = PlannerToolRegistry.of(new StubPlannerToolProvider(
			"recipe_search",
			"search_recipes",
			"Search recipes through a provider.",
			"Use search_recipes for recipe viewer searches.",
			"Tool result for search_recipes: provider=stub"
		));
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, """
			{
			  "choices": [
			    {
			      "message": {
			        "role": "assistant",
			        "content": null,
			        "tool_calls": [
			          {
			            "id": "call_search",
			            "type": "function",
			            "function": {
			              "name": "search_recipes",
			              "arguments": "{\\"query\\":\\"oak planks\\",\\"mode\\":\\"output\\"}"
			            }
			          }
			        ]
			      }
			    }
			  ]
			}
			""")) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(config(server.port()), registry);

			PlannerResponse response = backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Alice said just now: @agent recipes for oak planks", LlmMessageKind.USER_TURN)
			))).payload();

			JsonObject body = JsonParser.parseString(bodyRef.get()).getAsJsonObject();
			assertTrue(toolNames(body.getAsJsonArray("tools")).contains("search_recipes"));
			assertEquals("search_recipes", response.toolCall().name());
			assertEquals("oak planks", response.toolCall().arguments().get("query").getAsString());
			assertEquals("output", response.toolCall().arguments().get("mode").getAsString());
		}
	}

	@Test
	void providerReadToolsCanBeParsedInToolCallBatch() throws Exception {
		PlannerToolRegistry registry = PlannerToolRegistry.of(new StubPlannerToolProvider(
			"smelting",
			"check_smeltables",
			"Check currently executable smelting options.",
			"Use check_smeltables for furnace options.",
			"Tool result for check_smeltables: provider=stub"
		));
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, """
			{
			  "choices": [
			    {
			      "message": {
			        "role": "assistant",
			        "tool_calls": [
			          {"id":"call_craft","type":"function","function":{"name":"check_craftables","arguments":"{}"}},
			          {"id":"call_smelt","type":"function","function":{"name":"check_smeltables","arguments":"{\\"query\\":\\"iron ore\\"}"}}
			        ]
			      }
			    }
			  ]
			}
			""")) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(config(server.port()), registry);

			PlannerResponse response = backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Alice said just now: @agent what can I make?", LlmMessageKind.USER_TURN)
			))).payload();

			assertEquals(List.of("check_craftables", "check_smeltables"), response.toolCalls().stream()
				.map(PlannerToolCall::name)
				.toList());
			assertEquals("iron ore", response.toolCalls().get(1).arguments().get("query").getAsString());
		}
	}

	@Test
	void rejectsGivePlayerWithoutTargetPlayer() {
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("give_player", """
				{"itemId":"minecraft:oak_log","quantity":2}
				"""))
		);
	}

	@Test
	void rejectsEntityInteractionWithoutSelector() {
		assertThrows(com.google.gson.JsonParseException.class, () ->
			PlannerToolCatalog.parseToolCall(toolCall("attack_entity", """
				{}
				"""))
		);
	}

	@Test
	void parsesMultipleReadToolCallsInOneAssistantMessage() throws Exception {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, """
			{
			  "choices": [
			    {
			      "message": {
			        "role": "assistant",
			        "tool_calls": [
			          {"id":"call_1","type":"function","function":{"name":"inspect_inventory","arguments":"{}"}},
			          {"id":"call_2","type":"function","function":{"name":"check_craftables","arguments":"{}"}}
			        ]
			      }
			    }
			  ]
			}
			""")) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(config(server.port()));

			PlannerResponse response = backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Alice said just now: @agent inspect", LlmMessageKind.USER_TURN)
			))).payload();

			assertEquals(List.of("inspect_inventory", "check_craftables"), response.toolCalls().stream()
				.map(PlannerToolCall::name)
				.toList());
			assertEquals("inspect_inventory", response.toolCall().name());
		}
	}

	private static com.google.gson.Gson gson() {
		return new com.google.gson.Gson();
	}

	private static List<String> toolNames(JsonArray tools) {
		return tools.asList().stream()
			.map(element -> element.getAsJsonObject().getAsJsonObject("function").get("name").getAsString())
			.toList();
	}

	private static JsonObject toolSchema(JsonArray tools, String toolName) {
		return tools.asList().stream()
			.map(JsonElement::getAsJsonObject)
			.map(tool -> tool.getAsJsonObject("function"))
			.filter(function -> toolName.equals(function.get("name").getAsString()))
			.map(function -> function.getAsJsonObject("parameters"))
			.findFirst()
			.orElseThrow();
	}

	private static void assertRequiredUuid(JsonObject parameters) {
		JsonArray required = parameters.getAsJsonArray("required");
		assertNotNull(required);
		assertEquals(1, required.size());
		assertEquals("uuid", required.get(0).getAsString());
	}

	private static JsonObject toolCall(String name, String arguments) {
		JsonObject function = new JsonObject();
		function.addProperty("name", name);
		function.addProperty("arguments", arguments);
		JsonObject toolCall = new JsonObject();
		toolCall.addProperty("id", "call_" + name);
		toolCall.addProperty("type", "function");
		toolCall.add("function", function);
		return toolCall;
	}

	private record StubPlannerToolProvider(
		String id,
		String toolName,
		String description,
		String promptInstructions,
		String result
	) implements PlannerToolProvider {
		@Override
		public List<java.util.Map<String, Object>> openAiTools() {
			return List.of(PlannerToolCatalog.toolForProvider(
				toolName,
				description,
				PlannerToolCatalog.propertiesForProvider(
					PlannerToolCatalog.propForProvider("query", PlannerToolCatalog.stringForProvider("Item, recipe, or category query.")),
					PlannerToolCatalog.propForProvider("mode", PlannerToolCatalog.enumStringForProvider("Search mode.", List.of("output", "input", "all")))
				),
				List.of("query")
			));
		}

		@Override
		public boolean handles(String toolName) {
			return this.toolName.equals(PlannerToolCatalog.normalizeName(toolName));
		}

		@Override
		public java.util.concurrent.CompletableFuture<String> execute(PlannerToolCall toolCall) {
			return java.util.concurrent.CompletableFuture.completedFuture(result);
		}
	}

	@Test
	void legacyJsonContentIsPlaintextNotPlannerPayload() throws Exception {
		String legacyJson = "{\\\"replyText\\\":\\\"old\\\",\\\"intent\\\":{\\\"type\\\":\\\"reply_only\\\"},\\\"toolRequest\\\":null}";
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, """
			{
			  "choices": [
			    {
			      "message": {
			        "role": "assistant",
			        "content": "%s"
			      }
			    }
			  ]
			}
			""".formatted(legacyJson))) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(config(server.port()));

			PlannerResponse response = backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Alice said just now: @agent old style", LlmMessageKind.USER_TURN)
			))).payload();

			assertEquals("{\"replyText\":\"old\",\"intent\":{\"type\":\"reply_only\"},\"toolRequest\":null}", response.replyText());
			assertNull(response.toolCall());
		}
	}

	private static AgentConfig.LlmConfig config(int port) {
		return new AgentConfig.LlmConfig(
			"http://127.0.0.1:" + port,
			"planner-key",
			"planner-model",
			"https://api.openai.com/v1",
			"",
			"",
			15_000,
			10_000,
			8,
			65_536,
			"low",
			false
		);
	}

	private static final class TestServer implements AutoCloseable {
		private final HttpServer server;

		private TestServer(HttpServer server) {
			this.server = server;
		}

		private static TestServer start(AtomicReference<String> bodyRef, String responseBody) throws IOException {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			server.setExecutor(Executors.newCachedThreadPool());
			server.createContext("/chat/completions", exchange -> handle(exchange, bodyRef, responseBody));
			server.start();
			return new TestServer(server);
		}

		private static void handle(HttpExchange exchange, AtomicReference<String> bodyRef, String responseBody) throws IOException {
			bodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		}

		private int port() {
			return server.getAddress().getPort();
		}

		@Override
		public void close() {
			server.stop(0);
		}
	}
}
