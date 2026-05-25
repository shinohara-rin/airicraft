package ai.moeru.airicraft.agent.shell;

import ai.moeru.airicraft.agent.dialogue.DialogueRuntime;
import ai.moeru.airicraft.agent.llm.CurrentViewVisionService;

import java.util.List;
import java.util.function.Consumer;

public record PlannerShellComponents(
	CurrentViewVisionService visionService,
	DialogueRuntime dialogueRuntime,
	PlannerShellJournal plannerJournal,
	Consumer<List<String>> commonsenseRulesUpdater
) {
}
