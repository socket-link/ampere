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
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/ArcRunEvent.kt
related: [PropelLoop, EventSerialBus, MemoryProvenance, CognitiveRelay, SparkSystem]
last_verified: 2026-09-21
---

# Cognition Trace

## What it is

The cognition trace is AMPERE's read model for reconstructing one Arc run.
`ArcTraceProjection.project(runId)` queries `EventStore`, `KnowledgeStore`,
and `OutcomeMemoryStore` by `run_id` and assembles an `ArcRunTrace`:

- One `PropelPhase` per phase (Perceive / Recall / Observe / Plan / Execute / Learn) plus a synthetic `Run` row for the overall envelope. `CognitivePhaseEvent` phase boundaries are used directly when present.
- `ModelInvocationTrace`s joining `ProviderCallStartedEvent` ↔ `ProviderCallCompletedEvent` with `routingReason`, latency, tokens, and estimated cost.
- `ToolCallTrace`s joining `ToolEvent.ToolExecutionStarted` ↔ `ToolExecutionCompleted` with duration and success.
- `MemoryWriteTrace`s for `KnowledgeStored` / `OutcomeRecorded`, with `MilestoneReached` persisted as a queryable checkpoint event rather than a memory write row.
- `WattCost` per phase and per invocation, aggregated by `WattCostAggregator`.
- `completion` — for a run that ended without closing its loop (cancelled, or a phase threw), the `CompletionRecord` its `ArcRunEvent.CompletionManifestRecorded` carried: which phases ran and did not, the tick Flow reached, what each agent produced, and which intended goals did not happen (AMPR-359). `null` for a run that completed.

What the projection folds is a `ReplayWindow`, not a bare run id. Replay
walks recorded model calls by call index, and a call-index sequence needs an
end, so the window has to be bounded. In v1 the only window is
`ReplayWindow.ArcRun(runId)`: the run id is how a window is currently
identified, not what a window is. `ArcRunTrace.window`, `ArcRunHandle.window`,
the eval `Trace.window` and `PlaybackRelay.window` all expose it (AMPR-285).
A different window shape later (a turn window, say) is a new `ReplayWindow`
variant, and doesn't quietly change what `runId` means.

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
- `agents/domain/event/MemoryEvent.kt` — `KnowledgeStored`, `KnowledgeRecalled`, `MilestoneReached`, `OutcomeRecorded`.
- `agents/domain/event/ArcRunEvent.kt` — `CompletionManifestRecorded`, a cut-short run's manifest as a row of its own trace.
- `domain/arc/CompletionRecord.kt` — the bounded, persisted form of a `CompletionManifest`; `domain/arc/CompletionManifestSink.kt` — the production sink that writes it through `AgentEventApi`.

## Invariants

- **`run_id` is non-optional on persisted events and memory rows.** `ArcTraceProjection` joins by `run_id`. A row without it is invisible.
- **The projection never writes.** It is a read model. A change that has the projection update an event row or a memory row is a layering violation; corrections happen by writing new rows.
- **Provider call events come in pairs.** `ProviderCallStartedEvent` ↔ `ProviderCallCompletedEvent` keyed by `(workflowId, agentId, providerId, modelId, cognitivePhase)`. A start without a completion appears as a half-trace; a completion without a start is reconstructed from `latencyMs` (lossy — keep both). Since AMPR-240, `AmpereRuntime.execute(userGoal, runId)` threads the ambient Arc `runId` down through `ChargePhase` → `SparkAgentFactory` → `SparkBasedAgent` → `AgentReasoning`, so `workflowId` on this join key *is* the Arc `runId` for the lifetime of one run — this path is now production-proven (see `RunIdToTraceProjectionTest`), not just a theoretical join key with no real producer.
- **Tool events come in pairs by `invocationId`.** `ToolExecutionStarted` ↔ `ToolExecutionCompleted`. The projection retains starts that lack a completion as `pendingCalls` so in-flight work is visible.
- **Phase names are derived from the event, not assigned by the projector.** `phaseNameFor(event)` reads explicit phase fields (`CognitivePhaseEvent`, telemetry `cognitivePhase`, routing `phase`) or, for spark events, the `Phase:` prefix. The projector does not invent phase membership — events declare it. New event types that should be phase-aware must carry their own phase signal.
- **`WattCost` is monotone-additive.** `WattCost.plus` only adds; entries are never subtracted. A change that subtracts cost (e.g., to "correct" a previous estimate) breaks the running aggregate.
- **The schema migration that introduced `run_id` is not reversible without losing trace fidelity.** If the migration is renumbered or dropped, every persisted run before the change becomes opaque.
- **A cut-short run's manifest is run-level.** `CompletionManifestRecorded` folds into `ArcRunTrace.completion` and is bucketed into the synthetic `RUN` phase — never into the PROPEL phase that happened to be active when the run was cut short — and it does not move the projector's active phase. `completion` is matched on the record's own `runId`, not just on the rows the payload fallback drags in.
- **The manifest is written before the run is reported over.** `AmpereRuntime` hands it to its sink under `NonCancellable` before `execute` returns or rethrows, so a trace folded after `await`/`cancel` — or after the caller's scope was cancelled — already has it. A sink write that fails is counted (`CompletionManifestSink.failures`) and logged; it never changes the outcome.

## Common operations

- **Project one run** — `ArcTraceProjection.project(ReplayWindow.ArcRun(runId), arcId)`, or the `project(runId)` shorthand. Returns `Result<ArcRunTrace>`.
- **Show run cost** — `arcRunTrace.phases.sumOf { it.wattCost }` (using `WattCost.plus`). For per-phase cost, read `phase.wattCost` directly.
- **Add a new traced event** — give the event a `cognitivePhase` (where meaningful), persist it through the standard `EventLogger` chain, and (if needed) extend `phaseNameFor` and the relevant builder in `ArcTraceProjection`.
- **Read what a cut-short run left undone** — `project(runId).getOrThrow().completion`, or `ArcRunHandle.trace()?.completion` from a session built with a database. `unmetGoals == null` means the intended goals are unknown, never that none were missed.
- **Tag a memory write with a phase** — `MemoryWriteTrace.phaseName` is set by the projector based on the event type. `KnowledgeStored` → `LEARN`, `OutcomeRecorded` → `EXECUTE`. Override only by emitting a different event type, not by post-hoc patching.

## Anti-patterns

- **Mutating an event row to "fix" the trace.** The trace is wrong because the event was wrong; emit a new event. Mutating loses audit value.
- **Adding fields to `PropelPhase` for non-phase-scoped data.** Phase-scoped means: belongs to exactly one phase of one run. Run-level data goes on `ArcRunTrace`; system-level data goes elsewhere entirely.
- **Bypassing `WattCostAggregator` to compute cost.** Multiple paths compute cost differently and the per-phase totals diverge. Use the aggregator; if it lacks a metric, extend it.
- **Persisting rows without `run_id`.** Even "metadata" rows: if it relates to a run, it carries the id. Otherwise the trace can't see it.
- **Treating the projection as a write-through cache.** It rebuilds from stores on each call. Caching is fine; lying about the source of truth is not.
- **Persisting the whole `CompletionManifest`.** Its `producedOutcomes` hold full `Outcome` graphs — tasks, plans, tool results — and grow with every tick, on the teardown path. `CompletionRecord` is the bounded form; widen it deliberately in `CompletionRecord.of`, never by serializing the manifest.
- **Inventing phase names on the projector side.** `phaseNameFor` is intentionally a switch over event types — events own their phase tag. A "default to PLAN" fallback hides events that should have been tagged.
