---
concept: MemoryProvenance
status: stable
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/outcome/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/knowledge/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/memory/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/MemoryEvent.kt
  - ampere-core/src/commonMain/sqldelight/link/socket/ampere/db/memory/**
related: [PropelLoop, CognitionTrace, EventSerialBus, DreamCycle]
last_verified: 2026-10-10
---

# Memory Provenance

## What it is

AMPERE keeps two complementary, append-only memory stores:

- **`OutcomeMemoryRepository`** — *episodic memory*. Each `ExecutionOutcome`
  records what was attempted, by whom (`ExecutorId`), against which
  ticket and task, when it started/ended, and what the typed result was
  (`Success`, `Failure`, with structured payload variants:
  `CodeChanged`, `CodeReading`, `IssueManagement`, `GitOperation`,
  `NoChanges`).
- **`KnowledgeRepository`** — *semantic memory*. Each `Knowledge` entry is
  a distilled `(approach, learnings)` pair extracted from cognitive work.
  Sources are sealed: `FromIdea`, `FromOutcome`, `FromPerception`,
  `FromPlan`, `FromTask`. Entries carry tags, optional `taskType` and
  `complexityLevel`, and feed semantic search over past learnings.

Together they form blockchain-style memory cells: every entry is
timestamped, attributable, and indexed by `run_id` so a past Arc run can
be reconstructed deterministically by `ArcTraceProjection`.

## Why it exists

An animated agent without memory provenance has anterograde amnesia: it
recalls neither what it tried nor what it learned. The two-store split is
deliberate:

1. **Outcomes are raw, dense, costly to retrieve.** They answer
   *"what did this exact attempt do?"*. They are the substrate from which
   knowledge is extracted.
2. **Knowledge is distilled, sparse, semantically searchable.** It answers
   *"what tends to work for this kind of task?"*. The Recall phase of
   PROPEL queries Knowledge first; Outcomes are read for time-travel
   debugging, not for in-loop reasoning.

`run_id` provenance is what makes both stores trustworthy. Without it, an
outcome is an orphan — you can see what happened but not which cognitive
run produced it. With it, every outcome and knowledge entry can be replayed
through `ArcTraceProjection` into the full phase-by-phase narrative.

## Where it lives

- `agents/domain/outcome/Outcome.kt` — base sealed `Outcome` (`Success` / `Failure`).
- `agents/domain/outcome/ExecutionOutcome.kt` — tool-agnostic execution outcomes (variants: `CodeChanged`, `CodeReading`, `IssueManagement`, `GitOperation`, `NoChanges`).
- `agents/domain/outcome/OutcomeMemoryRepository.kt` + `OutcomeMemoryRepositoryImpl.kt` — episodic store.
- `agents/domain/outcome/StepOutcome.kt`, `TaskOutcome.kt`, `MeetingOutcome.kt` — outcome shapes for finer-grain steps and meetings.
- `agents/domain/knowledge/Knowledge.kt` — sealed knowledge sources.
- `agents/domain/knowledge/KnowledgeRepository.kt` + `KnowledgeRepositoryImpl.kt` — semantic store.
- `agents/domain/memory/AgentMemoryService.kt` — the recall facade; scores by similarity, tag overlap, task type, recency, complexity.
- `agents/domain/memory/MemoryTaskTypes.kt` — the `task_type` vocabulary shared by the write side and Recall (AMPR-402).
- `agents/domain/event/MemoryEvent.kt` — `KnowledgeStored` (`:56`), `KnowledgeRecalled` (`:115`), `MilestoneReached` (`:196`). There is no `OutcomeRecorded` event: an outcome write produces a row and no event, so the trace reads `OutcomeMemoryStore` by `run_id` rather than folding an event (`trace/ArcTraceProjection.kt:284-294`).
- `commonMain/sqldelight/link/socket/ampere/db/memory/OutcomeMemoryStore.sq`, `KnowledgeStore.sq` — schemas (each carries `run_id`).

## Invariants

- **Append-only.** Outcomes and knowledge entries are never updated in place. Corrections happen by inserting a new entry; the original stays for audit. A query that mutates a stored row is a violation.
- **Every entry carries `run_id`.** Outcomes and knowledge entries are written with the `run_id` of the Arc that produced them. An entry without a `run_id` is invisible to `ArcTraceProjection` and thus orphaned from the trace. **One production writer still passes the wrong id:** `AutonomousAgent.extractAndStoreKnowledge` stores with `runId = task.id.takeUnless { it.isBlank() }` (`agents/definition/AutonomousAgent.kt:496-527`, the id at `:522`) — a task id, not an Arc run id — so those entries are filed under a run that does not exist. `PulsePhase` passes the real one (AMPR-402). The column is correct; one of the two values is not.
- **Knowledge is distilled by a closing phase, not by tools.** Tool implementations write `ExecutionOutcome`s; `KnowledgeExtractor` (an agent's Learn phase) and `PulsePhase` (the Arc's close) produce `Knowledge`. Tools that write directly into `KnowledgeRepository` skip the cognitive distillation step and pollute the semantic store with raw observations.
- **The write side and Recall share one `task_type` vocabulary.** `recallRelevantKnowledge` finds candidates by matching `MemoryContext.taskType` against the stored `task_type` exactly, so a writer that invents its own string files an entry no reader asks for. The vocabulary is `MemoryTaskTypes`; both sides take their value from there.
- **A knowledge entry records one source, not a lineage.** Each row carries exactly one of `idea_id`, `outcome_id`, `perception_id`, `plan_id`, `task_id` — the id of the element it was distilled from — and no reference to a parent entry. Those elements have no tables of their own, and `OutcomeMemoryStore.id` is `generateUUID(ticketId, executorId)`, a different id space from `ExecutionOutcome.id`, so a source id resolves to no row at all. Entries relate to each other through `run_id`, never through source ids; `KnowledgeService.provenance` returns that single hop (AMPR-350).
- **A recall is a row of its run too.** `recallRelevantKnowledge(context, limit, runId)` stamps `runId` on both `MemoryEvent.KnowledgeRecalled` and its envelope (AMPR-386), so what a run recalled — and how relevant it scored — is on the run's trace beside what it stored. `Agent.recallRelevantKnowledge` defaults it to the agent's own run, so an in-loop Recall needs no argument.
- **Recall queries Knowledge first.** `AgentMemoryService.recallRelevantKnowledge` is the canonical Recall entry point. Domain code that goes straight to `OutcomeMemoryRepository` for in-loop reasoning is bypassing the semantic layer for performance reasons that don't exist. **One wiring caveat:** an Arc agent only gets a memory service when the runtime was built with a `KnowledgeRepository` — `ArcSession.create` supplies one from its `database` (`domain/arc/bridge/ArcSession.kt:631`, threaded at `domain/arc/ChargePhase.kt:141`) — and without it `createMemoryService` returns null (`agents/definition/SparkAgentFactory.kt:122-130`), so recall returns empty and Pulse's writes come back `stored = false`.
- **Outcome variants are tool-agnostic.** `ExecutionOutcome.CodeChanged` does not depend on which executor produced it; the same outcome shape is comparable across implementations. A new tool that needs a bespoke outcome variant must justify why an existing variant doesn't fit.
- **`Failure` outcomes are first-class learning signal.** They are stored, indexed, and recalled equally with `Success`. A change that filters failures out of recall (e.g., "only show successful approaches") loses the most valuable training signal.

## Common operations

- **Record an execution outcome** — your tool's `executionFunction` returns the typed `ExecutionOutcome` variant itself, and `ToolExecutionEngine` writes it through `OutcomeMemoryRepository.recordOutcome` on the way back to the caller (AMPR-406). There is nothing to call by hand: wire the store into the engine — `ReasoningSettings.outcomeRepository`, supplied by `SparkAgentFactory` / `AmpereRuntime` / `ArcSession` — and every outcome the engine returns is recorded against the request's ticket and run id. An engine with no store records nothing and behaves identically otherwise.
- **Record what a run itself came to** — an Arc run's close writes one outcome of its own, keyed by the run id in both the ticket and run columns, so `OutcomeService.forTicket(runId)` answers for a run. It is deliberately a pun: a run has a goal rather than a ticket, and the repository offers no read-by-run.
- **Extract knowledge** — LEARN phase, two routes. `AgentReasoning.evaluateOutcomes` runs the LEARN model call and, *only when the caller passes a `memoryService`*, stores every entry it returns (`agents/domain/reasoning/AgentReasoning.kt:203-229`). `AgentReasoning.extractKnowledge` is the deterministic route: `KnowledgeExtractor.extractDefault` formats one outcome into a `Knowledge.FromOutcome` and hands it back for the caller to store (`:237-242`). Either way `KnowledgeRepository.storeKnowledge` is what writes, and `KnowledgeStored` is emitted there. The Arc path has a third route, once per run rather than per outcome: `PulsePhase.captureLearnings` distils one entry per successful outcome and stores it *through the producing agent*, so `KnowledgeStored` names the holder rather than the runtime (`domain/arc/PulsePhase.kt:196-233`, AMPR-402). Read `Learning.stored` to see whether a write landed — a run whose agents have no memory service still returns the cells, unstored.
- **Emit a milestone** — milestone detection is separate from knowledge storage. `MilestoneTracker` listens for per-agent task lifecycle transitions and publishes `MilestoneReached` for first successful task types and recovery after failure; external systems use `AgentEventApi.reachMilestone(...)`. The tracker is started by `AgentEventApi` (`agents/events/api/AgentEventApi.kt:105`) and now actually fires: since AMPR-404 the task lifecycle has production publishers — `AutonomousWorkLoop.workIssue` opens a `TaskLifecycle` around each claimed issue and reports `completed` / `failed` in a `finally`, so a terminal event arrives even on a mid-issue stop (`agents/execution/AutonomousWorkLoop.kt:140-166`). `TaskLifecycle` is terminal exactly once, so the `finally` is a no-op when the work already reported. `reachMilestone` called directly still works.
- **Recall** — `AgentMemoryService.recallRelevantKnowledge(MemoryContext(...))` for in-loop reasoning. Returns scored entries.
- **Choose how relevant is relevant enough** — pass a `relevanceFloor` to the insight extractors (`ValidationInsights.fromKnowledge`, `PlanningInsights.fromKnowledge`). It defaults to `DEFAULT_RELEVANCE_FLOOR` (declared beside `KnowledgeWithScore`), compared strictly, so an entry scoring exactly the floor is excluded. The floor is caller policy, not a fact about a recalled entry: an agent summarising a hundred entries wants a high one, an agent working from three wants none. The default is representative, not calibrated — nothing has measured which floor produces better plans.
- **Time-travel a run** — `ArcTraceProjection.project(runId)` reads `EventStore`, `KnowledgeStore`, and `OutcomeMemoryStore` by `run_id` and rebuilds the per-phase trace.
- **Add a new outcome variant** — extend `ExecutionOutcome`, add a `Success`/`Failure` pair, update `OutcomeEvaluator`, add a CLI display handler.

## Anti-patterns

- **Updating an outcome in place after the fact.** "I'll just patch the error message." No — write a new outcome. The original is the audit trail.
- **Reaching for `OutcomeEvaluator` to build the `ExecutionOutcome` before storing it.** Its name and its place in Learn both suggest it, and this entry said so until AMPR-406 — but `OutcomeEvaluator.evaluate` takes `Outcome`s and returns learnings. Persisting through it would spend an LLM call to write a memory row.
- **Storing tool-specific shapes in `ExecutionOutcome`.** Once a variant carries a `KafkaPartition` or similar, cross-executor recall stops working. Keep variants tool-agnostic; put tool detail in nested response types.
- **Calling `KnowledgeRepository.storeKnowledge` from a tool.** Knowledge is the output of cognitive distillation, not a direct write target. Use `OutcomeMemoryRepository` from tools; let the Loop phase produce knowledge.
- **Using `KnowledgeStored` as a milestone flag.** Routine memory writes are high volume. Publish `MilestoneReached` as a sibling event when the semantic payload represents a meaningful checkpoint.
- **Recall by ticket id alone.** `MemoryContext` is built from task type, tags, and description for a reason. Ticket-id recall returns *only* this ticket's prior runs, missing the cross-ticket pattern recognition that's the point.
- **Filtering failures out of recall.** Failures teach what not to do. A "successful approaches only" filter erases that signal.
- **Hardcoding a relevance threshold at the call site.** `knowledge.filter { it.relevanceScore > 0.5 }` reads as arithmetic on a score, so it does not look like a policy decision — but it is one, and it was duplicated in `QualityParams` and `ProductParams` for months before AMPR-382 found it. A score is a fact the service computes; the floor above which a score counts belongs to whoever is reading. Take it as a parameter with `DEFAULT_RELEVANCE_FLOOR` as the default.
- **Reading a source id as a knowledge id.** `getKnowledgeById(entry.outcomeId)` can never match: a knowledge id is `generateUUID("knowledge-<type>", sourceId)`, which is a random prefix, and a source id names an `Idea`/`Outcome`/`Perception`/`Plan`/`Task`. The pre-AMPR-350 `provenance()` walked a "chain" this way and broke on its first iteration every time, returning a one-entry trail that looked plausible. To relate several entries, query by `run_id`.
- **Filing a learning under a task type nothing recalls with.** `storeKnowledge(knowledge, taskType = "arc-pulse")` persists a row, returns success, and is invisible forever: `FlowPhase` and `AutonomousAgent` recall with `code_change` or `generic`, and the match is exact. A store that succeeds is not the same as a store that can be read, which is why `MemoryTaskTypes` exists rather than three literals.
- **Stripping `run_id` when persisting.** A common refactoring trap: a helper drops the `run_id` parameter "because it's not used downstream". `ArcTraceProjection` is downstream. Keep it.
- **Passing a task id as the `runId`.** `AutonomousAgent.runtimeLoop` stamped `task.id` onto `storeKnowledge` until AMPR-386. `RunId` is a `String` typealias, so nothing complained; the entry simply filed itself under a run that never existed. Read the run from `Agent.currentRunId`.
