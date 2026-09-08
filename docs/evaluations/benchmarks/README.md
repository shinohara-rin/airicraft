# Trace refresh encoding experiment

`TraceRefreshBenchmark.java` is an offline prototype, not a supported recording format. It reads a production recording fragment, reconstructs every terrain snapshot, and compares full cell updates with a representation that stores positions for timestamp-only refreshes. It asserts equality of reconstructed terrain on every observed turn. It copies the remaining observation and decision fields unchanged.

With Java 21, compile it against `build/classes/java/main` and Gson, then run `TraceRefreshBenchmark RECORDING.jsonl.gz`. Put compiled classes outside the repository. The Gson JAR comes from the normal Gradle dependency cache. Recorded results are in `../trace-refresh-prototype.json`.

Measurements include warmed Gson serialization and gzip into memory: three warmups followed by ten measurements with alternating variant order. They exclude disk writes, thread scheduling, startup pauses, and live rendering. The input is an incomplete 52-turn fragment, so this does not establish a successful goal or full decision replay. No experimental tape is emitted or used by the agent.
