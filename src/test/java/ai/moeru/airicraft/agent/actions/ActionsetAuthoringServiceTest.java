package ai.moeru.airicraft.agent.actions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionsetAuthoringServiceTest {
	@TempDir
	Path tempDir;

	@Test
	void invalidDraftPersistsButNeverEntersResolverIndex() throws IOException {
		ActionsetAuthoringService service = new ActionsetAuthoringService(tempDir.resolve("actionsets"));

		ActionsetDraftWriteResult result = service.writeDraft("bad-draft", """
			version: 1
			actions:
			  bad_draft:
			    produces:
			      - fact: inventory.item
			        itemId: minecraft:bread
			    alternatives:
			      - id: broken
			        steps:
			          - id: broken_step
			            primitive: craft_recipee
			""");

		assertEquals(ActionsetDraftStatus.INVALID, result.status());
		assertTrue(Files.exists(tempDir.resolve("actionsets/planner_drafts/bad-draft.yml")));
		assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> "unknown_primitive".equals(diagnostic.code())));

		ActionsetLoadResult load = ActionsetLibraryLoader.defaults().load(tempDir.resolve("actionsets"));
		assertTrue(load.valid(), () -> load.diagnostics().toString());
		assertFalse(load.index().contains("bad_draft"));
	}

	@Test
	void promoteMarkFunctionalRequiresCurrentContentHashTrialPass() throws IOException {
		ActionsetAuthoringService service = new ActionsetAuthoringService(tempDir.resolve("actionsets"));
		service.writeDraft("bread_patch", validBreadActionset("bread_patch"));

		ActionsetPromotionException missingTrial = assertThrows(ActionsetPromotionException.class, () ->
			service.promoteDraft("bread_patch", "bread_patch", true));
		assertEquals("trial_required", missingTrial.code());

		String hash = service.draftSummary("bread_patch").contentHash();
		service.recordTrialReport(ActionsetTrialReport.passed("bread_patch", hash, Map.of("state", "SUCCEEDED")));

		ActionsetPromotionResult promoted = service.promoteDraft("bread_patch", "bread_patch", true);

		assertEquals(ActionsetDraftStatus.ENABLED, promoted.status());
		assertTrue(Files.exists(tempDir.resolve("actionsets/enabled/bread_patch.yml")));
	}

	@Test
	void editingDraftInvalidatesPreviousFunctionalTrial() throws IOException {
		ActionsetAuthoringService service = new ActionsetAuthoringService(tempDir.resolve("actionsets"));
		service.writeDraft("bread_patch", validBreadActionset("bread_patch"));
		String oldHash = service.draftSummary("bread_patch").contentHash();
		service.recordTrialReport(ActionsetTrialReport.passed("bread_patch", oldHash, Map.of("state", "SUCCEEDED")));

		service.writeDraft("bread_patch", validBreadActionset("changed_bread_patch"));

		ActionsetPromotionException staleTrial = assertThrows(ActionsetPromotionException.class, () ->
			service.promoteDraft("bread_patch", "bread_patch", true));
		assertEquals("trial_required", staleTrial.code());
	}

	@Test
	void rejectsUnsafeDraftIds() throws IOException {
		ActionsetAuthoringService service = new ActionsetAuthoringService(tempDir.resolve("actionsets"));

		ActionsetPromotionException exception = assertThrows(ActionsetPromotionException.class, () ->
			service.writeDraft("../bad", validBreadActionset("bad")));

		assertEquals("invalid_actionset_id", exception.code());
	}

	private static String validBreadActionset(String actionId) {
		return """
			version: 1
			actions:
			  %s:
			    produces:
			      - fact: inventory.item
			        itemId: minecraft:bread
			        countAtLeast: 1
			    alternatives:
			      - id: already_have_bread
			        guards:
			          - fact: inventory.item
			            itemId: minecraft:bread
			            countAtLeast: 1
			        steps: []
			""".formatted(actionId);
	}
}
