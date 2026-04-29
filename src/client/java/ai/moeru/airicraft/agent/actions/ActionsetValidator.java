package ai.moeru.airicraft.agent.actions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ActionsetValidator {
	private static final Pattern PARAM_REF = Pattern.compile("\\bparams\\.([A-Za-z_][A-Za-z0-9_]*)\\b");
	private static final Pattern GOAL_REF = Pattern.compile("\\bgoal\\.([A-Za-z_][A-Za-z0-9_]*)\\b");
	private static final Pattern ALLOWED_EXPR = Pattern.compile("[A-Za-z0-9_\\.\\s+\\-*/()]+");
	private static final Set<String> FACT_TYPES = ActionFactType.knownIds();
	private static final Set<String> GOAL_BINDINGS = Set.of("targetCount", "existingCount", "deficitCount");

	private final PrimitiveActionRegistry primitiveRegistry;

	private ActionsetValidator(PrimitiveActionRegistry primitiveRegistry) {
		this.primitiveRegistry = Objects.requireNonNull(primitiveRegistry, "primitiveRegistry");
	}

	public static ActionsetValidator defaults() {
		return new ActionsetValidator(PrimitiveActionRegistry.defaults());
	}

	public ActionsetValidationResult validate(ActionsetDocument document) {
		ArrayList<ActionsetValidationError> errors = new ArrayList<>();
		Map<String, Object> root = document == null ? Map.of() : document.root();
		validateVersion(root.get("version"), errors);
		Map<String, Object> actions = objectMap(root.get("actions"));
		if (actions.isEmpty()) {
			errors.add(new ActionsetValidationError("missing_field", "$.actions", "actions mapping is required"));
			return new ActionsetValidationResult(errors);
		}

		LinkedHashMap<String, ActionShape> shapes = new LinkedHashMap<>();
		for (Map.Entry<String, Object> actionEntry : actions.entrySet()) {
			String actionPath = "$.actions." + actionEntry.getKey();
			Map<String, Object> action = objectMap(actionEntry.getValue());
			Set<String> params = validateParams(actionEntry.getKey(), action, actionPath, errors);
			List<String> produces = validateFactList(action.get("produces"), actionPath + ".produces", params, errors);
			List<String> needs = validateAlternatives(actionEntry.getKey(), action, actionPath, params, errors);
			shapes.put(actionEntry.getKey(), new ActionShape(produces, needs));
		}
		validateCycles(shapes, errors);
		return new ActionsetValidationResult(errors);
	}

	private void validateVersion(Object version, List<ActionsetValidationError> errors) {
		if (!(version instanceof Number number) || number.intValue() != 1) {
			errors.add(new ActionsetValidationError("unsupported_version", "$.version", "version must be 1", "1", typeName(version)));
		}
	}

	private Set<String> validateParams(
		String actionId,
		Map<String, Object> action,
		String actionPath,
		List<ActionsetValidationError> errors
	) {
		LinkedHashSet<String> params = new LinkedHashSet<>();
		Map<String, Object> paramMap = objectMap(action.get("params"));
		for (Map.Entry<String, Object> paramEntry : paramMap.entrySet()) {
			String paramPath = actionPath + ".params." + paramEntry.getKey();
			Map<String, Object> definition = objectMap(paramEntry.getValue());
			String type = scalar(definition.get("type"));
			if (type == null || type.isBlank()) {
				errors.add(new ActionsetValidationError("missing_field", paramPath + ".type", "parameter type is required"));
				continue;
			}
			params.add(paramEntry.getKey());
			if (definition.containsKey("default")) {
				validateDefault(type, definition.get("default"), paramPath + ".default", errors);
			}
		}
		return params;
	}

	private void validateDefault(String expectedType, Object value, String path, List<ActionsetValidationError> errors) {
		if ("integer".equals(expectedType) && !(value instanceof Number)) {
			errors.add(new ActionsetValidationError("type_mismatch", path, "default must be an integer", "integer", typeName(value)));
		}
		if ("string".equals(expectedType) && !(value instanceof String)) {
			errors.add(new ActionsetValidationError("type_mismatch", path, "default must be a string", "string", typeName(value)));
		}
		if ("boolean".equals(expectedType) && !(value instanceof Boolean)) {
			errors.add(new ActionsetValidationError("type_mismatch", path, "default must be a boolean", "boolean", typeName(value)));
		}
	}

	private List<String> validateAlternatives(
		String actionId,
		Map<String, Object> action,
		String actionPath,
		Set<String> params,
		List<ActionsetValidationError> errors
	) {
		ArrayList<String> needs = new ArrayList<>();
		List<Object> alternatives = objectList(action.get("alternatives"));
		for (int index = 0; index < alternatives.size(); index++) {
			String alternativePath = actionPath + ".alternatives[" + index + "]";
			Map<String, Object> alternative = objectMap(alternatives.get(index));
			if (alternative.containsKey("requires")) {
				errors.add(new ActionsetValidationError("unsupported_field", alternativePath + ".requires", "use guards for existing facts or needs for resolver-satisfied facts"));
			}
			validateFactList(alternative.get("guards"), alternativePath + ".guards", params, errors);
			needs.addAll(validateFactList(alternative.get("needs"), alternativePath + ".needs", params, errors));
			validateFactList(alternative.get("consumes"), alternativePath + ".consumes", params, errors);
			validateSteps(alternative.get("steps"), alternativePath + ".steps", params, errors);
		}
		return List.copyOf(needs);
	}

	private List<String> validateFactList(
		Object value,
		String path,
		Set<String> params,
		List<ActionsetValidationError> errors
	) {
		ArrayList<String> keys = new ArrayList<>();
		if (value == null) {
			return keys;
		}
		List<Object> facts = objectList(value);
		for (int index = 0; index < facts.size(); index++) {
			String factPath = path + "[" + index + "]";
			Map<String, Object> fact = objectMap(facts.get(index));
			String factType = scalar(fact.get("fact"));
			if (factType == null || factType.isBlank()) {
				errors.add(new ActionsetValidationError("missing_field", factPath + ".fact", "fact type is required"));
				continue;
			}
			if (!FACT_TYPES.contains(factType)) {
				errors.add(new ActionsetValidationError("unknown_fact", factPath + ".fact", "unknown fact type " + factType));
			}
			for (Map.Entry<String, Object> entry : fact.entrySet()) {
				if ("countAtLeast".equals(entry.getKey()) || "matureCountAtLeast".equals(entry.getKey())) {
					validateExpressionValue(entry.getValue(), factPath + "." + entry.getKey(), params, errors);
				}
			}
			keys.add(normalizedFactKey(fact));
		}
		return keys;
	}

	private void validateSteps(
		Object value,
		String path,
		Set<String> params,
		List<ActionsetValidationError> errors
	) {
		if (value == null) {
			return;
		}
		List<Object> steps = objectList(value);
		for (int index = 0; index < steps.size(); index++) {
			String stepPath = path + "[" + index + "]";
			Map<String, Object> step = objectMap(steps.get(index));
			if (step.containsKey("action")) {
				errors.add(new ActionsetValidationError("unsupported_field", stepPath + ".action", "use primitive, actionset, goal, or watch"));
			}
			int kindCount = 0;
			kindCount += step.containsKey("primitive") ? 1 : 0;
			kindCount += step.containsKey("actionset") ? 1 : 0;
			kindCount += step.containsKey("goal") ? 1 : 0;
			kindCount += step.containsKey("watch") ? 1 : 0;
			if (kindCount == 0) {
				errors.add(new ActionsetValidationError("missing_step_kind", stepPath, "step must use primitive, actionset, goal, or watch"));
			}
			if (kindCount > 1) {
				errors.add(new ActionsetValidationError("ambiguous_step_kind", stepPath, "step can use only one step kind"));
			}
			if (step.containsKey("primitive")) {
				String primitive = scalar(step.get("primitive"));
				if (primitive == null || primitive.isBlank()) {
					errors.add(new ActionsetValidationError("missing_field", stepPath + ".primitive", "primitive id is required"));
				}
				else if (!primitiveRegistry.contains(primitive)) {
					errors.add(new ActionsetValidationError("unknown_primitive", stepPath + ".primitive", "unknown primitive \"" + primitive + "\""));
				}
			}
			if (step.containsKey("goal")) {
				validateFactMap(step.get("goal"), stepPath + ".goal", params, errors);
			}
			if (step.containsKey("watch")) {
				validateFactMap(step.get("watch"), stepPath + ".watch", params, errors);
			}
			if (step.containsKey("args")) {
				validateNestedExpressions(step.get("args"), stepPath + ".args", params, errors);
			}
		}
	}

	private void validateFactMap(
		Object value,
		String path,
		Set<String> params,
		List<ActionsetValidationError> errors
	) {
		Map<String, Object> fact = objectMap(value);
		String factType = scalar(fact.get("fact"));
		if (factType != null && !FACT_TYPES.contains(factType)) {
			errors.add(new ActionsetValidationError("unknown_fact", path + ".fact", "unknown fact type " + factType));
		}
		validateNestedExpressions(fact, path, params, errors);
	}

	@SuppressWarnings("unchecked")
	private void validateNestedExpressions(
		Object value,
		String path,
		Set<String> params,
		List<ActionsetValidationError> errors
	) {
		if (value instanceof Map<?, ?> map) {
			if (map.containsKey("expr")) {
				validateExpression(scalar(map.get("expr")), path + ".expr", params, errors);
				return;
			}
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				validateNestedExpressions(entry.getValue(), path + "." + entry.getKey(), params, errors);
			}
			return;
		}
		if (value instanceof List<?> list) {
			for (int index = 0; index < list.size(); index++) {
				validateNestedExpressions(list.get(index), path + "[" + index + "]", params, errors);
			}
		}
	}

	private void validateExpressionValue(
		Object value,
		String path,
		Set<String> params,
		List<ActionsetValidationError> errors
	) {
		if (value instanceof Map<?, ?> map && map.containsKey("expr")) {
			validateExpression(scalar(map.get("expr")), path + ".expr", params, errors);
		}
	}

	private void validateExpression(String expression, String path, Set<String> params, List<ActionsetValidationError> errors) {
		if (expression == null || expression.isBlank() || !ALLOWED_EXPR.matcher(expression).matches()) {
			errors.add(new ActionsetValidationError("invalid_expression", path, "invalid expression"));
			return;
		}
		Matcher matcher = PARAM_REF.matcher(expression);
		while (matcher.find()) {
			if (!params.contains(matcher.group(1))) {
				errors.add(new ActionsetValidationError("unknown_param", path, "unknown parameter " + matcher.group(1)));
			}
		}
		Matcher goalMatcher = GOAL_REF.matcher(expression);
		while (goalMatcher.find()) {
			if (!GOAL_BINDINGS.contains(goalMatcher.group(1))) {
				errors.add(new ActionsetValidationError("unknown_goal_binding", path, "unknown goal binding " + goalMatcher.group(1)));
			}
		}
	}

	private void validateCycles(Map<String, ActionShape> shapes, List<ActionsetValidationError> errors) {
		Map<String, String> producerByFact = new HashMap<>();
		for (Map.Entry<String, ActionShape> entry : shapes.entrySet()) {
			for (String produced : entry.getValue().produces()) {
				producerByFact.putIfAbsent(produced, entry.getKey());
			}
		}

		Map<String, Set<String>> edges = new LinkedHashMap<>();
		for (Map.Entry<String, ActionShape> entry : shapes.entrySet()) {
			LinkedHashSet<String> targets = new LinkedHashSet<>();
			for (String need : entry.getValue().needs()) {
				String target = producerByFact.get(need);
				if (target != null) {
					targets.add(target);
				}
			}
			edges.put(entry.getKey(), targets);
		}

		LinkedHashSet<String> visiting = new LinkedHashSet<>();
		HashSet<String> visited = new HashSet<>();
		for (String action : edges.keySet()) {
			if (detectCycle(action, edges, visiting, visited)) {
				errors.add(new ActionsetValidationError("cycle_detected", "$.actions." + visiting.iterator().next(), "cycle detected in action needs graph"));
				return;
			}
		}
	}

	private boolean detectCycle(
		String action,
		Map<String, Set<String>> edges,
		LinkedHashSet<String> visiting,
		Set<String> visited
	) {
		if (visited.contains(action)) {
			return false;
		}
		if (visiting.contains(action)) {
			return true;
		}
		visiting.add(action);
		for (String target : edges.getOrDefault(action, Set.of())) {
			if (detectCycle(target, edges, visiting, visited)) {
				return true;
			}
		}
		visiting.remove(action);
		visited.add(action);
		return false;
	}

	private static String normalizedFactKey(Map<String, Object> fact) {
		String factType = scalar(fact.get("fact"));
		StringBuilder builder = new StringBuilder(factType == null ? "" : factType);
		for (String key : List.of("actorId", "itemId", "toolTag", "dimension", "blockPos", "cropId", "siteId", "siteType", "entityTypeId", "recipeId")) {
			String value = scalar(fact.get(key));
			if (value != null && !value.isBlank()) {
				builder.append('|').append(key).append('=').append(value);
			}
		}
		return builder.toString();
	}

	@SuppressWarnings("unchecked")
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

	private static List<Object> objectList(Object value) {
		if (value instanceof List<?> list) {
			return List.copyOf(list);
		}
		return List.of();
	}

	private static String scalar(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private static String typeName(Object value) {
		if (value == null) {
			return "null";
		}
		if (value instanceof String) {
			return "string";
		}
		if (value instanceof Number) {
			return "number";
		}
		if (value instanceof Boolean) {
			return "boolean";
		}
		if (value instanceof Map<?, ?>) {
			return "object";
		}
		if (value instanceof List<?>) {
			return "array";
		}
		return value.getClass().getSimpleName();
	}

	private record ActionShape(
		List<String> produces,
		List<String> needs
	) {
	}
}
