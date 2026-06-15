package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.AiricraftConfig;
import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.agent.dialogue.DialogueRuntime;
import ai.moeru.airicraft.agent.llm.PlannerOrchestrator;
import ai.moeru.airicraft.agent.observability.AgentObservability;
import ai.moeru.airicraft.agent.observability.FlightRecordingObservability;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class EmbodiedAgentRuntimeObservabilityWiringTest {
	@Test
	void runtimePassesObservabilityThroughToPlannerOrchestrator() throws Exception {
		AgentObservability observability = AgentObservability.create(new AgentConfig.ObservabilityConfig(
			true,
			"otlp_http",
			"http://127.0.0.1:4318/v1/traces",
			Map.of(),
			Map.of(),
			"generic",
			false,
			false,
			false,
			false
		));

		try {
			EmbodiedAgentRuntime runtime = new EmbodiedAgentRuntime(
				AiricraftConfig.defaults(),
				AgentConfig.defaults(),
				new FirstPersonScreenshotService(),
				observability
			);

			DialogueRuntime dialogueRuntime = (DialogueRuntime) readField(runtime, "dialogueRuntime");
			PlannerOrchestrator plannerOrchestrator = (PlannerOrchestrator) readField(dialogueRuntime, "plannerOrchestrator");
			AgentObservability runtimeObservability = (AgentObservability) readField(runtime, "observability");
			AgentObservability plannerObservability = (AgentObservability) readField(plannerOrchestrator, "observability");

			FlightRecordingObservability recordingObservability = assertInstanceOf(FlightRecordingObservability.class, runtimeObservability);
			assertSame(observability, readField(recordingObservability, "delegate"));
			assertSame(runtimeObservability, plannerObservability);
		}
		finally {
			observability.shutdown();
		}
	}

	private static Object readField(Object target, String fieldName) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		return field.get(target);
	}
}
