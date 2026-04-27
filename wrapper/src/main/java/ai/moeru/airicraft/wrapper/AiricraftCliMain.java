package ai.moeru.airicraft.wrapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.IParameterExceptionHandler;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

public final class AiricraftCliMain {
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private AiricraftCliMain() {
	}

	public static void main(String[] args) {
		PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
		CommandLine commandLine = createCommandLine(new HttpBridgeTransport(), out);
		int exitCode = commandLine.execute(args);
		System.exit(exitCode);
	}

	static CommandLine createCommandLine(MinecraftTransport transport, PrintWriter out) {
		CliContext context = new CliContext(transport, out);
		CommandLine root = new CommandLine(new UsageCommand(out, "airicraft", "Airicraft agent CLI"));
		root.setCommandName("airicraft");
		root.setExecutionExceptionHandler(new CliExecutionExceptionHandler(context));
		root.setParameterExceptionHandler(new CliParameterExceptionHandler(context));
		root.setUsageHelpWidth(100);

		root.addSubcommand(new StatusCommand(context));
		root.addSubcommand(new ReloadCommand(context));

		root.addSubcommand("agent", new UsageCommand(out, "airicraft agent", "Agent observability and debug commands"));
		CommandLine agent = root.getSubcommands().get("agent");
		agent.addSubcommand(new AgentStatusCommand(context));
		agent.addSubcommand(new AgentSessionCommand(context));
		CommandLine agentSession = agent.getSubcommands().get("session");
		agentSession.addSubcommand(new AgentSessionOpenLanCommand(context));
		agent.addSubcommand(new AgentGoalsCommand(context));
		agent.addSubcommand(new AgentTasksCommand(context));
		agent.addSubcommand(new AgentLedgerCommand(context));
		agent.addSubcommand(new AgentEvidenceCommand(context));
		agent.addSubcommand(new AgentStepExecutionCommand(context));
		agent.addSubcommand("actions", new UsageCommand(out, "airicraft agent actions", "Composable action graph debug commands"));
		CommandLine agentActions = agent.getSubcommands().get("actions");
		agentActions.addSubcommand(new AgentActionsInspectCommand(context));
		agentActions.addSubcommand(new AgentActionsExecuteCommand(context));
		agentActions.addSubcommand(new AgentActionsResolveCommand(context));
		agent.addSubcommand("mission", new UsageCommand(out, "airicraft agent mission", "Mission-level agent commands"));
		CommandLine agentMission = agent.getSubcommands().get("mission");
		agentMission.addSubcommand(new AgentMissionSubmitCommand(context));
		CommandLine agentTasks = agent.getSubcommands().get("tasks");
		agentTasks.addSubcommand(new AgentTasksSubmitCommand(context));
		agentTasks.addSubcommand(new AgentTasksCancelCommand(context));
		agent.addSubcommand(new AgentTreeCommand(context));
		agent.addSubcommand(new AgentDialogueCommand(context));
		agent.addSubcommand("debug", new UsageCommand(out, "airicraft agent debug", "Agent debug commands"));
		CommandLine agentDebug = agent.getSubcommands().get("debug");
		agentDebug.addSubcommand(new AgentDebugChatCommand(context));
		agentDebug.addSubcommand(new AgentDebugStateCommand(context));
		agentDebug.addSubcommand(new AgentDebugTimelineCommand(context));
		agent.addSubcommand(new AgentContextCommand(context));
		agent.addSubcommand(new AgentCompactCommand(context));
		agent.addSubcommand(new AgentEventPolicyCommand(context));
		CommandLine agentEventPolicy = agent.getSubcommands().get("event-policy");
		agentEventPolicy.addSubcommand(new AgentEventPolicyShowCommand(context));
		agentEventPolicy.addSubcommand(new AgentEventPolicyClearCommand(context));
		agent.addSubcommand("events", new UsageCommand(out, "airicraft agent events", "Agent event stream commands"));
		CommandLine agentEvents = agent.getSubcommands().get("events");
		agentEvents.addSubcommand(new AgentEventsRecentCommand(context));

		root.addSubcommand("verification", new UsageCommand(out, "airicraft verification", "Dev-only verification commands"));
		CommandLine verification = root.getSubcommands().get("verification");
		verification.addSubcommand(new VerificationStatusCommand(context));
		verification.addSubcommand(new VerificationScenariosCommand(context));
		verification.addSubcommand(new VerificationRunCommand(context));
		verification.addSubcommand(new VerificationResultsCommand(context));
		verification.addSubcommand("player", new UsageCommand(out, "airicraft verification player", "Verification player control commands"));
		CommandLine verificationPlayer = verification.getSubcommands().get("player");
		verificationPlayer.addSubcommand(new VerificationPlayerStateCommand(context));
		verificationPlayer.addSubcommand(new VerificationPlayerTeleportCommand(context));
		verificationPlayer.addSubcommand(new VerificationPlayerVelocityCommand(context));
		verificationPlayer.addSubcommand(new VerificationPlayerRespawnCommand(context));
		verificationPlayer.addSubcommand(new VerificationPlayerGameModeCommand(context));
		verification.addSubcommand("command", new UsageCommand(out, "airicraft verification command", "Verification command execution"));
		CommandLine verificationCommand = verification.getSubcommands().get("command");
		verificationCommand.addSubcommand(new VerificationCommandRunCommand(context));

		root.addSubcommand("worlds", new UsageCommand(out, "airicraft worlds", "Saved singleplayer worlds"));
		CommandLine worlds = root.getSubcommands().get("worlds");
		worlds.addSubcommand(new WorldsListCommand(context));
		worlds.addSubcommand(new WorldsJoinCommand(context));

		root.addSubcommand("servers", new UsageCommand(out, "airicraft servers", "Saved multiplayer servers"));
		CommandLine servers = root.getSubcommands().get("servers");
		servers.addSubcommand(new ServersListCommand(context));
		servers.addSubcommand(new ServersJoinCommand(context));

		root.addSubcommand("player", new UsageCommand(out, "airicraft player", "Player-oriented commands"));
		CommandLine player = root.getSubcommands().get("player");
		player.addSubcommand(new PlayerFocusCommand(context));
		player.addSubcommand(new PlayerNearbyEntitiesCommand(context));
		player.addSubcommand(new PlayerLookAtCommand(context));
		player.addSubcommand(new PlayerAttackEntityCommand(context));
		player.addSubcommand(new PlayerUseEntityCommand(context));

		root.addSubcommand("camera", new UsageCommand(out, "airicraft camera", "Camera capture commands"));
		CommandLine camera = root.getSubcommands().get("camera");
		camera.addSubcommand(new CameraScreenshotCommand(context));

		root.addSubcommand("vision", new UsageCommand(out, "airicraft vision", "Vision analysis commands"));
		CommandLine vision = root.getSubcommands().get("vision");
		vision.addSubcommand(new VisionDescribeCommand(context));

		root.addSubcommand("world", new UsageCommand(out, "airicraft world", "World inspection commands"));
		CommandLine world = root.getSubcommands().get("world");
		world.addSubcommand(new WorldSnapshotCommand(context));

		root.addSubcommand("highlights", new UsageCommand(out, "airicraft highlights", "Highlight commands"));
		CommandLine highlights = root.getSubcommands().get("highlights");
		highlights.addSubcommand(new HighlightBlockCommand(context));
		highlights.addSubcommand(new HighlightRegionCommand(context));
		highlights.addSubcommand(new HighlightsListCommand(context));
		highlights.addSubcommand(new HighlightsClearCommand(context));
		highlights.addSubcommand(new HighlightsClearAllCommand(context));

		HelpCommand helpCommand = new HelpCommand(context);
		root.addSubcommand(helpCommand);
		context.setRootCommandLine(root);
		return root;
	}

	private static final class CliContext {
		private final MinecraftTransport transport;
		private final TextPrinter printer;
		private CommandLine rootCommandLine;

		private CliContext(MinecraftTransport transport, PrintWriter out) {
			this.transport = transport;
			this.printer = new TextPrinter(out);
		}

		private void setRootCommandLine(CommandLine rootCommandLine) {
			this.rootCommandLine = rootCommandLine;
		}
	}

	@Command(mixinStandardHelpOptions = true)
	private static final class UsageCommand implements Runnable {
		@Spec
		private CommandSpec spec;

