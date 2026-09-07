package ai.moeru.airicraft.systemone;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

class DecisionTraceWriterTest {
	@Test void closesWithAnExplicitFooterAfterDrainingQueuedRows(@TempDir Path directory) throws Exception {
		var tasks = new ArrayList<Runnable>();
		Path path = directory.resolve("trace.gz");
		var writer = new DecisionTraceWriter(path, tasks::add);
		writer.accept(Map.of("type", "input", "tick", 1));
		writer.accept(Map.of("type", "input", "tick", 2));
		writer.close();
		assertFalse(writer.finished());
		tasks.getFirst().run();
		assertEquals(true, writer.status().get("complete"));
		try (var reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(path))))) {
			var lines = reader.lines().toList();
			assertEquals(3, lines.size());
			assertTrue(lines.getLast().contains("\"end\""));
		}
	}

	@Test void slowRecorderCannotBackpressureTicksOrSilentlyDropInputs(@TempDir Path directory) throws Exception {
		var tasks = new ArrayList<Runnable>();
		Path path = directory.resolve("trace.gz");
		var writer = new DecisionTraceWriter(path, tasks::add);
		for (int tick = 1; tick <= 100; tick++) writer.accept(Map.of("tick", tick));
		assertEquals("trace_queue_overflow", writer.status().get("failure"));
		assertEquals(32, writer.status().get("queuedRows"));
		writer.close(); tasks.getFirst().run();
		assertEquals(false, writer.status().get("complete"));
		try (var reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(path))))) {
			assertTrue(reader.lines().toList().getLast().contains("\"incomplete\""));
		}
	}

	@Test void storageFailureIsReportedWithoutThrowingIntoTheTick(@TempDir Path directory) throws Exception {
		var tasks = new ArrayList<Runnable>();
		Path occupied = directory.resolve("file"); Files.writeString(occupied, "occupied");
		var writer = new DecisionTraceWriter(occupied.resolve("trace.gz"), tasks::add);
		writer.accept(Map.of("tick", 1)); writer.close(); tasks.getFirst().run();
		assertTrue(writer.finished());
		assertEquals(false, writer.status().get("complete"));
		assertNotEquals("", writer.status().get("failure"));
	}
}
