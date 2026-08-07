# Airicraft Evaluation

Airicraft evaluation measures agent behavior in isolated Minecraft scenarios. The evaluation harness collects scenario evidence and optional supporting evidence.

## Language

**Evaluation run**:
A set of one or more isolated scenario executions started by the evaluation harness.
_Avoid_: Batch, evaluation batch

**Scenario outcome**:
The evaluator decision about one scenario, independent of recorder success.
_Avoid_: Run result, recorder result

**Harness outcome**:
The orchestration decision for one scenario, including required supporting evidence.
_Avoid_: Scenario result

**Integrated-server capture**:
A recording of one evaluation player's connection from the singleplayer integrated server.
_Avoid_: Client-side recording, trajectory recording

**Recorder Play**:
A completed, replayable recorder artifact for one player connection. It is supporting evidence for a scenario outcome.
_Avoid_: Recorded result, trajectory

**Recorder-enabled run**:
An evaluation run that requires recorder preflight and one completed Recorder Play for each executed scenario.
_Avoid_: Optional recording

**Recorder-disabled run**:
An evaluation run that explicitly does not collect a Recorder Play.
_Avoid_: Missing recording