		private final PrintWriter out;
		private final String commandName;
		private final String description;

		private UsageCommand(PrintWriter out, String commandName, String description) {
			this.out = out;
			this.commandName = commandName;
			this.description = description;
		}

		@Override
		public void run() {
			out.println(commandName);
			out.println(description);
			out.println();
			spec.commandLine().usage(out);
		}
	}

	private abstract static class BaseCommand implements Callable<Integer> {
		private final CliContext context;
		private final String commandPath;

		@Option(names = "--verbose", description = "Include additional detail where supported.")
		private boolean verbose;

		private BaseCommand(CliContext context, String commandPath) {
			this.context = context;
			this.commandPath = commandPath;
		}

		@Override
		public final Integer call() {
			Map<String, Object> payload = runCommand();
			context.printer.printSuccess(commandPath, payload);
			return 0;
		}

		abstract Map<String, Object> runCommand();

		final MinecraftTransport transport() {
			return context.transport;
		}

		final boolean verbose() {
			return verbose;
		}

		final String commandPath() {
			return commandPath;
		}
	}

	@Command(name = "status", mixinStandardHelpOptions = true, description = "Inspect bridge and session status.")
	private static final class StatusCommand extends BaseCommand {
		private StatusCommand(CliContext context) {
			super(context, "status");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.status(transport().getStatus(), verbose());
		}
	}

	@Command(name = "reload", mixinStandardHelpOptions = true, description = "Reload runtime config without restarting Minecraft.")
	private static final class ReloadCommand extends BaseCommand {
		private ReloadCommand(CliContext context) {
			super(context, "reload");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.reload(transport().reload(), verbose());
		}
	}

	@Command(name = "status", mixinStandardHelpOptions = true, description = "Inspect agent runtime status.")
	private static final class AgentStatusCommand extends BaseCommand {
		private AgentStatusCommand(CliContext context) {
			super(context, "agent status");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentStatus(transport().getAgentStatus(), verbose());
		}
	}

	@Command(name = "session", mixinStandardHelpOptions = true, description = "Inspect agent session state.")
	private static final class AgentSessionCommand extends BaseCommand {
		private AgentSessionCommand(CliContext context) {
			super(context, "agent session");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentSession(transport().getAgentSession(), verbose());
		}
	}

	@Command(name = "open-lan", mixinStandardHelpOptions = true, description = "Open the current singleplayer session to LAN.")
	private static final class AgentSessionOpenLanCommand extends BaseCommand {
		private AgentSessionOpenLanCommand(CliContext context) {
			super(context, "agent session open-lan");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().openAgentSessionLan();
		}
	}

	@Command(name = "goals", mixinStandardHelpOptions = true, description = "Inspect active agent goals.")
	private static final class AgentGoalsCommand extends BaseCommand {
		private AgentGoalsCommand(CliContext context) {
			super(context, "agent goals");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentGoals(transport().getAgentGoals(), verbose());
		}
	}

	@Command(name = "tasks", mixinStandardHelpOptions = true, description = "Inspect active agent task state.")
	private static final class AgentTasksCommand extends BaseCommand {
		private AgentTasksCommand(CliContext context) {
			super(context, "agent tasks");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentTasks(transport().getAgentTasks(), verbose());
		}
	}

	@Command(name = "ledger", mixinStandardHelpOptions = true, description = "Inspect the active planner-owned mission ledger.")
	private static final class AgentLedgerCommand extends BaseCommand {
		private AgentLedgerCommand(CliContext context) {
			super(context, "agent ledger");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentLedger(transport().getAgentLedger(), verbose());
		}
	}

	@Command(name = "evidence", mixinStandardHelpOptions = true, description = "Inspect the latest runtime-owned world evidence.")
	private static final class AgentEvidenceCommand extends BaseCommand {
		private AgentEvidenceCommand(CliContext context) {
			super(context, "agent evidence");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentEvidence(transport().getAgentEvidence(), verbose());
		}
	}

	@Command(name = "step-execution", mixinStandardHelpOptions = true, description = "Inspect the latest semantic step execution result and primitive execution state.")
	private static final class AgentStepExecutionCommand extends BaseCommand {
		private AgentStepExecutionCommand(CliContext context) {
			super(context, "agent step-execution");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentStepExecution(transport().getAgentStepExecution(), verbose());
		}
	}

	@Command(name = "resolve", mixinStandardHelpOptions = true, description = "Dry-run resolve an inventory-item goal through the composable action graph.")
	private static final class AgentActionsResolveCommand extends BaseCommand {
		@Option(names = "--item", required = true, description = "Namespaced output item id, for example minecraft:bread.")
		private String itemId;

		@Option(names = "--quantity", defaultValue = "1", description = "Requested item count.")
		private int quantity;

		@Option(names = "--assume-inventory", split = ",", description = "Assumed inventory fact in item=count form. Can be repeated or comma-separated.")
		private List<String> assumedInventory = new ArrayList<>();

		private AgentActionsResolveCommand(CliContext context) {
			super(context, "agent actions resolve");
		}

