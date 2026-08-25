package ai.moeru.airicraft.agent.events;

import ai.moeru.airicraft.agent.llm.PlannerTrigger;
import ai.moeru.airicraft.agent.llm.PlannerTriggerType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEventPipelineTest {
	@Test
	void plannerOffStillProducesTriggersButDoesNotRetainSemanticInput() {
		SemanticEventBuffer raw = new SemanticEventBuffer(16, () -> 1000L);
		SemanticEventBuffer planner = new SemanticEventBuffer(16, () -> 1000L);
		AgentEventPipeline pipeline = new AgentEventPipeline(raw, planner, new EventPolicyState(), Map.of(
			"pickup.item_picked_up", new EventRoutingProfile("pickup.item_picked_up", true, PlannerTriggerType.PICKUP, false)
		));
		pipeline.setPlannerEnabled(false);

		pipeline.appendRaw(10L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:apple"));
		List<PlannerTrigger> triggers = pipeline.drain((event, profile) ->
			PlannerTrigger.pending(profile.triggerType(), "self", "picked up", event.tick(), event.timestampMs())
		);

		assertEquals(1, triggers.size());
		assertEquals(0, planner.size());

		pipeline.setPlannerEnabled(true);
		assertEquals(0, planner.size());
	}

	@Test
	void reenabledPlannerFeedContinuesAfterThePreviousSequenceWatermark() {
		SemanticEventBuffer raw = new SemanticEventBuffer(16, () -> 1000L);
		SemanticEventBuffer planner = new SemanticEventBuffer(16, () -> 1000L);
		AgentEventPipeline pipeline = new AgentEventPipeline(raw, planner, new EventPolicyState(), Map.of(
			"pickup.item_picked_up", new EventRoutingProfile("pickup.item_picked_up", true, PlannerTriggerType.PICKUP, false)
		));

		pipeline.appendRaw(10L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:apple"));
		pipeline.drain((event, profile) ->
			PlannerTrigger.pending(profile.triggerType(), "self", "picked up", event.tick(), event.timestampMs())
		);
		long previousPlannerSeqNo = planner.latestSeqNo();

		pipeline.setPlannerEnabled(false);
		pipeline.setPlannerEnabled(true);
		pipeline.appendRaw(11L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:stick"));
		pipeline.drain((event, profile) ->
			PlannerTrigger.pending(profile.triggerType(), "self", "picked up", event.tick(), event.timestampMs())
		);

		SemanticEventQueryResult resumed = planner.query(previousPlannerSeqNo);
		assertEquals(1, resumed.events().size());
		assertEquals("minecraft:stick", resumed.events().getFirst().payload().get("itemId"));
	}

	@Test
	void ignoreKeepsRawEventButSuppressesSemanticAndTrigger() {
		SemanticEventBuffer raw = new SemanticEventBuffer(16, () -> 1000L);
		SemanticEventBuffer planner = new SemanticEventBuffer(16, () -> 1000L);
		EventPolicyState policyState = new EventPolicyState();
		policyState.upsert(new EventPolicyRule(
			"mute-system",
			EventPolicyEffect.IGNORE,
			new EventPolicyMatch("social.system_message", null, "server", null, null, null, null),
			"mute system spam",
			1000L,
			null,
			0L,
			"planner"
		));
		AgentEventPipeline pipeline = new AgentEventPipeline(raw, planner, policyState, Map.of(
			"social.system_message", new EventRoutingProfile("social.system_message", false, PlannerTriggerType.SYSTEM, false),
			"policy.event_intervened", EventRoutingProfile.rawOnly("policy.event_intervened")
		));

		pipeline.appendRaw(10L, "social.system_message", Map.of("message", "hello"));
		List<PlannerTrigger> triggers = pipeline.drain((event, profile) ->
			PlannerTrigger.pending(profile.triggerType(), "server", "hello", event.tick(), event.timestampMs())
		);

		assertEquals(0, triggers.size());
		assertEquals(0, planner.size());
		assertEquals(2, raw.size());
		assertTrue(raw.containsType("social.system_message"));
		assertTrue(raw.containsType("policy.event_intervened"));
	}

	@Test
	void semanticOnlyKeepsPlannerSemanticFeedButSuppressesTrigger() {
		SemanticEventBuffer raw = new SemanticEventBuffer(16, () -> 1000L);
		SemanticEventBuffer planner = new SemanticEventBuffer(16, () -> 1000L);
		EventPolicyState policyState = new EventPolicyState();
		policyState.upsert(new EventPolicyRule(
			"pickup-semantic",
			EventPolicyEffect.SEMANTIC_ONLY,
			new EventPolicyMatch("pickup.item_picked_up", null, null, "self", "minecraft:apple", null, null),
			"keep notice only",
			1000L,
			null,
			0L,
			"planner"
		));
		AgentEventPipeline pipeline = new AgentEventPipeline(raw, planner, policyState, Map.of(
			"pickup.item_picked_up", new EventRoutingProfile("pickup.item_picked_up", true, PlannerTriggerType.PICKUP, false),
			"policy.event_intervened", EventRoutingProfile.rawOnly("policy.event_intervened")
		));

		pipeline.appendRaw(10L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:apple", "count", 1));
		List<PlannerTrigger> triggers = pipeline.drain((event, profile) ->
			PlannerTrigger.pending(profile.triggerType(), "self", "picked up", event.tick(), event.timestampMs())
		);

		assertEquals(0, triggers.size());
		assertEquals(1, planner.size());
		assertTrue(planner.containsType("pickup.item_picked_up"));
	}

	@Test
	void defaultSemanticOnlyPolicySuppressesTriggerWithoutDroppingContext() {
		SemanticEventBuffer raw = new SemanticEventBuffer(16, () -> 1000L);
		SemanticEventBuffer planner = new SemanticEventBuffer(16, () -> 1000L);
		EventPolicyState policyState = new EventPolicyState();
		AgentEventPipeline pipeline = new AgentEventPipeline(
			raw,
			planner,
			policyState,
			Map.of(
				"pickup.item_picked_up", new EventRoutingProfile("pickup.item_picked_up", true, PlannerTriggerType.PICKUP, false),
				"policy.event_intervened", EventRoutingProfile.rawOnly("policy.event_intervened")
			),
			new ai.moeru.airicraft.agent.debug.AgentDebugRecorder(),
			(event, profile) -> new EventPolicyDecision(
				EventPolicyEffect.SEMANTIC_ONLY,
				"default-mining-pickup-semantic-only",
				"mining owns pickup progress",
				false
			)
		);

		pipeline.appendRaw(10L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:cobblestone", "count", 1));
		List<PlannerTrigger> triggers = pipeline.drain((event, profile) ->
			PlannerTrigger.pending(profile.triggerType(), "self", "picked up", event.tick(), event.timestampMs())
		);

		assertEquals(0, triggers.size());
		assertEquals(1, planner.size());
		assertTrue(planner.containsType("pickup.item_picked_up"));
		assertEquals("default-mining-pickup-semantic-only", policyState.lastDecision().orElseThrow().matchedRuleId());
	}

	@Test
	void explicitAllowRuleOverridesDefaultSemanticOnlyPolicy() {
		SemanticEventBuffer raw = new SemanticEventBuffer(16, () -> 1000L);
		SemanticEventBuffer planner = new SemanticEventBuffer(16, () -> 1000L);
		EventPolicyState policyState = new EventPolicyState();
		policyState.upsert(new EventPolicyRule(
			"allow-pickups",
			EventPolicyEffect.ALLOW,
			new EventPolicyMatch("pickup.item_picked_up", null, null, "self", null, null, null),
			"wake on pickups",
			1000L,
			null,
			0L,
			"planner"
		));
		AgentEventPipeline pipeline = new AgentEventPipeline(
			raw,
			planner,
			policyState,
			Map.of(
				"pickup.item_picked_up", new EventRoutingProfile("pickup.item_picked_up", true, PlannerTriggerType.PICKUP, false),
				"policy.event_intervened", EventRoutingProfile.rawOnly("policy.event_intervened")
			),
			new ai.moeru.airicraft.agent.debug.AgentDebugRecorder(),
			(event, profile) -> new EventPolicyDecision(
				EventPolicyEffect.SEMANTIC_ONLY,
				"default-mining-pickup-semantic-only",
				"mining owns pickup progress",
				false
			)
		);

		pipeline.appendRaw(10L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:cobblestone", "count", 1));
		List<PlannerTrigger> triggers = pipeline.drain((event, profile) ->
			PlannerTrigger.pending(profile.triggerType(), "self", "picked up", event.tick(), event.timestampMs())
		);

		assertEquals(1, triggers.size());
		assertEquals(1, planner.size());
		assertEquals("allow-pickups", policyState.lastDecision().orElseThrow().matchedRuleId());
	}

	@Test
	void triggerOnlyWakesPlannerWithoutSemanticProjectionInput() {
		SemanticEventBuffer raw = new SemanticEventBuffer(16, () -> 1000L);
		SemanticEventBuffer planner = new SemanticEventBuffer(16, () -> 1000L);
		EventPolicyState policyState = new EventPolicyState();
		policyState.upsert(new EventPolicyRule(
			"pickup-trigger",
			EventPolicyEffect.TRIGGER_ONLY,
			new EventPolicyMatch("pickup.item_picked_up", null, null, null, null, null, null),
			"wake only",
			1000L,
			null,
			0L,
			"planner"
		));
		AgentEventPipeline pipeline = new AgentEventPipeline(raw, planner, policyState, Map.of(
			"pickup.item_picked_up", new EventRoutingProfile("pickup.item_picked_up", true, PlannerTriggerType.PICKUP, false),
			"policy.event_intervened", EventRoutingProfile.rawOnly("policy.event_intervened")
		));

		pipeline.appendRaw(10L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:apple", "count", 1));
		List<PlannerTrigger> triggers = pipeline.drain((event, profile) ->
			PlannerTrigger.pending(profile.triggerType(), "self", "picked up", event.tick(), event.timestampMs())
		);

		assertEquals(1, triggers.size());
		assertEquals(0, planner.size());
	}

	@Test
	void newestMatchingRuleWins() {
		SemanticEventBuffer raw = new SemanticEventBuffer(16, () -> 1000L);
		SemanticEventBuffer planner = new SemanticEventBuffer(16, () -> 1000L);
		EventPolicyState policyState = new EventPolicyState();
		policyState.upsert(new EventPolicyRule(
			"allow-system",
			EventPolicyEffect.ALLOW,
			new EventPolicyMatch("social.system_message", null, null, null, null, null, null),
			"allow",
			1000L,
			null,
			0L,
			"planner"
		));
		policyState.upsert(new EventPolicyRule(
			"ignore-system",
			EventPolicyEffect.IGNORE,
			new EventPolicyMatch("social.system_message", null, null, null, null, null, null),
			"ignore latest",
			1001L,
			null,
			0L,
			"planner"
		));
		AgentEventPipeline pipeline = new AgentEventPipeline(raw, planner, policyState, Map.of(
			"social.system_message", new EventRoutingProfile("social.system_message", false, PlannerTriggerType.SYSTEM, false),
			"policy.event_intervened", EventRoutingProfile.rawOnly("policy.event_intervened")
		));

		pipeline.appendRaw(10L, "social.system_message", Map.of("message", "hello"));
		List<PlannerTrigger> triggers = pipeline.drain((event, profile) ->
			PlannerTrigger.pending(profile.triggerType(), "server", "hello", event.tick(), event.timestampMs())
		);

		assertEquals(0, triggers.size());
		assertEquals("ignore-system", policyState.lastDecision().orElseThrow().matchedRuleId());
	}

	@Test
	void bypassEventsIgnoreMatchingPolicyRules() {
		SemanticEventBuffer raw = new SemanticEventBuffer(16, () -> 1000L);
		SemanticEventBuffer planner = new SemanticEventBuffer(16, () -> 1000L);
		EventPolicyState policyState = new EventPolicyState();
		policyState.upsert(new EventPolicyRule(
			"ignore-addressed",
			EventPolicyEffect.IGNORE,
			new EventPolicyMatch("social.player_addressed_agent", "Alice", null, null, null, null, null),
			"should be bypassed",
			1000L,
			null,
			0L,
			"planner"
		));
		AgentEventPipeline pipeline = new AgentEventPipeline(raw, planner, policyState, Map.of(
			"social.player_addressed_agent", new EventRoutingProfile("social.player_addressed_agent", false, PlannerTriggerType.CHAT, true),
			"policy.event_intervened", EventRoutingProfile.rawOnly("policy.event_intervened")
		));

		pipeline.appendRaw(10L, "social.player_addressed_agent", Map.of("player", "Alice", "message", "@agent hi"));
		List<PlannerTrigger> triggers = pipeline.drain((event, profile) ->
			PlannerTrigger.pending(profile.triggerType(), "Alice", "@agent hi", event.tick(), event.timestampMs())
		);

		assertEquals(1, triggers.size());
		assertEquals(0, policyState.recentInterventionCount());
		assertEquals(1, raw.size());
	}

	@Test
	void taskBlockedBypassesPolicyAndEmitsSemanticAndTrigger() {
		SemanticEventBuffer raw = new SemanticEventBuffer(16, () -> 1000L);
		SemanticEventBuffer planner = new SemanticEventBuffer(16, () -> 1000L);
		EventPolicyState policyState = new EventPolicyState();
		policyState.upsert(new EventPolicyRule(
			"ignore-task-blocked",
			EventPolicyEffect.IGNORE,
			new EventPolicyMatch("task.blocked", null, null, null, null, null, null),
			"should be bypassed",
			1000L,
			null,
			0L,
			"planner"
		));
		AgentEventPipeline pipeline = new AgentEventPipeline(raw, planner, policyState, Map.of(
			"task.blocked", new EventRoutingProfile("task.blocked", true, PlannerTriggerType.SYSTEM, true),
			"policy.event_intervened", EventRoutingProfile.rawOnly("policy.event_intervened")
		));

		pipeline.appendRaw(10L, "task.blocked", Map.of(
			"taskType", "COLLECT_RESOURCE",
			"resourceKind", "WOOD_LOGS",
			"blockedReason", "target_missing",
			"collected", 0,
			"remaining", 5
		));
		List<PlannerTrigger> triggers = pipeline.drain((event, profile) ->
			PlannerTrigger.pending(
				profile.triggerType(),
				"runtime",
				"Task blocked: taskType=COLLECT_RESOURCE resourceKind=WOOD_LOGS reason=target_missing collected=0 remaining=5.",
				event.tick(),
				event.timestampMs()
			)
		);

		assertEquals(1, triggers.size());
		assertEquals(PlannerTriggerType.SYSTEM, triggers.getFirst().type());
		assertEquals("runtime", triggers.getFirst().speaker());
		assertEquals(
			"Task blocked: taskType=COLLECT_RESOURCE resourceKind=WOOD_LOGS reason=target_missing collected=0 remaining=5.",
			triggers.getFirst().text()
		);
		assertEquals(1, planner.size());
		assertTrue(planner.containsType("task.blocked"));
		assertEquals(0, policyState.recentInterventionCount());
		assertEquals(1, raw.size());
		assertTrue(policyState.lastDecision().orElseThrow().bypassed());
	}
}
