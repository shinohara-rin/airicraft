package ai.moeru.airicraft.agent.actions;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import ai.moeru.actionplan.PlanCommand;
import ai.moeru.actionplan.MethodKey;
import ai.moeru.actionplan.ProviderId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The selected, bounded command list, independent of how the planner obtained it. */
public final class CommittedActionPlan {
	public static final int MAX_STEPS = 64;
	private static final Gson GSON = new Gson();
	private static final PrimitiveActionRegistry PRIMITIVES = PrimitiveActionRegistry.defaults();

	private CommittedActionPlan() {}

	public static ActionRoute parse(JsonArray steps, ActionResolverContext context) {
		if (steps == null || steps.size() > MAX_STEPS) {
			throw new IllegalArgumentException("steps must be an array with at most " + MAX_STEPS + " entries");
		}
		List<ActionPlanStep> parsed = new ArrayList<>();
		for (JsonElement element : steps) {
			if (!element.isJsonObject()) {
				throw new IllegalArgumentException("each step must be an object");
			}
			JsonObject step = element.getAsJsonObject();
			if (!step.has("primitive") || !step.get("primitive").isJsonPrimitive() || !step.get("primitive").getAsJsonPrimitive().isString()
				|| !step.has("args") || !step.get("args").isJsonObject()) {
				throw new IllegalArgumentException("each step requires primitive (string) and args (object)");
			}
			String primitive = step.get("primitive").getAsString();
			PrimitiveActionMetadata metadata = PRIMITIVES.find(primitive);
			boolean watch = "watch".equals(primitive);
			if (!watch && (metadata == null || !metadata.executable())) {
				throw new IllegalArgumentException("unsupported_primitive: " + primitive);
			}
			Map<String, Object> args = GSON.fromJson(step.getAsJsonObject("args"), new TypeToken<LinkedHashMap<String, Object>>() {}.getType());
			if (!watch) {
				metadata.parameterSchema().forEach((key, parameter) -> {
					if (parameter.required() && !args.containsKey(key)) {
						throw new IllegalArgumentException(primitive + " requires " + key);
					}
					if (args.containsKey(key) && !validParameter(args.get(key), parameter.type())) {
						throw new IllegalArgumentException(primitive + " invalid " + key + ": expected " + parameter.type());
					}
				});
			}
			else if (!args.containsKey("optionId") || !args.containsKey("itemId")) {
				throw new IllegalArgumentException("watch requires optionId and itemId for a smelting process");
			}
			for (String quantity : List.of("quantity", "inputQuantity", "timeoutTicks")) {
				if (args.containsKey(quantity) && (!validParameter(args.get(quantity), "integer") || ((Number) args.get(quantity)).longValue() < 1)) {
					throw new IllegalArgumentException(quantity + " must be a positive integer");
				}
			}
			args.put("airicraftKind", watch ? "WATCH" : "PRIMITIVE");
			args.put("actionId", "planner");
			args.put("alternativeId", "committed");
			parsed.add(AiricraftPlanConversions.toActionStep(new PlanCommand(
				"committed-" + parsed.size(), primitive, args, new MethodKey(new ProviderId("planner"), "committed")
			), context));
		}
		return new ActionRoute(parsed, 0);
	}

	private static boolean validParameter(Object value, String type) {
		return switch (type) {
			case "string" -> value instanceof String text && !text.isBlank();
			case "integer" -> value instanceof Number number && Double.isFinite(number.doubleValue())
				&& number.doubleValue() == Math.rint(number.doubleValue())
				&& number.doubleValue() >= Integer.MIN_VALUE && number.doubleValue() <= Integer.MAX_VALUE;
			case "boolean" -> value instanceof Boolean;
			case "string[]" -> value instanceof List<?> list && !list.isEmpty()
				&& list.stream().allMatch(item -> validParameter(item, "string"));
			case "object" -> value instanceof Map<?, ?>;
			default -> throw new IllegalStateException("Unknown primitive parameter type " + type);
		};
	}

	public static List<Map<String, Object>> stepsPayload(ActionRoute route) {
		return route.steps().stream().map(step -> Map.<String, Object>of("primitive", step.targetId(), "args", step.args())).toList();
	}
}
