---
concept: MemoryProvenance
status: stable
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/memory/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/outcome/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/knowledge/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/memory/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/MemoryEvent.kt
  - ampere-core/src/commonMain/sqldelight/link/socket/ampere/db/memory/**
  - ampere-core/src/commonMain/sqldelight/link/socket/ampere/db/10.sqm
  - ampere-core/src/commonMain/sqldelight/link/socket/ampere/db/15.sqm
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/api/internal/DefaultKnowledgeService.kt
related: [PropelLoop, CognitionTrace, EventSerialBus, DreamCycle, SubjectRegistry, DecideSeam]
last_verified: 2026-10-15
---

# Memory Provenance

## What it is

AMPERE keeps two complementary, append-only memory stores:

- **`OutcomeMemoryRepository`** — *episodic memory*. Each `ExecutionOutcome`
  records what was attempted, by whom — the agent that decided it (`actor_id`)
  and the `Executor` that performed it (`executor_id`), two values since
  AMPR-443 (F17) — against which
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

Since AMPR-417 (`10.sqm`) a knowledge row is also a stored **Belief** in the
Field's sense: it has a `holder` (the Subject whose belief it is, F9), a
`confidence` (a number, F10), a `sensitivity` (`PUBLIC | PRIVATE | PROTECTED`,
F12) and a `created_by_event` (the `KnowledgeStored` that recorded it, F11).
Provenance is a walk over events from that id, never over knowledge rows.

Neither of them is the only thing a run may recall from. **`RecallSource`**
(`memory/RecallSource.kt`, AMPR-394) is the consumer-implemented SPI for a
corpus Ampere does not own — the documents a person saved, scoped by tags —
answering `recall(intent, scopes, limit): List<Recalled>`. It is read-only
and nothing it returns is ever stored, which is what keeps it outside the
two stores above rather than a third one: there is no provenance to keep for
a row that was never written.

```kotlin
val myDocuments = RecallSource { intent, scopes, limit ->
    corpus.search(intent, scopes).take(limit).map { doc ->
        Recalled(id = doc.id, title = doc.title, text = doc.snippet, score = doc.score)
    }
}
```

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
- `agents/domain/outcome/ContentOrigin.kt` — `Own` / `External`: where a step result's text came from (AMPR-453). Every `StepOutcome` carries one, `External` by default and never written when it is, so a stored outcome without the key reads as outside content. It is a label on the result, not a provenance walk: it says *whether* the text came from outside the run, never from where. `ExecutionOutcome.NoChanges` and `Held` carry one as well — `PlanExecutor` stamps a plan's fold with it, `Own` only when every step was — and `Outcome.contentOrigin()` reads it off any outcome. A knowledge row stores none, so everything RECALL returns is rendered `[external]`: a learning distilled from a run's outcome can quote what that run's tools read.
- `agents/domain/knowledge/Knowledge.kt` — sealed knowledge sources.
- `agents/domain/knowledge/KnowledgeRepository.kt` + `KnowledgeRepositoryImpl.kt` — semantic store. Writes take `holder`, `confidence`, `sensitivity` and `createdByEvent`; every read takes a `viewer`. `KnowledgeEntry` carries the row's `runId`.
- `agents/domain/knowledge/KnowledgeBelief.kt` — `KnowledgeEntry.toBelief(subject)`: the row as the Field's `Belief` (AMPR-439). See [Field](field.md).
- `agents/domain/knowledge/BeliefConfidence.kt` — what the write side takes for the `confidence` column: `SelfReported(level)` (stored as the F10 mapping) or `Measured(probability)` (stored as given).
- `api/internal/DefaultKnowledgeService.kt` — the SDK's store (through the door, as the door's identity) and `provenance()`, the event walk; `api/model/KnowledgeProvenance.kt` is what it returns.
- `agents/domain/memory/AgentMemoryService.kt` — the recall facade; scores by similarity, tag overlap, task type, recency, complexity. `recallRelevantKnowledge` is the query plus its event; `scoreRecall` is the query alone, `internal` and for the one caller that publishes an event covering more than this (AMPR-394).
- `memory/RecallSource.kt` — `RecallSource` and `Recalled`: a corpus the consumer owns and Ampere only reads.
- `propel/RecalledContext.kt` — how a hosted run merges the two halves into one prompt section and one `KnowledgeRecalled`.
- `agents/domain/memory/MemoryTaskTypes.kt` — the `task_type` vocabulary shared by the write side and Recall (AMPR-402).
- `agents/domain/event/MemoryEvent.kt` — `KnowledgeStored` (`:67`), `KnowledgeRecalled` (`:128`), `MilestoneReached` (`:209`). There is no `OutcomeRecorded` event: an outcome write produces a row and no event, so the trace reads `OutcomeMemoryStore` by `run_id` rather than folding an event (`trace/ArcTraceProjection.kt:316-326`).
- `commonMain/sqldelight/link/socket/ampere/db/memory/OutcomeMemoryStore.sq`, `KnowledgeStore.sq` — schemas (each carries `run_id`). `OutcomeMemoryStore.sq` declares `actor_id` last, where `15.sqm`'s ADD COLUMN puts it. `KnowledgeStore.sq` declares the four belief columns last, where `10.sqm`'s ADD COLUMN puts them, and writes the viewer predicate into every Subject's read.

## Invariants

- **Append-only.** Outcomes and knowledge entries are never updated in place. Corrections happen by inserting a new entry; the original stays for audit. A query that mutates a stored row is a violation.
- **An outcome row names its actor and its executor apart.** `recordOutcome` takes both, and `actorId` is required (nullable, never defaulted) so every writer says which agent decided the work: `ToolExecutionEngine` writes the agent it dispatches for and the executor that ran the tool, a hosted run writes its host seat and `hosted-run`, and an Arc run's own row writes no actor and `arc:<name>`. Nothing writes an agent's id as an executor (AMPR-443, F17). `getOutcomesByActor` / `OutcomeService.byActor` / `ampere outcomes actor` read by the first; `getOutcomesByExecutor` / `byExecutor` / `outcomes executor` by the second. Rows older than `15.sqm` held the agent in `executor_id`; the migration copied it into `actor_id` (all but `arc:%` rows, which no agent decided) and left `executor_id` as written, since the executor that performed a legacy call was never recorded. That copy is a schema migration's one-time backfill, not a query — the append-only invariant below is about the store's own reads and writes.
- **Every entry carries `run_id`.** Outcomes and knowledge entries are written with the `run_id` of the Arc that produced them. An entry without a `run_id` is invisible to `ArcTraceProjection` and thus orphaned from the trace. **One production writer still passes the wrong id:** `AutonomousAgent.extractAndStoreKnowledge` stores with `runId = task.id.takeUnless { it.isBlank() }` (`agents/definition/AutonomousAgent.kt:496-527`, the id at `:522`) — a task id, not an Arc run id — so those entries are filed under a run that does not exist. `PulsePhase` passes the real one (AMPR-402). The column is correct; one of the two values is not.
- **Knowledge is distilled by a closing phase, not by tools.** Tool implementations write `ExecutionOutcome`s; `KnowledgeExtractor` (an agent's Learn phase) and `PulsePhase` (the Arc's close) produce `Knowledge`. Tools that write directly into `KnowledgeRepository` skip the cognitive distillation step and pollute the semantic store with raw observations.
- **The write side and Recall share one `task_type` vocabulary.** `recallRelevantKnowledge` finds candidates by matching `MemoryContext.taskType` against the stored `task_type` exactly, so a writer that invents its own string files an entry no reader asks for. The vocabulary is `MemoryTaskTypes`; both sides take their value from there.
- **A knowledge entry records one source, not a lineage.** Each row carries exactly one of `idea_id`, `outcome_id`, `perception_id`, `plan_id`, `task_id` — the id of the element it was distilled from — and no reference to a parent entry. Those elements have no tables of their own, and `OutcomeMemoryStore.id` is `generateUUID(ticketId, executorId)`, a different id space from `ExecutionOutcome.id`, so a source id resolves to no row at all. Entries relate to each other through `run_id`, never through source ids; `KnowledgeService.provenance` reports the source as one hop and never follows it (AMPR-350).
- **Provenance walks events.** A row's `created_by_event` is the id of the `KnowledgeStored` that recorded it, and that event's envelope carries the run and, in `caused_by`, what the write was reacting to (F11). `KnowledgeService.provenance` follows `created_by_event` and then each `caused_by` to an event with no cause; an id with no row stops the walk and is reported as `unresolvedEventId`. `AgentMemoryService` chooses the event id before the write, so the row and the envelope agree by construction; it writes the row first only because the repository picks the knowledge id the event names. Rows older than `10.sqm` name no event and have an empty chain.
- **Every row has a holder.** `AgentMemoryService` writes as its `agentId` — the same identity its door stamps as `eventSource` — so the holder needs no plumbing of its own (F9). Rows older than the column are the `system` pseudo-Subject's (the backfill is the column default). A write through the repository that names no holder is `system`'s too.
- **Every Subject's read names its viewer.** A viewer sees `PUBLIC` rows and the rows it holds; another Subject's `PRIVATE` and `PROTECTED` rows never come back. A `null` viewer is nobody and sees `PUBLIC` only. The predicate is in the SQL, ahead of the `LIMIT`, so a page is never short because rows the viewer cannot see took its slots. `AgentMemoryService` reads as its agent; the SDK's `KnowledgeService` reads as its door's identity.
- **A `PROTECTED` belief leaves no copy outside its row.** Replay reads (`findKnowledgeByRunId`, `getReplayableKnowledgeById`) exclude it, and the projection skips its `KnowledgeStored`. Its `KnowledgeStored` carries no `approach`, `learnings` or `tags`, and a `KnowledgeRecalled` never summarises it — even for the holder recalling its own — because the event log is what every replay and bake reads (F12). `PRIVATE` is holder-only for reads but stays in replays. Judge locality for `PROTECTED` (J11) is not enforced here.
- **A row reads as a Belief only with its event.** `toBelief` makes a row with a confidence `Belief.Settled` and one without `Belief.Unsettled`, keeps the row's holder and sensitivity, and takes its provenance from `created_by_event` and the row's timestamp. Its `kind` comes from `knowledge_type` through `KnowledgeType.beliefKind()`: `FROM_OUTCOME` is a `LESSON` (F21), and every other source is its namesake. The kind is decided at read time and no event carries it (AMPR-448). A row naming no event is `UnprovenancedKnowledgeException`, never a belief with a made-up provenance; the Field read counts such rows on `unmappedBeliefs` (AMPR-439). The table has no subject column, so what the belief is about is the caller's argument.
- **Confidence is a column, not prose.** `Knowledge.confidence` carries the level a model reported; the write stores its F10 mapping (`0.25 / 0.5 / 0.75`). A measured probability is passed as `BeliefConfidence.Measured` and stored as given. The column holds the number and not its source, so no band is ever fitted on it (F10 as amended).
- **A recall is a row of its run too.** `recallRelevantKnowledge(context, limit, runId)` stamps `runId` on both `MemoryEvent.KnowledgeRecalled` and its envelope (AMPR-386), so what a run recalled — and how relevant it scored — is on the run's trace beside what it stored. `Agent.recallRelevantKnowledge` defaults it to the agent's own run, so an in-loop Recall needs no argument.
- **One `KnowledgeRecalled` per RECALL, whatever it read.** A hosted run recalls from the seat's memory *and* from every `RecallSource` it was given, and publishes one event summarising both through the host seat's own door — so `resultsFound` is the phase's total and `retrievedKnowledge` holds both halves, corpus items typed `FROM_TASK` with the document as `sourceId` (AMPR-394). That is why `AgentMemoryService.scoreRecall` exists: the memory half is read without its own event, because two events for one phase would double-count it on the trace. The provenance is unchanged either way — the door is the seat's, so `eventSource` names the seat and the envelope carries the run.
- **A recalled corpus item is never written.** `Recalled` is not a `Knowledge`, and the `FROM_TASK` typing lives on the event's summary, not in `KnowledgeStore`. Copying a consumer's document into the semantic store would give it a second home that LEARN then keeps rewriting, and would file a document the agent did not learn anything from under a task type Recall matches on.
- **Recall queries Knowledge first.** `AgentMemoryService.recallRelevantKnowledge` is the canonical Recall entry point. Domain code that goes straight to `OutcomeMemoryRepository` for in-loop reasoning is bypassing the semantic layer for performance reasons that don't exist. **One wiring caveat:** an Arc agent only gets a memory service when the runtime was built with a `KnowledgeRepository` — `ArcSession.create` supplies one from its `database` (`domain/arc/bridge/ArcSession.kt:631`, threaded at `domain/arc/ChargePhase.kt:141`) — and without it `createMemoryService` returns null (`agents/definition/SparkAgentFactory.kt:122-130`), so recall returns empty and Pulse's writes come back `stored = false`.
- **Outcome variants are tool-agnostic.** `ExecutionOutcome.CodeChanged` does not depend on which executor produced it; the same outcome shape is comparable across implementations. A new tool that needs a bespoke outcome variant must justify why an existing variant doesn't fit.
- **`Failure` outcomes are first-class learning signal.** They are stored, indexed, and recalled equally with `Success`. A change that filters failures out of recall (e.g., "only show successful approaches") loses the most valuable training signal.

## Common operations

- **Record an execution outcome** — your tool's `executionFunction` returns the typed `ExecutionOutcome` variant itself, and `ToolExecutionEngine` writes it through `OutcomeMemoryRepository.recordOutcome` on the way back to the caller (AMPR-406). There is nothing to call by hand: wire the store into the engine — `ReasoningSettings.outcomeRepository`, supplied by `SparkAgentFactory` / `AmpereRuntime` / `ArcSession` — and every outcome the engine returns is recorded against the request's ticket and run id — bar a blank one. A tool that ran and had nothing to report left nothing to remember, and `ExecutionOutcome.Blank` carries placeholder timestamps (`Instant.DISTANT_PAST`) that would file the row at the year -100001 (AMPR-420); its call is still on the trace as a tool pair. A held one is not recorded either (AMPR-425): an action waiting on a person has not finished, and the row's `success` column can only say succeeded or failed. `OutcomeMemoryRepositoryImpl.recordOutcome` refuses an `ExecutionOutcome.Held` with a failed `Result` rather than filing it as a failure, and a hosted run that held writes no run outcome and stores no Knowledge. Telling a held run from a failed one in the store would need a migration, and is left to resuming (row H29). An engine with no store records nothing and behaves identically otherwise.
- **Record what a run itself came to** — an Arc run's close writes one outcome of its own, keyed by the run id in both the ticket and run columns, so `OutcomeService.forTicket(runId)` answers for a run. It is deliberately a pun: a run has a goal rather than a ticket, and the repository offers no read-by-run.
- **Extract knowledge** — LEARN phase, two routes. `AgentReasoning.evaluateOutcomes` runs the LEARN model call and, *only when the caller passes a `memoryService`*, stores every entry it returns (`agents/domain/reasoning/AgentReasoning.kt:203-229`). `AgentReasoning.extractKnowledge` is the deterministic route: `KnowledgeExtractor.extractDefault` formats one outcome into a `Knowledge.FromOutcome` and hands it back for the caller to store (`:237-242`). Either way `KnowledgeRepository.storeKnowledge` is what writes, and `KnowledgeStored` is emitted by the `AgentMemoryService` that called it. The Arc path has a third route, once per run rather than per outcome: `PulsePhase.captureLearnings` distils one entry per successful outcome and stores it *through the producing agent*, so `KnowledgeStored` names the holder rather than the runtime (`domain/arc/PulsePhase.kt:196-233`, AMPR-402). Read `Learning.stored` to see whether a write landed — a run whose agents have no memory service still returns the cells, unstored.
- **Emit a milestone** — milestone detection is separate from knowledge storage. `MilestoneTracker` listens for per-agent task lifecycle transitions and publishes `MilestoneReached` for first successful task types and recovery after failure; external systems use `AgentEventApi.reachMilestone(...)`. The tracker is started by `AgentEventApi` (`agents/events/api/AgentEventApi.kt:105`) and now actually fires: since AMPR-404 the task lifecycle has production publishers — `AutonomousWorkLoop.workIssue` opens a `TaskLifecycle` around each claimed issue and reports `completed` / `failed` in a `finally`, so a terminal event arrives even on a mid-issue stop (`agents/execution/AutonomousWorkLoop.kt:140-166`). `TaskLifecycle` is terminal exactly once, so the `finally` is a no-op when the work already reported. `reachMilestone` called directly still works.
- **Recall** — `AgentMemoryService.recallRelevantKnowledge(MemoryContext(...))` for in-loop reasoning. Returns scored entries, and publishes the `KnowledgeRecalled` that records the query.
- **Recall from a consumer's corpus as well** — wire the sources onto the run (`RunPolicy(recall = RecallPolicy(sources = …))`) and declare what to look under on the host seat (`HostedAgent.recallScopes`). RECALL asks each source once for `perSourceLimit` items, merges them with the memory half, and renders both into every seat's prompt as "# Recalled context" — clipped to `titleClip`/`textClip`, one line an item. Scopes are load-bearing: with none declared the sources are not asked at all, because an unscoped corpus query is a request for the whole corpus. See [HostedRun](hosted-run.md).
- **Recall with something other than the goal** — the query and the scope are separate inputs, and the goal is only their default (AMPR-457). `RecallPolicy.query` replaces the goal's description as both the memory half's `MemoryContext.description` and every source's `intent`; empty searches nothing, so memory is recalled by task type and tags alone and no source is asked. `RecallPolicy.taskType` replaces the goal's bucket as `MemoryContext.taskType`. The run's one `KnowledgeRecalled` carries the context RECALL ran with, overrides applied.
- **Get what was recalled in front of the model** — it is not enough to recall it. Until AMPR-394 the only prompt that saw a recalled entry was `PlanGenerator`'s (AMPR-388), so a tool's parameter call or a reasoning step in the same run was answered by an agent that had forgotten it. A hosted run now puts the section on `AutonomousAgent.recalledContext`, which `currentSystemPrompt` renders ahead of the spark stack — so every model call after RECALL carries it. Nothing on the Arc path or in `runtimeLoop` sets it yet.
- **Choose how relevant is relevant enough** — pass a `relevanceFloor` to the insight extractors (`ValidationInsights.fromKnowledge`, `PlanningInsights.fromKnowledge`). It defaults to `DEFAULT_RELEVANCE_FLOOR` (declared beside `KnowledgeWithScore`), compared strictly, so an entry scoring exactly the floor is excluded. The floor is caller policy, not a fact about a recalled entry: an agent summarising a hundred entries wants a high one, an agent working from three wants none. The default is representative, not calibrated — nothing has measured which floor produces better plans.
- **Store a belief about a real person** — `memory.storeKnowledge(knowledge, sensitivity = Sensitivity.PROTECTED)`, or `ampere.knowledge.store(…, sensitivity = PROTECTED)` from the SDK. Pass `causedBy` with the event the write is reacting to, so the provenance walk reaches it.
- **Read every entry a Subject may see** — `findKnowledgeVisibleTo(viewer, limit)`, newest first: the Field read's belief half. It has an interface default that fails, so a repository that predates it still compiles and a read through it is never mistaken for an empty store.
- **Trace where an entry came from** — `ampere.knowledge.provenance(id)`: `events` is the producing `KnowledgeStored` and its `caused_by` ancestors, in that order; `sourceType`/`sourceId` is the separate one-hop source fact.
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
- **Reading a source id as a knowledge id.** `getKnowledgeById(entry.outcomeId)` can never match: a knowledge id is `generateUUID("knowledge-<type>", sourceId)`, which is a random prefix, and a source id names an `Idea`/`Outcome`/`Perception`/`Plan`/`Task`. The pre-AMPR-350 `provenance()` walked a "chain" this way and broke on its first iteration every time, returning a one-entry trail that looked plausible. To relate several entries, query by `run_id`; to find what produced one, walk its events (AMPR-417).
- **Writing a confidence into the prose.** `appendLine("Confidence: high")` in `learnings` (or `Idea.description`) is how confidence lived until AMPR-417, and it could be neither queried nor calibrated (C15). Set `Knowledge.confidence`, or pass a `BeliefConfidence`.
- **Remapping a measured confidence.** `asProbability()` is for a self-reported level. A probability a decision model returned is already a number; `BeliefConfidence.Measured` stores it as is, and running it through the F10 table would turn a measurement into a self-report.
- **Naming `BeliefConfidence`'s number `value`.** It shipped that way in AMPR-417, first on an interface and then on the sealed class, and both renamed `StateFlow.value` in the Objective-C header and broke the iOS jobs: the exporter lets same-named properties of different types coexist only when one is declared in a *final* class, and a sealed class is not final. The property is `probability`.
- **Filtering a recall by sensitivity after the query.** `findKnowledgeByTaskType(…, limit = 10).filter { it.sensitivity == PUBLIC }` returns fewer than ten while visible rows exist, and is one forgotten call site away from a leak. Pass the `viewer`; the store applies it before the limit.
- **Putting a belief's content on an event "for the trace".** A `PROTECTED` row's content on any event — `KnowledgeStored`, a recall summary, a custom event — is in every replay and bake of the log. The row is the only place it lives.
- **Filing a learning under a task type nothing recalls with.** `storeKnowledge(knowledge, taskType = "arc-pulse")` persists a row, returns success, and is invisible forever: `FlowPhase` and `AutonomousAgent` recall with `code_change` or `generic`, and the match is exact. A hosted run recalls under a consumer's own string only when its `RecallPolicy.taskType` names it (AMPR-457) — and its LEARN still writes the goal's bucket, so the consumer files its own lessons under that string itself. A store that succeeds is not the same as a store that can be read, which is why `MemoryTaskTypes` exists rather than three literals.
- **Reaching for `scoreRecall` because the event is noise.** It is `internal` and it is not the quiet version of recall: a recall that leaves no `KnowledgeRecalled` behind is a recall `ArcTraceProjection` cannot show, and "Recall precedes Plan" then has no evidence either way. The only legitimate caller is one that publishes an event covering strictly more than the memory half.
- **Reading a recall of nothing as proof that nothing is stored.** Each of `scoreCandidates`' four strategies drops its own failure — the full-text strategy throws on punctuation it does not escape (AMPR-280) — so an empty recall can mean an empty store, a mismatched `task_type`, or a query the index refused. Check the store directly before concluding the write side is broken.
- **Passing the agent's id as `executorId`.** `ExecutorId` and `AgentId` are both `String` typealiases, so nothing complains — and until AMPR-443 the live path did exactly this, which left `executor_id` meaning the agent on every row and `outcomes executor` answering a question about agents. The agent is the `actorId`; the executor is an `Executor.id`, or `ExecutionContext.NO_EXECUTOR_ID` when none is bound.
- **Stripping `run_id` when persisting.** A common refactoring trap: a helper drops the `run_id` parameter "because it's not used downstream". `ArcTraceProjection` is downstream. Keep it.
- **Passing a task id as the `runId`.** `AutonomousAgent.runtimeLoop` stamped `task.id` onto `storeKnowledge` until AMPR-386. `RunId` is a `String` typealias, so nothing complained; the entry simply filed itself under a run that never existed. Read the run from `Agent.currentRunId`.
