package ai.moeru.airicraft.wrapper;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpBridgeTransportTest {
	private String originalUserHome = System.getProperty("user.home");
	private String originalBridgeStateFile = System.getProperty(BridgeStateFile.PATH_PROPERTY);

	@AfterEach
	void restoreUserHome() {
		System.setProperty("user.home", originalUserHome);
		if (originalBridgeStateFile == null) {
			System.clearProperty(BridgeStateFile.PATH_PROPERTY);
		}
		else {
			System.setProperty(BridgeStateFile.PATH_PROPERTY, originalBridgeStateFile);
		}
	}

	@Test
	void getStatusFallsBackWhenBridgeIsUnavailable(@TempDir Path tempDir) throws Exception {
		writeBridgeState(tempDir);
		System.setProperty("user.home", tempDir.toString());

		HttpBridgeTransport transport = new HttpBridgeTransport();

		var status = transport.getStatus();

		assertEquals(false, status.get("available"));
		assertEquals("minecraft_unavailable", status.get("state"));
		assertFalse(Files.exists(tempDir.resolve(".airicraft/bridge-state.json")));
	}

	@Test
	void reloadPostsPayload(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/reload", 0, 200, """
				{"available":true,"reloaded":true,"agentStateReset":true,"sessionMode":"SINGLEPLAYER_LOCAL","worldLoaded":true}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.reload();

			assertEquals(true, payload.get("reloaded"));
			assertEquals(1, server.requestCount("/v1/reload"));
			assertEquals("POST", server.lastMethod("/v1/reload"));
		}
	}

	@Test
	void staleBridgeStateIsDeletedOnConnectFailure(@TempDir Path tempDir) throws Exception {
		writeBridgeState(tempDir);
		System.setProperty("user.home", tempDir.toString());

		HttpBridgeTransport transport = new HttpBridgeTransport();
		BridgeUnavailableException exception = assertThrows(BridgeUnavailableException.class, transport::listWorlds);

		assertEquals("minecraft_unavailable", exception.code());
	}

	@Test
	void joinWorldAllowsLongerBridgeResponse(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/worlds/join", 2500, 200, """
				{"started":true,"worldId":"test-world","name":"Test World","displayName":"Test World"}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();

			Map<String, Object> payload = transport.joinWorld("test-world");

			assertEquals(true, payload.get("started"));
			assertEquals(1, server.requestCount("/v1/worlds/join"));
		}
	}

	@Test
	void nonJoinRequestsStillUseShortTimeout(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/focus", 2500, 200, """
				{"available":true,"worldLoaded":true}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			BridgeUnavailableException exception = assertThrows(BridgeUnavailableException.class, transport::getFocus);

			assertEquals("bridge_io_error", exception.code());
			assertTrue(exception.getMessage().contains("timed out"));
		}
	}

	@Test
	void captureScreenshotDecodesBase64Payload(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/camera/screenshot", 0, 200, """
				{
				  "format":"png",
				  "width":854,
				  "height":480,
				  "sourceWidth":1920,
				  "sourceHeight":1080,
				  "capturedAtMs":123456789,
				  "imageBase64":"%s"
				}
				""".formatted(Base64.getEncoder().encodeToString(new byte[]{5, 6, 7})));
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			CapturedImage capturedImage = transport.captureScreenshot();

			assertEquals("png", capturedImage.format());
			assertEquals(854, capturedImage.width());
			assertEquals(480, capturedImage.height());
			assertEquals(1920, capturedImage.sourceWidth());
			assertEquals(1080, capturedImage.sourceHeight());
			assertEquals(123456789L, capturedImage.capturedAtMs());
			assertArrayEquals(new byte[]{5, 6, 7}, capturedImage.bytes());
		}
	}

	@Test
	void captureScreenshotUsesLongerTimeout(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/camera/screenshot", 2500, 200, """
				{
				  "format":"png",
				  "width":854,
				  "height":480,
				  "sourceWidth":854,
				  "sourceHeight":480,
				  "capturedAtMs":1,
				  "imageBase64":"AQ=="
				}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			CapturedImage capturedImage = transport.captureScreenshot();

			assertEquals("png", capturedImage.format());
			assertEquals(1, server.requestCount("/v1/camera/screenshot"));
		}
	}

	@Test
	void describeVisionParsesTextPayload(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/vision/describe", 0, 200, """
				{
				  "format":"text",
				  "capturedAtMs":123,
				  "model":"gpt-4.1-mini",
				  "description":"A grassy hill under open sky."
				}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			VisionDescriptionResult result = transport.describeVision("Describe the scene.");

			assertEquals("text", result.format());
			assertEquals(123L, result.capturedAtMs());
			assertEquals("gpt-4.1-mini", result.model());
			assertEquals("A grassy hill under open sky.", result.description());
		}
	}

	@Test
	void describeVisionUsesLongerTimeout(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/vision/describe", 2500, 200, """
				{
				  "format":"text",
				  "capturedAtMs":1,
				  "model":"gpt-4.1-mini",
				  "description":"ok"
				}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			VisionDescriptionResult result = transport.describeVision(null);

			assertEquals("text", result.format());
			assertEquals(1, server.requestCount("/v1/vision/describe"));
		}
	}

	@Test
	void listRecentAgentEventsReadsPayload(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/events/recent", 0, 200, """
				{"available":true,"oldestSeqNo":10,"latestSeqNo":12,"truncated":false,"events":[]}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.listRecentAgentEvents(11L);

			assertEquals(true, payload.get("available"));
			assertEquals(12, ((Number) payload.get("latestSeqNo")).intValue());
			assertEquals(1, server.requestCount("/v1/agent/events/recent"));
		}
	}

	@Test
	void listAgentToolsReadsCompleteSurface(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/tools", 0, 200, """
				{"available":true,"codexDriverActive":true,"toolCount":1,"tools":[{"type":"function","function":{"name":"navigate_to"}}]}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.listAgentTools();

			assertEquals(true, payload.get("codexDriverActive"));
			assertEquals(1, ((Number) payload.get("toolCount")).intValue());
			assertEquals("GET", server.lastMethod("/v1/agent/tools"));
		}
	}

	@Test
	void callAgentToolPostsAndAllowsLongRunningResult(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/tools", 2500, 200, """
				{"available":true,"codexDriverActive":true,"toolName":"inspect_inventory","result":"ok","imageAttached":false}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.callAgentTool("inspect_inventory", Map.of(), 30_000);

			assertEquals("ok", payload.get("result"));
			assertEquals("POST", server.lastMethod("/v1/agent/tools"));
		}
	}

	@Test
	void getAgentDebugStateReadsPayload(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/debug/state", 0, 200, """
				{"available":true,"dialogueState":{"pendingReply":true},"plannerAttempts":[],"timelineTail":[]}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.getAgentDebugState();

			assertEquals(true, payload.get("available"));
			assertEquals(1, server.requestCount("/v1/agent/debug/state"));
		}
	}

	@Test
	void listAgentDebugTimelineReadsPayload(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/debug/timeline", 0, 200, """
				{"available":true,"oldestEntryId":1,"latestEntryId":3,"truncated":false,"entries":[]}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.listAgentDebugTimeline(2L);

			assertEquals(true, payload.get("available"));
			assertEquals(1, server.requestCount("/v1/agent/debug/timeline"));
		}
	}

	@Test
	void getClientTickDebugStateUsesStateEndpoint(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/debug/ticks/state", 0, 200, """
				{"available":true,"phase":"PAUSED","pauseEpoch":2,"clientTickId":40}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			Map<String, Object> payload = new HttpBridgeTransport().getClientTickDebugState();

			assertEquals("PAUSED", payload.get("phase"));
			assertEquals("GET", server.lastMethod("/v1/agent/debug/ticks/state"));
		}
	}

	@Test
	void stepClientTickPostsExactSessionIdentity(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/debug/ticks/step", 0, 200, """
				{"available":true,"debugSessionId":"debug-1","pauseEpoch":3}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			Map<String, Object> payload = new HttpBridgeTransport().stepClientTick("debug-1", 2L);

			assertEquals(3, payload.get("pauseEpoch"));
			assertEquals("POST", server.lastMethod("/v1/agent/debug/ticks/step"));
			String requestBody = server.lastRequestBody("/v1/agent/debug/ticks/step");
			assertTrue(requestBody.contains("\"debugSessionId\":\"debug-1\""));
			assertTrue(requestBody.contains("\"pauseEpoch\":2"));
		}
	}

	@Test
	void queryClientTickWorldPostsTypedRegionRequest(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/debug/world/query", 0, 200, """
				{"available":true,"snapshotId":"snapshot-2","blocks":[]}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());
			Map<String, Object> request = new java.util.LinkedHashMap<>();
			request.put("snapshotId", "snapshot-2");
			request.put("operation", "scan_box");
			request.put("minX", -4);
			request.put("maxX", 8);

			Map<String, Object> payload = new HttpBridgeTransport().queryClientTickWorld(request);

			assertEquals("snapshot-2", payload.get("snapshotId"));
			assertEquals("POST", server.lastMethod("/v1/agent/debug/world/query"));
			assertEquals(
				"{\"snapshotId\":\"snapshot-2\",\"operation\":\"scan_box\",\"minX\":-4,\"maxX\":8}",
				server.lastRequestBody("/v1/agent/debug/world/query")
			);
		}
	}

	@Test
	void clientTickTraceMethodsUseTypedBridgeEndpoints(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/debug/trace", 0, 200, """
				{"available":true,"active":false}
				""");
			server.respondJson("/v1/agent/debug/trace/start", 0, 200, """
				{"available":true,"active":true,"traceId":"trace-1"}
				""");
			server.respondJson("/v1/agent/debug/trace/records", 0, 200, """
				{"available":true,"traceId":"trace-1","records":[]}
				""");
			server.respondJson("/v1/agent/debug/trace/stop", 0, 200, """
				{"available":true,"active":false,"traceId":"trace-1"}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());
			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> startRequest = new java.util.LinkedHashMap<>();
			startRequest.put("infos", java.util.List.of("metadata", "player_state"));
			startRequest.put("windowTicks", 40);
			startRequest.put("once", true);

			assertEquals(false, transport.getClientTickTraceStatus().get("active"));
			assertEquals("trace-1", transport.startClientTickTrace(startRequest).get("traceId"));
			assertEquals(
				"{\"infos\":[\"metadata\",\"player_state\"],\"windowTicks\":40,\"once\":true}",
				server.lastRequestBody("/v1/agent/debug/trace/start")
			);
			transport.listClientTickTraceRecords("trace-1", 20L, 8, true);
			assertEquals(
				"{\"traceId\":\"trace-1\",\"sinceClientTickId\":20,\"limit\":8,\"includeImageBytes\":true}",
				server.lastRequestBody("/v1/agent/debug/trace/records")
			);
			assertEquals(false, transport.stopClientTickTrace("trace-1").get("active"));
			assertEquals("{\"traceId\":\"trace-1\"}", server.lastRequestBody("/v1/agent/debug/trace/stop"));
		}
	}

	@Test
	void openAgentSessionLanPostsPayload(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/session/open-lan", 0, 200, """
				{"opened":true,"port":25565}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.openAgentSessionLan();

			assertEquals(true, payload.get("opened"));
			assertEquals(25565, ((Number) payload.get("port")).intValue());
			assertEquals(1, server.requestCount("/v1/agent/session/open-lan"));
			assertEquals("POST", server.lastMethod("/v1/agent/session/open-lan"));
		}
	}

	@Test
	void sendAgentDebugChatPostsJsonPayload(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/debug/chat", 0, 200, """
				{"available":true,"accepted":true,"senderName":"Player688","message":"@agent get me 4 wood logs"}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.sendAgentDebugChat("@agent get me 4 wood logs");

			assertEquals(true, payload.get("accepted"));
			assertEquals(1, server.requestCount("/v1/agent/debug/chat"));
			assertEquals("POST", server.lastMethod("/v1/agent/debug/chat"));
			assertEquals("{\"message\":\"@agent get me 4 wood logs\"}", server.lastRequestBody("/v1/agent/debug/chat"));
		}
	}

	@Test
	void getAgentTasksReadsPayload(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/tasks", 0, 200, """
				{"available":true,"task":{"state":"RUNNING","spec":{"type":"COLLECT_RESOURCE","resourceKind":"WOOD_LOGS","quantity":4}}}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.getAgentTasks();

			assertEquals(true, payload.get("available"));
			assertEquals(1, server.requestCount("/v1/agent/tasks"));
		}
	}

	@Test
	void resumeAgentTaskPostsExactSafetyHoldId(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/tasks/resume", 0, 200, """
				{"available":true,"resumed":true,"holdId":"hold-42","reflex":{"state":"IDLE"}}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.resumeAgentTask("hold-42");

			assertEquals(true, payload.get("resumed"));
			assertEquals("hold-42", payload.get("holdId"));
			assertEquals("POST", server.lastMethod("/v1/agent/tasks/resume"));
			assertEquals("{\"holdId\":\"hold-42\"}", server.lastRequestBody("/v1/agent/tasks/resume"));
		}
	}

	@Test
	void getAgentLedgerReadsPayload(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/ledger", 0, 200, """
				{"available":true,"ledger":{"missionId":"mission-wood-1","activeStepId":"collect_logs"}}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.getAgentLedger();

			assertEquals(true, payload.get("available"));
			assertEquals(1, server.requestCount("/v1/agent/ledger"));
		}
	}

	@Test
	void getAgentEvidenceReadsPayload(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/evidence", 0, 200, """
				{"available":true,"evidence":{"dimension":"minecraft:overworld"}}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.getAgentEvidence();

			assertEquals(true, payload.get("available"));
			assertEquals(1, server.requestCount("/v1/agent/evidence"));
		}
	}

	@Test
	void getAgentStepExecutionReadsPayload(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/step-execution", 0, 200, """
				{"available":true,"stepExecution":{"stepId":"craft_sticks","status":"RUNNING"}}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.getAgentStepExecution();

			assertEquals(true, payload.get("available"));
			assertEquals(1, server.requestCount("/v1/agent/step-execution"));
		}
	}

	@Test
	void submitAgentMissionPostsPayload(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/tasks", 0, 200, """
				{"available":true,"task":{"state":"QUEUED"}}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.submitAgentMission(Map.of(
				"type", "COLLECT_RESOURCE",
				"resourceKind", "WOOD_LOGS",
				"quantity", 4
			));

			assertEquals(true, payload.get("available"));
			assertEquals(1, server.requestCount("/v1/agent/tasks"));
			assertEquals("POST", server.lastMethod("/v1/agent/tasks"));
		}
	}

	@Test
	void submitAgentTaskPostsJsonPayload(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/tasks", 0, 200, """
				{"available":true,"task":{"state":"QUEUED"}}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.submitAgentTask(Map.of(
				"type", "COLLECT_RESOURCE",
				"resourceKind", "WOOD_LOGS",
				"quantity", 4
			));

			assertEquals(true, payload.get("available"));
			assertEquals(1, server.requestCount("/v1/agent/tasks"));
		}
	}

	@Test
	void startAgentActionGoalPostsResourceGoalPayload(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/action-goals", 0, 200, """
				{"available":true,"execution":{"executionId":"graph-raw-iron","state":"RESOLVING"}}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.startAgentActionGoal(Map.of(
				"kind", "resource_collection",
				"resourceKind", "RAW_IRON",
				"quantity", 3
			));

			assertEquals(true, payload.get("available"));
			assertEquals(1, server.requestCount("/v1/agent/action-goals"));
			assertEquals("POST", server.lastMethod("/v1/agent/action-goals"));
		}
	}

	@Test
	void actionGoalListingAndIdOperationsUseQueryParameters(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/action-goals", 0, 200, """
				{"available":true,"executions":[],"watches":[]}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			transport.listAgentActionGoals();
			assertEquals("list=true", server.lastQuery("/v1/agent/action-goals"));
			transport.getAgentActionGoal("graph one");
			assertEquals("execution-id=graph+one", server.lastQuery("/v1/agent/action-goals"));
			transport.cancelAgentActionGoal("graph one");
			assertEquals("DELETE", server.lastMethod("/v1/agent/action-goals"));
			assertEquals("execution-id=graph+one", server.lastQuery("/v1/agent/action-goals"));
		}
	}

	@Test
	void agentDebugCompactUsesLongerTimeout(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/debug/compact", 2500, 200, """
				{"available":true,"started":true,"completed":true,"timeoutMs":5000}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.triggerAgentCompaction(true, 5000);

			assertEquals(true, payload.get("started"));
			assertEquals(1, server.requestCount("/v1/agent/debug/compact"));
		}
	}

	@Test
	void agentDebugIdleTriggerPostsToBridgePath(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/debug/idle-trigger", 0, 200, """
				{"available":true,"accepted":true,"triggerType":"idle_think","speaker":"self"}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.fireAgentDebugIdleTrigger();

			assertEquals(true, payload.get("accepted"));
			assertEquals(1, server.requestCount("/v1/agent/debug/idle-trigger"));
			assertEquals("POST", server.lastMethod("/v1/agent/debug/idle-trigger"));
		}
	}

	@Test
	void agentEventPolicyEndpointsMapToBridgePaths(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/event-policy", 0, 200, """
				{"available":true,"activeRuleCount":1,"recentInterventionCount":0,"activeRules":[],"recentInterventions":[]}
				""");
			server.respondJson("/v1/agent/event-policy/clear", 0, 200, """
				{"available":true,"activeRuleCount":0,"recentInterventionCount":0,"activeRules":[],"recentInterventions":[]}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();

			Map<String, Object> before = transport.getAgentEventPolicy();
			Map<String, Object> after = transport.clearAgentEventPolicy();

			assertEquals(1, ((Number) before.get("activeRuleCount")).intValue());
			assertEquals(0, ((Number) after.get("activeRuleCount")).intValue());
			assertEquals(1, server.requestCount("/v1/agent/event-policy"));
			assertEquals(1, server.requestCount("/v1/agent/event-policy/clear"));
		}
	}

	@Test
	void evaluationStatusReadsPayload(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/evaluation/status", 0, 200, """
				{"available":true,"sessionMode":"SINGLEPLAYER_LOCAL","worldLoaded":true,"capabilities":["scenario_run","planner_loop"]}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.getEvaluationStatus();

			assertEquals(true, payload.get("available"));
			assertEquals("SINGLEPLAYER_LOCAL", payload.get("sessionMode"));
			assertEquals(1, server.requestCount("/v1/evaluation/status"));
		}
	}

	@Test
	void evaluationScenariosReadsPayload(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/evaluation/scenarios", 0, 200, """
				{"available":true,"scenarios":[{"id":"smelting-basic","name":"Smelting Basic","frozen":true}]}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.getEvaluationScenarios();

			assertEquals(true, payload.get("available"));
			assertEquals(1, server.requestCount("/v1/evaluation/scenarios"));
		}
	}

	@Test
	void evaluationConfigReadsPayload(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/evaluation/config", 0, 200, """
				{"available":true,"scenarioId":"smelting-basic","configPath":"/repo/scenarios/smelting-basic/scenario.yml"}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.getEvaluationConfig();

			assertEquals(true, payload.get("available"));
			assertEquals("smelting-basic", payload.get("scenarioId"));
			assertEquals(1, server.requestCount("/v1/evaluation/config"));
		}
	}

	@Test
	void evaluationRunPostsScenarioAndOutputDir(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/evaluation/run", 0, 200, """
				{"accepted":true,"scenario":"smelting-basic","running":true}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.runEvaluationScenario("smelting-basic", "/tmp/eval-output/run-1");

			assertEquals(true, payload.get("accepted"));
			assertEquals(1, server.requestCount("/v1/evaluation/run"));
			assertTrue(server.lastRequestBody("/v1/evaluation/run").contains("\"scenario\":\"smelting-basic\""));
			assertTrue(server.lastRequestBody("/v1/evaluation/run").contains("\"outputDir\":\"/tmp/eval-output/run-1\""));
		}
	}

	@Test
	void evaluationResultsReadsPayload(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/evaluation/results", 0, 200, """
				{"available":true,"report":{"scenarioId":"smelting-basic","status":"PASSED","reason":"all checks passed"}}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.getEvaluationResults();

			assertEquals(true, payload.get("available"));
			assertEquals(1, server.requestCount("/v1/evaluation/results"));
		}
	}

	@Test
	void evaluationEvidenceReadsPayload(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/evaluation/evidence", 0, 200, """
				{"available":true,"report":{"scenarioId":"smelting-basic","status":"FAILED"},"evidence":{"plannerJournal":[],"recentEvents":[]}}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.getEvaluationEvidence();

			assertEquals(true, payload.get("available"));
			assertEquals(1, server.requestCount("/v1/evaluation/evidence"));
		}
	}

	@Test
	void attackEntityPostsSelectorPayload(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/player/attack-entity", 0, 200, """
				{"accepted":true,"task":{"state":"QUEUED"}}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.attackEntity(null, null, "minecraft:sheep", "hit_once");

			assertEquals(true, payload.get("accepted"));
			assertEquals("POST", server.lastMethod("/v1/player/attack-entity"));
			assertTrue(server.lastRequestBody("/v1/player/attack-entity").contains("\"entityTypeId\":\"minecraft:sheep\""));
			assertTrue(server.lastRequestBody("/v1/player/attack-entity").contains("\"mode\":\"hit_once\""));
		}
	}

	@Test
	void nearbyEntitiesUsesGetEndpoint(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/player/nearby-entities", 0, 200, """
				{"entityCount":1,"entities":[{"entityTypeId":"minecraft:sheep","name":"Sheep"}]}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.listNearbyEntities();

			assertEquals(1, payload.get("entityCount"));
			assertEquals("GET", server.lastMethod("/v1/player/nearby-entities"));
			assertEquals("", server.lastRequestBody("/v1/player/nearby-entities"));
		}
	}

	@Test
	void useEntityPostsSelectorAndItemPayload(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/player/use-entity", 0, 200, """
				{"accepted":true,"task":{"state":"QUEUED"}}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.useEntity(null, "Dinner", null, "minecraft:shears");

			assertEquals(true, payload.get("accepted"));
			assertEquals("POST", server.lastMethod("/v1/player/use-entity"));
			assertTrue(server.lastRequestBody("/v1/player/use-entity").contains("\"name\":\"Dinner\""));
			assertTrue(server.lastRequestBody("/v1/player/use-entity").contains("\"itemId\":\"minecraft:shears\""));
		}
	}

	private static void writeBridgeState(Path tempDir) throws Exception {
		writeBridgeState(tempDir, 1);
	}

	private static void writeBridgeState(Path tempDir, int port) throws Exception {
		Path bridgeDir = tempDir.resolve(".airicraft");
		Files.createDirectories(bridgeDir);
		Path bridgeStatePath = bridgeDir.resolve("bridge-state.json");
		Files.writeString(bridgeStatePath, """
			{
			  "port": %d,
			  "token": "test-token",
			  "startedAtEpochMillis": 1
			}
			""".formatted(port));
		System.setProperty(BridgeStateFile.PATH_PROPERTY, bridgeStatePath.toString());
	}

	private static final class TestBridgeServer implements AutoCloseable {
		private final HttpServer server;
		private final Map<String, Integer> requestCounts = new java.util.concurrent.ConcurrentHashMap<>();
		private final Map<String, String> lastMethods = new java.util.concurrent.ConcurrentHashMap<>();
		private final Map<String, String> lastRequestBodies = new java.util.concurrent.ConcurrentHashMap<>();
		private final Map<String, String> lastQueries = new java.util.concurrent.ConcurrentHashMap<>();

		private TestBridgeServer(HttpServer server) {
			this.server = server;
		}

		private static TestBridgeServer start() throws IOException {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			server.setExecutor(Executors.newCachedThreadPool());
			server.start();
			return new TestBridgeServer(server);
		}

		private int port() {
			return server.getAddress().getPort();
		}

		private void respondJson(String path, long delayMillis, int statusCode, String body) {
			server.createContext(path, exchange -> {
				requestCounts.merge(path, 1, Integer::sum);
				lastMethods.put(path, exchange.getRequestMethod());
				lastRequestBodies.put(path, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				lastQueries.put(path, exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery());
				if (!"Bearer test-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
					writeResponse(exchange, 401, "{\"error\":\"unauthorized\"}");
					return;
				}
				if (delayMillis > 0) {
					try {
						Thread.sleep(delayMillis);
					}
					catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
					}
				}
				writeResponse(exchange, statusCode, body);
			});
		}

		private int requestCount(String path) {
			return requestCounts.getOrDefault(path, 0);
		}

		private String lastMethod(String path) {
			return lastMethods.get(path);
		}

		private String lastRequestBody(String path) {
			return lastRequestBodies.get(path);
		}

		private String lastQuery(String path) {
			return lastQueries.get(path);
		}

		@Override
		public void close() {
			server.stop(0);
		}
	}

	private static void writeResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(statusCode, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}
}
