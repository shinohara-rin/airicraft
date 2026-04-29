package ai.moeru.airicraft.agent.actions;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionsetValidatorTest {
	@Test
	void acceptsBreadSkeletonWithGuardsNeedsAndExplicitSteps() {
		ActionsetValidationResult result = validate("""
			version: 1
			actions:
			  make_bread:
			    summary: Produce bread in inventory.
			    params:
			      quantity:
			        type: integer
			        default: 1
			        min: 1
			    produces:
			      - fact: inventory.item
			        itemId: minecraft:bread
			        countAtLeast:
			          expr: "goal.targetCount"
			    alternatives:
			      - id: already_have_bread
			        cost: 0
			        guards:
			          - fact: inventory.item
			            itemId: minecraft:bread
			            countAtLeast:
			              expr: "goal.targetCount"
			        steps: []
			      - id: craft_from_inventory_wheat
			        cost: 10
			        guards:
			          - fact: inventory.item
			            itemId: minecraft:wheat
			            countAtLeast:
			              expr: "goal.deficitCount * 3"
			        steps:
			          - id: craft_bread
			            primitive: craft_item
			            args:
			              itemId: minecraft:bread
			              quantity:
			                expr: "goal.deficitCount"
			      - id: obtain_wheat_then_craft
			        cost: 40
			        needs:
			          - fact: inventory.item
			            itemId: minecraft:wheat
			            countAtLeast:
			              expr: "goal.deficitCount * 3"
			        steps:
			          - id: craft_bread
			            primitive: craft_item
			            args:
			              itemId: minecraft:bread
			              quantity:
			                expr: "goal.deficitCount"
			""");

		assertTrue(result.valid(), () -> result.errors().toString());
	}

	@Test
	void rejectsUnknownPrimitivesWithStablePath() {
		ActionsetValidationResult result = validate("""
			version: 1
			actions:
			  make_bread:
			    alternatives:
			      - id: bad_route
			        steps:
			          - id: craft_bread
			            primitive: craft_recipee
			""");

		assertError(result, "unknown_primitive", "$.actions.make_bread.alternatives[0].steps[0].primitive");
	}

	@Test
	void rejectsAmbiguousLegacyRequiresAndActionStepKeys() {
		ActionsetValidationResult result = validate("""
			version: 1
			actions:
			  make_bread:
			    alternatives:
			      - id: bad_route
			        requires:
			          - fact: inventory.item
			            itemId: minecraft:wheat
			        steps:
			          - id: craft_bread
			            action: craft_item
			""");

		assertError(result, "unsupported_field", "$.actions.make_bread.alternatives[0].requires");
		assertError(result, "unsupported_field", "$.actions.make_bread.alternatives[0].steps[0].action");
	}

	@Test
	void typechecksParamDefaultsAndExpressions() {
		ActionsetValidationResult result = validate("""
			version: 1
			actions:
			  make_bread:
			    params:
			      quantity:
			        type: integer
			        default: "two"
			    produces:
			      - fact: inventory.item
			        itemId: minecraft:bread
			        countAtLeast:
			          expr: "params.missing * 3"
			""");

		assertError(result, "type_mismatch", "$.actions.make_bread.params.quantity.default");
		assertError(result, "unknown_param", "$.actions.make_bread.produces[0].countAtLeast.expr");
	}

	@Test
	void rejectsUnknownGoalBindings() {
		ActionsetValidationResult result = validate("""
			version: 1
			actions:
			  make_bread:
			    produces:
			      - fact: inventory.item
			        itemId: minecraft:bread
			        countAtLeast:
			          expr: "goal.missingQuantity"
			""");

		assertError(result, "unknown_goal_binding", "$.actions.make_bread.produces[0].countAtLeast.expr");
	}

	@Test
	void detectsCyclesByNormalizedGoalKeys() {
		ActionsetValidationResult result = validate("""
			version: 1
			actions:
			  make_bread:
			    produces:
			      - fact: inventory.item
			        itemId: minecraft:bread
			    alternatives:
			      - id: obtain_wheat_then_craft
			        needs:
			          - fact: inventory.item
			            itemId: minecraft:wheat
			  obtain_wheat:
			    produces:
			      - fact: inventory.item
			        itemId: minecraft:wheat
			    alternatives:
			      - id: impossible
			        needs:
			          - fact: inventory.item
			            itemId: minecraft:bread
			""");

		assertError(result, "cycle_detected", "$.actions.make_bread");
	}

	private static ActionsetValidationResult validate(String yaml) {
		return ActionsetValidator.defaults().validate(ActionsetParser.parse("test.yml", yaml));
	}

	private static void assertError(ActionsetValidationResult result, String code, String path) {
		List<ActionsetValidationError> matches = result.errors().stream()
			.filter(error -> code.equals(error.code()) && path.equals(error.path()))
			.toList();
		assertEquals(1, matches.size(), () -> result.errors().toString());
	}
}
