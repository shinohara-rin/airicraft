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
			)
		);

		CliResult result = execute(transport, "agent", "context", "--verbose");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("contextExcerptLineCount: 1\n"));
		assertTrue(result.output().contains("value: Context update: You took 4 damage from minecraft:fall and dropped to 16 health just now.\n"));
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
	void agentStatusIncludesPlannerVisionMode() {
		TestTransport transport = new TestTransport();
		transport.agentStatusPayload = linkedMap(
			"available", true,
			"initialized", true,
			"tickCount", 42,
			"llmAvailable", true,
			"visionAvailable", false,
			"plannerVisionMode", "native_tool_image",
			"degraded", false
		);

		CliResult result = execute(transport, "agent", "status");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent status\n"));
		assertTrue(result.output().contains("plannerVisionMode: native_tool_image\n"));
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

		CliResult result = execute(transport, "player", "attack-entity", "--entity-type-id", "minecraft:sheep");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: player attack-entity\n"));
		assertEquals("minecraft:sheep", transport.lastAttackEntityTypeId);
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
	void verificationStatusShowsCapabilities() {
		TestTransport transport = new TestTransport();
		transport.verificationStatusPayload = linkedMap(
			"available", true,
			"sessionMode", "SINGLEPLAYER_LOCAL",
			"worldLoaded", true,
			"capabilities", List.of("player_state", "scenario_run")
		);

		CliResult result = execute(transport, "verification", "status", "--verbose");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: verification status\n"));
		assertTrue(result.output().contains("capabilityCount: 2\n"));
		assertTrue(result.output().contains("[capability 1]\n"));
		assertTrue(result.output().contains("value: player_state\n"));
	}

	@Test
	void verificationPlayerTeleportPassesCoordinates() {
		TestTransport transport = new TestTransport();
		transport.verificationPlayerPayload = linkedMap(
			"available", true,
			"sessionMode", "SINGLEPLAYER_LOCAL",
			"worldLoaded", true,
			"teleported", true,
			"x", 10.5D,
			"y", 94.0D,
			"z", -3.0D,
			"health", 20.0D,
			"maxHealth", 20.0D,
			"food", 20,
			"saturation", 5.0D,
			"onGround", false,
			"fallDistance", 0.0D,
			"gameMode", "survival",
			"dimensionId", "minecraft:overworld"
		);

		CliResult result = execute(transport, "verification", "player", "teleport", "--x", "10.5", "--y", "94", "--z", "-3");

		assertEquals(0, result.exitCode());
		assertEquals(10.5D, transport.lastTeleportX);
		assertEquals(94.0D, transport.lastTeleportY);
		assertEquals(-3.0D, transport.lastTeleportZ);
		assertTrue(result.output().contains("teleported: true\n"));
		assertTrue(result.output().contains("gameMode: survival\n"));
	}

	@Test
	void verificationPlayerVelocityPassesComponents() {
		TestTransport transport = new TestTransport();
		transport.verificationPlayerPayload = linkedMap(
			"available", true,
			"sessionMode", "SINGLEPLAYER_LOCAL",
			"worldLoaded", true,
			"applied", true,
			"x", 10.5D,
			"y", 94.0D,
			"z", -3.0D,
			"health", 20.0D,
			"maxHealth", 20.0D,
			"food", 20,
			"saturation", 5.0D,
			"onGround", false,
			"fallDistance", 0.0D,
			"gameMode", "survival",
			"dimensionId", "minecraft:overworld"
		);

		CliResult result = execute(transport, "verification", "player", "velocity", "--x", "0", "--y", "1.5", "--z", "-0.25");

		assertEquals(0, result.exitCode());
		assertEquals(0.0D, transport.lastVelocityX);
		assertEquals(1.5D, transport.lastVelocityY);
		assertEquals(-0.25D, transport.lastVelocityZ);
		assertTrue(result.output().contains("applied: true\n"));
	}

	@Test
	void verificationPlayerRespawnCallsTransport() {
		TestTransport transport = new TestTransport();
		transport.verificationPlayerPayload = linkedMap(
			"available", true,
			"sessionMode", "SINGLEPLAYER_LOCAL",
			"worldLoaded", true,
			"respawned", true,
			"health", 20.0D,
			"currentScreen", "in_game"
		);

		CliResult result = execute(transport, "verification", "player", "respawn");

		assertEquals(0, result.exitCode());
		assertTrue(transport.verificationRespawnCalled);
		assertTrue(result.output().contains("respawned: true\n"));
	}

	@Test
	void verificationRunPassesScenarioName() {
		TestTransport transport = new TestTransport();
		transport.verificationRunPayload = linkedMap(
			"accepted", true,
			"scenario", "damage.fall_context",
			"running", true
		);

		CliResult result = execute(transport, "verification", "run", "--scenario", "damage.fall_context");

		assertEquals(0, result.exitCode());
		assertEquals("damage.fall_context", transport.lastVerificationScenario);
		assertTrue(result.output().contains("scenario: damage.fall_context\n"));
	}

	@Test
	void verificationResultsVerboseShowsDiagnostics() {
		TestTransport transport = new TestTransport();
		transport.verificationResultsPayload = linkedMap(
			"available", true,
			"scenarios", List.of("damage.fall_context"),
			"report", linkedMap(
				"status", "FAILED",
				"scenarioName", "damage.fall_context",
				"message", "Timed out after 300 ticks",
				"steps", List.of(linkedMap(
					"description", "planner context shows new damage notice",
					"status", "FAILED",
					"waitedTicks", 300,
					"message", "Timed out after 300 ticks"
				)),
				"diagnostics", linkedMap(
					"failureReason", "Timed out after 300 ticks"
				)
			)
		);

		CliResult result = execute(transport, "verification", "results", "--verbose");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("reportStatus: FAILED\n"));
		assertTrue(result.output().contains("[report]\n"));
		assertTrue(result.output().contains("[diagnostics]\n"));
		assertTrue(result.output().contains("failureReason: Timed out after 300 ticks\n"));
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
		private Map<String, Object> agentSessionPayload = Map.of();
		private Map<String, Object> agentSessionOpenLanPayload = Map.of();
		private Map<String, Object> agentGoalsPayload = Map.of();
		private Map<String, Object> agentTreePayload = Map.of();
		private Map<String, Object> agentDialoguePayload = Map.of();
		private Map<String, Object> agentDebugStatePayload = Map.of();
		private Map<String, Object> agentDebugTimelinePayload = Map.of("entries", List.of());
		private Map<String, Object> agentDebugChatPayload = Map.of();
		private Map<String, Object> agentContextPayload = Map.of();
		private Map<String, Object> agentTasksPayload = Map.of();
		private Map<String, Object> agentLedgerPayload = Map.of();
		private Map<String, Object> agentEvidencePayload = Map.of();
		private Map<String, Object> agentStepExecutionPayload = Map.of();
		private Map<String, Object> agentTaskSubmitPayload = Map.of();
		private Map<String, Object> agentMissionSubmitPayload = Map.of();
		private Map<String, Object> agentEventsPayload = Map.of("events", List.of());
		private Map<String, Object> agentCompactPayload = Map.of("started", true);
		private Map<String, Object> agentEventPolicyPayload = Map.of("activeRules", List.of(), "recentInterventions", List.of());
		private Map<String, Object> verificationStatusPayload = Map.of("capabilities", List.of());
		private Map<String, Object> verificationPlayerPayload = Map.of();
		private Map<String, Object> verificationRunPayload = Map.of("accepted", true);
		private Map<String, Object> verificationResultsPayload = Map.of("scenarios", List.of(), "report", Map.of());
		private Map<String, Object> blockHighlightPayload = Map.of("highlightId", "highlight-1");
		private Map<String, Object> regionHighlightPayload = Map.of("highlightId", "highlight-2");
		private Map<String, Object> highlightsPayload = Map.of("highlights", List.of());
		private Map<String, Object> clearHighlightPayload = Map.of("cleared", true);
		private Map<String, Object> clearHighlightsPayload = Map.of("cleared", true, "clearedCount", 0);
		private Map<String, Object> worldsJoinPayload = Map.of("started", true);
		private Map<String, Object> serversJoinPayload = Map.of("started", true);
		private Map<String, Object> lookAtPayload = Map.of("started", true);
		private Map<String, Object> playerAttackEntityPayload = Map.of("accepted", true);
		private Map<String, Object> playerUseEntityPayload = Map.of("accepted", true);
		private CapturedImage capturedImage = new CapturedImage(new byte[0], "png", 854, 480, 854, 480, 1L);
		private VisionDescriptionResult visionDescriptionResult = new VisionDescriptionResult("text", 1L, "gpt-4.1-mini", "desc");
		private String lastVisionPrompt;
		private Long lastEventSince;
		private boolean lastCompactWait = true;
		private Integer lastCompactTimeoutMs;
		private String lastVerificationScenario;
		private Double lastTeleportX;
		private Double lastTeleportY;
		private Double lastTeleportZ;
		private Double lastVelocityX;
		private Double lastVelocityY;
		private Double lastVelocityZ;
		private String lastVerificationGameMode;
		private String lastVerificationCommand;
		private boolean verificationRespawnCalled;
		private boolean agentEventPolicyCleared;
		private boolean reloadCalled;
		private Map<String, Object> lastSubmittedTask;
		private Map<String, Object> lastSubmittedMission;
		private String lastDebugChatMessage;
		private Long lastDebugTimelineSince;
		private String lastAttackEntityUuid;
		private String lastAttackEntityName;
		private String lastAttackEntityTypeId;
		private String lastUseEntityUuid;
		private String lastUseEntityName;
		private String lastUseEntityTypeId;
		private String lastUseEntityItemId;
		private boolean playerNearbyEntitiesCalled;

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
		public Map<String, Object> lookAt(double x, double y, double z) {
			return lookAtPayload;
		}

		@Override
		public Map<String, Object> attackEntity(String uuid, String name, String entityTypeId) {
			lastAttackEntityUuid = uuid;
			lastAttackEntityName = name;
			lastAttackEntityTypeId = entityTypeId;
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
		public Map<String, Object> sendAgentDebugChat(String message) {
			lastDebugChatMessage = message;
			return agentDebugChatPayload;
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
		public Map<String, Object> getVerificationStatus() {
			return verificationStatusPayload;
		}

		@Override
		public Map<String, Object> getVerificationPlayerState() {
			return verificationPlayerPayload;
		}

		@Override
		public Map<String, Object> teleportVerificationPlayer(double x, double y, double z) {
			lastTeleportX = x;
			lastTeleportY = y;
			lastTeleportZ = z;
			return verificationPlayerPayload;
		}

		@Override
		public Map<String, Object> setVerificationPlayerVelocity(double x, double y, double z) {
			lastVelocityX = x;
			lastVelocityY = y;
			lastVelocityZ = z;
			return verificationPlayerPayload;
		}

		@Override
		public Map<String, Object> respawnVerificationPlayer() {
			verificationRespawnCalled = true;
			return verificationPlayerPayload;
		}

		@Override
		public Map<String, Object> setVerificationPlayerGameMode(String mode) {
			lastVerificationGameMode = mode;
			return verificationPlayerPayload;
		}

		@Override
		public Map<String, Object> runVerificationCommand(String command) {
			lastVerificationCommand = command;
			return verificationPlayerPayload;
		}

		@Override
		public Map<String, Object> runVerificationScenario(String scenario) {
			lastVerificationScenario = scenario;
			return verificationRunPayload;
		}

		@Override
		public Map<String, Object> getVerificationResults() {
			return verificationResultsPayload;
		}
	}
}
