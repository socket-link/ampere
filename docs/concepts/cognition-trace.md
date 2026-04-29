---
concept: CognitionTrace
status: stable
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/trace/**
  - ampere-core/src/commonMain/sqldelight/link/socket/ampere/db/events/EventStore.sq
  - ampere-core/src/commonMain/sqldelight/link/socket/ampere/db/memory/KnowledgeStore.sq
  - ampere-core/src/commonMain/sqldelight/link/socket/ampere/db/memory/OutcomeMemoryStore.sq
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/ProviderCallStartedEvent.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/ProviderCallCompletedEvent.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/ToolEvent.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/MemoryEvent.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/RoutingEvent.kt
related: [PropelLoop, EventSerialBus, MemoryProvenance, CognitiveRelay, SparkSystem]
last_verified: 2026-04-29
---

# Cognition Trace

## What it is

The cognition trace is AMPERE's read model for reconstructing one Arc run.
`ArcTraceProjection.project(runId)` queries `EventStore`, `KnowledgeStore`,
and `OutcomeMemoryStore` by `run_id` and assembles an `ArcRunTrace`:

- One `PropelPhase` per phase (Perceive / Recall / Optimize / Plan / Execute / Learn / Run).
- `ModelInvocationTrace`s joining `ProviderCallStartedEvent` ↔ `ProviderCallCompletedEvent` with `routingReason`, latency, tokens, and estimated cost.
- `ToolCallTrace`s joining `ToolEvent.ToolExecutionStarted` ↔ `ToolExecutionCompleted` with duration and success.
- `MemoryWriteTrace`s for `KnowledgeStored` / `OutcomeRecorded`.
- `WattCost` per phase and per invocation, aggregated by `WattCostAggregator`.

This is the glass brain made queryable. It is a *read model* — the trace
is rebuilt from the underlying event and memory stores; it does not
mutate them.

## Why it exists

The PROPEL loop emits a lot of structured signal during a run.
`SOUL.md` says cognition should be visible — but raw event logs and
disconnected memory rows are not visibility. They are noise. The trace is
the projection that makes the run *legible*:

1. **Time-travel debugging.** "Why did the agent pick that approach in
   the planning phase yesterday?" The trace bucketed by phase makes the
   answer accessible without re-running anything.
2. **Provider-decision audit.** `routingReason` from `RoutingEvent` is
   threaded through to the model invocation, so each call shows *which
   rule selected this provider/model*. Routing without explanation is
   one of the failure modes the relay was built to prevent.
3. **Cost attribution.** `WattCost` accumulates input/output tokens,
   estimated USD, and a watts metric per phase. The aggregate per-Arc
   cost falls out of the projection rather than needing a parallel
   metering pipeline.

## Where it lives

- `ampere-core/src/commonMain/kotlin/link/socket/ampere/trace/ArcRunTrace.kt` — the data shapes (`ArcRunTrace`, `PropelPhase`, `ModelInvocationTrace`, `ToolCallTrace`, `MemoryWriteTrace`, `TraceEvent`, `WattCost`).
- `trace/ArcTraceProjection.kt` — the projection logic; reads stores, joins start/end events, buckets by phase.
- `trace/WattCostAggregator.kt` — per-invocation and per-phase cost rollup.
- `commonMain/sqldelight/link/socket/ampere/db/events/EventStore.sq` — event store with `run_id` indexes.
- `commonMain/sqldelight/link/socket/ampere/db/memory/KnowledgeStore.sq`, `OutcomeMemoryStore.sq` — memory stores with `run_id`.
- `agents/domain/event/ProviderCallStartedEvent.kt`, `ProviderCallCompletedEvent.kt` — model-invocation event pair.
- `agents/domain/event/ToolEvent.kt` — tool-call event pair (`ToolCallTrace` payload).
- `agents/domain/event/MemoryEvent.kt` — `KnowledgeStored`, `KnowledgeRecalled`, `OutcomeRecorded`.

## Invariants

- **`run_id` is non-optional on persisted events and memory rows.** `ArcTraceProjection` joins by `run_id`. A row without it is invisible.
- **The projection never writes.** It is a read model. A change that has the projection update an event row or a memory row is a layering violation; corrections happen by writing new rows.
- **Provider call events come in pairs.** `ProviderCallStartedEvent` ↔ `ProviderCallCompletedEvent` keyed by `(workflowId, agentId, providerId, modelId, cognitivePhase)`. A start without a completion appears as a half-trace; a completion without a start is reconstructed from `latencyMs` (lossy — keep both).
- **Tool events come in pairs by `invocationId`.** `ToolExecutionStarted` ↔ `ToolExecutionCompleted`. The projection retains starts that lack a completion as `pendingCalls` so in-flight work is visible.
- **Phase names are derived from the event, not assigned by the projector.** `phaseNameFor(event)` reads `cognitivePhase` (or, for spark events, the `Phase:` prefix). The projector does not invent phase membership — events declare it. New event types that should be phase-aware must populate `cognitivePhase` themselves.
- **`WattCost` is monotone-additive.** `WattCost.plus` only adds; entries are never subtracted. A change that subtracts cost (e.g., to "correct" a previous estimate) breaks the running aggregate.
- **The schema migration that introduced `run_id` is not reversible without losing trace fidelity.** If the migration is renumbered or dropped, every persisted run before the change becomes opaque.

## Common operations

- **Project one run** — `ArcTraceProjection.project(runId)`. Returns `Result<ArcRunTrace>`.
- **Show run cost** — `arcRunTrace.phases.sumOf { it.wattCost }` (using `WattCost.plus`). For per-phase cost, read `phase.wattCost` directly.
- **Add a new traced event** — give the event a `cognitivePhase` (where meaningful), persist it through the standard `EventLogger` chain, and (if needed) extend `phaseNameFor` and the relevant builder in `ArcTraceProjection`.
- **Tag a memory write with a phase** — `MemoryWriteTrace.phaseName` is set by the projector based on the event type. `KnowledgeStored` → `LEARN`, `OutcomeRecorded` → `EXECUTE`. Override only by emitting a different event type, not by post-hoc patching.

## Anti-patterns

- **Mutating an event row to "fix" the trace.** The trace is wrong because the event was wrong; emit a new event. Mutating loses audit value.
- **Adding fields to `PropelPhase` for non-phase-scoped data.** Phase-scoped means: belongs to exactly one phase of one run. Run-level data goes on `ArcRunTrace`; system-level data goes elsewhere entirely.
- **Bypassing `WattCostAggregator` to compute cost.** Multiple paths compute cost differently and the per-phase totals diverge. Use the aggregator; if it lacks a metric, extend it.
- **Persisting rows without `run_id`.** Even "metadata" rows: if it relates to a run, it carries the id. Otherwise the trace can't see it.
- **Treating the projection as a write-through cache.** It rebuilds from stores on each call. Caching is fine; lying about the source of truth is not.
- **Inventing phase names on the projector side.** `phaseNameFor` is intentionally a switch over event types — events own their phase tag. A "default to PLAN" fallback hides events that should have been tagged.
