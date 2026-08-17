package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.AiricraftConfig;
import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.dialogue.DialogueRuntime;
import ai.moeru.airicraft.agent.llm.PlannerOrchestrator;
import ai.moeru.airicraft.agent.observability.AgentObservability;
import ai.moeru.airicraft.agent.observability.FlightRecordingObservability;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.tasks.SmeltingProcessManager;
import ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskTerminalEvent;
import ai.moeru.airicraft.agent.tasks.WorldTaskExecutor;
import ai.moeru.airicraft.agent.tasks.WorldTaskRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;

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
		AiricraftConfig airicraftConfig = AiricraftConfig.defaults();

		try {
			EmbodiedAgentRuntime runtime = new EmbodiedAgentRuntime(
				airicraftConfig,
				AgentConfig.defaults(),
				new FirstPersonScreenshotService(),
				new NoopWorldTaskExecutor(),
				observability,
				new SmeltingProcessManager(),
				new CameraController(airicraftConfig.cameraLerpDefaultTicks()),
				null
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

	private static final class NoopWorldTaskExecutor implements WorldTaskExecutor {
		@Override
		public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
			return Optional.empty();
		}

		@Override
		public TaskExecutionSnapshot snapshot() {
			return TaskExecutionSnapshot.idle();
		}

		@Override
		public void onWorldLeave() {
		}

		@Override
		public void shutdown() {
		}
	}
}
