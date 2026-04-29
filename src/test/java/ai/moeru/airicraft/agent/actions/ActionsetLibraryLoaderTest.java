package ai.moeru.airicraft.agent.actions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionsetLibraryLoaderTest {
	@TempDir
	Path tempDir;

	@Test
	void loadsValidBuiltinActionsetIntoIndex() throws IOException {
		Path root = tempDir.resolve("actionsets");
		writeActionset(root.resolve("builtin/make_bread.yml"), validMakeBread("make_bread"));

		ActionsetLoadResult result = ActionsetLibraryLoader.defaults().load(root);

		assertTrue(result.valid(), () -> result.diagnostics().toString());
		assertTrue(result.index().contains("make_bread"));
		ActionsetEntry entry = result.index().require("make_bread");
		assertEquals(ActionsetNamespace.BUILTIN, entry.namespace());
		assertTrue(entry.sourceName().endsWith("builtin/make_bread.yml"));
	}

	@Test
	void rejectsInvalidEnabledActionsetWithStableValidationPath() throws IOException {
		Path root = tempDir.resolve("actionsets");
		writeActionset(root.resolve("enabled/bad.yml"), """
			version: 1
			actions:
			  bad_bread:
			    alternatives:
			      - id: typo
			        steps:
			          - id: craft
			            primitive: craft_recipee
			""");

		ActionsetLoadResult result = ActionsetLibraryLoader.defaults().load(root);

		assertFalse(result.valid());
		assertFalse(result.index().contains("bad_bread"));
		assertDiagnostic(result.diagnostics(), "unknown_primitive", "$.actions.bad_bread.alternatives[0].steps[0].primitive", true);
	}

	@Test
	void invalidPlannerDraftIsReportedButNeverIndexedOrBlocking() throws IOException {
		Path root = tempDir.resolve("actionsets");
		writeActionset(root.resolve("builtin/make_bread.yml"), validMakeBread("make_bread"));
		writeActionset(root.resolve("planner_drafts/draft.yml"), """
			version: 1
			actions:
			  draft_bread:
			    alternatives:
			      - id: typo
			        steps:
			          - id: craft
			            primitive: craft_recipee
			""");

		ActionsetLoadResult result = ActionsetLibraryLoader.defaults().load(root);

		assertTrue(result.valid(), () -> result.diagnostics().toString());
		assertTrue(result.index().contains("make_bread"));
		assertFalse(result.index().contains("draft_bread"));
		assertDiagnostic(result.diagnostics(), "unknown_primitive", "$.actions.draft_bread.alternatives[0].steps[0].primitive", false);
	}

	@Test
	void duplicateActionIdsAcrossIndexedNamespacesAreBlocking() throws IOException {
		Path root = tempDir.resolve("actionsets");
		writeActionset(root.resolve("builtin/make_bread.yml"), validMakeBread("make_bread"));
		writeActionset(root.resolve("enabled/make_bread.yml"), validMakeBread("make_bread"));

		ActionsetLoadResult result = ActionsetLibraryLoader.defaults().load(root);

		assertFalse(result.valid());
		assertEquals(ActionsetNamespace.BUILTIN, result.index().require("make_bread").namespace());
		assertDiagnostic(result.diagnostics(), "duplicate_action", "$.actions.make_bread", true);
	}

	@Test
	void detectsCyclesAcrossSeparateActionsetFiles() throws IOException {
		Path root = tempDir.resolve("actionsets");
		writeActionset(root.resolve("builtin/make_bread.yml"), """
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
			""");
		writeActionset(root.resolve("builtin/obtain_wheat.yml"), """
			version: 1
			actions:
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

		ActionsetLoadResult result = ActionsetLibraryLoader.defaults().load(root);

		assertFalse(result.valid());
		assertDiagnostic(result.diagnostics(), "cycle_detected", "$.actions.make_bread", true);
	}

	@Test
	void repositorySeedMakeBreadLoadsFromDefaultLayout() throws IOException {
		ActionsetLoadResult result = ActionsetLibraryLoader.defaults().load(Path.of("actionsets"));

		assertTrue(result.valid(), () -> result.diagnostics().toString());
		assertTrue(result.index().contains("make_bread"));
	}

	private static void writeActionset(Path path, String yaml) throws IOException {
		Files.createDirectories(path.getParent());
		Files.writeString(path, yaml);
	}

	private static String validMakeBread(String actionId) {
		return """
			version: 1
			actions:
			  %s:
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
			""".formatted(actionId);
	}

	private static void assertDiagnostic(List<ActionsetLoadDiagnostic> diagnostics, String code, String path, boolean blocking) {
		long matches = diagnostics.stream()
			.filter(diagnostic -> code.equals(diagnostic.code()))
			.filter(diagnostic -> path.equals(diagnostic.path()))
			.filter(diagnostic -> blocking == diagnostic.blocking())
			.count();
		assertEquals(1, matches, () -> diagnostics.toString());
	}
}
