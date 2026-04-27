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

	@AfterEach
	void restoreUserHome() {
		System.setProperty("user.home", originalUserHome);
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
	void resolveAgentActionGraphPostsGoalAndAssumptions(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/action-graph/resolve", 0, 200, """
				{"available":true,"resolved":true,"route":{"cost":10,"steps":[]},"trace":[]}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.resolveAgentActionGraph("minecraft:bread", 1, Map.of("minecraft:wheat", 3));

			assertEquals(true, payload.get("resolved"));
			assertEquals(1, server.requestCount("/v1/agent/action-graph/resolve"));
			assertEquals("POST", server.lastMethod("/v1/agent/action-graph/resolve"));
			assertTrue(server.lastRequestBody("/v1/agent/action-graph/resolve").contains("\"itemId\":\"minecraft:bread\""));
			assertTrue(server.lastRequestBody("/v1/agent/action-graph/resolve").contains("\"countAtLeast\":1"));
			assertTrue(server.lastRequestBody("/v1/agent/action-graph/resolve").contains("\"itemId\":\"minecraft:wheat\""));
			assertTrue(server.lastRequestBody("/v1/agent/action-graph/resolve").contains("\"count\":3"));
		}
	}

	@Test
	void inspectAgentActionGraphGetsInspectionPayload(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/action-graph/inspect", 0, 200, """
				{"available":true,"actionsetValid":true,"primitiveCount":11,"actionsetCount":2}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.inspectAgentActionGraph();

			assertEquals(true, payload.get("actionsetValid"));
			assertEquals(1, server.requestCount("/v1/agent/action-graph/inspect"));
			assertEquals("GET", server.lastMethod("/v1/agent/action-graph/inspect"));
			assertEquals("", server.lastRequestBody("/v1/agent/action-graph/inspect"));
		}
	}

	@Test
	void executeAgentActionGraphPostsGoalAndAssumptions(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/action-graph/execute", 0, 200, """
				{"available":true,"resolved":true,"accepted":true,"selectedStep":{"targetId":"craft_item"}}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.executeAgentActionGraph("minecraft:bread", 1, Map.of("minecraft:wheat", 3));

			assertEquals(true, payload.get("accepted"));
			assertEquals(1, server.requestCount("/v1/agent/action-graph/execute"));
			assertEquals("POST", server.lastMethod("/v1/agent/action-graph/execute"));
			assertTrue(server.lastRequestBody("/v1/agent/action-graph/execute").contains("\"itemId\":\"minecraft:bread\""));
			assertTrue(server.lastRequestBody("/v1/agent/action-graph/execute").contains("\"countAtLeast\":1"));
			assertTrue(server.lastRequestBody("/v1/agent/action-graph/execute").contains("\"itemId\":\"minecraft:wheat\""));
			assertTrue(server.lastRequestBody("/v1/agent/action-graph/execute").contains("\"count\":3"));
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
	void verificationStatusReadsPayload(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/verification/status", 0, 200, """
				{"available":true,"sessionMode":"SINGLEPLAYER_LOCAL","worldLoaded":true,"capabilities":["player_state","scenario_run"]}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.getVerificationStatus();

			assertEquals(true, payload.get("available"));
			assertEquals("SINGLEPLAYER_LOCAL", payload.get("sessionMode"));
			assertEquals(1, server.requestCount("/v1/verification/status"));
		}
	}

	@Test
	void teleportVerificationPlayerPostsCoordinates(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/verification/player/teleport", 0, 200, """
				{"available":true,"teleported":true,"x":10.5,"y":94.0,"z":-3.0}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.teleportVerificationPlayer(10.5D, 94.0D, -3.0D);

			assertEquals(true, payload.get("teleported"));
			assertEquals(1, server.requestCount("/v1/verification/player/teleport"));
			assertTrue(server.lastRequestBody("/v1/verification/player/teleport").contains("\"x\":10.5"));
			assertTrue(server.lastRequestBody("/v1/verification/player/teleport").contains("\"y\":94.0"));
			assertTrue(server.lastRequestBody("/v1/verification/player/teleport").contains("\"z\":-3.0"));
		}
	}

	@Test
	void setVerificationPlayerVelocityPostsComponents(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/verification/player/velocity", 0, 200, """
				{"available":true,"applied":true,"x":0.0,"y":1.5,"z":-0.25}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.setVerificationPlayerVelocity(0.0D, 1.5D, -0.25D);

			assertEquals(true, payload.get("applied"));
			assertEquals(1, server.requestCount("/v1/verification/player/velocity"));
			assertTrue(server.lastRequestBody("/v1/verification/player/velocity").contains("\"x\":0.0"));
			assertTrue(server.lastRequestBody("/v1/verification/player/velocity").contains("\"y\":1.5"));
			assertTrue(server.lastRequestBody("/v1/verification/player/velocity").contains("\"z\":-0.25"));
		}
	}

	@Test
	void respawnVerificationPlayerPostsToRespawnEndpoint(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/verification/player/respawn", 0, 200, """
				{"available":true,"respawned":true,"health":20.0,"currentScreen":"in_game"}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.respawnVerificationPlayer();

			assertEquals(true, payload.get("respawned"));
			assertEquals(1, server.requestCount("/v1/verification/player/respawn"));
			assertEquals("", server.lastRequestBody("/v1/verification/player/respawn"));
		}
	}

	@Test
	void verificationRunPostsScenario(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/verification/run", 0, 200, """
				{"accepted":true,"scenario":"damage.fall_context","running":true}
				""");
			writeBridgeState(tempDir, server.port());
			System.setProperty("user.home", tempDir.toString());

			HttpBridgeTransport transport = new HttpBridgeTransport();
			Map<String, Object> payload = transport.runVerificationScenario("damage.fall_context");

			assertEquals(true, payload.get("accepted"));
			assertTrue(server.lastRequestBody("/v1/verification/run").contains("\"scenario\":\"damage.fall_context\""));
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
		Files.writeString(bridgeDir.resolve("bridge-state.json"), """
			{
			  "port": %d,
			  "token": "test-token",
			  "startedAtEpochMillis": 1
			}
			""".formatted(port));
	}

	private static final class TestBridgeServer implements AutoCloseable {
		private final HttpServer server;
		private final Map<String, Integer> requestCounts = new java.util.concurrent.ConcurrentHashMap<>();
		private final Map<String, String> lastMethods = new java.util.concurrent.ConcurrentHashMap<>();
		private final Map<String, String> lastRequestBodies = new java.util.concurrent.ConcurrentHashMap<>();

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
