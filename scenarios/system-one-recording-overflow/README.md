# Required decision-recording failure

This is a negative harness fixture. It holds the real decision writer worker until its bounded input queue overflows. The scenario should end promptly as `NEEDS_REVIEW`, with `Required decision recording failed: trace_queue_overflow`. Cleanup must release the motor and finalize the incomplete trace. The outer harness should report `DECISION_TRACE_INCOMPLETE` and exit nonzero, retaining the worker artifacts.

This expected failure is the assertion. It must not count as a passed gameplay scenario or a complete/replayable recording. The fixture tests failure detection and cleanup, not writer throughput under normal storage load.
