package ai.moeru.airicraft.agent.recording;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlannerCallRecordV1Test {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final Path CONTRACT_ROOT = Path.of("docs", "contracts");

	@Test
	void exampleIsTheSerializedProducerContract() throws IOException {
		JsonObject expected = readObject(CONTRACT_ROOT.resolve("airicraft.planner-call.v1.example.json"));
		PlannerCallRecordV1 record = GSON.fromJson(expected, PlannerCallRecordV1.class);

		assertEquals(PlannerCallRecordV1.SCHEMA_VERSION, record.schemaVersion());
		assertEquals(PlannerCallRecordV1.Status.COMPLETED, record.outcome().status());
		assertEquals(expected, GSON.toJsonTree(record));
	}

	@Test
	void schemaNamesTheAssetContractAndRequiresEveryProducerField() throws IOException {
		JsonObject schema = readObject(CONTRACT_ROOT.resolve("airicraft.planner-call.v1.schema.json"));
		JsonObject example = readObject(CONTRACT_ROOT.resolve("airicraft.planner-call.v1.example.json"));
		Set<String> required = schema.getAsJsonArray("required").asList().stream()
			.map(element -> element.getAsString())
			.collect(Collectors.toSet());

		assertEquals(PlannerCallRecordV1.ASSET_SCHEMA, schema.get("$id").getAsString());
		assertEquals(required, example.keySet());
	}

	@Test
	void rejectsTimelineAnchorsThatMoveBackward() {
		var submitted = new PlannerCallRecordV1.Anchor("120");
		var completed = new PlannerCallRecordV1.Anchor("119");

		assertThrows(
			IllegalArgumentException.class,
			() -> new PlannerCallRecordV1.Timeline(submitted, completed, null)
		);
	}

	@Test
	void rejectsACompletedOutcomeWithFailureData() {
		assertThrows(
			IllegalArgumentException.class,
			() -> new PlannerCallRecordV1.Outcome(
				PlannerCallRecordV1.Status.COMPLETED,
				null,
				java.util.List.of(),
				new PlannerCallRecordV1.Usage(null, null, null),
				new PlannerCallRecordV1.Failure("PROVIDER_ERROR", "request failed")
			)
		);
	}

	private static JsonObject readObject(Path path) throws IOException {
		return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
	}
}
