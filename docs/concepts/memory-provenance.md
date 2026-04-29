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
last_verified: 2026-04-29
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
- `agents/domain/event/MemoryEvent.kt` — `KnowledgeStored`, `KnowledgeRecalled`, `OutcomeRecorded` events.
- `commonMain/sqldelight/link/socket/ampere/db/memory/OutcomeMemoryStore.sq`, `KnowledgeStore.sq` — schemas (each carries `run_id`).

## Invariants

- **Append-only.** Outcomes and knowledge entries are never updated in place. Corrections happen by inserting a new entry; the original stays for audit. A query that mutates a stored row is a violation.
- **Every entry carries `run_id`.** Outcomes and knowledge entries are written with the `run_id` of the Arc that produced them. An entry without a `run_id` is invisible to `ArcTraceProjection` and thus orphaned from the trace.
- **Knowledge is distilled by `KnowledgeExtractor`, not by tools.** Tool implementations write `ExecutionOutcome`s; the Loop phase's `KnowledgeExtractor` produces `Knowledge`. Tools that write directly into `KnowledgeRepository` skip the cognitive distillation step and pollute the semantic store with raw observations.
- **Recall queries Knowledge first.** `AgentMemoryService.recallRelevantKnowledge` is the canonical Recall entry point. Domain code that goes straight to `OutcomeMemoryRepository` for in-loop reasoning is bypassing the semantic layer for performance reasons that don't exist.
- **Outcome variants are tool-agnostic.** `ExecutionOutcome.CodeChanged` does not depend on which executor produced it; the same outcome shape is comparable across implementations. A new tool that needs a bespoke outcome variant must justify why an existing variant doesn't fit.
- **`Failure` outcomes are first-class learning signal.** They are stored, indexed, and recalled equally with `Success`. A change that filters failures out of recall (e.g., "only show successful approaches") loses the most valuable training signal.

## Common operations

- **Record an execution outcome** — your tool returns an `ExecutionResult`; `OutcomeEvaluator` wraps it into the appropriate `ExecutionOutcome` variant; `OutcomeMemoryRepository.recordOutcome` writes it. Don't bypass the evaluator.
- **Extract knowledge** — Loop phase: `KnowledgeExtractor` reads outcomes from the current run, distils into `Knowledge.FromOutcome` (or `FromPlan`, etc.), and `KnowledgeRepository.storeKnowledge` writes it. `KnowledgeStored` event emitted.
- **Recall** — `AgentMemoryService.recallRelevantKnowledge(MemoryContext(...))` for in-loop reasoning. Returns scored entries.
- **Time-travel a run** — `ArcTraceProjection.project(runId)` reads `EventStore`, `KnowledgeStore`, and `OutcomeMemoryStore` by `run_id` and rebuilds the per-phase trace.
- **Add a new outcome variant** — extend `ExecutionOutcome`, add a `Success`/`Failure` pair, update `OutcomeEvaluator`, add a CLI display handler.

## Anti-patterns

- **Updating an outcome in place after the fact.** "I'll just patch the error message." No — write a new outcome. The original is the audit trail.
- **Storing tool-specific shapes in `ExecutionOutcome`.** Once a variant carries a `KafkaPartition` or similar, cross-executor recall stops working. Keep variants tool-agnostic; put tool detail in nested response types.
- **Calling `KnowledgeRepository.storeKnowledge` from a tool.** Knowledge is the output of cognitive distillation, not a direct write target. Use `OutcomeMemoryRepository` from tools; let the Loop phase produce knowledge.
- **Recall by ticket id alone.** `MemoryContext` is built from task type, tags, and description for a reason. Ticket-id recall returns *only* this ticket's prior runs, missing the cross-ticket pattern recognition that's the point.
- **Filtering failures out of recall.** Failures teach what not to do. A "successful approaches only" filter erases that signal.
- **Stripping `run_id` when persisting.** A common refactoring trap: a helper drops the `run_id` parameter "because it's not used downstream". `ArcTraceProjection` is downstream. Keep it.
