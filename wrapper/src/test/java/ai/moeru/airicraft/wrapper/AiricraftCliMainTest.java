package ai.moeru.airicraft.wrapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiricraftCliMainTest {
	@Test
	void statusRendersDeterministicText() {
		TestTransport transport = new TestTransport();
		transport.statusPayload = linkedMap(
			"available", false,
			"bridgeAvailable", false,
			"worldLoaded", false,
			"sessionState", "minecraft_unavailable",
			"state", "minecraft_unavailable",
			"message", "Minecraft bridge is not active"
		);

		CliResult result = execute(transport, "status");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().startsWith(
			"status: ok\n" +
				"command: status\n" +
				"available: false\n"
		));
		assertTrue(result.output().contains("state: minecraft_unavailable\n"));
	}

	@Test
	void reloadRendersDeterministicText() {
		TestTransport transport = new TestTransport();
		transport.reloadPayload = linkedMap(
			"available", true,
			"reloaded", true,
			"agentStateReset", true,
			"sessionMode", "SINGLEPLAYER_LOCAL",
			"worldLoaded", true,
			"plannerVisionMode", "native_tool_image",
			"llmConfigured", true,
			"visionConfigured", true,
			"observabilityEnabled", false
		);

		CliResult result = execute(transport, "reload");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().startsWith(
			"status: ok\n" +
				"command: reload\n"
		));
		assertTrue(result.output().contains("reloaded: true\n"));
		assertTrue(result.output().contains("agentStateReset: true\n"));
		assertTrue(result.output().contains("plannerVisionMode: native_tool_image\n"));
		assertTrue(transport.reloadCalled);
	}

	@Test
	void agentContextRendersCompactionState() {
		TestTransport transport = new TestTransport();
		transport.agentContextPayload = linkedMap(
			"available", true,
			"planner", linkedMap(
				"configured", true,
				"plannerVisionMode", "native_tool_image",
				"inFlight", false,
				"plannerInFlight", false,
				"compactionInFlight", false,
				"captureInFlight", false,
				"toolInFlight", false,
				"toolUsed", false,
				"coalescePending", true,
				"coalesceReadyAtMs", 123456999L,
				"coalesceWindowMs", 20L,
				"context", linkedMap(
					"compactionTriggerTokens", 65536,
					"compactionPending", true,
					"acceptedTurnCount", 8,
					"pendingSemanticEventCount", 3,
					"projectedPendingNoticeCount", 2,
					"frozenPlannerMessageCount", 0,
					"queuedTriggerCount", 3,
					"lastObservedEventSeqNo", 42,
					"lastAcceptedTimeContextAtMs", 123456789L,
					"pendingSemanticGap", false,
					"overflowFlushPending", true
				)
			),
			"conversationSources", linkedMap(
				"canonicalMessageCount", 11,
				"projectedMessageCount", 14,
				"canonicalUserTurnCount", 4,
				"projectedUserTurnCount", 4,
				"hiddenKinds", List.of("SYSTEM")
			)
		);

		CliResult result = execute(transport, "agent", "context");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent context\n"));
		assertTrue(result.output().contains("plannerVisionMode: native_tool_image\n"));
		assertTrue(result.output().contains("compactionPending: true\n"));
		assertTrue(result.output().contains("coalescePending: true\n"));
		assertTrue(result.output().contains("coalesceWindowMs: 20\n"));
		assertTrue(result.output().contains("queuedTriggerCount: 3\n"));
		assertTrue(result.output().contains("acceptedTurnCount: 8\n"));
		assertTrue(result.output().contains("projectedPendingNoticeCount: 2\n"));
		assertTrue(result.output().contains("overflowFlushPending: true\n"));
		assertTrue(result.output().contains("canonicalMessageCount: 11\n"));
		assertTrue(result.output().contains("projectedMessageCount: 14\n"));
	}

	@Test
	void agentContextVerboseIncludesContextExcerpt() {
		TestTransport transport = new TestTransport();
		transport.agentContextPayload = linkedMap(
			"available", true,
			"planner", linkedMap(
				"configured", true,
				"context", linkedMap(
					"acceptedTurnCount", 1
				)
			),
			"contextExcerpt", List.of(
				"Context update: You took 4 damage from minecraft:fall and dropped to 16 health just now."
			),
			"conversation", linkedMap("messageCount", 1),
			"canonicalConversation", linkedMap("messageCount", 1),
			"projectedConversation", linkedMap("messageCount", 2),
			"conversationSources", linkedMap(
				"canonicalMessageCount", 1,
				"projectedMessageCount", 2
			),
			"plannerJournal", linkedMap("eventCount", 3)
		);

		CliResult result = execute(transport, "agent", "context", "--verbose");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("contextExcerptLineCount: 1\n"));
		assertTrue(result.output().contains("projectedMessageCount: 2\n"));
		assertTrue(result.output().contains("[canonicalConversation]\n"));
		assertTrue(result.output().contains("[plannerJournal]\n"));
		assertTrue(result.output().contains("value: Context update: You took 4 damage from minecraft:fall and dropped to 16 health just now.\n"));
	}

	@Test
	void agentDialogueVerboseIncludesConversationAliases() {
		TestTransport transport = new TestTransport();
		transport.agentDialoguePayload = linkedMap(
			"available", true,
			"dialogue", linkedMap(
				"pendingReply", false,
				"recentTurns", List.of()
			),
			"conversation", linkedMap("messageCount", 1),
			"canonicalConversation", linkedMap("messageCount", 1),
			"projectedConversation", linkedMap("messageCount", 2),
			"conversationSources", linkedMap(
				"canonicalMessageCount", 1,
				"projectedMessageCount", 2,
				"canonicalUserTurnCount", 1,
				"projectedUserTurnCount", 1
			),
			"plannerJournal", linkedMap("eventCount", 4),
			"lastChatTick", 42L,
			"lastChatText", "done"
		);

		CliResult result = execute(transport, "agent", "dialogue", "--verbose");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent dialogue\n"));
		assertTrue(result.output().contains("canonicalMessageCount: 1\n"));
		assertTrue(result.output().contains("projectedMessageCount: 2\n"));
		assertTrue(result.output().contains("[canonicalConversation]\n"));
		assertTrue(result.output().contains("[plannerJournal]\n"));
	}

	@Test
	void agentSessionOpenLanRendersDeterministicText() {
		TestTransport transport = new TestTransport();
		transport.agentSessionOpenLanPayload = linkedMap(
			"opened", true,
			"port", 25565
		);

		CliResult result = execute(transport, "agent", "session", "open-lan");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().startsWith(
			"status: ok\n" +
				"command: agent session open-lan\n"
		));
		assertTrue(result.output().contains("opened: true\n"));
		assertTrue(result.output().contains("port: 25565\n"));
	}

	@Test
	void agentDebugChatPassesMessagePayload() {
		TestTransport transport = new TestTransport();
		transport.agentDebugChatPayload = linkedMap(
			"available", true,
			"accepted", true,
			"senderName", "Player688",
			"message", "@agent get me 4 wood logs"
		);

		CliResult result = execute(
			transport,
			"agent", "debug", "chat",
			"--message", "@agent get me 4 wood logs"
		);

		assertEquals(0, result.exitCode());
		assertEquals("@agent get me 4 wood logs", transport.lastDebugChatMessage);
		assertTrue(result.output().contains("command: agent debug chat\n"));
		assertTrue(result.output().contains("accepted: true\n"));
	}

	@Test
	void agentDebugIdleTriggerFiresManualTrigger() {
		TestTransport transport = new TestTransport();
		transport.agentDebugIdleTriggerPayload = linkedMap(
			"available", true,
			"accepted", true,
			"triggerType", "idle_think",
			"speaker", "self",
			"tick", 42L,
			"sessionMode", "SINGLEPLAYER_LAN_HOST"
		);

		CliResult result = execute(transport, "agent", "debug", "idle-trigger");

		assertEquals(0, result.exitCode());
		assertTrue(transport.agentDebugIdleTriggerCalled);
		assertTrue(result.output().contains("command: agent debug idle-trigger\n"));
		assertTrue(result.output().contains("accepted: true\n"));
		assertTrue(result.output().contains("triggerType: idle_think\n"));
	}

	@Test
	void agentDebugStateShowsActionDispatchState() {
		TestTransport transport = new TestTransport();
		transport.agentDebugStatePayload = linkedMap(
			"available", true,
			"planner", linkedMap("configured", true),
			"dialogueState", linkedMap("pendingReply", false),
			"conversationSources", linkedMap("canonicalMessageCount", 1, "projectedMessageCount", 1),
			"taskProgressProbe", linkedMap("active", false),
			"chatProbe", linkedMap("lastSendSucceeded", true),
			"eventPipeline", linkedMap("lastEventType", "task.failed"),
			"plannerAttempts", List.of(),
			"timelineTail", List.of(),
			"activeJob", linkedMap(
				"type", "NAVIGATE_TO",
				"status", "FAILED",
				"lastError", "CALC_FAILED"
			),
			"taskExecution", linkedMap("state", "IDLE")
		);

		CliResult result = execute(transport, "agent", "debug", "state");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("[activeJob]\n"));
		assertTrue(result.output().contains("type: NAVIGATE_TO\n"));
		assertTrue(result.output().contains("lastError: CALC_FAILED\n"));
		assertTrue(result.output().contains("[taskExecution]\n"));
		assertTrue(result.output().contains("state: IDLE\n"));
	}

	@Test
	void agentDebugTimelinePassesSinceAndRendersEntries() {
		TestTransport transport = new TestTransport();
		transport.agentDebugTimelinePayload = linkedMap(
			"available", true,
			"oldestEntryId", 3,
			"latestEntryId", 7,
			"truncated", false,
			"entries", List.of(
				linkedMap(
					"entryId", 7,
					"tick", 120,
					"timestampMs", 123456L,
					"domain", "planner",
					"action", "failure",
					"summary", "TIMEOUT: LLM request timed out"
				)
			)
		);

		CliResult result = execute(transport, "agent", "debug", "timeline", "--since", "4");

		assertEquals(0, result.exitCode());
		assertEquals(4L, transport.lastDebugTimelineSince);
		assertTrue(result.output().contains("command: agent debug timeline\n"));
		assertTrue(result.output().contains("entryCount: 1\n"));
		assertTrue(result.output().contains("summary: TIMEOUT: LLM request timed out\n"));
	}

	@Test
	void clientTickPauseSavesFrameAndRendersSnapshotIdentity(@TempDir Path tempDir) throws Exception {
		TestTransport transport = new TestTransport();
		transport.clientTickDebugPausePayload = linkedMap(
			"available", true,
			"debugSessionId", "debug-1",
			"pauseEpoch", 1L,
			"captureId", "capture-1",
			"snapshotId", "snapshot-1",
			"clientTickId", 44L,
			"snapshot", linkedMap(
				"capturedAtMs", 100L,
				"dimensionId", "minecraft:overworld",
				"worldTime", 200L,
				"timeOfDay", 200L,
				"player", linkedMap("blockX", 1, "blockY", 64, "blockZ", 2),
				"plannerGeneration", 7L,
				"plannerPhase", "IDLE"
			),
			"frame", linkedMap("status", "CAPTURED", "format", "png", "width", 854, "height", 480),
			"imageBase64", java.util.Base64.getEncoder().encodeToString(new byte[] {1, 2, 3})
		);
		Path output = tempDir.resolve("pause.png");

		CliResult result = execute(transport, "agent", "debug", "ticks", "pause", "--output-image", output.toString());

		assertEquals(0, result.exitCode());
		assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(output));
		assertTrue(result.output().contains("snapshotId: snapshot-1\n"));
		assertTrue(result.output().contains("clientTickId: 44\n"));
		assertTrue(result.output().contains("imageOutputPath: " + output + "\n"));
	}

	@Test
	void clientTickStepPassesExactSessionAndPauseEpoch() {
		TestTransport transport = new TestTransport();
		transport.clientTickDebugStepPayload = linkedMap(
			"available", true,
			"debugSessionId", "debug-1",
			"pauseEpoch", 3L,
			"snapshotId", "snapshot-3",
			"clientTickId", 46L,
			"snapshot", Map.of(),
			"frame", linkedMap("status", "CAPTURED")
		);

		CliResult result = execute(
			transport,
			"agent", "debug", "ticks", "step",
			"--debug-session-id", "debug-1",
			"--pause-epoch", "2"
		);

		assertEquals(0, result.exitCode());
		assertEquals("debug-1", transport.lastClientTickDebugSessionId);
		assertEquals(2L, transport.lastClientTickDebugPauseEpoch);
		assertFalse(result.output().contains("imageBase64"));
	}

	@Test
	void clientTickTraceStartBuildsSelectedQueriesAndWindow() {
		TestTransport transport = new TestTransport();
		transport.clientTickTraceStartPayload = linkedMap(
			"active", true,
			"traceId", "trace-1",
			"windowTicks", 100
		);

		CliResult result = execute(
			transport,
			"agent", "debug", "trace", "start",
			"--info", "metadata,player-state,entities,frame",
			"--window-ticks", "100",
			"--entity-query", "{\"radius\":16,\"entityTypeIds\":[\"minecraft:zombie\"],\"limit\":12}"
		);

		assertEquals(0, result.exitCode());
		assertEquals(
			List.of("metadata", "player_state", "entities", "frame"),
			transport.lastClientTickTraceStartRequest.get("infos")
		);
		assertEquals(100, transport.lastClientTickTraceStartRequest.get("windowTicks"));
		Map<String, Object> entityQuery = castMap(transport.lastClientTickTraceStartRequest.get("entityQuery"));
		assertEquals(16, entityQuery.get("radius"));
		assertEquals(List.of("minecraft:zombie"), entityQuery.get("entityTypeIds"));
		assertEquals(12, entityQuery.get("limit"));
	}

	@Test
	void clientTickTraceStartRequiresBlockQueryForBlockRecords() {
		TestTransport transport = new TestTransport();

		CliResult result = execute(
			transport,
			"agent", "debug", "trace", "start",
			"--info", "blocks",
			"--window-ticks", "20"
		);

		assertEquals(2, result.exitCode());
		assertTrue(result.output().contains("error_code: invalid_arguments\n"));
		assertTrue(transport.lastClientTickTraceStartRequest == null);
	}

	@Test
	void clientTickTraceStartRejectsLargeFrameWindow() {
		TestTransport transport = new TestTransport();

		CliResult result = execute(
			transport,
			"agent", "debug", "trace", "start",
			"--info", "frame",
			"--window-ticks", "201"
		);

		assertEquals(2, result.exitCode());
		assertTrue(result.output().contains("window-ticks must not exceed 200 with frame tracing"));
	}

	@Test
	void clientTickTraceRecordsWritesRequestedFrames(@TempDir Path tempDir) throws Exception {
		TestTransport transport = new TestTransport();
		transport.clientTickTraceRecordsPayload = linkedMap(
			"traceId", "trace-1",
			"recordCount", 1,
			"records", List.of(linkedMap(
				"clientTickId", 42L,
				"frame", linkedMap(
					"status", "CAPTURED",
					"format", "png",
					"imageBase64", java.util.Base64.getEncoder().encodeToString(new byte[]{7, 8, 9})
				)
			))
		);

		CliResult result = execute(
			transport,
			"agent", "debug", "trace", "records",
			"--trace-id", "trace-1",
			"--since-client-tick-id", "40",
			"--limit", "8",
			"--output-image-dir", tempDir.toString()
		);

		Path image = tempDir.resolve("tick-00000000000000000042.png");
		assertEquals(0, result.exitCode());
		assertArrayEquals(new byte[]{7, 8, 9}, Files.readAllBytes(image));
		assertEquals("trace-1", transport.lastClientTickTraceId);
		assertEquals(40L, transport.lastClientTickTraceSince);
		assertEquals(8, transport.lastClientTickTraceLimit);
		assertTrue(transport.lastClientTickTraceIncludeImageBytes);
		assertTrue(result.output().contains("imageOutputPath: " + image + "\n"));
		assertFalse(result.output().contains("imageBase64"));
	}

	@Test
	void clientTickWorldScanBoxBuildsBoundedRegionRequest() {
		TestTransport transport = new TestTransport();
		transport.clientTickWorldQueryPayload = linkedMap("snapshotId", "snapshot-3", "blocks", List.of());

		CliResult result = execute(
			transport,
			"agent", "debug", "world", "scan-box",
			"--snapshot-id", "snapshot-3",
			"--min-x", "-10", "--min-y", "60", "--min-z", "20",
			"--max-x", "10", "--max-y", "70", "--max-z", "40",
			"--cursor", "100", "--limit", "512"
		);

		assertEquals(0, result.exitCode());
		assertEquals("snapshot-3", transport.lastClientTickWorldQuery.get("snapshotId"));
		assertEquals("scan_box", transport.lastClientTickWorldQuery.get("operation"));
		assertEquals(-10, transport.lastClientTickWorldQuery.get("minX"));
		assertEquals(40, transport.lastClientTickWorldQuery.get("maxZ"));
		assertEquals(100L, transport.lastClientTickWorldQuery.get("cursor"));
		assertEquals(512, transport.lastClientTickWorldQuery.get("limit"));
	}

	@Test
	void clientTickWorldPlayerStateBuildsSnapshotRequest() {
		TestTransport transport = new TestTransport();
		transport.clientTickWorldQueryPayload = linkedMap("snapshotId", "snapshot-3", "player", Map.of());

		CliResult result = execute(
			transport,
			"agent", "debug", "world", "player-state",
			"--snapshot-id", "snapshot-3"
		);

		assertEquals(0, result.exitCode());
		assertEquals("snapshot-3", transport.lastClientTickWorldQuery.get("snapshotId"));
		assertEquals("player_state", transport.lastClientTickWorldQuery.get("operation"));
	}

	@Test
	void clientTickWorldEntitiesBuildsRadiusAndFilterRequest() {
		TestTransport transport = new TestTransport();
		transport.clientTickWorldQueryPayload = linkedMap("snapshotId", "snapshot-3", "entities", List.of());

		CliResult result = execute(
			transport,
			"agent", "debug", "world", "entities",
			"--snapshot-id", "snapshot-3",
			"--radius", "16",
			"--name", "Sheep",
			"--entity-type-id", "minecraft:sheep,minecraft:cow",
			"--alive=false",
			"--living-only",
			"--include-self",
			"--cursor", "4",
			"--limit", "5"
		);

		assertEquals(0, result.exitCode());
		assertEquals("entities", transport.lastClientTickWorldQuery.get("operation"));
		assertEquals(16.0D, transport.lastClientTickWorldQuery.get("radius"));
		assertFalse(transport.lastClientTickWorldQuery.containsKey("centerX"));
		assertEquals("Sheep", transport.lastClientTickWorldQuery.get("name"));
		assertEquals(List.of("minecraft:sheep", "minecraft:cow"), transport.lastClientTickWorldQuery.get("entityTypeIds"));
		assertEquals(false, transport.lastClientTickWorldQuery.get("alive"));
		assertEquals(true, transport.lastClientTickWorldQuery.get("livingOnly"));
		assertEquals(true, transport.lastClientTickWorldQuery.get("includeSelf"));
		assertEquals(4L, transport.lastClientTickWorldQuery.get("cursor"));
		assertEquals(5, transport.lastClientTickWorldQuery.get("limit"));
	}

	@Test
	void clientTickWorldEntitiesBuildsRegionAndIdentityRequest() {
		TestTransport transport = new TestTransport();
		transport.clientTickWorldQueryPayload = linkedMap("snapshotId", "snapshot-3", "entities", List.of());

		CliResult result = execute(
			transport,
			"agent", "debug", "world", "entities",
			"--snapshot-id", "snapshot-3",
			"--min-x", "-2", "--min-y", "60", "--min-z", "-3",
			"--max-x", "2", "--max-y", "70", "--max-z", "3",
			"--entity-id", "42",
			"--uuid", "entity-42"
		);

		assertEquals(0, result.exitCode());
		assertEquals(-2, transport.lastClientTickWorldQuery.get("minX"));
		assertEquals(3, transport.lastClientTickWorldQuery.get("maxZ"));
		assertEquals(42, transport.lastClientTickWorldQuery.get("entityId"));
		assertEquals("entity-42", transport.lastClientTickWorldQuery.get("uuid"));
	}

	@Test
	void clientTickWorldEntitiesRejectsPartialRegion() {
		TestTransport transport = new TestTransport();

		CliResult result = execute(
			transport,
			"agent", "debug", "world", "entities",
			"--snapshot-id", "snapshot-3",
			"--min-x", "0"
		);

		assertEquals(2, result.exitCode());
		assertTrue(result.output().contains("error_code: invalid_arguments\n"));
		assertTrue(transport.lastClientTickWorldQuery == null);
	}

	@Test
	void clientTickWorldFindBlocksUsesMatchItemLabel() {
		TestTransport transport = new TestTransport();
		transport.clientTickWorldQueryPayload = linkedMap(
			"matches", List.of(linkedMap("id", "minecraft:stone"))
		);

		CliResult result = execute(
			transport,
			"agent", "debug", "world", "find-blocks",
			"--snapshot-id", "snapshot-3",
			"--min-x", "0", "--min-y", "0", "--min-z", "0",
			"--max-x", "0", "--max-y", "0", "--max-z", "0",
			"--block-id", "minecraft:stone"
		);

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("[match 1]\n"));
		assertFalse(result.output().contains("[matche 1]\n"));
	}

	@Test
	void agentGoalsShowsActiveDirectJobFailure() {
		TestTransport transport = new TestTransport();
		transport.agentGoalsPayload = linkedMap(
			"available", true,
			"activeJob", linkedMap(
				"type", "NAVIGATE_TO",
				"status", "FAILED",
				"lastError", "CALC_FAILED"
			),
			"taskExecution", linkedMap("state", "IDLE")
		);

		CliResult result = execute(transport, "agent", "goals", "--verbose");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("[activeJob]\n"));
		assertTrue(result.output().contains("type: NAVIGATE_TO\n"));
		assertTrue(result.output().contains("status: FAILED\n"));
		assertTrue(result.output().contains("lastError: CALC_FAILED\n"));
	}

	@Test
	void agentStatusIncludesPlannerVisionMode() {
		TestTransport transport = new TestTransport();
		transport.agentStatusPayload = linkedMap(
			"available", true,
			"initialized", true,
			"tickCount", 42,
			"codexDriverActive", true,
			"llmAvailable", true,
			"visionAvailable", false,
			"plannerVisionMode", "native_tool_image",
			"reflex", linkedMap("state", "AWAITING_PLANNER", "holdId", "hold-42"),
			"degraded", false
		);

		CliResult result = execute(transport, "agent", "status");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent status\n"));
		assertTrue(result.output().contains("codexDriverActive: true\n"));
		assertTrue(result.output().contains("plannerVisionMode: native_tool_image\n"));
		assertTrue(result.output().contains("state: AWAITING_PLANNER\n"));
		assertTrue(result.output().contains("holdId: hold-42\n"));
	}

	@Test
	void agentToolsListRendersCompleteFunctionSchemas() {
		TestTransport transport = new TestTransport();
		transport.agentToolsPayload = linkedMap(
			"available", true,
			"codexDriverActive", true,
			"toolCount", 1,
			"tools", List.of(linkedMap(
				"type", "function",
				"function", linkedMap(
					"name", "navigate_to",
					"description", "Navigate to a block position.",
					"parameters", linkedMap("type", "object", "required", List.of("x", "y", "z"))
				)
			))
		);

		CliResult result = execute(transport, "agent", "tools", "list", "--verbose");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent tools list\n"));
		assertTrue(result.output().contains("codexDriverActive: true\n"));
		assertTrue(result.output().contains("name: navigate_to\n"));
		assertTrue(result.output().contains("[parameters]\n"));
	}

	@Test
	void agentToolsCallPassesJsonAndWritesReturnedImage(@TempDir Path tempDir) throws Exception {
		TestTransport transport = new TestTransport();
		transport.agentToolCallPayload = linkedMap(
			"available", true,
			"codexDriverActive", true,
			"toolName", "take_a_look",
			"result", "current view attached",
			"imageAttached", true,
			"imageMimeType", "image/png",
			"imageDetail", "high",
			"imageBase64", java.util.Base64.getEncoder().encodeToString(new byte[]{4, 5, 6})
		);
		Path output = tempDir.resolve("look.png");

		CliResult result = execute(
			transport,
			"agent", "tools", "call",
			"--name", "take_a_look",
			"--arguments", "{\"direction\":\"north\"}",
			"--timeout-seconds", "30",
			"--output-image", output.toString()
		);

		assertEquals(0, result.exitCode());
		assertEquals("take_a_look", transport.lastAgentToolName);
		assertEquals("north", transport.lastAgentToolArguments.get("direction"));
		assertEquals(30_000, transport.lastAgentToolTimeoutMs);
		assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(output));
		assertTrue(result.output().contains("imageOutputPath: " + output.toAbsolutePath().normalize() + "\n"));
		assertFalse(result.output().contains("imageBase64"));
	}

	@Test
	void agentEventPolicyShowsRuleSummary() {
		TestTransport transport = new TestTransport();
		transport.agentEventPolicyPayload = linkedMap(
			"available", true,
			"activeRuleCount", 2,
			"recentInterventionCount", 1,
			"lastDecision", linkedMap(
				"matchedRuleId", "mute-system",
				"effect", "IGNORE",
				"reason", "suppress noisy system spam",
				"bypassed", false
			),
			"activeRules", List.of(
				linkedMap("ruleId", "mute-system", "effect", "IGNORE")
			),
			"recentInterventions", List.of(
				linkedMap("matchedRuleId", "mute-system", "effect", "IGNORE")
			)
		);

		CliResult result = execute(transport, "agent", "event-policy");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent event-policy\n"));
		assertTrue(result.output().contains("activeRuleCount: 2\n"));
		assertTrue(result.output().contains("matchedRuleId: mute-system\n"));
	}

	@Test
	void playerAttackEntityPassesSelectorPayload() {
		TestTransport transport = new TestTransport();
		transport.playerAttackEntityPayload = linkedMap("accepted", true, "task", linkedMap("state", "QUEUED"));

		CliResult result = execute(transport, "player", "attack-entity", "--entity-type-id", "minecraft:sheep", "--mode", "hit_once");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: player attack-entity\n"));
		assertEquals("minecraft:sheep", transport.lastAttackEntityTypeId);
		assertEquals("hit_once", transport.lastAttackEntityMode);
	}

	@Test
	void playerNearbyEntitiesCallsTransport() {
		TestTransport transport = new TestTransport();
		transport.playerNearbyEntitiesPayload = linkedMap(
			"entityCount", 1,
			"entities", List.of(linkedMap("entityTypeId", "minecraft:sheep", "name", "Sheep", "distance", 3.0))
		);

		CliResult result = execute(transport, "player", "nearby-entities");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: player nearby-entities\n"));
		assertTrue(result.output().contains("entityCount: 1\n"));
		assertTrue(transport.playerNearbyEntitiesCalled);
	}

	@Test
	void playerUseEntityPassesSelectorAndItemPayload() {
		TestTransport transport = new TestTransport();
		transport.playerUseEntityPayload = linkedMap("accepted", true, "task", linkedMap("state", "QUEUED"));

		CliResult result = execute(
			transport,
			"player", "use-entity",
			"--name", "Dinner",
			"--item-id", "minecraft:shears"
		);

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: player use-entity\n"));
		assertEquals("Dinner", transport.lastUseEntityName);
		assertEquals("minecraft:shears", transport.lastUseEntityItemId);
	}

	@Test
	void playerLookAtPassesDurationOverride() {
		TestTransport transport = new TestTransport();
		transport.lookAtPayload = linkedMap("available", true, "durationTicks", 12, "scheduled", true);

		CliResult result = execute(
			transport,
			"player", "look-at",
			"--x", "1.5",
			"--y", "64",
			"--z", "-2.25",
			"--duration-ticks", "12"
		);

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: player look-at\n"));
		assertEquals(1.5D, transport.lastLookAtX, 0.001D);
		assertEquals(64.0D, transport.lastLookAtY, 0.001D);
		assertEquals(-2.25D, transport.lastLookAtZ, 0.001D);
		assertEquals(12, transport.lastLookAtDurationTicks);
		assertTrue(result.output().contains("durationTicks: 12\n"));
		assertTrue(result.output().contains("scheduled: true\n"));
	}

	@Test
	void agentEventPolicyClearCallsTransport() {
		TestTransport transport = new TestTransport();
		transport.agentEventPolicyPayload = linkedMap(
			"available", true,
			"activeRuleCount", 0,
			"recentInterventionCount", 0,
			"activeRules", List.of(),
			"recentInterventions", List.of()
		);

		CliResult result = execute(transport, "agent", "event-policy", "clear");

		assertEquals(0, result.exitCode());
		assertTrue(transport.agentEventPolicyCleared);
		assertTrue(result.output().contains("command: agent event-policy clear\n"));
		assertTrue(result.output().contains("activeRuleCount: 0\n"));
	}

	@Test
	void agentTasksShowsCurrentTaskSnapshot() {
		TestTransport transport = new TestTransport();
		transport.agentTasksPayload = linkedMap(
			"available", true,
			"task", linkedMap(
				"state", "RUNNING",
				"spec", linkedMap(
					"type", "COLLECT_RESOURCE",
					"resourceKind", "WOOD_LOGS",
					"quantity", 4
				)
			)
		);

		CliResult result = execute(transport, "agent", "tasks");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent tasks\n"));
		assertTrue(result.output().contains("state: RUNNING\n"));
		assertTrue(result.output().contains("resourceKind: WOOD_LOGS\n"));
	}

	@Test
	void agentTasksSubmitPassesNormalizedTaskPayload() {
		TestTransport transport = new TestTransport();
		transport.agentTaskSubmitPayload = linkedMap(
			"available", true,
			"task", linkedMap("state", "QUEUED")
		);

		CliResult result = execute(
			transport,
			"agent", "tasks", "submit",
			"--type", "collect-resource",
			"--resource", "wood-logs",
			"--quantity", "4"
		);

		assertEquals(0, result.exitCode());
		assertEquals("COLLECT_RESOURCE", transport.lastSubmittedTask.get("type"));
		assertEquals("WOOD_LOGS", transport.lastSubmittedTask.get("resourceKind"));
		assertEquals(4, transport.lastSubmittedTask.get("quantity"));
		assertTrue(result.output().contains("command: agent tasks submit\n"));
	}

	@Test
	void agentTasksResumePassesSafetyHoldId() {
		TestTransport transport = new TestTransport();
		transport.agentTaskResumePayload = linkedMap(
			"available", true,
			"resumed", true,
			"holdId", "hold-42",
			"reflex", linkedMap("state", "IDLE")
		);

		CliResult result = execute(transport, "agent", "tasks", "resume", "--hold-id", "hold-42");

		assertEquals(0, result.exitCode());
		assertEquals("hold-42", transport.lastResumeHoldId);
		assertTrue(result.output().contains("command: agent tasks resume\n"));
		assertTrue(result.output().contains("resumed: true\n"));
		assertTrue(result.output().contains("holdId: hold-42\n"));
	}

	@Test
	void agentLedgerRendersCurrentMissionLedger() {
		TestTransport transport = new TestTransport();
		transport.agentLedgerPayload = linkedMap(
			"available", true,
			"ledger", linkedMap(
				"missionId", "mission-wood-1",
				"activeStepId", "collect_logs"
			)
		);

		CliResult result = execute(transport, "agent", "ledger");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent ledger\n"));
		assertTrue(result.output().contains("missionId: mission-wood-1\n"));
		assertTrue(result.output().contains("activeStepId: collect_logs\n"));
	}

	@Test
	void agentEvidenceRendersWorldEvidenceSummary() {
		TestTransport transport = new TestTransport();
		transport.agentEvidencePayload = linkedMap(
			"available", true,
			"evidence", linkedMap(
				"dimension", "minecraft:overworld",
				"inventoryCounts", linkedMap(
					"WOOD_LOGS", 4
				)
			)
		);

		CliResult result = execute(transport, "agent", "evidence");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent evidence\n"));
		assertTrue(result.output().contains("dimension: minecraft:overworld\n"));
		assertTrue(result.output().contains("WOOD_LOGS: 4\n"));
	}

	@Test
	void agentStepExecutionRendersLatestStepResult() {
		TestTransport transport = new TestTransport();
		transport.agentStepExecutionPayload = linkedMap(
			"available", true,
			"stepExecution", linkedMap(
				"stepId", "craft_sticks",
				"status", "RUNNING"
			)
		);

		CliResult result = execute(transport, "agent", "step-execution");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent step-execution\n"));
		assertTrue(result.output().contains("stepId: craft_sticks\n"));
		assertTrue(result.output().contains("status: RUNNING\n"));
	}

	@Test
	void agentActionsInspectRendersActionGraphSummary() {
		TestTransport transport = new TestTransport();
		transport.agentActionGraphInspectPayload = linkedMap(
			"available", true,
			"actionsetValid", true,
			"actionsetRoot", "actionsets",
			"worldLoaded", false,
			"sessionState", "title_screen",
			"primitiveCount", 2,
			"actionsetCount", 1,
			"domainProviderCount", 1,
			"diagnosticCount", 0,
			"primitives", List.of(linkedMap(
				"id", "craft_item",
				"version", 1,
				"summary", "Craft an item",
				"foregroundActuation", true,
				"executorBinding", "WorldTaskRequest.CRAFT_RECIPE"
			)),
			"actionsets", List.of(linkedMap(
				"actionId", "make_bread",
				"namespace", "BUILTIN",
				"sourceName", "make_bread.yml",
				"summary", "Produce bread",
				"alternativeCount", 3
			)),
			"domainProviders", List.of(linkedMap(
				"id", "recipe_provider",
				"summary", "Plans craft routes",
				"producedGoal", "inventory.item"
			)),
			"actionsetDiagnostics", List.of()
		);

		CliResult result = execute(transport, "agent", "actions", "inspect");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent actions inspect\n"));
		assertTrue(result.output().contains("primitiveCount: 2\n"));
		assertTrue(result.output().contains("actionsetCount: 1\n"));
		assertTrue(result.output().contains("id: craft_item\n"));
		assertTrue(result.output().contains("actionId: make_bread\n"));
		assertTrue(result.output().contains("id: recipe_provider\n"));
	}

	@Test
	void agentActionsGoalStartSubmitsInventoryGoal() {
		TestTransport transport = new TestTransport();
		transport.agentActionGoalStartPayload = actionGoalPayload("action-graph-1", "RESOLVING");

		CliResult result = execute(
			transport,
			"agent", "actions", "goal", "start",
			"--item-id", "minecraft:bread",
			"--quantity", "2"
		);

		assertEquals(0, result.exitCode());
		assertEquals("inventory_item", transport.lastActionGoalPayload.get("kind"));
		assertEquals("minecraft:bread", transport.lastActionGoalPayload.get("itemId"));
		assertEquals(2, transport.lastActionGoalPayload.get("quantity"));
		assertTrue(result.output().contains("command: agent actions goal start\n"));
		assertTrue(result.output().contains("executionId: action-graph-1\n"));
		assertTrue(result.output().contains("state: RESOLVING\n"));
	}

	@Test
	void agentActionsGoalStartSubmitsResourceGoal() {
		TestTransport transport = new TestTransport();
		transport.agentActionGoalStartPayload = actionGoalPayload("action-graph-raw-iron", "RESOLVING");

		CliResult result = execute(
			transport,
			"agent", "actions", "goal", "start",
			"--kind", "resource_collection",
			"--resource-kind", "RAW_IRON",
			"--quantity", "3"
		);

		assertEquals(0, result.exitCode());
		assertEquals("resource_collection", transport.lastActionGoalPayload.get("kind"));
		assertEquals("RAW_IRON", transport.lastActionGoalPayload.get("resourceKind"));
		assertEquals(3, transport.lastActionGoalPayload.get("quantity"));
		assertTrue(result.output().contains("executionId: action-graph-raw-iron\n"));
	}

	@Test
	void agentActionsGoalInspectAndCancelRenderSnapshot() {
		TestTransport transport = new TestTransport();
		transport.agentActionGoalPayload = actionGoalPayload("action-graph-2", "WAITING_PRIMITIVE");
		transport.agentActionGoalCancelPayload = actionGoalPayload("action-graph-2", "CANCELLED");

		CliResult inspect = execute(transport, "agent", "actions", "goal", "inspect");
		CliResult cancel = execute(transport, "agent", "actions", "goal", "cancel");

		assertEquals(0, inspect.exitCode());
		assertEquals(0, cancel.exitCode());
		assertTrue(inspect.output().contains("command: agent actions goal inspect\n"));
		assertTrue(inspect.output().contains("state: WAITING_PRIMITIVE\n"));
		assertTrue(cancel.output().contains("command: agent actions goal cancel\n"));
		assertTrue(cancel.output().contains("state: CANCELLED\n"));
	}

	@Test
	void agentActionsGoalListAndIdSpecificOperationsUseSchedulerSurface() {
		TestTransport transport = new TestTransport();
		transport.agentActionGoalsPayload = linkedMap(
			"available", true,
			"foregroundExecutionId", "graph-foreground",
			"executionCount", 2,
			"nonterminalCount", 2,
			"suspendedCount", 1,
			"runnableCount", 0,
			"watchCount", 1,
			"executions", List.of(
				linkedMap("executionId", "graph-suspended", "state", "WATCHING", "residency", "SUSPENDED", "watchCount", 1, "updatedTick", 10),
				linkedMap("executionId", "graph-foreground", "state", "WAITING_PRIMITIVE", "residency", "FOREGROUND", "watchCount", 0, "updatedTick", 11)
			),
			"watches", List.of(linkedMap("executionId", "graph-suspended", "watchId", "watch-1", "stepId", "wait", "progressKind", "AREA_TICKING", "consumedEligibleTicks", 4, "timeoutTicks", 20, "progressEligible", true, "pauseReason", ""))
		);
		transport.agentActionGoalPayload = actionGoalPayload("graph-suspended", "WATCHING");
		transport.agentActionGoalCancelPayload = actionGoalPayload("graph-suspended", "CANCELLED");

		CliResult list = execute(transport, "agent", "actions", "goal", "list");
		CliResult inspect = execute(transport, "agent", "actions", "goal", "inspect", "--execution-id", "graph-suspended");
		CliResult cancel = execute(transport, "agent", "actions", "goal", "cancel", "--execution-id", "graph-suspended");

		assertEquals(0, list.exitCode());
		assertTrue(list.output().contains("executionCount: 2\n"));
		assertTrue(list.output().contains("residency: SUSPENDED\n"));
		assertEquals("graph-suspended", transport.lastActionGoalInspectExecutionId);
		assertEquals("graph-suspended", transport.lastActionGoalCancelExecutionId);
		assertEquals(0, inspect.exitCode());
		assertEquals(0, cancel.exitCode());
	}

	@Test
	void agentActionsFactsListPassesFiltersAndRendersFacts() {
		TestTransport transport = new TestTransport();
		transport.agentActionFactsPayload = linkedMap(
			"available", true,
			"worldId", "world-a",
			"type", "world.crop_group",
			"factCount", 1,
			"facts", List.of(linkedMap(
				"type", "world.crop_group",
				"keys", linkedMap("siteId", "farm-1", "cropId", "minecraft:wheat"),
				"provenance", "INFERRED",
				"observedTick", 100,
				"staleAfterTick", -1,
				"payload", linkedMap("matureCount", 3)
			))
		);

		CliResult result = execute(
			transport,
			"agent", "actions", "facts", "list",
			"--world-id", "world-a",
			"--type", "world.crop_group"
		);

		assertEquals(0, result.exitCode());
		assertEquals("world-a", transport.lastActionFactWorldId);
		assertEquals("world.crop_group", transport.lastActionFactType);
		assertTrue(result.output().contains("command: agent actions facts list\n"));
		assertTrue(result.output().contains("factCount: 1\n"));
		assertTrue(result.output().contains("type: world.crop_group\n"));
		assertTrue(result.output().contains("siteId: farm-1\n"));
	}

	@Test
	void agentActionsFactsClearPassesWorldScope() {
		TestTransport transport = new TestTransport();
		transport.agentActionFactsClearPayload = linkedMap(
			"available", true,
			"worldId", "world-a",
			"cleared", true,
			"clearedCount", 2
		);

		CliResult result = execute(
			transport,
			"agent", "actions", "facts", "clear",
			"--world-id", "world-a"
		);

		assertEquals(0, result.exitCode());
		assertEquals("world-a", transport.lastActionFactClearWorldId);
		assertTrue(result.output().contains("command: agent actions facts clear\n"));
		assertTrue(result.output().contains("cleared: true\n"));
		assertTrue(result.output().contains("clearedCount: 2\n"));
	}

	@Test
	void agentActionsWatchesListRendersActiveWatchSummary() {
		TestTransport transport = new TestTransport();
		transport.agentActionGoalsPayload = linkedMap(
			"available", true,
			"foregroundExecutionId", "",
			"executionCount", 1,
			"nonterminalCount", 1,
			"suspendedCount", 1,
			"runnableCount", 0,
			"watchCount", 1,
			"watches", List.of(linkedMap(
				"executionId", "action-graph-watch",
				"watchId", "action-graph-watch:wait_for_bread",
				"stepId", "wait_for_bread",
				"progressKind", "AREA_TICKING",
				"consumedEligibleTicks", 3,
				"timeoutTicks", 20,
				"progressEligible", true,
				"pauseReason", ""
			))
		);

		CliResult result = execute(transport, "agent", "actions", "watches", "list");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent actions watches list\n"));
		assertTrue(result.output().contains("watchCount: 1\n"));
		assertTrue(result.output().contains("watchId: action-graph-watch:wait_for_bread\n"));
	}

	@Test
	void agentMissionSubmitPassesNormalizedMissionPayload() {
		TestTransport transport = new TestTransport();
		transport.agentMissionSubmitPayload = linkedMap(
			"available", true,
			"task", linkedMap("state", "QUEUED")
		);

		CliResult result = execute(
			transport,
			"agent", "mission", "submit",
			"--type", "collect-resource",
			"--resource", "wood-logs",
			"--quantity", "4"
		);

		assertEquals(0, result.exitCode());
		assertEquals("COLLECT_RESOURCE", transport.lastSubmittedMission.get("type"));
		assertEquals("WOOD_LOGS", transport.lastSubmittedMission.get("resourceKind"));
		assertEquals(4, transport.lastSubmittedMission.get("quantity"));
		assertTrue(result.output().contains("command: agent mission submit\n"));
	}

	@Test
	void agentMissionSubmitReadsLedgerPayloadFromFile(@TempDir Path tempDir) throws Exception {
		TestTransport transport = new TestTransport();
		transport.agentMissionSubmitPayload = linkedMap(
			"available", true,
			"task", linkedMap("state", "QUEUED")
		);
		Path ledgerFile = tempDir.resolve("ledger.json");
		Files.writeString(
			ledgerFile,
			"""
				{
				  "missionId": "mission-craft-1",
				  "missionType": "CRAFT_TOOL",
				  "goalText": "Turn wood into sticks",
				  "steps": [],
				  "activeStepId": null,
				  "completionCriteria": [],
				  "replanReason": "debug",
				  "plannerNotes": "manual"
				}
				""",
			StandardCharsets.UTF_8
		);

		CliResult result = execute(
			transport,
			"agent", "mission", "submit",
			"--ledger-file", ledgerFile.toString()
		);

		assertEquals(0, result.exitCode());
		assertEquals("mission-craft-1", transport.lastSubmittedMission.get("missionId"));
		assertEquals("CRAFT_TOOL", transport.lastSubmittedMission.get("missionType"));
		assertTrue(result.output().contains("command: agent mission submit\n"));
	}

	@Test
	void agentCompactPassesWaitAndTimeout() {
		TestTransport transport = new TestTransport();
		transport.agentCompactPayload = linkedMap(
			"available", true,
			"started", true,
			"completed", false,
			"timeoutMs", 7000,
			"planner", linkedMap(
				"configured", true,
				"plannerVisionMode", "external_summary",
				"inFlight", true,
				"plannerInFlight", false,
				"compactionInFlight", true,
				"captureInFlight", false,
				"toolInFlight", false,
				"context", linkedMap(
					"compactionPending", false,
					"acceptedTurnCount", 5
				)
			)
		);

		CliResult result = execute(transport, "agent", "compact", "--no-wait", "--timeout-seconds", "7");

		assertEquals(0, result.exitCode());
		assertFalse(transport.lastCompactWait);
		assertEquals(Integer.valueOf(7000), transport.lastCompactTimeoutMs);
		assertTrue(result.output().contains("command: agent compact\n"));
		assertTrue(result.output().contains("completed: false\n"));
	}

	@Test
	void evaluationStatusShowsCapabilities() {
		TestTransport transport = new TestTransport();
		transport.evaluationStatusPayload = linkedMap(
			"available", true,
			"sessionMode", "SINGLEPLAYER_LOCAL",
			"worldLoaded", true,
			"scenarioRoot", "scenarios",
			"capabilities", List.of("scenario_list", "planner_loop"),
			"report", linkedMap(
				"status", "IDLE",
				"plannerTurns", 0,
				"elapsedTicks", 0,
				"evidenceReviewRequired", false
			)
		);

		CliResult result = execute(transport, "evaluation", "status", "--verbose");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: evaluation status\n"));
		assertTrue(result.output().contains("capabilityCount: 2\n"));
		assertTrue(result.output().contains("scenarioRoot: scenarios\n"));
		assertTrue(result.output().contains("[capability 1]\n"));
		assertTrue(result.output().contains("value: scenario_list\n"));
	}

	@Test
	void evaluationScenariosRendersTrackedScenarioSummary() {
		TestTransport transport = new TestTransport();
		transport.evaluationScenariosPayload = linkedMap(
			"available", true,
			"scenarioRoot", "scenarios",
			"scenarios", List.of(linkedMap(
				"id", "smelting-basic",
				"name", "Smelting basic",
				"frozen", true,
				"promptConfigured", true,
				"checkCount", 1,
				"maxPlannerTurns", 8,
				"maxElapsedTicks", 1200
			))
		);

		CliResult result = execute(transport, "evaluation", "scenarios");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: evaluation scenarios\n"));
		assertTrue(result.output().contains("scenarioCount: 1\n"));
		assertTrue(result.output().contains("id: smelting-basic\n"));
		assertTrue(result.output().contains("promptConfigured: true\n"));
	}

	@Test
	void evaluationConfigShowsCurrentWorldScenarioConfig() {
		TestTransport transport = new TestTransport();
		transport.evaluationConfigPayload = linkedMap(
			"available", true,
			"scenarioId", "smelting-basic",
			"configPath", "/repo/scenarios/smelting-basic/scenario.yml",
			"worldArchivePath", "/repo/scenarios/smelting-basic/world.zip",
			"scenarioRoot", "/repo/scenarios",
			"scenario", linkedMap(
				"id", "smelting-basic",
				"name", "Smelting basic",
				"frozen", true,
				"promptConfigured", true,
				"checkCount", 1,
				"worldArchive", "world.zip"
			)
		);

		CliResult result = execute(transport, "evaluation", "config");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: evaluation config\n"));
		assertTrue(result.output().contains("scenarioId: smelting-basic\n"));
		assertTrue(result.output().contains("configPath: /repo/scenarios/smelting-basic/scenario.yml\n"));
		assertTrue(result.output().contains("frozen: true\n"));
	}

	@Test
	void evaluationRunPassesScenarioNameAndOutputDir() {
		TestTransport transport = new TestTransport();
		transport.evaluationRunPayload = linkedMap(
			"accepted", true,
			"scenario", "smelting-basic",
			"worldName", "airicraft_eval_smelting-basic_1",
			"report", linkedMap(
				"status", "PENDING_WORLD",
				"scenarioId", "smelting-basic",
				"message", "Waiting for evaluation world",
				"plannerTurns", 0,
				"elapsedTicks", 0,
				"evidenceReviewRequired", false
			)
		);

		CliResult result = execute(
			transport,
			"evaluation", "run",
			"--scenario", "smelting-basic",
			"--output-dir", "/tmp/eval-output/run-1"
		);

		assertEquals(0, result.exitCode());
		assertEquals("smelting-basic", transport.lastEvaluationScenario);
		assertEquals("/tmp/eval-output/run-1", transport.lastEvaluationOutputDir);
		assertTrue(result.output().contains("scenario: smelting-basic\n"));
		assertTrue(result.output().contains("reportStatus: PENDING_WORLD\n"));
	}

	@Test
	void evaluationResultsVerboseShowsDiagnostics() {
		TestTransport transport = new TestTransport();
		transport.evaluationResultsPayload = linkedMap(
			"available", true,
			"report", linkedMap(
				"status", "FAILED",
				"scenarioId", "smelting-basic",
				"message", "Evaluation budget exhausted before expected outcome",
				"plannerTurns", 8,
				"elapsedTicks", 1200,
				"checks", List.of(linkedMap(
					"type", "inventory_contains",
					"passed", false,
					"message", "inventory contains 0x minecraft:iron_ingot, expected at least 1"
				)),
				"evidenceReviewRequired", true,
				"diagnostics", linkedMap(
					"maxPlannerTurns", 8
				)
			)
		);

		CliResult result = execute(transport, "evaluation", "results", "--verbose");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("reportStatus: FAILED\n"));
		assertTrue(result.output().contains("reportScenarioId: smelting-basic\n"));
		assertTrue(result.output().contains("[diagnostics]\n"));
		assertTrue(result.output().contains("maxPlannerTurns: 8\n"));
	}

	@Test
	void evaluationEvidenceVerboseShowsEvidenceBundle() {
		TestTransport transport = new TestTransport();
		transport.evaluationEvidencePayload = linkedMap(
			"available", true,
			"evidence", linkedMap(
				"report", linkedMap(
					"status", "NEEDS_REVIEW",
					"scenarioId", "smelting-basic",
					"plannerTurns", 8,
					"elapsedTicks", 1200,
					"evidenceReviewRequired", true
				),
				"plannerJournal", List.of(linkedMap("kind", "planner_succeeded"))
			)
		);

		CliResult result = execute(transport, "evaluation", "evidence", "--verbose");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: evaluation evidence\n"));
		assertTrue(result.output().contains("reportStatus: NEEDS_REVIEW\n"));
		assertTrue(result.output().contains("[evidence]\n"));
		assertTrue(result.output().contains("[plannerJournal 1]\n"));
	}

	@Test
	void agentEventsRecentPassesSinceFilter() {
		TestTransport transport = new TestTransport();
		transport.agentEventsPayload = linkedMap(
			"available", true,
			"oldestSeqNo", 10,
			"latestSeqNo", 12,
			"truncated", false,
			"events", List.of(linkedMap(
				"seqNo", 12,
				"tick", 300,
				"timestampMs", 123456789L,
				"type", "planner.goal_set"
			))
		);

		CliResult result = execute(transport, "agent", "events", "recent", "--since", "11");

		assertEquals(0, result.exitCode());
		assertEquals(Long.valueOf(11L), transport.lastEventSince);
		assertTrue(result.output().contains("eventCount: 1\n"));
	}

	@Test
	void worldsListOmitsVerboseFieldsByDefault() {
		TestTransport transport = new TestTransport();
		transport.worldsPayload = linkedMap(
			"available", true,
			"sessionState", "out_of_world",
			"worlds", List.of(linkedMap(
				"worldId", "survival-12345678",
				"name", "survival",
				"displayName", "Survival",
				"lastPlayed", 123L,
				"gameMode", "survival",
				"selectable", true,
				"immediatelyLoadable", true,
				"locked", false,
				"unavailable", false,
				"experimental", false,
				"details", "A long details string",
				"version", "1.21.8"
			))
		);

		CliResult compact = execute(transport, "worlds", "list");
		CliResult verbose = execute(transport, "worlds", "list", "--verbose");

		assertEquals(0, compact.exitCode());
		assertTrue(compact.output().contains("worldCount: 1\n"));
		assertFalse(compact.output().contains("details:"));
		assertTrue(verbose.output().contains("details: A long details string\n"));
		assertTrue(verbose.output().contains("version: 1.21.8\n"));
	}

	@Test
	void missingRequiredArgumentReturnsUsageError() {
		CliResult result = execute(new TestTransport(), "worlds", "join");

		assertEquals(2, result.exitCode());
		assertTrue(result.output().contains("status: error\n"));
		assertTrue(result.output().contains("command: worlds join\n"));
		assertTrue(result.output().contains("error_code: invalid_arguments\n"));
		assertTrue(result.output().contains("Missing required option: '--world-id"));
	}

	@Test
	void bridgeErrorsMapToExitCodes() {
		TestTransport transport = new TestTransport();
		transport.worldsJoinFailure = new BridgeUnavailableException("already_in_world", "A world is already loaded");
		transport.serversListFailure = new BridgeUnavailableException("minecraft_unavailable", "Minecraft bridge is not active");

		CliResult domainFailure = execute(transport, "worlds", "join", "--world-id", "survival-12345678");
		CliResult transportFailure = execute(transport, "servers", "list");

		assertEquals(4, domainFailure.exitCode());
		assertTrue(domainFailure.output().contains("error_code: already_in_world\n"));
		assertEquals(3, transportFailure.exitCode());
		assertTrue(transportFailure.output().contains("error_code: minecraft_unavailable\n"));
	}

	@Test
	void helpCommandShowsScopedUsage() {
		CliResult result = execute(new TestTransport(), "help", "highlights", "block");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("Usage: airicraft highlights block"));
		assertTrue(result.output().contains("--x"));
		assertTrue(result.output().contains("--duration-seconds"));
	}

	@Test
	void cameraScreenshotWritesFileAndPrintsMetadata(@TempDir Path tempDir) throws Exception {
		TestTransport transport = new TestTransport();
		transport.capturedImage = new CapturedImage(
			new byte[]{1, 2, 3, 4},
			"png",
			854,
			480,
			1920,
			1080,
			123456789L
		);
		Path output = tempDir.resolve("captures/view.png");

		CliResult result = execute(transport, "camera", "screenshot", "--output", output.toString());

		assertEquals(0, result.exitCode());
		assertTrue(Files.exists(output));
		assertArrayEquals(new byte[]{1, 2, 3, 4}, Files.readAllBytes(output));
		assertTrue(result.output().contains("status: ok\n"));
		assertTrue(result.output().contains("command: camera screenshot\n"));
		assertTrue(result.output().contains("outputPath: " + output.toAbsolutePath().normalize() + "\n"));
		assertTrue(result.output().contains("format: png\n"));
		assertTrue(result.output().contains("width: 854\n"));
		assertTrue(result.output().contains("height: 480\n"));
		assertTrue(result.output().contains("capturedAtMs: 123456789\n"));
	}

	@Test
	void mapStatusPrintsProviderSummary() {
		TestTransport transport = new TestTransport();
		transport.mapStatusPayload = linkedMap(
			"available", true,
			"preferredProvider", "journeymap",
			"providers", List.of(linkedMap("id", "journeymap", "available", true))
		);

		CliResult result = execute(transport, "map", "status");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("status: ok\n"));
		assertTrue(result.output().contains("command: map status\n"));
		assertTrue(result.output().contains("available: true\n"));
		assertTrue(result.output().contains("preferredProvider: journeymap\n"));
	}

	@Test
	void mapWaypointsListPrintsWaypoints() {
		TestTransport transport = new TestTransport();
		transport.mapWaypointsPayload = linkedMap(
			"waypoints", List.of(linkedMap(
				"id", "guid-1",
				"name", "Home",
				"dimension", "minecraft:overworld",
				"x", 1,
				"y", 64,
				"z", 2
			))
		);

		CliResult result = execute(transport, "map", "waypoints", "list");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: map waypoints list\n"));
		assertTrue(result.output().contains("id: guid-1\n"));
		assertTrue(result.output().contains("name: Home\n"));
		assertTrue(result.output().contains("dimension: minecraft:overworld\n"));
	}

	@Test
	void mapImageWritesFileAndPrintsMetadata(@TempDir Path tempDir) throws Exception {
		TestTransport transport = new TestTransport();
		transport.mapImage = new CapturedImage(
			new byte[] {9, 8, 7},
			"png",
			512,
			512,
			512,
			512,
			123456789L
		);
		Path output = tempDir.resolve("captures/map.png");

		CliResult result = execute(transport, "map", "image", "--kind", "worldmap", "--output", output.toString());

		assertEquals(0, result.exitCode());
		assertArrayEquals(new byte[] {9, 8, 7}, Files.readAllBytes(output));
		assertTrue(result.output().contains("command: map image\n"));
		assertTrue(result.output().contains("outputPath: " + output.toAbsolutePath().normalize() + "\n"));
		assertTrue(result.output().contains("format: png\n"));
		assertTrue(result.output().contains("width: 512\n"));
		assertTrue(result.output().contains("height: 512\n"));
	}

	@Test
	void mapImagePassesOriginCoordinates(@TempDir Path tempDir) {
		TestTransport transport = new TestTransport();
		Path output = tempDir.resolve("map.png");

		CliResult result = execute(
			transport,
			"map",
			"image",
			"--origin-x",
			"128",
			"--origin-z",
			"-64",
			"--output",
			output.toString()
		);

		assertEquals(0, result.exitCode());
		assertEquals(128, transport.lastMapImageRequest.get("originX"));
		assertEquals(-64, transport.lastMapImageRequest.get("originZ"));
	}

	@Test
	void cameraScreenshotRequiresOutputPath() {
		CliResult result = execute(new TestTransport(), "camera", "screenshot");

		assertEquals(2, result.exitCode());
		assertTrue(result.output().contains("status: error\n"));
		assertTrue(result.output().contains("command: camera screenshot\n"));
		assertTrue(result.output().contains("error_code: invalid_arguments\n"));
		assertTrue(result.output().contains("Missing required option: '--output"));
	}

	@Test
	void cameraScreenshotBridgeErrorsUseTransportExitCodes() {
		TestTransport transport = new TestTransport();
		transport.captureScreenshotFailure = new BridgeUnavailableException("capture_timeout", "Screenshot capture timed out");

		CliResult result = execute(transport, "camera", "screenshot", "--output", "capture.png");

		assertEquals(4, result.exitCode());
		assertTrue(result.output().contains("command: camera screenshot\n"));
		assertTrue(result.output().contains("error_code: capture_timeout\n"));
	}

	@Test
	void visionDescribePrintsDeterministicText() {
		TestTransport transport = new TestTransport();
		transport.visionDescriptionResult = new VisionDescriptionResult(
			"text",
			987654321L,
			"gpt-4.1-mini",
			"A birch forest hill with open sky and no visible structures."
		);

		CliResult result = execute(transport, "vision", "describe");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("status: ok\n"));
		assertTrue(result.output().contains("command: vision describe\n"));
		assertTrue(result.output().contains("format: text\n"));
		assertTrue(result.output().contains("capturedAtMs: 987654321\n"));
		assertTrue(result.output().contains("model: gpt-4.1-mini\n"));
		assertTrue(result.output().contains("description: A birch forest hill with open sky and no visible structures.\n"));
	}

	@Test
	void visionDescribePassesPromptOverride() {
		TestTransport transport = new TestTransport();

		CliResult result = execute(transport, "vision", "describe", "--prompt", "Describe hazards only.");

		assertEquals(0, result.exitCode());
		assertEquals("Describe hazards only.", transport.lastVisionPrompt);
	}

	private static CliResult execute(MinecraftTransport transport, String... args) {
		StringWriter writer = new StringWriter();
		CommandLine commandLine = AiricraftCliMain.createCommandLine(transport, new PrintWriter(writer, true));
		commandLine.setColorScheme(new CommandLine.Help.ColorScheme.Builder().ansi(CommandLine.Help.Ansi.OFF).build());
		int exitCode = commandLine.execute(args);
		return new CliResult(exitCode, writer.toString().replace("\r\n", "\n"));
	}

	private static LinkedHashMap<String, Object> linkedMap(Object... values) {
		LinkedHashMap<String, Object> map = new LinkedHashMap<>();
		for (int index = 0; index < values.length; index += 2) {
			map.put(String.valueOf(values[index]), values[index + 1]);
		}
		return map;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> castMap(Object value) {
		return (Map<String, Object>) value;
	}

	private static Map<String, Object> actionGoalPayload(String executionId, String state) {
		return linkedMap(
			"available", true,
			"executionId", executionId,
			"state", state,
			"resolved", false,
			"accepted", false,
			"cursor", 0,
			"traceEventCount", 1,
			"goal", linkedMap("fact", "inventory.item", "itemId", "minecraft:bread", "countAtLeast", 1),
			"route", linkedMap("cost", 0, "steps", List.of())
		);
	}

	private record CliResult(int exitCode, String output) {
	}

	private static final class TestTransport implements MinecraftTransport {
		private Map<String, Object> statusPayload = Map.of();
		private Map<String, Object> reloadPayload = Map.of();
		private Map<String, Object> worldsPayload = Map.of("worlds", List.of());
		private Map<String, Object> serversPayload = Map.of("servers", List.of());
		private Map<String, Object> focusPayload = Map.of();
		private Map<String, Object> snapshotPayload = Map.of();
		private Map<String, Object> playerNearbyEntitiesPayload = Map.of("entities", List.of());
		private Map<String, Object> agentStatusPayload = Map.of();
		private Map<String, Object> agentToolsPayload = Map.of("tools", List.of());
		private Map<String, Object> agentToolCallPayload = Map.of();
		private Map<String, Object> agentSessionPayload = Map.of();
		private Map<String, Object> agentSessionOpenLanPayload = Map.of();
		private Map<String, Object> agentGoalsPayload = Map.of();
		private Map<String, Object> agentTreePayload = Map.of();
		private Map<String, Object> agentDialoguePayload = Map.of();
		private Map<String, Object> agentDebugStatePayload = Map.of();
		private Map<String, Object> agentDebugTimelinePayload = Map.of("entries", List.of());
		private Map<String, Object> clientTickDebugStatePayload = Map.of();
		private Map<String, Object> clientTickDebugPausePayload = Map.of();
		private Map<String, Object> clientTickDebugStepPayload = Map.of();
		private Map<String, Object> clientTickDebugContinuePayload = Map.of();
		private Map<String, Object> clientTickWorldQueryPayload = Map.of();
		private Map<String, Object> clientTickTraceStatusPayload = Map.of();
		private Map<String, Object> clientTickTraceStartPayload = Map.of();
		private Map<String, Object> clientTickTraceStopPayload = Map.of();
		private Map<String, Object> clientTickTraceRecordsPayload = Map.of("records", List.of());
		private Map<String, Object> agentDebugChatPayload = Map.of();
		private Map<String, Object> agentDebugIdleTriggerPayload = Map.of();
		private Map<String, Object> agentContextPayload = Map.of();
		private Map<String, Object> agentTasksPayload = Map.of();
		private Map<String, Object> agentLedgerPayload = Map.of();
		private Map<String, Object> agentEvidencePayload = Map.of();
		private Map<String, Object> agentStepExecutionPayload = Map.of();
		private Map<String, Object> agentActionGraphInspectPayload = Map.of();
		private Map<String, Object> agentActionGoalPayload = Map.of();
		private Map<String, Object> agentActionGoalsPayload = Map.of("executions", List.of(), "watches", List.of());
		private Map<String, Object> agentActionGoalStartPayload = Map.of();
		private Map<String, Object> agentActionGoalCancelPayload = Map.of();
		private String lastActionGoalInspectExecutionId;
		private String lastActionGoalCancelExecutionId;
		private Map<String, Object> agentActionFactsPayload = Map.of("facts", List.of());
		private Map<String, Object> agentActionFactsClearPayload = Map.of();
		private Map<String, Object> agentTaskSubmitPayload = Map.of();
		private Map<String, Object> agentTaskResumePayload = Map.of();
		private Map<String, Object> agentMissionSubmitPayload = Map.of();
		private Map<String, Object> agentEventsPayload = Map.of("events", List.of());
		private Map<String, Object> agentCompactPayload = Map.of("started", true);
		private Map<String, Object> agentEventPolicyPayload = Map.of("activeRules", List.of(), "recentInterventions", List.of());
		private Map<String, Object> evaluationStatusPayload = Map.of("capabilities", List.of(), "report", Map.of());
		private Map<String, Object> evaluationScenariosPayload = Map.of("scenarios", List.of(), "report", Map.of());
		private Map<String, Object> evaluationConfigPayload = Map.of("scenario", Map.of());
		private Map<String, Object> evaluationRunPayload = Map.of("accepted", true);
		private Map<String, Object> evaluationResultsPayload = Map.of("report", Map.of());
		private Map<String, Object> evaluationEvidencePayload = Map.of("evidence", Map.of());
		private Map<String, Object> blockHighlightPayload = Map.of("highlightId", "highlight-1");
		private Map<String, Object> regionHighlightPayload = Map.of("highlightId", "highlight-2");
		private Map<String, Object> highlightsPayload = Map.of("highlights", List.of());
		private Map<String, Object> clearHighlightPayload = Map.of("cleared", true);
		private Map<String, Object> clearHighlightsPayload = Map.of("cleared", true, "clearedCount", 0);
		private Map<String, Object> mapStatusPayload = Map.of("available", false, "providers", List.of());
		private Map<String, Object> mapWaypointsPayload = Map.of("waypoints", List.of());
		private Map<String, Object> mapWaypointSetPayload = Map.of("waypoint", Map.of("id", "guid-1"));
		private Map<String, Object> mapWaypointDeletePayload = Map.of("deleted", true);
		private Map<String, Object> lastMapImageRequest = Map.of();
		private Map<String, Object> worldsJoinPayload = Map.of("started", true);
		private Map<String, Object> serversJoinPayload = Map.of("started", true);
		private Map<String, Object> lookAtPayload = Map.of("started", true);
		private Map<String, Object> playerAttackEntityPayload = Map.of("accepted", true);
		private Map<String, Object> playerUseEntityPayload = Map.of("accepted", true);
		private CapturedImage capturedImage = new CapturedImage(new byte[0], "png", 854, 480, 854, 480, 1L);
		private CapturedImage mapImage = new CapturedImage(new byte[0], "png", 512, 512, 512, 512, 1L);
		private VisionDescriptionResult visionDescriptionResult = new VisionDescriptionResult("text", 1L, "gpt-4.1-mini", "desc");
		private String lastVisionPrompt;
		private Long lastEventSince;
		private boolean lastCompactWait = true;
		private Integer lastCompactTimeoutMs;
		private boolean agentDebugIdleTriggerCalled;
		private String lastEvaluationScenario;
		private String lastEvaluationOutputDir;
		private boolean agentEventPolicyCleared;
		private boolean reloadCalled;
		private Map<String, Object> lastSubmittedTask;
		private String lastResumeHoldId;
		private Map<String, Object> lastSubmittedMission;
		private Map<String, Object> lastActionGoalPayload;
		private String lastActionFactWorldId;
		private String lastActionFactType;
		private String lastActionFactClearWorldId;
		private String lastDebugChatMessage;
		private Long lastDebugTimelineSince;
		private String lastClientTickDebugSessionId;
		private Long lastClientTickDebugPauseEpoch;
		private Map<String, Object> lastClientTickWorldQuery;
		private Map<String, Object> lastClientTickTraceStartRequest;
		private String lastClientTickTraceId;
		private Long lastClientTickTraceSince;
		private int lastClientTickTraceLimit;
		private boolean lastClientTickTraceIncludeImageBytes;
		private String lastAttackEntityUuid;
		private String lastAttackEntityName;
		private String lastAttackEntityTypeId;
		private String lastAttackEntityMode;
		private String lastUseEntityUuid;
		private String lastUseEntityName;
		private String lastUseEntityTypeId;
		private String lastUseEntityItemId;
		private double lastLookAtX;
		private double lastLookAtY;
		private double lastLookAtZ;
		private Integer lastLookAtDurationTicks;
		private boolean playerNearbyEntitiesCalled;
		private String lastAgentToolName;
		private Map<String, Object> lastAgentToolArguments;
		private Integer lastAgentToolTimeoutMs;

		private RuntimeException worldsJoinFailure;
		private RuntimeException serversListFailure;
		private RuntimeException captureScreenshotFailure;

		@Override
		public Map<String, Object> getStatus() {
			return statusPayload;
		}

		@Override
		public Map<String, Object> reload() {
			reloadCalled = true;
			return reloadPayload;
		}

		@Override
		public Map<String, Object> getFocus() {
			return focusPayload;
		}

		@Override
		public Map<String, Object> getWorldSnapshot(Integer x, Integer y, Integer z, int radius) {
			return snapshotPayload;
		}

		@Override
		public Map<String, Object> listNearbyEntities() {
			playerNearbyEntitiesCalled = true;
			return playerNearbyEntitiesPayload;
		}

		@Override
		public CapturedImage captureScreenshot() {
			if (captureScreenshotFailure != null) {
				throw captureScreenshotFailure;
			}
			return capturedImage;
		}

		@Override
		public VisionDescriptionResult describeVision(String prompt) {
			lastVisionPrompt = prompt;
			return visionDescriptionResult;
		}

		@Override
		public Map<String, Object> mapStatus() {
			return mapStatusPayload;
		}

		@Override
		public Map<String, Object> listMapWaypoints(String providerId, String dimension) {
			return mapWaypointsPayload;
		}

		@Override
		public Map<String, Object> setMapWaypoint(Map<String, Object> request) {
			return mapWaypointSetPayload;
		}

		@Override
		public Map<String, Object> deleteMapWaypoint(String waypointId) {
			return mapWaypointDeletePayload;
		}

		@Override
		public CapturedImage captureMapImage(Map<String, Object> request) {
			lastMapImageRequest = request;
			return mapImage;
		}

		@Override
		public Map<String, Object> listWorlds() {
			return worldsPayload;
		}

		@Override
		public Map<String, Object> joinWorld(String worldId) {
			if (worldsJoinFailure != null) {
				throw worldsJoinFailure;
			}
			return worldsJoinPayload;
		}

		@Override
		public Map<String, Object> listServers() {
			if (serversListFailure != null) {
				throw serversListFailure;
			}
			return serversPayload;
		}

		@Override
		public Map<String, Object> joinServer(String serverId) {
			return serversJoinPayload;
		}

		@Override
		public Map<String, Object> lookAt(double x, double y, double z, Integer durationTicks) {
			lastLookAtX = x;
			lastLookAtY = y;
			lastLookAtZ = z;
			lastLookAtDurationTicks = durationTicks;
			return lookAtPayload;
		}

		@Override
		public Map<String, Object> attackEntity(String uuid, String name, String entityTypeId, String mode) {
			lastAttackEntityUuid = uuid;
			lastAttackEntityName = name;
			lastAttackEntityTypeId = entityTypeId;
			lastAttackEntityMode = mode;
			return playerAttackEntityPayload;
		}

		@Override
		public Map<String, Object> useEntity(String uuid, String name, String entityTypeId, String itemId) {
			lastUseEntityUuid = uuid;
			lastUseEntityName = name;
			lastUseEntityTypeId = entityTypeId;
			lastUseEntityItemId = itemId;
			return playerUseEntityPayload;
		}

		@Override
		public Map<String, Object> createBlockHighlight(int x, int y, int z, String color, Long durationMs, String overlayText) {
			return blockHighlightPayload;
		}

		@Override
		public Map<String, Object> createRegionHighlight(int x1, int y1, int z1, int x2, int y2, int z2, String color, Long durationMs, String overlayText) {
			return regionHighlightPayload;
		}

		@Override
		public Map<String, Object> listHighlights() {
			return highlightsPayload;
		}

		@Override
		public Map<String, Object> clearHighlight(String highlightId) {
			return clearHighlightPayload;
		}

		@Override
		public Map<String, Object> clearHighlights() {
			return clearHighlightsPayload;
		}

		@Override
		public Map<String, Object> getAgentStatus() {
			return agentStatusPayload;
		}

		@Override
		public Map<String, Object> listAgentTools() {
			return agentToolsPayload;
		}

		@Override
		public Map<String, Object> callAgentTool(String name, Map<String, Object> arguments, Integer timeoutMs) {
			lastAgentToolName = name;
			lastAgentToolArguments = arguments;
			lastAgentToolTimeoutMs = timeoutMs;
			return agentToolCallPayload;
		}

		@Override
		public Map<String, Object> getAgentSession() {
			return agentSessionPayload;
		}

		@Override
		public Map<String, Object> openAgentSessionLan() {
			return agentSessionOpenLanPayload;
		}

		@Override
		public Map<String, Object> getAgentGoals() {
			return agentGoalsPayload;
		}

		@Override
		public Map<String, Object> getAgentTree() {
			return agentTreePayload;
		}

		@Override
		public Map<String, Object> getAgentDialogue() {
			return agentDialoguePayload;
		}

		@Override
		public Map<String, Object> getAgentDebugState() {
			return agentDebugStatePayload;
		}

		@Override
		public Map<String, Object> listAgentDebugTimeline(Long sinceEntryId) {
			lastDebugTimelineSince = sinceEntryId;
			return agentDebugTimelinePayload;
		}

		@Override
		public Map<String, Object> getClientTickDebugState() {
			return clientTickDebugStatePayload;
		}

		@Override
		public Map<String, Object> pauseClientTicks() {
			return clientTickDebugPausePayload;
		}

		@Override
		public Map<String, Object> stepClientTick(String debugSessionId, long pauseEpoch) {
			lastClientTickDebugSessionId = debugSessionId;
			lastClientTickDebugPauseEpoch = pauseEpoch;
			return clientTickDebugStepPayload;
		}

		@Override
		public Map<String, Object> continueClientTicks(String debugSessionId, long pauseEpoch) {
			lastClientTickDebugSessionId = debugSessionId;
			lastClientTickDebugPauseEpoch = pauseEpoch;
			return clientTickDebugContinuePayload;
		}

		@Override
		public Map<String, Object> queryClientTickWorld(Map<String, Object> request) {
			lastClientTickWorldQuery = request;
			return clientTickWorldQueryPayload;
		}

		@Override
		public Map<String, Object> getClientTickTraceStatus() {
			return clientTickTraceStatusPayload;
		}

		@Override
		public Map<String, Object> startClientTickTrace(Map<String, Object> request) {
			lastClientTickTraceStartRequest = request;
			return clientTickTraceStartPayload;
		}

		@Override
		public Map<String, Object> stopClientTickTrace(String traceId) {
			lastClientTickTraceId = traceId;
			return clientTickTraceStopPayload;
		}

		@Override
		public Map<String, Object> listClientTickTraceRecords(
			String traceId,
			Long sinceClientTickId,
			int limit,
			boolean includeImageBytes
		) {
			lastClientTickTraceId = traceId;
			lastClientTickTraceSince = sinceClientTickId;
			lastClientTickTraceLimit = limit;
			lastClientTickTraceIncludeImageBytes = includeImageBytes;
			return clientTickTraceRecordsPayload;
		}

		@Override
		public Map<String, Object> sendAgentDebugChat(String message) {
			lastDebugChatMessage = message;
			return agentDebugChatPayload;
		}

		@Override
		public Map<String, Object> fireAgentDebugIdleTrigger() {
			agentDebugIdleTriggerCalled = true;
			return agentDebugIdleTriggerPayload;
		}

		@Override
		public Map<String, Object> getAgentContext() {
			return agentContextPayload;
		}

		@Override
		public Map<String, Object> getAgentTasks() {
			return agentTasksPayload;
		}

		@Override
		public Map<String, Object> submitAgentTask(Map<String, Object> taskPayload) {
			lastSubmittedTask = taskPayload;
			return agentTaskSubmitPayload;
		}

		@Override
		public Map<String, Object> resumeAgentTask(String holdId) {
			lastResumeHoldId = holdId;
			return agentTaskResumePayload;
		}

		@Override
		public Map<String, Object> getAgentLedger() {
			return agentLedgerPayload;
		}

		@Override
		public Map<String, Object> getAgentEvidence() {
			return agentEvidencePayload;
		}

		@Override
		public Map<String, Object> getAgentStepExecution() {
			return agentStepExecutionPayload;
		}

		@Override
		public Map<String, Object> inspectAgentActionGraph() {
			return agentActionGraphInspectPayload;
		}

		@Override
		public Map<String, Object> getAgentActionGoal() {
			return agentActionGoalPayload;
		}

		@Override
		public Map<String, Object> getAgentActionGoal(String executionId) {
			lastActionGoalInspectExecutionId = executionId;
			return agentActionGoalPayload;
		}

		@Override
		public Map<String, Object> listAgentActionGoals() {
			return agentActionGoalsPayload;
		}

		@Override
		public Map<String, Object> startAgentActionGoal(Map<String, Object> goalPayload) {
			lastActionGoalPayload = goalPayload;
			return agentActionGoalStartPayload;
		}

		@Override
		public Map<String, Object> cancelAgentActionGoal() {
			return agentActionGoalCancelPayload;
		}

		@Override
		public Map<String, Object> cancelAgentActionGoal(String executionId) {
			lastActionGoalCancelExecutionId = executionId;
			return agentActionGoalCancelPayload;
		}

		@Override
		public Map<String, Object> listAgentActionFacts(String worldId, String type) {
			lastActionFactWorldId = worldId;
			lastActionFactType = type;
			return agentActionFactsPayload;
		}

		@Override
		public Map<String, Object> clearAgentActionFacts(String worldId) {
			lastActionFactClearWorldId = worldId;
			return agentActionFactsClearPayload;
		}

		@Override
		public Map<String, Object> submitAgentMission(Map<String, Object> missionPayload) {
			lastSubmittedMission = missionPayload;
			return agentMissionSubmitPayload;
		}

		@Override
		public Map<String, Object> cancelAgentTask() {
			return Map.of("available", true, "cancelled", true);
		}

		@Override
		public Map<String, Object> listRecentAgentEvents(Long sinceSeqNo) {
			lastEventSince = sinceSeqNo;
			return agentEventsPayload;
		}

		@Override
		public Map<String, Object> triggerAgentCompaction(boolean wait, Integer timeoutMs) {
			lastCompactWait = wait;
			lastCompactTimeoutMs = timeoutMs;
			return agentCompactPayload;
		}

		@Override
		public Map<String, Object> getAgentEventPolicy() {
			return agentEventPolicyPayload;
		}

		@Override
		public Map<String, Object> clearAgentEventPolicy() {
			agentEventPolicyCleared = true;
			return agentEventPolicyPayload;
		}

		@Override
		public Map<String, Object> getEvaluationStatus() {
			return evaluationStatusPayload;
		}

		@Override
		public Map<String, Object> getEvaluationScenarios() {
			return evaluationScenariosPayload;
		}

		@Override
		public Map<String, Object> getEvaluationConfig() {
			return evaluationConfigPayload;
		}

		@Override
		public Map<String, Object> runEvaluationScenario(String scenario, String outputDir) {
			lastEvaluationScenario = scenario;
			lastEvaluationOutputDir = outputDir;
			return evaluationRunPayload;
		}

		@Override
		public Map<String, Object> getEvaluationResults() {
			return evaluationResultsPayload;
		}

		@Override
		public Map<String, Object> getEvaluationEvidence() {
			return evaluationEvidencePayload;
		}

	}
}
