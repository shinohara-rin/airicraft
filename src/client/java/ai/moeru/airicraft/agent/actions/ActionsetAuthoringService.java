package ai.moeru.airicraft.agent.actions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class ActionsetAuthoringService {
	private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");
	private static final int MAX_YAML_BYTES = 128 * 1024;

	private final Path root;
	private final ActionsetValidator validator;
	private final Map<String, ActionsetTrialReport> trialReports = new LinkedHashMap<>();

	public ActionsetAuthoringService(Path root) {
		this(root, ActionsetValidator.defaults());
	}

	public ActionsetAuthoringService(Path root, ActionsetValidator validator) {
		this.root = root == null ? ActionsetLibraryPaths.defaultRoot() : root;
		this.validator = Objects.requireNonNull(validator, "validator");
		ensureWorkspace();
		seedBuiltinActionsets();
	}

	public Path root() {
		return root;
	}

	public ActionsetDraftWriteResult writeDraft(String draftId, String yaml) {
		String safeDraftId = safeId(draftId);
		String normalizedYaml = yaml == null ? "" : yaml;
		if (normalizedYaml.getBytes(StandardCharsets.UTF_8).length > MAX_YAML_BYTES) {
			throw new ActionsetPromotionException("actionset_too_large", "actionset YAML exceeds " + MAX_YAML_BYTES + " bytes");
		}
		writeString(draftPath(safeDraftId), normalizedYaml);
		List<ActionsetLoadDiagnostic> diagnostics = validateYaml("planner_drafts/" + safeDraftId + ".yml", normalizedYaml, ActionsetNamespace.PLANNER_DRAFTS);
		String hash = contentHash(normalizedYaml);
		ActionsetDraftStatus status = diagnostics.isEmpty() ? draftStatusFor(safeDraftId, hash, false) : ActionsetDraftStatus.INVALID;
		return new ActionsetDraftWriteResult(safeDraftId, status, hash, diagnostics);
	}

	public Map<String, Object> validatePayload(String sourceName, String yaml) {
		String safeSourceName = sourceName == null || sourceName.isBlank() ? "inline.yml" : sourceName;
		String normalizedYaml = yaml == null ? "" : yaml;
		List<ActionsetLoadDiagnostic> diagnostics = validateYaml(safeSourceName, normalizedYaml, ActionsetNamespace.PLANNER_DRAFTS);
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("available", true);
		payload.put("valid", diagnostics.isEmpty());
		payload.put("contentHash", contentHash(normalizedYaml));
		payload.put("diagnosticCount", diagnostics.size());
		payload.put("diagnostics", diagnostics.stream().map(ActionsetAuthoringService::diagnosticPayload).toList());
		return payload;
	}

	public ActionsetDraftSummary draftSummary(String draftId) {
		String safeDraftId = safeId(draftId);
		String yaml = readString(draftPath(safeDraftId));
		List<ActionsetLoadDiagnostic> diagnostics = validateYaml("planner_drafts/" + safeDraftId + ".yml", yaml, ActionsetNamespace.PLANNER_DRAFTS);
		String hash = contentHash(yaml);
		return new ActionsetDraftSummary(safeDraftId, diagnostics.isEmpty() ? draftStatusFor(safeDraftId, hash, enabledPath(safeDraftId).toFile().exists()) : ActionsetDraftStatus.INVALID, hash, diagnostics.size());
	}

	public List<ActionsetDraftSummary> listDrafts() {
		Path drafts = namespaceRoot(ActionsetNamespace.PLANNER_DRAFTS);
		if (!Files.exists(drafts)) {
			return List.of();
		}
		try (Stream<Path> stream = Files.list(drafts)) {
			return stream
				.filter(Files::isRegularFile)
				.filter(ActionsetAuthoringService::isYamlFile)
				.sorted(Comparator.comparing(path -> path.getFileName().toString()))
				.map(path -> fileBaseName(path.getFileName().toString()))
				.map(this::draftSummary)
				.toList();
		}
		catch (IOException exception) {
			throw new ActionsetPromotionException("source_read_failed", exception.getMessage());
		}
	}

	public String readFile(ActionsetNamespace namespace, String fileId) {
		ActionsetNamespace safeNamespace = namespace == null ? ActionsetNamespace.PLANNER_DRAFTS : namespace;
		return readString(namespaceRoot(safeNamespace).resolve(safeId(fileId) + ".yml"));
	}

	public ActionsetPromotionResult promoteDraft(String draftId, String enabledId, boolean markFunctional) {
		String safeDraftId = safeId(draftId);
		String safeEnabledId = safeId(enabledId);
		Path draftPath = draftPath(safeDraftId);
		String yaml = readString(draftPath);
		List<ActionsetLoadDiagnostic> diagnostics = validateYaml("planner_drafts/" + safeDraftId + ".yml", yaml, ActionsetNamespace.PLANNER_DRAFTS);
		if (!diagnostics.isEmpty()) {
			throw new ActionsetPromotionException("actionset_validation_failed", "draft has validation diagnostics");
		}
		String hash = contentHash(yaml);
		if (markFunctional && !hasPassedTrial(safeDraftId, hash)) {
			throw new ActionsetPromotionException("trial_required", "current draft content has no passing live trial");
		}
		Path enabledPath = enabledPath(safeEnabledId);
		writeString(enabledPath, yaml);
		ActionsetLoadResult load = ActionsetLibraryLoader.defaults().load(root);
		if (!load.valid()) {
			try {
				Files.deleteIfExists(enabledPath);
			}
			catch (IOException ignored) {
			}
			throw new ActionsetPromotionException("actionset_validation_failed", "enabled actionset library failed validation");
		}
		return new ActionsetPromotionResult(safeDraftId, safeEnabledId, ActionsetDraftStatus.ENABLED, hash);
	}

	public void recordTrialReport(ActionsetTrialReport report) {
		if (report == null || report.draftId().isBlank()) {
			return;
		}
		trialReports.put(report.draftId(), report);
	}

	public Optional<ActionsetTrialReport> trialReport(String draftId) {
		return Optional.ofNullable(trialReports.get(safeId(draftId)));
	}

	public boolean hasPassedTrial(String draftId, String contentHash) {
		return trialReport(draftId)
			.filter(ActionsetTrialReport::passed)
			.filter(report -> Objects.equals(report.contentHash(), contentHash))
			.isPresent();
	}

	public ActionsetIndex temporaryTrialIndex(String draftId) {
		String safeDraftId = safeId(draftId);
		String yaml = readString(draftPath(safeDraftId));
		ActionsetDocument document = parseDocument("planner_drafts/" + safeDraftId + ".yml", yaml);
		ActionsetValidationResult validation = validator.validate(document);
		if (!validation.valid()) {
			throw new ActionsetPromotionException("actionset_validation_failed", "draft has validation diagnostics");
		}
		ActionsetLoadResult baseLoad = ActionsetLibraryLoader.defaults().load(root);
		if (!baseLoad.valid()) {
			throw new ActionsetPromotionException("actionset_validation_failed", "base actionset library failed validation");
		}
		LinkedHashMap<String, ActionsetEntry> entries = new LinkedHashMap<>();
		LinkedHashMap<String, ActionsetEntry> draftEntries = new LinkedHashMap<>();
		for (Map.Entry<String, Object> action : actionMap(document).entrySet()) {
			String actionId = action.getKey();
			draftEntries.put(actionId, new ActionsetEntry(
				actionId,
				ActionsetNamespace.PLANNER_DRAFTS,
				"planner_drafts/" + safeDraftId + ".yml",
				draftPath(safeDraftId),
				objectMap(action.getValue())
			));
		}
		entries.putAll(draftEntries);
		for (ActionsetEntry entry : baseLoad.index().all()) {
			entries.putIfAbsent(entry.actionId(), entry);
		}
		return new ActionsetIndex(entries);
	}

	public boolean draftUsesForegroundPrimitive(String draftId) {
		String safeDraftId = safeId(draftId);
		ActionsetDocument document = parseDocument("planner_drafts/" + safeDraftId + ".yml", readString(draftPath(safeDraftId)));
		Map<String, PrimitiveActionMetadata> primitives = new LinkedHashMap<>();
		for (PrimitiveActionMetadata metadata : PrimitiveActionRegistry.defaults().all()) {
			primitives.put(metadata.id(), metadata);
		}
		for (Object actionObject : actionMap(document).values()) {
			for (Object alternativeObject : objectList(objectMap(actionObject).get("alternatives"))) {
				for (Object stepObject : objectList(objectMap(alternativeObject).get("steps"))) {
					Map<String, Object> step = objectMap(stepObject);
					String primitive = stringValue(step.get("primitive"));
					if (!primitive.isBlank()) {
						PrimitiveActionMetadata metadata = primitives.get(primitive);
						if (metadata != null && metadata.foregroundActuation()) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	public Map<String, Object> listPayload() {
		ActionsetLoadResult load = ActionsetLibraryLoader.defaults().load(root);
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("available", true);
		payload.put("actionsetRoot", root.toString());
		payload.put("valid", load.valid());
		payload.put("diagnosticCount", load.diagnostics().size());
		payload.put("diagnostics", load.diagnostics().stream().map(ActionsetAuthoringService::diagnosticPayload).toList());
		payload.put("drafts", listDrafts().stream().map(ActionsetDraftSummary::toPayload).toList());
		payload.put("enabled", namespaceFiles(ActionsetNamespace.ENABLED));
		payload.put("operator", namespaceFiles(ActionsetNamespace.OPERATOR));
		payload.put("builtin", namespaceFiles(ActionsetNamespace.BUILTIN));
		return payload;
	}

	static Map<String, Object> draftPayload(String draftId, ActionsetDraftStatus status, String contentHash, List<ActionsetLoadDiagnostic> diagnostics) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("available", true);
		payload.put("draftId", draftId);
		payload.put("status", status.name());
		payload.put("contentHash", contentHash);
		payload.put("valid", diagnostics == null || diagnostics.isEmpty());
		payload.put("diagnostics", diagnostics == null ? List.of() : diagnostics.stream().map(ActionsetAuthoringService::diagnosticPayload).toList());
		return payload;
	}

	public static Map<String, Object> diagnosticPayload(ActionsetLoadDiagnostic diagnostic) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("code", diagnostic.code());
		payload.put("path", diagnostic.path());
		payload.put("message", diagnostic.message());
		payload.put("sourceName", diagnostic.sourceName());
		payload.put("namespace", diagnostic.namespace() == null ? "" : diagnostic.namespace().name());
		payload.put("blocking", diagnostic.blocking());
		return payload;
	}

	private ActionsetDraftStatus draftStatusFor(String draftId, String hash, boolean enabled) {
		if (enabled) {
			return ActionsetDraftStatus.ENABLED;
		}
		Optional<ActionsetTrialReport> report = trialReport(draftId).filter(current -> Objects.equals(current.contentHash(), hash));
		if (report.isPresent()) {
			return report.get().passed() ? ActionsetDraftStatus.TRIAL_PASSED : ActionsetDraftStatus.TRIAL_FAILED;
		}
		return ActionsetDraftStatus.VALID;
	}

	private List<ActionsetLoadDiagnostic> validateYaml(String sourceName, String yaml, ActionsetNamespace namespace) {
		try {
			ActionsetDocument document = ActionsetParser.parse(sourceName, yaml);
			ActionsetValidationResult validation = validator.validate(document);
			return validation.errors().stream()
				.map(error -> new ActionsetLoadDiagnostic(error.code(), error.path(), error.message(), sourceName, namespace, namespace.indexed()))
				.toList();
		}
		catch (RuntimeException exception) {
			return List.of(new ActionsetLoadDiagnostic("parse_failed", "$", exception.getMessage(), sourceName, namespace, namespace.indexed()));
		}
	}

	private ActionsetDocument parseDocument(String sourceName, String yaml) {
		return ActionsetParser.parse(sourceName, yaml);
	}

	private void ensureWorkspace() {
		for (ActionsetNamespace namespace : ActionsetNamespace.values()) {
			createDirectories(namespaceRoot(namespace));
		}
		createDirectories(root.resolve("trial_reports"));
	}

	private void seedBuiltinActionsets() {
		Path target = namespaceRoot(ActionsetNamespace.BUILTIN);
		Path source = ActionsetLibraryPaths.defaultRoot().resolve(ActionsetNamespace.BUILTIN.directoryName());
		if (!Files.exists(source) || source.equals(target)) {
			return;
		}
		try (Stream<Path> stream = Files.list(source)) {
			for (Path file : stream.filter(Files::isRegularFile).filter(ActionsetAuthoringService::isYamlFile).toList()) {
				Path destination = target.resolve(file.getFileName().toString());
				Files.copy(file, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException exception) {
			throw new ActionsetPromotionException("source_write_failed", exception.getMessage());
		}
	}

	private Path namespaceRoot(ActionsetNamespace namespace) {
		return root.resolve(namespace.directoryName());
	}

	private Path draftPath(String draftId) {
		return namespaceRoot(ActionsetNamespace.PLANNER_DRAFTS).resolve(safeId(draftId) + ".yml");
	}

	private Path enabledPath(String enabledId) {
		return namespaceRoot(ActionsetNamespace.ENABLED).resolve(safeId(enabledId) + ".yml");
	}

	private List<String> namespaceFiles(ActionsetNamespace namespace) {
		Path namespaceRoot = namespaceRoot(namespace);
		if (!Files.exists(namespaceRoot)) {
			return List.of();
		}
		try (Stream<Path> stream = Files.list(namespaceRoot)) {
			return stream
				.filter(Files::isRegularFile)
				.filter(ActionsetAuthoringService::isYamlFile)
				.map(path -> path.getFileName().toString())
				.sorted()
				.toList();
		}
		catch (IOException exception) {
			return List.of();
		}
	}

	private static String safeId(String id) {
		if (id == null || !SAFE_ID.matcher(id).matches()) {
			throw new ActionsetPromotionException("invalid_actionset_id", "actionset id must match " + SAFE_ID.pattern());
		}
		return id;
	}

	private static void createDirectories(Path path) {
		try {
			Files.createDirectories(path);
		}
		catch (IOException exception) {
			throw new ActionsetPromotionException("source_write_failed", exception.getMessage());
		}
	}

	private static String readString(Path path) {
		try {
			return Files.readString(path, StandardCharsets.UTF_8);
		}
		catch (IOException exception) {
			throw new ActionsetPromotionException("source_read_failed", exception.getMessage());
		}
	}

	private static void writeString(Path path, String value) {
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, value, StandardCharsets.UTF_8);
		}
		catch (IOException exception) {
			throw new ActionsetPromotionException("source_write_failed", exception.getMessage());
		}
	}

	private static String contentHash(String text) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static Map<String, Object> actionMap(ActionsetDocument document) {
		return objectMap(document.root().get("actions"));
	}

	private static List<Object> objectList(Object value) {
		if (value instanceof List<?> list) {
			return new ArrayList<>(list);
		}
		return List.of();
	}

	private static Map<String, Object> objectMap(Object value) {
		if (value instanceof Map<?, ?> map) {
			LinkedHashMap<String, Object> typed = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				typed.put(String.valueOf(entry.getKey()), entry.getValue());
			}
			return typed;
		}
		return Map.of();
	}

	private static String stringValue(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private static boolean isYamlFile(Path path) {
		return isYamlFile(path.getFileName().toString());
	}

	private static boolean isYamlFile(String fileName) {
		return fileName.endsWith(".yml") || fileName.endsWith(".yaml");
	}

	private static String fileBaseName(String fileName) {
		if (fileName.endsWith(".yaml")) {
			return fileName.substring(0, fileName.length() - ".yaml".length());
		}
		if (fileName.endsWith(".yml")) {
			return fileName.substring(0, fileName.length() - ".yml".length());
		}
		return fileName;
	}
}
