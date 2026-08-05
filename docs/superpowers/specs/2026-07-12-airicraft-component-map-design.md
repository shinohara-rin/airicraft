# Airicraft Component Map Design

## Purpose

Create a durable codebase map that makes Airicraft's major components, roles,
and runtime relationships understandable without exposing the repository's 582
files as individual graph nodes.

The map documents the current implementation. It does not prescribe a target
architecture or propose refactors.

## Deliverables

- `docs/architecture/airicraft-component-map.dot`: editable Graphviz source.
- `docs/architecture/airicraft-component-map.svg`: rendered diagram.
- `docs/architecture/airicraft-component-map.md`: concise component index with
  roles, boundaries, and links to primary source entrypoints.

## Diagram Structure

Use one directed Graphviz diagram with clusters for these component families:

1. External control: wrapper CLI and localhost HTTP bridge.
2. Client lifecycle: Fabric initialization and runtime controller.
3. Agent cognition: session, dialogue, planner/LLM, semantic context, and events.
4. Execution: goals, tasks, action graph, primitive dispatch, Baritone, movement,
   and camera control.
5. World interfaces: screenshots, world/inventory perception, chat, and social
   state.
6. Support: configuration, debugging, flight recording, and OpenTelemetry.
7. Addons: evaluator, JourneyMap compatibility, and REI compatibility.
8. Data-driven assets: evaluation scenarios.

Keep the diagram near 15 to 20 nodes. Combine packages that participate in one
coherent responsibility instead of representing every package or class.

## Node and Edge Semantics

Each node contains:

- a short component name;
- a one-line role description;
- primary package or class anchors where space permits.

Relationships use labeled directed edges:

- solid arrows for construction, ownership, control, requests, or command flow;
- dashed arrows for observations, feedback, instrumentation, or optional
  extension;
- cluster placement and restrained color distinguish component families without
  making color the only carrier of meaning.

Important loops, especially planner intent to execution and execution feedback
to planner context, must remain visible. Crossing edges should be minimized even
when that requires placing a supporting component close to its main consumer.

## Markdown Component Index

The companion Markdown document provides information that would make the graph
too dense:

- component responsibility;
- what invokes or owns it;
- its principal dependencies and consumers;
- primary source entrypoints;
- important runtime or thread boundary, when applicable.

The index follows the same cluster order as the diagram so readers can move
between them directly.

## Evidence Rules

- Derive components and relationships from current constructors, imports,
  lifecycle callbacks, bridge routes, and addon registration code.
- Treat existing documentation as supporting context, not proof of current
  wiring.
- Show evaluator and optional-mod integrations as first-class components.
- Distinguish actual current behavior from desired planner/action-graph
  ownership boundaries.
- Do not include generated output, runtime logs, Gradle caches, or evaluation
  result directories.

## Verification

1. Render the DOT source with Graphviz and require a successful exit.
2. Inspect the rendered output for clipped labels, unreadable text, excessive
   edge crossings, and ambiguous arrow direction.
3. Check every linked source entrypoint in the Markdown index exists.
4. Compare the final component relationships against the main runtime
   construction path and addon entrypoints.
5. Keep the generated SVG reproducible from the committed DOT source.

## Non-Goals

- A per-file or per-class dependency graph.
- A replacement for the UA knowledge graph.
- Refactoring recommendations or a target-state architecture.
- Exhaustive method-level call traces.
