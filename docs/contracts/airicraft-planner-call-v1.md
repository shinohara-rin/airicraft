# Airicraft Planner Call V1

`airicraft.planner-call.v1` is the producer-owned schema for final Airicraft
planner model calls. The machine-readable contract is
[`airicraft.planner-call.v1.schema.json`](airicraft.planner-call.v1.schema.json).

## File contract

The Play extension asset is UTF-8 JSON Lines. Each non-empty line is one JSON
object that conforms to the schema. Records are final and are not updated after
publication.

The recorder Play manifest identifies the asset with these values:

- Role: `planner_calls`
- Media type: `application/x-ndjson`
- Schema: `airicraft.planner-call.v1`

`sequence` starts at `1` and is contiguous within one asset. `callId` is unique
within one Play. `turnId` groups retries and tool follow-up calls that belong
to the same planner turn. `plannerAttempt` identifies the generation, attempt,
and phase reported by the planner runtime. These coordinates can repeat for
sequential tool follow-up calls, so they are not a call identifier.

## Timeline contract

All timeline anchors use the global Server tick from the Minecraft integrated
server used by singleplayer evaluation. No recorder tick adapter or client to
server offset is part of this contract.

- `submitted` is sampled when the planner attempt is submitted.
- `completed` is sampled when the final response or failure returns to the
  planner runtime.
- `applied` is optional. It is sampled when the accepted result changes runtime
  state.

The order invariant is `submitted <= completed <= applied` when `applied` is
present. Unix times are diagnostic data only. They do not align the record with
the Play.

## Data contract

Values that can exceed JavaScript's safe integer range are decimal strings.
This includes sequence, generation, Server ticks, and Unix times.

`request.messages` and `request.tools` contain the canonical model request
context. They must not contain credentials or authorization headers.
`outcome.status` is `completed`, `failed`, or `cancelled`. `completed` means
that the planner runtime received and decoded a model response. It does not
mean that the runtime applied the response; only `timeline.applied` means that.
`failed` includes provider, timeout, and response parse failures. `cancelled`
means that the call was still pending when the planner or evaluation stopped.
Failed and cancelled records contain `outcome.failure`. A completed record does
not.
