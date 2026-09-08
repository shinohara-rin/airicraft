package ai.moeru.airicraft.systemone;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedWriter;
import java.io.BufferedOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

/** Bounded, nonblocking capture. Losing inputs makes the artifact explicitly incomplete. */
public final class DecisionTraceWriter {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(32);
	private final AtomicReference<String> failure = new AtomicReference<>();
	private final CompletableFuture<Void> completion = new CompletableFuture<>();
	private volatile boolean closing;
	private volatile long written;

	public DecisionTraceWriter(Path path) {
		this(path, task -> Thread.ofPlatform().name("system-one-recorder").daemon(true).start(task));
	}
	/** The supplied owner schedules the I/O worker; accepting inputs never waits for that worker. */
	public DecisionTraceWriter(Path path, java.util.function.Consumer<Runnable> startWorker) { startWorker.accept(() -> write(path)); }
	public java.util.Optional<String> failure() { return java.util.Optional.ofNullable(failure.get()); }
	public void accept(Object row) {
		if (closing) { failure.compareAndSet(null, "input_after_close"); return; }
		if (failure.get() == null && !queue.offer(row)) failure.compareAndSet(null, "trace_queue_overflow");
	}
	public void close() { closing = true; }
	public boolean finished() { return completion.isDone(); }
	public Map<String, Object> status() {
		return Map.of("finished", finished(), "complete", closing && finished() && failure.get() == null,
			"failure", failure.get() == null ? "" : failure.get(), "writtenRows", written, "queuedRows", queue.size());
	}
	private void write(Path path) {
		try {
			Files.createDirectories(path.toAbsolutePath().getParent());
			// GZIP emits small compressed chunks; coalesce them before writing to external storage.
			try (var writer = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(path), 64 * 1024)), StandardCharsets.UTF_8))) {
				while (!closing || !queue.isEmpty()) {
					Object row = queue.poll(100, TimeUnit.MILLISECONDS);
					if (row != null) { writer.write(GSON.toJson(row)); writer.newLine(); written++; }
				}
				if (written == 0) failure.compareAndSet(null, "no_recorded_inputs");
				writer.write(GSON.toJson(failure.get() == null ? Map.of("type", "end", "rows", written)
					: Map.of("type", "incomplete", "rows", written, "reason", failure.get())));
				writer.newLine();
			}
		}
		catch (Exception exception) {
			failure.compareAndSet(null, exception.getClass().getSimpleName() + ":" + exception.getMessage());
		}
		finally { completion.complete(null); }
	}
}