		@Override
		Map<String, Object> runCommand() {
			if (itemId == null || itemId.isBlank()) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "item must be non-empty");
			}
			if (quantity < 1) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "quantity must be positive");
			}
			return PayloadViews.agentActionResolve(
				transport().resolveAgentActionGraph(itemId, quantity, parseInventoryAssumptions(assumedInventory, commandPath())),
				verbose()
			);
		}
	}

	@Command(name = "inspect", mixinStandardHelpOptions = true, description = "Inspect loaded actionsets and registered primitive actions.")
	private static final class AgentActionsInspectCommand extends BaseCommand {
		private AgentActionsInspectCommand(CliContext context) {
			super(context, "agent actions inspect");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentActionInspect(transport().inspectAgentActionGraph(), verbose());
		}
	}

	@Command(name = "execute", mixinStandardHelpOptions = true, description = "Resolve and dispatch the first executable primitive for an inventory-item goal.")
	private static final class AgentActionsExecuteCommand extends BaseCommand {
		@Option(names = "--item", required = true, description = "Namespaced output item id, for example minecraft:bread.")
		private String itemId;

		@Option(names = "--quantity", defaultValue = "1", description = "Requested item count.")
		private int quantity;

		@Option(names = "--assume-inventory", split = ",", description = "Assumed inventory fact in item=count form. Can be repeated or comma-separated.")
		private List<String> assumedInventory = new ArrayList<>();

		private AgentActionsExecuteCommand(CliContext context) {
			super(context, "agent actions execute");
		}

		@Override
		Map<String, Object> runCommand() {
			if (itemId == null || itemId.isBlank()) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "item must be non-empty");
			}
			if (quantity < 1) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "quantity must be positive");
			}
			return PayloadViews.agentActionExecute(
				transport().executeAgentActionGraph(itemId, quantity, parseInventoryAssumptions(assumedInventory, commandPath())),
				verbose()
			);
		}
	}

	@Command(name = "submit", mixinStandardHelpOptions = true, description = "Submit a high-level agent task.")
	private static final class AgentTasksSubmitCommand extends BaseCommand {
		@Option(names = "--type", required = true, description = "Task type, for example collect-resource.")
		private String type;

		@Option(names = "--resource", required = true, description = "Task resource kind, for example wood-logs.")
		private String resource;

		@Option(names = "--quantity", required = true, description = "Requested quantity.")
		private int quantity;

		private AgentTasksSubmitCommand(CliContext context) {
			super(context, "agent tasks submit");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentTasks(transport().submitAgentTask(Map.of(
				"type", PayloadViews.normalizeTaskValue(type),
				"resourceKind", PayloadViews.normalizeTaskValue(resource),
				"quantity", quantity
			)), verbose());
		}
	}

	@Command(name = "submit", mixinStandardHelpOptions = true, description = "Submit a mission-level request and let the runtime seed a ledger.")
	private static final class AgentMissionSubmitCommand extends BaseCommand {
		@Option(names = "--type", description = "Mission type, for example collect-resource.")
		private String type;

		@Option(names = "--resource", description = "Mission resource kind, for example wood-logs.")
		private String resource;

		@Option(names = "--quantity", description = "Requested quantity.")
		private int quantity;

		@Option(names = "--ledger-file", description = "Path to a JSON file containing a full TaskLedger payload.")
		private Path ledgerFile;

		private AgentMissionSubmitCommand(CliContext context) {
			super(context, "agent mission submit");
		}

		@Override
		Map<String, Object> runCommand() {
			if (ledgerFile != null) {
				if (type != null || resource != null || quantity > 0) {
					throw new CliUsageException(commandPath(), "invalid_arguments", "--ledger-file cannot be combined with --type/--resource/--quantity");
				}
				return PayloadViews.agentTasks(transport().submitAgentMission(readLedgerPayload(ledgerFile)), verbose());
			}
			if (type == null || resource == null || quantity <= 0) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "Either provide --ledger-file or all of --type, --resource, and --quantity");
			}
			return PayloadViews.agentTasks(transport().submitAgentMission(Map.of(
				"type", PayloadViews.normalizeTaskValue(type),
				"resourceKind", PayloadViews.normalizeTaskValue(resource),
				"quantity", quantity
			)), verbose());
		}

		private Map<String, Object> readLedgerPayload(Path ledgerFile) {
			try {
				return OBJECT_MAPPER.readValue(Files.newBufferedReader(ledgerFile, StandardCharsets.UTF_8), MAP_TYPE);
			}
			catch (java.io.IOException exception) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "Failed to read ledger file: " + ledgerFile);
			}
		}
	}

	@Command(name = "cancel", mixinStandardHelpOptions = true, description = "Cancel the active high-level task.")
	private static final class AgentTasksCancelCommand extends BaseCommand {
		private AgentTasksCancelCommand(CliContext context) {
			super(context, "agent tasks cancel");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentTasks(transport().cancelAgentTask(), verbose());
		}
	}

	@Command(name = "tree", mixinStandardHelpOptions = true, description = "Inspect the behavior tree snapshot.")
	private static final class AgentTreeCommand extends BaseCommand {
		private AgentTreeCommand(CliContext context) {
			super(context, "agent tree");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().getAgentTree();
		}
	}

	@Command(name = "dialogue", mixinStandardHelpOptions = true, description = "Inspect dialogue state and recent turns.")
	private static final class AgentDialogueCommand extends BaseCommand {
		private AgentDialogueCommand(CliContext context) {
			super(context, "agent dialogue");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentDialogue(transport().getAgentDialogue(), verbose());
		}
	}

	@Command(name = "chat", mixinStandardHelpOptions = true, description = "Inject a local-controller chat message into the agent runtime.")
	private static final class AgentDebugChatCommand extends BaseCommand {
		@Option(names = "--message", required = true, description = "Chat message to inject, for example '@agent get me 4 wood logs'.")
		private String message;

		private AgentDebugChatCommand(CliContext context) {
			super(context, "agent debug chat");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().sendAgentDebugChat(message);
		}
	}

	@Command(name = "state", mixinStandardHelpOptions = true, description = "Inspect correlated agent debug state across planner, dialogue, chat, and task progress.")
	private static final class AgentDebugStateCommand extends BaseCommand {
		private AgentDebugStateCommand(CliContext context) {
			super(context, "agent debug state");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentDebugState(transport().getAgentDebugState(), verbose());
		}
	}

	@Command(name = "timeline", mixinStandardHelpOptions = true, description = "Inspect the recent agent debug timeline.")
	private static final class AgentDebugTimelineCommand extends BaseCommand {
		@Option(names = "--since", description = "Only return timeline entries with an entryId greater than this value.")
		private Long since;

		private AgentDebugTimelineCommand(CliContext context) {
			super(context, "agent debug timeline");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentDebugTimeline(transport().listAgentDebugTimeline(since), verbose());
		}
	}

	@Command(name = "context", mixinStandardHelpOptions = true, description = "Inspect planner context aggregation and compaction state.")
	private static final class AgentContextCommand extends BaseCommand {
		private AgentContextCommand(CliContext context) {
			super(context, "agent context");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentContext(transport().getAgentContext(), verbose());
		}
	}

	@Command(name = "recent", mixinStandardHelpOptions = true, description = "List recent agent events.")
	private static final class AgentEventsRecentCommand extends BaseCommand {
		@Option(names = "--since", description = "Only return events with seqNo greater than this value.")
		private Long since;

		private AgentEventsRecentCommand(CliContext context) {
			super(context, "agent events recent");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentEvents(transport().listRecentAgentEvents(since), verbose());
		}
	}

	@Command(name = "compact", mixinStandardHelpOptions = true, description = "Trigger a planner context compaction for debugging.")
	private static final class AgentCompactCommand extends BaseCommand {
		@Option(names = "--no-wait", description = "Return immediately after starting compaction.")
		private boolean noWait;

		@Option(names = "--timeout-seconds", description = "Wait timeout in seconds when compaction is synchronous.")
		private Integer timeoutSeconds;

		private AgentCompactCommand(CliContext context) {
			super(context, "agent compact");
		}

		@Override
		Map<String, Object> runCommand() {
			if (timeoutSeconds != null && timeoutSeconds < 1) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "timeout-seconds must be positive");
			}
			if (timeoutSeconds != null && timeoutSeconds > Integer.MAX_VALUE / 1000) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "timeout-seconds is too large");
			}
			Integer timeoutMs = timeoutSeconds == null ? null : timeoutSeconds * 1000;
			return PayloadViews.agentCompact(transport().triggerAgentCompaction(!noWait, timeoutMs), verbose());
		}
	}

	@Command(name = "event-policy", mixinStandardHelpOptions = true, description = "Inspect active event-policy rules and recent interventions.")
	private static final class AgentEventPolicyCommand extends BaseCommand {
		private AgentEventPolicyCommand(CliContext context) {
			super(context, "agent event-policy");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentEventPolicy(transport().getAgentEventPolicy(), verbose());
		}
	}

	@Command(name = "show", mixinStandardHelpOptions = true, description = "Inspect active event-policy rules and recent interventions.")
	private static final class AgentEventPolicyShowCommand extends BaseCommand {
		private AgentEventPolicyShowCommand(CliContext context) {
			super(context, "agent event-policy show");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentEventPolicy(transport().getAgentEventPolicy(), verbose());
		}
	}

	@Command(name = "clear", mixinStandardHelpOptions = true, description = "Clear all session-scoped event-policy rules.")
	private static final class AgentEventPolicyClearCommand extends BaseCommand {
		private AgentEventPolicyClearCommand(CliContext context) {
			super(context, "agent event-policy clear");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.agentEventPolicy(transport().clearAgentEventPolicy(), verbose());
		}
	}

	@Command(name = "status", mixinStandardHelpOptions = true, description = "Inspect verification availability.")
	private static final class VerificationStatusCommand extends BaseCommand {
		private VerificationStatusCommand(CliContext context) {
			super(context, "verification status");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.verificationStatus(transport().getVerificationStatus(), verbose());
		}
	}

	@Command(name = "scenarios", mixinStandardHelpOptions = true, description = "List available verification scenarios.")
	private static final class VerificationScenariosCommand extends BaseCommand {
		private VerificationScenariosCommand(CliContext context) {
			super(context, "verification scenarios");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.verificationScenarios(transport().getVerificationResults(), verbose());
		}
	}

	@Command(name = "run", mixinStandardHelpOptions = true, description = "Start a verification scenario.")
	private static final class VerificationRunCommand extends BaseCommand {
		@Option(names = "--scenario", required = true, description = "Scenario name from `airicraft verification scenarios`.")
		private String scenario;

		private VerificationRunCommand(CliContext context) {
			super(context, "verification run");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().runVerificationScenario(scenario);
		}
	}

	@Command(name = "results", mixinStandardHelpOptions = true, description = "Inspect verification scenario results.")
	private static final class VerificationResultsCommand extends BaseCommand {
		private VerificationResultsCommand(CliContext context) {
			super(context, "verification results");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.verificationResults(transport().getVerificationResults(), verbose());
		}
	}

	@Command(name = "state", mixinStandardHelpOptions = true, description = "Inspect the verification player state.")
	private static final class VerificationPlayerStateCommand extends BaseCommand {
		private VerificationPlayerStateCommand(CliContext context) {
			super(context, "verification player state");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.verificationPlayerState(transport().getVerificationPlayerState(), verbose());
		}
	}

	@Command(name = "teleport", mixinStandardHelpOptions = true, description = "Teleport the verification player.")
	private static final class VerificationPlayerTeleportCommand extends BaseCommand {
		@Option(names = "--x", required = true)
		private double x;

		@Option(names = "--y", required = true)
		private double y;

		@Option(names = "--z", required = true)
		private double z;

		private VerificationPlayerTeleportCommand(CliContext context) {
			super(context, "verification player teleport");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.verificationPlayerState(transport().teleportVerificationPlayer(x, y, z), verbose());
		}
	}

	@Command(name = "velocity", mixinStandardHelpOptions = true, description = "Apply velocity to the verification player.")
	private static final class VerificationPlayerVelocityCommand extends BaseCommand {
		@Option(names = "--x", required = true, description = "Horizontal X velocity.")
		private double x;

		@Option(names = "--y", required = true, description = "Vertical Y velocity.")
		private double y;

		@Option(names = "--z", required = true, description = "Horizontal Z velocity.")
		private double z;

		private VerificationPlayerVelocityCommand(CliContext context) {
			super(context, "verification player velocity");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.verificationPlayerState(transport().setVerificationPlayerVelocity(x, y, z), verbose());
		}
	}

	@Command(name = "respawn", mixinStandardHelpOptions = true, description = "Respawn the verification player if the client is on the death screen.")
	private static final class VerificationPlayerRespawnCommand extends BaseCommand {
		private VerificationPlayerRespawnCommand(CliContext context) {
			super(context, "verification player respawn");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.verificationPlayerState(transport().respawnVerificationPlayer(), verbose());
		}
	}

	@Command(name = "gamemode", mixinStandardHelpOptions = true, description = "Set the verification player gamemode.")
	private static final class VerificationPlayerGameModeCommand extends BaseCommand {
		@Option(names = "--mode", required = true, description = "One of survival, creative, spectator.")
		private String mode;

		private VerificationPlayerGameModeCommand(CliContext context) {
			super(context, "verification player gamemode");
		}

		@Override
		Map<String, Object> runCommand() {
			String normalized = mode == null ? null : mode.trim().toLowerCase(Locale.ROOT);
			if (!List.of("survival", "creative", "spectator").contains(normalized)) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "mode must be survival, creative, or spectator");
			}
			return PayloadViews.verificationPlayerState(transport().setVerificationPlayerGameMode(normalized), verbose());
		}
	}

	@Command(name = "run", mixinStandardHelpOptions = true, description = "Run an integrated-server command for verification.")
	private static final class VerificationCommandRunCommand extends BaseCommand {
		@Option(names = "--command", required = true, description = "Command text to execute.")
		private String command;

		private VerificationCommandRunCommand(CliContext context) {
			super(context, "verification command run");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.verificationPlayerState(transport().runVerificationCommand(command), verbose());
		}
	}

	@Command(name = "list", mixinStandardHelpOptions = true, description = "List saved singleplayer worlds.")
	private static final class WorldsListCommand extends BaseCommand {
		private WorldsListCommand(CliContext context) {
			super(context, "worlds list");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.worldsList(transport().listWorlds(), verbose());
		}
	}

	@Command(name = "join", mixinStandardHelpOptions = true, description = "Join a saved singleplayer world.")
	private static final class WorldsJoinCommand extends BaseCommand {
		@Option(names = "--world-id", required = true, description = "World identifier from `airicraft worlds list`.")
		private String worldId;

		private WorldsJoinCommand(CliContext context) {
			super(context, "worlds join");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().joinWorld(worldId);
		}
	}

	@Command(name = "list", mixinStandardHelpOptions = true, description = "List saved multiplayer servers.")
	private static final class ServersListCommand extends BaseCommand {
		private ServersListCommand(CliContext context) {
			super(context, "servers list");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.serversList(transport().listServers(), verbose());
		}
	}

	@Command(name = "join", mixinStandardHelpOptions = true, description = "Join a saved multiplayer server.")
	private static final class ServersJoinCommand extends BaseCommand {
		@Option(names = "--server-id", required = true, description = "Server identifier from `airicraft servers list`.")
		private String serverId;

		private ServersJoinCommand(CliContext context) {
			super(context, "servers join");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().joinServer(serverId);
		}
	}

	@Command(name = "focus", mixinStandardHelpOptions = true, description = "Describe the current player focus target.")
	private static final class PlayerFocusCommand extends BaseCommand {
		private PlayerFocusCommand(CliContext context) {
			super(context, "player focus");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().getFocus();
		}
	}

	@Command(name = "nearby-entities", mixinStandardHelpOptions = true, description = "List nearby loaded entities and their exact selectors.")
	private static final class PlayerNearbyEntitiesCommand extends BaseCommand {
		private PlayerNearbyEntitiesCommand(CliContext context) {
			super(context, "player nearby-entities");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().listNearbyEntities();
		}
	}

	@Command(name = "look-at", mixinStandardHelpOptions = true, description = "Rotate the player camera toward coordinates.")
	private static final class PlayerLookAtCommand extends BaseCommand {
		@Option(names = "--x", required = true)
		private double x;

		@Option(names = "--y", required = true)
		private double y;

		@Option(names = "--z", required = true)
		private double z;

		private PlayerLookAtCommand(CliContext context) {
			super(context, "player look-at");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().lookAt(x, y, z);
		}
	}

	private abstract static class PlayerEntitySelectorCommand extends BaseCommand {
		@Option(names = "--uuid", description = "Exact entity UUID.")
		private String uuid;

		@Option(names = "--name", description = "Visible entity name.")
		private String name;

		@Option(names = "--entity-type-id", description = "Exact namespaced entity type id, for example minecraft:sheep.")
		private String entityTypeId;

		private PlayerEntitySelectorCommand(CliContext context, String commandPath) {
			super(context, commandPath);
		}

		final String selectorUuid() {
			return uuid;
		}

		final String selectorName() {
			return name;
		}

		final String selectorEntityTypeId() {
			return entityTypeId;
		}

		final void requireSelector() {
			if ((uuid == null || uuid.isBlank())
				&& (name == null || name.isBlank())
				&& (entityTypeId == null || entityTypeId.isBlank())) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "Provide at least one of --uuid, --name, or --entity-type-id");
			}
		}
	}

	@Command(name = "attack-entity", mixinStandardHelpOptions = true, description = "Attack a nearby entity selected by uuid, name, or entity type.")
	private static final class PlayerAttackEntityCommand extends PlayerEntitySelectorCommand {
		@Option(names = "--mode", description = "Attack mode: kill or hit_once. Defaults to kill.")
		private String mode = "kill";

		private PlayerAttackEntityCommand(CliContext context) {
			super(context, "player attack-entity");
		}

		@Override
		Map<String, Object> runCommand() {
			requireSelector();
			return transport().attackEntity(selectorUuid(), selectorName(), selectorEntityTypeId(), mode);
		}
	}

	@Command(name = "use-entity", mixinStandardHelpOptions = true, description = "Use current hand or an optional item on a nearby entity.")
	private static final class PlayerUseEntityCommand extends PlayerEntitySelectorCommand {
		@Option(names = "--item-id", description = "Optional exact namespaced item id to equip first.")
		private String itemId;

		private PlayerUseEntityCommand(CliContext context) {
			super(context, "player use-entity");
		}

		@Override
		Map<String, Object> runCommand() {
			requireSelector();
			return transport().useEntity(selectorUuid(), selectorName(), selectorEntityTypeId(), itemId);
		}
	}

	@Command(name = "screenshot", mixinStandardHelpOptions = true, description = "Capture a first-person screenshot.")
	private static final class CameraScreenshotCommand implements Callable<Integer> {
		private final CliContext context;

		@Option(names = "--output", required = true, description = "Path to write the screenshot PNG.")
		private Path output;

		private CameraScreenshotCommand(CliContext context) {
			this.context = context;
		}

		@Override
		public Integer call() {
			CapturedImage capture = context.transport.captureScreenshot();
			Path outputPath = output.toAbsolutePath().normalize();
			try {
				Path parent = outputPath.getParent();
				if (parent != null) {
					Files.createDirectories(parent);
				}
				Files.write(outputPath, capture.bytes());
			}
			catch (java.io.IOException exception) {
				throw new UncheckedIOException(exception);
			}

			LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
			payload.put("outputPath", outputPath.toString());
			payload.put("format", capture.format());
			payload.put("width", capture.width());
			payload.put("height", capture.height());
			payload.put("capturedAtMs", capture.capturedAtMs());
			context.printer.printSuccess("camera screenshot", payload);
			return 0;
		}
	}

	@Command(name = "describe", mixinStandardHelpOptions = true, description = "Describe the current first-person view.")
	private static final class VisionDescribeCommand implements Callable<Integer> {
		private final CliContext context;

		@Option(names = "--prompt", description = "Custom prompt for the vision model.")
		private String prompt;

		private VisionDescribeCommand(CliContext context) {
			this.context = context;
		}

		@Override
		public Integer call() {
			VisionDescriptionResult result = context.transport.describeVision(prompt);
			LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
			payload.put("format", result.format());
			payload.put("capturedAtMs", result.capturedAtMs());
			payload.put("model", result.model());
			payload.put("description", result.description());
			context.printer.printSuccess("vision describe", payload);
			return 0;
		}
	}

	@Command(name = "snapshot", mixinStandardHelpOptions = true, description = "Capture a snapshot of blocks around a position.")
	private static final class WorldSnapshotCommand extends BaseCommand {
		@Option(names = "--x")
		private Integer x;

		@Option(names = "--y")
		private Integer y;

		@Option(names = "--z")
		private Integer z;

		@Option(names = "--radius", description = "Snapshot radius from 0 to 4.")
		private Integer radius;

		private WorldSnapshotCommand(CliContext context) {
			super(context, "world snapshot");
		}

		@Override
		Map<String, Object> runCommand() {
			boolean anyCoordinate = x != null || y != null || z != null;
			boolean allCoordinates = x != null && y != null && z != null;
			if (anyCoordinate && !allCoordinates) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "x, y, and z must be provided together");
			}
			int requestedRadius = radius == null ? 1 : radius;
			if (requestedRadius < 0 || requestedRadius > 4) {
				throw new CliUsageException(commandPath(), "invalid_arguments", "radius must be between 0 and 4");
			}
			return PayloadViews.worldSnapshot(
				transport().getWorldSnapshot(x, y, z, requestedRadius),
				verbose()
			);
		}
	}

	@Command(name = "block", mixinStandardHelpOptions = true, description = "Create a block highlight.")
	private static final class HighlightBlockCommand extends BaseCommand {
		@Option(names = "--x", required = true)
		private int x;

		@Option(names = "--y", required = true)
		private int y;

		@Option(names = "--z", required = true)
		private int z;

		@Option(names = "--color")
		private String color;

		@Option(names = "--duration-seconds")
		private Integer durationSeconds;

		@Option(names = "--overlay-text")
		private String overlayText;

		private HighlightBlockCommand(CliContext context) {
			super(context, "highlights block");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().createBlockHighlight(
				x,
				y,
				z,
				validatedColor(color, commandPath()),
				validatedDurationMs(durationSeconds, commandPath()),
				overlayText
			);
		}
	}

	@Command(name = "region", mixinStandardHelpOptions = true, description = "Create a region highlight.")
	private static final class HighlightRegionCommand extends BaseCommand {
		@Option(names = "--x1", required = true)
		private int x1;

		@Option(names = "--y1", required = true)
		private int y1;

		@Option(names = "--z1", required = true)
		private int z1;

		@Option(names = "--x2", required = true)
		private int x2;

		@Option(names = "--y2", required = true)
		private int y2;

		@Option(names = "--z2", required = true)
		private int z2;

		@Option(names = "--color")
		private String color;

		@Option(names = "--duration-seconds")
		private Integer durationSeconds;

		@Option(names = "--overlay-text")
		private String overlayText;

		private HighlightRegionCommand(CliContext context) {
			super(context, "highlights region");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().createRegionHighlight(
				x1,
				y1,
				z1,
				x2,
				y2,
				z2,
				validatedColor(color, commandPath()),
				validatedDurationMs(durationSeconds, commandPath()),
				overlayText
			);
		}
	}

	@Command(name = "list", mixinStandardHelpOptions = true, description = "List active highlights.")
	private static final class HighlightsListCommand extends BaseCommand {
		private HighlightsListCommand(CliContext context) {
			super(context, "highlights list");
		}

		@Override
		Map<String, Object> runCommand() {
			return PayloadViews.highlightsList(transport().listHighlights(), verbose());
		}
	}

	@Command(name = "clear", mixinStandardHelpOptions = true, description = "Clear one highlight.")
	private static final class HighlightsClearCommand extends BaseCommand {
		@Option(names = "--highlight-id", required = true)
		private String highlightId;

		private HighlightsClearCommand(CliContext context) {
			super(context, "highlights clear");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().clearHighlight(highlightId);
		}
	}

	@Command(name = "clear-all", mixinStandardHelpOptions = true, description = "Clear all highlights.")
	private static final class HighlightsClearAllCommand extends BaseCommand {
		private HighlightsClearAllCommand(CliContext context) {
			super(context, "highlights clear-all");
		}

		@Override
		Map<String, Object> runCommand() {
			return transport().clearHighlights();
		}
	}

	@Command(name = "help", mixinStandardHelpOptions = true, description = "Show CLI help for a command path.")
	private static final class HelpCommand implements Callable<Integer> {
		private final CliContext context;

		@Parameters(arity = "0..*", description = "Command path to inspect, for example `highlights block`.")
		private List<String> commandPath = List.of();

		private HelpCommand(CliContext context) {
			this.context = context;
		}

		@Override
		public Integer call() {
			CommandLine target = context.rootCommandLine;
			List<String> consumed = new ArrayList<>();
			for (String token : commandPath) {
				CommandLine next = target.getSubcommands().get(token);
				if (next == null) {
					throw new CliUsageException(
						"help",
						"invalid_arguments",
						"Unknown command path: " + String.join(" ", append(consumed, token))
					);
				}
				target = next;
				consumed.add(token);
			}
			target.usage(context.printer.out);
			return 0;
		}
	}

	private static final class CliParameterExceptionHandler implements IParameterExceptionHandler {
		private final CliContext context;

		private CliParameterExceptionHandler(CliContext context) {
			this.context = context;
		}

		@Override
		public int handleParseException(ParameterException exception, String[] args) {
			String commandPath = commandPathFromArgs(rootCommandLine(exception.getCommandLine()), args);
			context.printer.printError(commandPath, "invalid_arguments", exception.getMessage());
			return 2;
		}
	}

	private static final class CliExecutionExceptionHandler implements IExecutionExceptionHandler {
		private final CliContext context;

		private CliExecutionExceptionHandler(CliContext context) {
			this.context = context;
		}

		@Override
		public int handleExecutionException(Exception exception, CommandLine commandLine, ParseResult parseResult) {
			String commandPath = parsedCommandPath(parseResult);
			Throwable cause = exception instanceof CommandLine.ExecutionException executionException && executionException.getCause() != null
				? executionException.getCause()
				: exception;

			if (cause instanceof CliUsageException usageException) {
				context.printer.printError(usageException.commandPath(), usageException.code(), usageException.getMessage());
				return 2;
			}
			if (cause instanceof BridgeUnavailableException bridgeUnavailableException) {
				context.printer.printError(commandPath, bridgeUnavailableException.code(), bridgeUnavailableException.getMessage());
				return transportExitCode(bridgeUnavailableException.code());
			}
			if (cause instanceof UncheckedIOException uncheckedIOException) {
				context.printer.printError(commandPath, "io_error", uncheckedIOException.getMessage());
				return 1;
			}

			context.printer.printError(commandPath, "internal_error", nonEmpty(cause.getMessage(), cause.getClass().getSimpleName()));
			return 1;
		}
	}

	private static final class CliUsageException extends RuntimeException {
		private final String commandPath;
		private final String code;

		private CliUsageException(String commandPath, String code, String message) {
			super(message);
			this.commandPath = commandPath;
			this.code = code;
		}

		private String commandPath() {
			return commandPath;
		}

		private String code() {
			return code;
		}
	}

	private static final class TextPrinter {
		private final PrintWriter out;

		private TextPrinter(PrintWriter out) {
			this.out = out;
		}

		private void printSuccess(String commandPath, Map<String, Object> payload) {
			out.println("status: ok");
			out.println("command: " + commandPath);
			renderMapBody(normalizeMap(payload));
			out.flush();
		}

		private void printError(String commandPath, String code, String message) {
			out.println("status: error");
			out.println("command: " + commandPath);
			out.println("error_code: " + code);
			out.println("message: " + message);
			out.flush();
		}

		private void renderMapBody(Map<String, Object> map) {
			List<Map.Entry<String, Object>> deferred = new ArrayList<>();
			for (Map.Entry<String, Object> entry : map.entrySet()) {
				Object value = entry.getValue();
				if (isScalar(value)) {
					out.println(entry.getKey() + ": " + scalarText(value));
				}
				else {
					deferred.add(entry);
				}
			}

			for (Map.Entry<String, Object> entry : deferred) {
				renderComplex(entry.getKey(), entry.getValue());
			}
		}

		private void renderComplex(String label, Object value) {
			if (value instanceof Map<?, ?> nested) {
				out.println();
				out.println("[" + label + "]");
				renderMapBody(normalizeMap(castMap(nested)));
				return;
			}
			if (value instanceof List<?> list) {
				String itemLabel = singularize(label);
				for (int index = 0; index < list.size(); index++) {
					Object item = list.get(index);
					out.println();
					out.println("[" + itemLabel + " " + (index + 1) + "]");
					if (item instanceof Map<?, ?> nested) {
						renderMapBody(normalizeMap(castMap(nested)));
					}
					else {
						out.println("value: " + scalarText(item));
					}
				}
			}
		}
	}

	private static final class PayloadViews {
		private PayloadViews() {
		}

		private static Map<String, Object> status(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "bridgeAvailable", "worldLoaded", "sessionState", "currentScreen", "canJoinWorldOrServer", "state", "message");
			if (verbose) {
				copy(view, payload, "dimension", "player", "focus");
			}
			return view;
		}

		private static Map<String, Object> reload(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "reloaded", "agentStateReset", "sessionMode", "worldLoaded",
				"plannerVisionMode", "llmConfigured", "visionConfigured", "observabilityEnabled");
			if (verbose) {
				copy(view, payload, "config", "llm", "observability");
			}
			return view;
		}

		private static Map<String, Object> agentStatus(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "initialized", "tickCount", "llmAvailable", "visionAvailable", "plannerVisionMode", "degraded");
			if (payload.containsKey("session")) {
				view.put("session", payload.get("session"));
			}
			Map<String, Object> eventPolicy = map(payload.get("eventPolicy"));
			copy(view, eventPolicy, "activeRuleCount", "recentInterventionCount", "lastMatchedRuleId", "lastMatchedEffect");
			if (payload.containsKey("taskExecution")) {
				view.put("taskExecution", payload.get("taskExecution"));
			}
			if (payload.containsKey("task")) {
				view.put("task", payload.get("task"));
			}
			if (verbose && payload.containsKey("verification")) {
				view.put("verification", payload.get("verification"));
			}
			return view;
		}

		private static Map<String, Object> agentSession(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "lanPublished", "lanPort", "primaryInteractionPlayer", "session");
			List<Map<String, Object>> nearbyPlayers = maps(payload.get("nearbyPlayers"));
			view.put("nearbyPlayerCount", nearbyPlayers.size());
			if (verbose) {
				view.put("nearbyPlayers", nearbyPlayers);
			}
			return view;
		}

		private static Map<String, Object> agentGoals(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "activeGoal", "taskExecution");
			if (verbose) {
				copy(view, payload, "task", "lastDialogueResponse");
			}
			return view;
		}

		private static Map<String, Object> agentTasks(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "cancelled", "task", "taskExecution", "missionExecution");
			if (!verbose && payload.containsKey("task")) {
				Map<String, Object> task = map(payload.get("task"));
				LinkedHashMap<String, Object> compactTask = new LinkedHashMap<>();
				copy(compactTask, task, "state", "spec", "mission", "activeStepId", "activeStepKind");
				view.put("task", compactTask);
			}
			return view;
		}

		private static Map<String, Object> agentLedger(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "ledger");
			if (!verbose && payload.containsKey("ledger")) {
				Map<String, Object> ledger = map(payload.get("ledger"));
				LinkedHashMap<String, Object> compactLedger = new LinkedHashMap<>();
				copy(compactLedger, ledger, "missionId", "missionType", "goalText", "activeStepId", "replanReason");
				view.put("ledger", compactLedger);
			}
			return view;
		}

		private static Map<String, Object> agentEvidence(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "evidence");
			if (!verbose && payload.containsKey("evidence")) {
				Map<String, Object> evidence = map(payload.get("evidence"));
				LinkedHashMap<String, Object> compactEvidence = new LinkedHashMap<>();
				copy(compactEvidence, evidence, "dimension", "x", "y", "z", "inventoryCounts");
				view.put("evidence", compactEvidence);
			}
			return view;
		}

		private static Map<String, Object> agentStepExecution(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "stepExecution", "taskExecution");
			if (!verbose && payload.containsKey("stepExecution")) {
				Map<String, Object> stepExecution = map(payload.get("stepExecution"));
				LinkedHashMap<String, Object> compactStep = new LinkedHashMap<>();
				copy(compactStep, stepExecution, "stepId", "status", "failureReason", "updatedTick");
				view.put("stepExecution", compactStep);
			}
			return view;
		}

		private static Map<String, Object> agentActionResolve(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "resolved", "failureCode", "message");
			Map<String, Object> route = map(payload.get("route"));
			List<Map<String, Object>> steps = maps(route.get("steps"));
			List<Map<String, Object>> trace = maps(payload.get("trace"));
			if (payload.containsKey("goal")) {
				view.put("goal", payload.get("goal"));
			}
			if (route.containsKey("cost")) {
				view.put("routeCost", route.get("cost"));
			}
			view.put("stepCount", steps.size());
			view.put("traceEventCount", trace.size());
			view.put("steps", filterItems(steps, verbose,
				List.of("kind", "actionId", "alternativeId", "stepId", "targetId"),
				List.of("args")
			));
			if (verbose) {
				view.put("trace", trace);
				copy(view, payload, "factSourceCounts", "actionsetDiagnostics");
			}
			return view;
		}

		private static Map<String, Object> agentActionExecute(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "resolved", "accepted", "alreadySatisfied", "failureCode", "message");
			Map<String, Object> route = map(payload.get("route"));
			List<Map<String, Object>> steps = maps(route.get("steps"));
			List<Map<String, Object>> trace = maps(payload.get("trace"));
			if (route.containsKey("cost")) {
				view.put("routeCost", route.get("cost"));
			}
			view.put("stepCount", steps.size());
			view.put("traceEventCount", trace.size());
			if (payload.containsKey("selectedStep")) {
				view.put("selectedStep", payload.get("selectedStep"));
			}
			if (payload.containsKey("dispatch")) {
				view.put("dispatch", payload.get("dispatch"));
			}
			copy(view, payload, "task", "taskExecution");
			if (verbose) {
				copy(view, payload, "goal", "factSourceCounts", "missionExecution", "actionsetDiagnostics");
				view.put("trace", trace);
			}
			return view;
		}

		private static Map<String, Object> agentActionInspect(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "actionsetValid", "actionsetRoot", "primitiveCount", "actionsetCount", "diagnosticCount");
			List<Map<String, Object>> primitives = maps(payload.get("primitives"));
			List<Map<String, Object>> actionsets = maps(payload.get("actionsets"));
			List<Map<String, Object>> diagnostics = maps(payload.get("actionsetDiagnostics"));
			view.put("primitives", filterItems(primitives, verbose,
				List.of("id", "version", "summary", "foregroundActuation", "executorBinding"),
				List.of("cost", "cancellable", "defaultTimeoutTicks", "capabilityTags", "failureCodes", "guardFactTypes", "needFactTypes", "producedFactTypes", "consumedFactTypes", "params")
			));
			view.put("actionsets", filterItems(actionsets, verbose,
				List.of("actionId", "namespace", "sourceName", "summary", "alternativeCount"),
				List.of("paramCount", "produces", "alternatives")
			));
			if (verbose || !diagnostics.isEmpty()) {
				view.put("actionsetDiagnostics", diagnostics);
			}
			return view;
		}

		private static Map<String, Object> agentDialogue(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "lastChatTick", "lastChatText");
			Map<String, Object> dialogue = map(payload.get("dialogue"));
			copy(view, dialogue, "pendingReply", "pendingReplyReason", "degraded", "consecutiveFailureCount", "lastFailureType", "lastFailureTick");
			List<Map<String, Object>> recentTurns = maps(dialogue.get("recentTurns"));
			view.put("recentTurnCount", recentTurns.size());
			if (verbose) {
				copy(view, dialogue, "lastResponse");
				view.put("recentTurns", recentTurns);
			}
			return view;
		}

		private static Map<String, Object> agentDebugState(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available");
			Map<String, Object> planner = map(payload.get("planner"));
			Map<String, Object> dialogueState = map(payload.get("dialogueState"));
			Map<String, Object> conversationSources = map(payload.get("conversationSources"));
			Map<String, Object> taskProgressProbe = map(payload.get("taskProgressProbe"));
			Map<String, Object> chatProbe = map(payload.get("chatProbe"));
			Map<String, Object> eventPipeline = map(payload.get("eventPipeline"));
			List<Map<String, Object>> plannerAttempts = maps(payload.get("plannerAttempts"));
			List<Map<String, Object>> timelineTail = maps(payload.get("timelineTail"));
			copy(view, planner, "configured", "plannerVisionMode", "inFlight", "plannerInFlight", "compactionInFlight", "toolInFlight", "activeGeneration", "currentPhase", "activeAttemptCount");
			copy(view, dialogueState, "pendingReply", "pendingReplyReason", "degraded", "consecutiveFailureCount", "lastFailureType", "lastFailureTick");
			copy(view, conversationSources, "canonicalMessageCount", "projectedMessageCount", "canonicalUserTurnCount", "projectedUserTurnCount", "hiddenKinds");
			copy(view, taskProgressProbe, "active", "resourceKind", "baselineResourceCount", "currentResourceCount", "inventoryDelta", "targetQuantity", "collected", "remaining", "activeJobStatus", "blockedReason", "completionReason");
			copy(view, chatProbe, "lastAttemptTick", "lastAttemptSource", "lastAttemptReusedPriorResponse", "lastSendSucceeded", "lastEmissionTick", "lastEmissionSource");
			copy(view, eventPipeline, "rawLatestSeqNo", "plannerLatestSeqNo", "lastProcessedRawSeqNo", "lastRawEventSeqNo", "lastPlannerEventSeqNo", "lastEventType", "lastDecisionEffect", "lastTriggerType", "lastEmitSemantic", "lastEmitTrigger");
			view.put("plannerAttemptCount", plannerAttempts.size());
			view.put("timelineEntryCount", timelineTail.size());
			if (verbose) {
				copy(view, dialogueState, "lastResponse");
				copy(view, chatProbe, "lastAttemptText", "lastEmissionText");
				copy(view, conversationSources, "canonicalConversation", "projectedConversation");
				view.put("plannerAttempts", plannerAttempts);
				view.put("timelineTail", timelineTail);
			}
			return view;
		}

		private static Map<String, Object> agentDebugTimeline(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "oldestEntryId", "latestEntryId", "truncated");
			List<Map<String, Object>> entries = maps(payload.get("entries"));
			view.put("entryCount", entries.size());
			view.put("entries", verbose ? entries : filterItems(entries, false,
				List.of("entryId", "tick", "timestampMs", "domain", "action", "summary"),
				List.of("correlation", "payload")
			));
			return view;
		}

		private static Map<String, Object> agentEvents(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "oldestSeqNo", "latestSeqNo", "truncated");
			List<Map<String, Object>> events = maps(payload.get("events"));
			view.put("eventCount", events.size());
			view.put("events", verbose ? events : filterItems(events, false,
				List.of("seqNo", "tick", "timestampMs", "type"),
				List.of("payload")
			));
			return view;
		}

		private static Map<String, Object> agentContext(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			view.put("available", payload.getOrDefault("available", false));
			Map<String, Object> planner = map(payload.get("planner"));
			Map<String, Object> context = map(planner.get("context"));
			Map<String, Object> eventPolicy = map(payload.get("eventPolicy"));
			List<Object> contextExcerpt = values(payload.get("contextExcerpt"));
			if (payload.containsKey("taskExecution")) {
				view.put("taskExecution", payload.get("taskExecution"));
			}
			copy(view, planner, "configured", "plannerVisionMode", "inFlight", "plannerInFlight", "compactionInFlight", "captureInFlight", "toolInFlight", "toolUsed");
			copy(view, planner, "coalescePending", "coalesceReadyAtMs", "coalesceWindowMs");
			copy(view, context, "compactionTriggerTokens", "compactionPending", "acceptedTurnCount",
				"pendingSemanticEventCount", "projectedPendingNoticeCount", "frozenPlannerMessageCount", "queuedTriggerCount",
				"lastObservedEventSeqNo", "lastAcceptedTimeContextAtMs", "pendingSemanticGap", "overflowFlushPending");
			copy(view, eventPolicy, "activeRuleCount", "recentInterventionCount", "lastMatchedRuleId", "lastMatchedEffect");
			view.put("contextExcerptLineCount", contextExcerpt.size());
			if (verbose) {
				copy(view, planner, "baseRequest", "lastCompactionResult");
				copy(view, context, "lastObservedUsage", "acceptedAmbientContext", "activeCheckpoint");
				view.put("contextExcerpt", contextExcerpt);
			}
			return view;
		}

		private static Map<String, Object> agentEventPolicy(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "activeRuleCount", "recentInterventionCount");
			Map<String, Object> lastDecision = map(payload.get("lastDecision"));
			copy(view, lastDecision, "matchedRuleId", "effect", "reason", "bypassed");
			List<Map<String, Object>> activeRules = maps(payload.get("activeRules"));
			List<Map<String, Object>> recentInterventions = maps(payload.get("recentInterventions"));
			view.put("ruleCount", activeRules.size());
			view.put("interventionCount", recentInterventions.size());
			if (verbose) {
				view.put("activeRules", activeRules);
				view.put("recentInterventions", recentInterventions);
			}
			return view;
		}

		private static Map<String, Object> agentCompact(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "started", "completed", "timeoutMs");
			Map<String, Object> planner = map(payload.get("planner"));
			Map<String, Object> context = map(planner.get("context"));
			copy(view, planner, "configured", "inFlight", "plannerInFlight", "compactionInFlight", "toolInFlight");
			copy(view, context, "compactionPending", "acceptedTurnCount");
			if (verbose) {
				copy(view, planner, "lastCompactionResult");
				copy(view, context, "lastObservedUsage", "activeCheckpoint");
			}
			return view;
		}

		private static Map<String, Object> verificationStatus(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			List<Object> capabilities = values(payload.get("capabilities"));
			copy(view, payload, "available", "sessionMode", "worldLoaded");
			view.put("capabilityCount", capabilities.size());
			if (verbose) {
				view.put("capabilities", capabilities);
			}
			return view;
		}

		private static Map<String, Object> verificationScenarios(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			List<Object> scenarios = values(payload.get("scenarios"));
			copy(view, payload, "available");
			view.put("scenarioCount", scenarios.size());
			view.put("scenarios", scenarios);
			if (verbose && payload.containsKey("report")) {
				view.put("report", payload.get("report"));
			}
			return view;
		}

		private static Map<String, Object> verificationResults(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			List<Object> scenarios = values(payload.get("scenarios"));
			Map<String, Object> report = map(payload.get("report"));
			copy(view, payload, "available");
			view.put("scenarioCount", scenarios.size());
			if (report.containsKey("status")) {
				view.put("reportStatus", report.get("status"));
			}
			if (report.containsKey("scenarioName")) {
				view.put("reportScenarioName", report.get("scenarioName"));
			}
			if (report.containsKey("message")) {
				view.put("reportMessage", report.get("message"));
			}
			List<Map<String, Object>> steps = maps(report.get("steps"));
			view.put("stepCount", steps.size());
			if (verbose) {
				view.put("scenarios", scenarios);
				view.put("report", report);
			}
			return view;
		}

		private static Map<String, Object> verificationPlayerState(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "sessionMode", "worldLoaded", "teleported", "applied", "respawned", "changed", "executed", "command", "currentScreen");
			copy(view, payload, "x", "y", "z", "health", "maxHealth", "food", "saturation", "onGround", "fallDistance", "gameMode", "dimensionId");
			return view;
		}

		private static Map<String, Object> worldsList(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "sessionState");
			List<Map<String, Object>> worlds = maps(payload.get("worlds"));
			view.put("worldCount", worlds.size());
			view.put("worlds", filterItems(worlds, verbose,
				List.of("worldId", "name", "displayName", "gameMode", "selectable", "lastPlayed"),
				List.of("immediatelyLoadable", "locked", "unavailable", "experimental", "details", "version")
			));
			return view;
		}

		private static Map<String, Object> serversList(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "sessionState");
			List<Map<String, Object>> servers = maps(payload.get("servers"));
			view.put("serverCount", servers.size());
			view.put("servers", filterItems(servers, verbose,
				List.of("serverId", "name", "address", "serverType", "index"),
				List.of("local", "realm", "resourcePackPolicy")
			));
			return view;
		}

		private static Map<String, Object> worldSnapshot(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			copy(view, payload, "available", "worldLoaded", "center", "radius");
			List<Map<String, Object>> blocks = maps(payload.get("blocks"));
			view.put("blockCount", blocks.size());
			view.put("blocks", verbose ? blocks : filterItems(blocks, false,
				List.of("pos", "id", "chunk"),
				List.of("state")
			));
			return view;
		}

		private static Map<String, Object> highlightsList(Map<String, Object> payload, boolean verbose) {
			LinkedHashMap<String, Object> view = new LinkedHashMap<>();
			List<Map<String, Object>> highlights = maps(payload.get("highlights"));
			view.put("highlightCount", highlights.size());
			view.put("highlights", highlights);
			if (verbose && payload.containsKey("available")) {
				view.put("available", payload.get("available"));
			}
			return view;
		}

		private static String normalizeTaskValue(String value) {
			return value == null ? null : value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
		}

		private static List<Map<String, Object>> filterItems(
			List<Map<String, Object>> items,
			boolean verbose,
			List<String> baseFields,
			List<String> verboseFields
		) {
			List<Map<String, Object>> filtered = new ArrayList<>(items.size());
			for (Map<String, Object> item : items) {
				LinkedHashMap<String, Object> current = new LinkedHashMap<>();
				copy(current, item, baseFields.toArray(String[]::new));
				if (verbose) {
					copy(current, item, verboseFields.toArray(String[]::new));
				}
				filtered.add(current);
			}
			return filtered;
		}

		private static void copy(Map<String, Object> target, Map<String, Object> source, String... keys) {
			for (String key : keys) {
				if (source.containsKey(key)) {
					target.put(key, source.get(key));
				}
			}
		}

		private static List<Map<String, Object>> maps(Object value) {
			if (!(value instanceof List<?> raw)) {
				return List.of();
			}
			List<Map<String, Object>> maps = new ArrayList<>(raw.size());
			for (Object item : raw) {
				if (item instanceof Map<?, ?> map) {
					maps.add(castMap(map));
				}
			}
			return maps;
		}

		private static List<Object> values(Object value) {
			if (value instanceof List<?> list) {
				return List.copyOf(list);
			}
			return List.of();
		}

		private static Map<String, Object> map(Object value) {
			if (value instanceof Map<?, ?> map) {
				return castMap(map);
			}
			return Map.of();
		}
	}

	private static Long validatedDurationMs(Integer durationSeconds, String commandPath) {
		if (durationSeconds == null) {
			return null;
		}
		if (durationSeconds < 1 || durationSeconds > 86_400) {
			throw new CliUsageException(commandPath, "invalid_arguments", "duration-seconds must be between 1 and 86400");
		}
		return durationSeconds * 1000L;
	}

	private static String validatedColor(String color, String commandPath) {
		if (color == null || color.isBlank()) {
			return null;
		}
		String normalized = color.startsWith("#") ? color.substring(1) : color;
		if (!(normalized.length() == 6 || normalized.length() == 8) || !normalized.matches("[0-9a-fA-F]+")) {
			throw new CliUsageException(commandPath, "invalid_arguments", "color must be a 6 or 8 digit hex value");
		}
		return color;
	}

	private static Map<String, Integer> parseInventoryAssumptions(List<String> values, String commandPath) {
		LinkedHashMap<String, Integer> assumptions = new LinkedHashMap<>();
		for (String value : values == null ? List.<String>of() : values) {
			if (value == null || value.isBlank()) {
				continue;
			}
			String[] parts = value.split("=", 2);
			if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
				throw new CliUsageException(commandPath, "invalid_arguments", "assume-inventory must use item=count");
			}
			int count;
			try {
				count = Integer.parseInt(parts[1]);
			}
			catch (NumberFormatException exception) {
				throw new CliUsageException(commandPath, "invalid_arguments", "assume-inventory count must be an integer");
			}
			if (count < 1) {
				throw new CliUsageException(commandPath, "invalid_arguments", "assume-inventory count must be positive");
			}
			assumptions.put(parts[0], count);
		}
		return assumptions;
	}

	private static Map<String, Object> normalizeMap(Map<String, Object> source) {
		LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
		source.entrySet().stream()
			.sorted(entryComparator(source))
			.forEach(entry -> normalized.put(entry.getKey(), normalizeValue(entry.getValue())));
		return normalized;
	}

	private static Comparator<Map.Entry<String, Object>> entryComparator(Map<String, Object> source) {
		if (source instanceof LinkedHashMap<?, ?>) {
			return (left, right) -> 0;
		}
		return Comparator.comparing(Map.Entry::getKey);
	}

	private static Object normalizeValue(Object value) {
		if (value instanceof Map<?, ?> map) {
			return normalizeMap(castMap(map));
		}
		if (value instanceof List<?> list) {
			List<Object> normalized = new ArrayList<>(list.size());
			for (Object item : list) {
				normalized.add(normalizeValue(item));
			}
			return normalized;
		}
		return value;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> castMap(Map<?, ?> map) {
		Map<String, Object> converted = map instanceof LinkedHashMap<?, ?> ? new LinkedHashMap<>() : new TreeMap<>();
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			converted.put(String.valueOf(entry.getKey()), entry.getValue());
		}
		return (Map<String, Object>) converted;
	}

	private static boolean isScalar(Object value) {
		return value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
	}

	private static String scalarText(Object value) {
		if (value == null) {
			return "none";
		}
		return String.valueOf(value).replace("\n", "\\n");
	}

	private static String singularize(String value) {
		String lower = value.toLowerCase(Locale.ROOT);
		if (lower.endsWith("ies") && value.length() > 3) {
			return value.substring(0, value.length() - 3) + "y";
		}
		if (lower.endsWith("s") && value.length() > 1) {
			return value.substring(0, value.length() - 1);
		}
		return value;
	}

	private static int transportExitCode(String code) {
		return switch (code) {
			case "minecraft_unavailable", "bridge_io_error", "bridge_interrupted" -> 3;
			default -> 4;
		};
	}

	private static String parsedCommandPath(ParseResult parseResult) {
		if (parseResult == null) {
			return "airicraft";
		}
		List<String> names = new ArrayList<>();
		for (CommandLine commandLine : parseResult.asCommandLineList()) {
			if (!"airicraft".equals(commandLine.getCommandName())) {
				names.add(commandLine.getCommandName());
			}
		}
		return names.isEmpty() ? "airicraft" : String.join(" ", names);
	}

	private static String commandPathFromArgs(CommandLine root, String[] args) {
		List<String> names = new ArrayList<>();
		CommandLine current = root;
		for (String arg : args) {
			if (arg.startsWith("-")) {
				break;
			}
			CommandLine next = current.getSubcommands().get(arg);
			if (next == null) {
				break;
			}
			names.add(arg);
			current = next;
		}
		return names.isEmpty() ? "airicraft" : String.join(" ", names);
	}

	private static CommandLine rootCommandLine(CommandLine commandLine) {
		CommandLine current = commandLine;
		while (current.getParent() != null) {
			current = current.getParent();
		}
		return current;
	}

	private static List<String> append(List<String> values, String value) {
		List<String> copy = new ArrayList<>(values);
		copy.add(value);
		return copy;
	}

	private static String nonEmpty(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}
}
