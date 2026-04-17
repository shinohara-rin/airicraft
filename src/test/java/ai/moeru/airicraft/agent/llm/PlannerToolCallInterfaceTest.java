package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.AgentConfig;
import com.google.gson.JsonArray;
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
	void rejectsMultipleToolCallsInOneAssistantMessage() throws Exception {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, """
			{
			  "choices": [
			    {
			      "message": {
			        "role": "assistant",
			        "tool_calls": [
			          {"id":"call_1","type":"function","function":{"name":"inspect_inventory","arguments":"{}"}},
			          {"id":"call_2","type":"function","function":{"name":"inspect_recipes","arguments":"{}"}}
			        ]
			      }
			    }
			  ]
			}
			""")) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(config(server.port()));

			LlmBackendException exception = assertThrows(LlmBackendException.class, () ->
				backend.generate(LlmConversation.of(List.of(
					LlmChatMessage.system("system"),
					LlmChatMessage.user("Alice said just now: @agent inspect", LlmMessageKind.USER_TURN)
				)))
			);
			assertEquals(LlmFailureType.PARSE_ERROR, exception.failureType());
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
