package ai.moeru.airicraft.wrapper;

import java.util.Map;

interface MinecraftTransport {
	Map<String, Object> getStatus();

	Map<String, Object> reload();

	Map<String, Object> getFocus();

	Map<String, Object> getWorldSnapshot(Integer x, Integer y, Integer z, int radius);

	Map<String, Object> listNearbyEntities();

	CapturedImage captureScreenshot();

	VisionDescriptionResult describeVision(String prompt);

	Map<String, Object> mapStatus();

	Map<String, Object> listMapWaypoints(String providerId, String dimension);

	Map<String, Object> setMapWaypoint(Map<String, Object> request);

	Map<String, Object> deleteMapWaypoint(String waypointId);

	CapturedImage captureMapImage(Map<String, Object> request);

	Map<String, Object> listWorlds();

	Map<String, Object> joinWorld(String worldId);

	Map<String, Object> listServers();

	Map<String, Object> joinServer(String serverId);

	Map<String, Object> lookAt(double x, double y, double z, Integer durationTicks);

	Map<String, Object> attackEntity(String uuid, String name, String entityTypeId, String mode);

	Map<String, Object> useEntity(String uuid, String name, String entityTypeId, String itemId);

	Map<String, Object> createBlockHighlight(int x, int y, int z, String color, Long durationMs, String overlayText);

	Map<String, Object> createRegionHighlight(
		int x1,
		int y1,
		int z1,
		int x2,
		int y2,
		int z2,
		String color,
		Long durationMs,
		String overlayText
	);

	Map<String, Object> listHighlights();

	Map<String, Object> clearHighlight(String highlightId);

	Map<String, Object> clearHighlights();

	Map<String, Object> getAgentStatus();

	Map<String, Object> getAgentSession();

	Map<String, Object> openAgentSessionLan();

	Map<String, Object> getAgentGoals();

	Map<String, Object> getAgentTasks();

	Map<String, Object> getAgentLedger();

	Map<String, Object> getAgentEvidence();

	Map<String, Object> getAgentStepExecution();

	Map<String, Object> submitAgentTask(Map<String, Object> taskPayload);

	Map<String, Object> submitAgentMission(Map<String, Object> missionPayload);

	Map<String, Object> cancelAgentTask();

	Map<String, Object> getAgentTree();

	Map<String, Object> getAgentDialogue();

	Map<String, Object> getAgentDebugState();

	Map<String, Object> listAgentDebugTimeline(Long sinceEntryId);

	Map<String, Object> sendAgentDebugChat(String message);

	Map<String, Object> fireAgentDebugIdleTrigger();

	Map<String, Object> getAgentContext();

	Map<String, Object> listRecentAgentEvents(Long sinceSeqNo);

	Map<String, Object> triggerAgentCompaction(boolean wait, Integer timeoutMs);

	Map<String, Object> getAgentEventPolicy();

	Map<String, Object> clearAgentEventPolicy();

	Map<String, Object> getEvaluationStatus();

	Map<String, Object> getEvaluationScenarios();

	Map<String, Object> getEvaluationConfig();

	Map<String, Object> runEvaluationScenario(String scenario, String outputDir);

	Map<String, Object> getEvaluationResults();

	Map<String, Object> getEvaluationEvidence();
}
