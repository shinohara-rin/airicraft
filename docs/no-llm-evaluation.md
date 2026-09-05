# No-LLM evaluation

Run a structured objective through the existing deterministic action graph:

```bash
source .envrc
scripts/run-evaluation-scenarios --no-llm --scenario iron-pickaxe \
  --recorder-jar /absolute/path/to/recorder-profile.jar \
  --stop-client-after-scenario
```

Recording remains enabled by default. Use `--no-recorder` explicitly for a run
without the external Recorder Play; evaluator reports and flight logs still run.
Each scenario runs in an isolated client and restored world. The normal planner
mode remains the default when `--no-llm` is omitted.

For a manually launched evaluator, use
`scripts/eval run -Pairicraft.noLlm=true`. This process-wide setting persists
across runtime reloads. It cannot be combined with Codex-driver mode or toggled
back to a planner during the process lifetime.

## Scenario objective

Add an explicit goal alongside the existing prompt and independent checks:

```yaml
goal:
  kind: inventory_item
  itemId: minecraft:iron_pickaxe
  quantity: 1
```

Only `inventory_item` goals are currently supported. The evaluator submits the
goal once after world load. The executor owns decomposition and recovery. The
scenario prompt and checks are never translated into actions. Missing goals and
unsupported goal kinds fail explicitly; there is no fallback to an LLM or an
external driver. Existing checks determine success. Goal failure, cancellation,
or a request for planner intervention terminates the evaluation with evidence.
Elapsed budgets still apply; planner-turn budgets do not apply in this mode.

## Evidence and limitations

Reports identify `diagnostics.executionMode: no_llm` and `goalExecutionId`.
Agent status exposes `noLlmActive: true`, `codexDriverActive: false`, and
`llmAvailable: false`. Planner and vision use disconnected backends, the planner
is disabled, and no evaluator prompts or heartbeats are submitted. Compaction
cannot start while the planner is disabled. Planner journal records should be
empty; `plannerTurns: 0` alone is not sufficient evidence of no provider calls.

No-LLM mode enables Baritone `legitMine` and disables hidden diagonal vein
discovery. The existing mining illumination preflight remains in force. These
settings do not establish a complete perception contract: nearby world evidence
and illumination preflight still inspect loaded blocks, and automatic torch
placement starts disabled. A successful inventory check alone therefore does
not establish compliant visibility, lighting, or general survival competence.

The next design seam is between observed facts, game-specific priors (recipes,
fuel, tool requirements, likely resource locations), and generic execution
lifecycle. Keep world-specific knowledge in the Minecraft implementation; prove
cross-game portability with a second implementation before extracting a framework.

## First sanity check: 2026-09-05

The isolated `iron-pickaxe` run reached a stone pickaxe, then failed after 2,649
elapsed ticks when iron mining was rejected for `insufficient_illumination`
with zero torches. It made zero LLM calls; the planner journal and final LLM
flight record were empty. The harness completed successfully and finalized a
Recorder Play. The replay ZIP passed a CRC check; playback was not visually
reviewed.

The flight log identified the failed primitive, arguments, and lighting guard
without adding instrumentation. This establishes one useful diagnosis. The
remaining gameplay work is to express illumination as a Minecraft-specific
planning prerequisite and maintain it during execution, while keeping resource
location priors separate from observations of actual blocks.

Local evidence: `eval-output/20260905-191236-909930-34467/01-iron-pickaxe/diagnosis.md`.
