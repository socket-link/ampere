---
concept: PropelLoop
status: stable
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/reasoning/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/cognition/sparks/PhaseSpark.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/cognition/sparks/PhaseSparkManager.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/trace/ArcRunTrace.kt
  - docs/AGENT_LIFECYCLE.md
related: [CognitiveRelay, MemoryProvenance, SparkSystem, CognitionTrace, EventSerialBus]
last_verified: 2026-04-29
---

# PROPEL Loop

## What it is

PROPEL is AMPERE's autonomous cognitive cycle: **Perceive → Recall → Optimize → Plan → Execute → Loop**.
Every animated agent runs this loop continuously. Each phase is a discrete
cognitive step that emits structured events, writes typed memory cells, and
hands a refined context object to the next phase. The phases collectively
form one *Arc run* (`ArcRunId`), which is the unit of observability the rest
of the system reports against.

The loop is not a hot path through a single function — it is a *contract*
between independent reasoning services (`PerceptionEvaluator`,
`PlanGenerator`, `PlanExecutor`, `OutcomeEvaluator`, `KnowledgeExtractor`)
composed by `AgentReasoning`. Each service owns one phase's transformation.

## Why it exists

The loop is the load-bearing answer to *"how does an animated agent decide
what to do next?"*. Three forces shaped it:

1. **Recall before action.** LLMs are notoriously bad at remembering what
   *this very system* has already tried. PROPEL forces a Recall step
   before any planning, so prior `ExecutionOutcome`s and `Knowledge`
   entries are surfaced into the prompt, not silently re-discovered.
2. **Optimization is a distinct step.** The leap from "I have ideas and
   memories" to "I have a chosen approach" is the place where past
   learnings get *applied*. Folding it into Plan made the planning prompt
   too crowded and the recalled-knowledge field went unused.
3. **Loop closes via Knowledge.** Without the explicit closing phase that
   stores knowledge, the loop is open-loop and the agent never improves.
   The "Loop / Learn" phase is where the autocatalytic property lives.

The phase boundaries exist because of cognitive load on the LLM, not on the
runtime. Each phase has a focused prompt (optionally narrowed further by a
[`PhaseSpark`](spark-system.md)), a clear input/output contract, and a
single point at which we emit telemetry.

## Where it lives

- `ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/reasoning/AgentReasoning.kt` — the facade composing all phase services.
- `agents/domain/reasoning/PerceptionEvaluator.kt` — Perceive: distills `AgentState` into `Idea`s.
- `agents/domain/reasoning/PlanGenerator.kt` — Optimize + Plan: combines ideas, recalled `Knowledge`, and the ticket into a `Plan`.
- `agents/domain/reasoning/PlanExecutor.kt` — Execute: dispatches each `Task` through `ToolExecutionEngine`.
- `agents/domain/reasoning/OutcomeEvaluator.kt` — first half of Loop: turns raw tool returns into typed `ExecutionOutcome`s.
- `agents/domain/reasoning/KnowledgeExtractor.kt` — second half of Loop: distils outcomes into `Knowledge`.
- `agents/domain/cognition/sparks/PhaseSpark.kt` + `PhaseSparkManager.kt` — phase-aware prompt augmentation (`PERCEIVE | PLAN | EXECUTE | LEARN`).
- `trace/ArcRunTrace.kt` — `PropelPhase` is the telemetry record per phase.
- `docs/AGENT_LIFECYCLE.md` — the human-readable narrative.

## Invariants

- **Recall precedes Plan.** No `Plan` may be generated without first calling `AgentMemoryService.recallRelevantKnowledge` and feeding the result into `PlanGenerator`. Skipping Recall when context "feels obvious" is the canonical failure mode.
- **Each phase emits its own boundary events.** `ProviderCallStartedEvent` / `ProviderCallCompletedEvent` carry a `cognitivePhase`; memory writes carry the phase that produced them; tool calls are tagged via the active phase. `ArcTraceProjection` relies on this to bucket activity per phase. A phase that runs without emitting boundary events is invisible to the trace, which is equivalent to it not having run.
- **The loop closes through `Knowledge`.** Every successful Arc run ends with `KnowledgeExtractor` writing at least one `Knowledge` entry tagged with the `run_id`. An Arc run that produced outcomes but no Knowledge entry has not closed the loop and will not contribute to future Recall.
- **Phase order is fixed.** Perceive → Recall → Optimize → Plan → Execute → Loop. New phases are added by extending the enum and updating every service that switches on it; phases are never reordered or skipped per call site.
- **Optimize is not a side-effect of Plan.** When recalled `Knowledge` contradicts an `Idea`, that contradiction must be resolved in Optimize and recorded in the Plan's rationale, not silently dropped during Plan generation.

## Common operations

- **Add a phase** — extend `CognitivePhase` (in `PhaseSpark.kt`), add a corresponding `PhaseSpark` data object with prompt contribution, update `PhaseSpark.forPhase`, add a service implementing the phase transform, wire it through `AgentReasoning`, and update `ArcTraceProjection.phaseNameFor` so the trace knows about the new phase.
- **Hook into a phase** — subscribe to `SparkAppliedEvent` filtering on `phaseSparkName()`; this tells you exactly when the agent enters/exits a phase. Do not assume `PhaseSpark`s are always enabled — they're gated on `AgentConfiguration.cognitiveConfig.phaseSparks.enabled` or `AMPERE_PHASE_SPARKS=true`.
- **Read what one Arc run did** — `ArcTraceProjection.project(runId)` returns an `ArcRunTrace` with one `PropelPhase` per phase. This is the "playback" of a cognitive cycle. Use this for debugging, not for orchestration.
- **Validate a change to the loop** — run `./gradlew jvmTest` (the primary gate) and verify the loop still produces a `Knowledge` entry tagged with the run id.

## Anti-patterns

- **Short-circuiting Recall when the context "feels obvious"** — the whole point of Recall is to override the agent's confidence with prior outcomes. Plans that look obvious are exactly the ones where past failures live.
- **Treating Evaluate / Loop as bookkeeping** — the closing phase is where the system *learns*. If your change records outcomes but doesn't extract `Knowledge`, you've made the loop open-loop.
- **Inlining a "quick LLM call" outside a phase** — every model invocation should be wrapped in a `ProviderCallStartedEvent` / `ProviderCallCompletedEvent` pair tagged with `cognitivePhase`. Calls outside this contract don't appear in `ArcRunTrace` and break the glass-brain guarantee.
- **Folding Optimize into Plan** — recalled `Knowledge` becomes a passive context dump rather than an explicit selection between alternatives. The Plan emerges with no record of *why this approach over the others*.
- **Holding state across runs in the agent object** — agents are animated, not stateful in memory. Cross-run state belongs in `OutcomeMemoryRepository` / `KnowledgeRepository`, keyed by ids retrievable in Recall. Anything else is invisible to the trace and fragile across restarts.
