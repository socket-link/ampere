---
concept: Field
status: experimental
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/field/Field.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/field/Action.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/field/Belief.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/api/service/FieldService.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/api/internal/DefaultFieldService.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/knowledge/KnowledgeBelief.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/observe/ObservationStore.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/observe/ObservationBudget.kt
  - ampere-core/src/commonMain/sqldelight/link/socket/ampere/db/field/ObservationStore.sq
  - ampere-core/src/commonMain/sqldelight/link/socket/ampere/db/field/ObservationStorePruning.sq
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/field/Commitment.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/commitment/CommitmentStore.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/commitment/CommitmentLedger.kt
  - ampere-core/src/commonMain/sqldelight/link/socket/ampere/db/field/CommitmentStore.sq
related: [SubjectRegistry, GoalBranch, MemoryProvenance, DomainCanon, EventSerialBus]
last_verified: 2026-10-17
---

# Field

## What it is

Ampere's world state read as one value (AMPR-439, F-register row F6): `FieldService.snapshot(viewer)`
returns a `field.Field` — every registered Subject, every Relationship touching one, each
Subject's Goals with their state, the Beliefs the viewer may see (each with its `BeliefKind`),
the Observations the viewer may see (the Environment branch, AMPR-447), every open Commitment
(AMPR-449), and the newest taken Actions (AMPR-448) — read from the stores the W2 doors and the
ticket publishers write, with the log's newest sequence (`asOfSequence`) as its anchor. The
Actions are the one branch folded from the log instead: each persisted
`ToolExecutionStarted` / `ToolExecutionCompleted` pair reads as an `Action` in `TAKEN` state. It is exposed on the
instance as `AmpereInstance.field`, which is `@AmpereExperimentalApi`.

## Why it exists

W2 shipped the Field's primitives (`field/`, AMPR-415) and a store per branch — `SubjectStore` and
`RelationshipStore` (AMPR-416), belief columns on `KnowledgeStore` (AMPR-417), `GoalStore`
(AMPR-419) — and nothing read them back together. A reader wanting "the world as this agent sees
it" had five tables, three row types and a visibility rule to re-implement, and the visibility
rule is the one that leaks when re-implemented (F12). Replaying the log was never an option: the
event store prunes under `EventStoreBudget`, and `CanonPerceivedEvent` carries ids, not entities.

That last fact is why the Environment branch has a store of its own (AMPR-447, AMPR-328 Q7): an
Observation's content cannot be rebuilt from its event, so it is stored, and it is stored in
`ObservationStore` rather than `KnowledgeStore` so a perceived entity never surfaces in recall.

Commitments (AMPR-449, F31) are the first branch formed by work assignment rather than by a W2
door. A ticket's `dueDate` was stored and never read, and nothing recorded who had undertaken to
meet it. A `TicketAssigned` on a ticket with a due date now forms `Commitment(committer =
assignee, ticket, due)`; a `TicketStatusChanged` to Done, or a reassignment, resolves it. That
history is what F32 (coordination state, Trust) will read; F32 is deferred to W5.

Actions and Lessons (AMPR-448, F20 and F21) are retypes, and the ruling on AMPR-328 question Q5
(2026-10-11) is that **the retypes are fold-side: no event class carries them**. F20 said the tool
events "are `ActionTaken` / `ActionFailed`" and F21 that Pulse's learnings become
`BeliefFormed(Lesson)`. Neither event exists. The tool-event pair already names the deciding
agent (its source), the performer (`executorId`, AMPR-443), the run and the verdict, so the Field
reads the pair as a taken Action. A `KnowledgeStored` of a `Knowledge.FromOutcome` row already is
a lesson, so the Field reads it as a Belief of kind `LESSON`. This keeps F2's "zero changes to the
concrete event classes".

## Where it lives

- `field/Field.kt` — `Field`, `Field.HeldGoal`, `Field.GoalState`. Data only, inside the leaf.
- `field/Belief.kt` — `Belief` and `BeliefKind { LESSON, IDEA, PERCEPTION, PLAN, TASK,
  OBSERVATION }`; `kind` is required, with no default.
- `field/Action.kt` — `Action` with nullable `reversibility` / `cost` (`null` = unknown) and
  `result: ActionResult?` (`Succeeded` | `Failed(reason?)`), which only a `TAKEN` Action may have.
- `field/Commitment.kt` — `Commitment` and `Commitment.Status { OPEN, RESOLVED }`. Data only, inside
  the leaf; `ticketId` is a `String` because `TicketId` lives outside it.
- `commitment/CommitmentStore.kt` — `CommitmentStore` and `SqlDelightCommitmentStore` over
  `CommitmentStore.sq` (`13.sqm`); a row's identity is its forming `TicketAssigned` event id.
- `commitment/CommitmentLedger.kt` — the fold: `assigned(event, recordedAt, ticket)` and
  `statusChanged(event, recordedAt)`. One per environment (`EnvironmentService.commitmentLedger`),
  shared by `TicketOrchestrator` and every `TicketActionService` built off it.
- `api/service/FieldService.kt` — the public read: `snapshot(viewer, beliefLimit)`.
- `api/internal/DefaultFieldService.kt` — the read over `SubjectStore`, `RelationshipStore`,
  `GoalStore`, `KnowledgeRepository`, `ObservationStore`, `EventRepository` and `CommitmentStore`;
  `DefaultFieldService.over(database, …)` builds every SQLDelight store from the one database.
  `takenActions` is the tool-event fold; `actionLimit` (default 100) bounds it.
- `observe/ObservationStore.kt` — `ObservationStore` / `SqlDelightObservationStore`: the
  Environment branch's rows. A non-leaf package, because it imports the database.
  `db/field/ObservationStore.sq`, created by `12.sqm`. Written by
  `RecordedPerception.store(holder, store)` (`plug/spi/RecordedPerceive.kt`). `forgetSubject` /
  `forgetHolder` are the sanctioned deletes.
- `observe/ObservationBudget.kt` — the retention contract (AMPR-465): `MAX_AGE` (90 days),
  `MAX_ROWS` (300 000) / `PRUNE_TO_ROWS` (240 000), `SWEEP_INTERVAL_INSERTS` (256),
  `MAX_PRUNING_RECORDS`. Enforced by `SqlDelightObservationStore.insert`; each pass that drops
  anything leaves one row in `db/field/ObservationStorePruning.sq` (`16.sqm`).
- `agents/domain/knowledge/KnowledgeBelief.kt` — `KnowledgeEntry.toBelief(subject)`,
  `KnowledgeType.beliefKind()` and `UnprovenancedKnowledgeException`.
- `KnowledgeStore.sq` `findKnowledgeVisibleTo` / `KnowledgeRepository.findKnowledgeVisibleTo` — the
  viewer-scoped list read; `EventStore.sq` `latestSequence` / `EventRepository.latestSequence`;
  `EventStore.sq` `getLatestEventsByType` / `EventRepository.getLatestEventsByType` — the
  bounded, newest-first, typed read the Action fold makes.
- Wiring: `DefaultAmpereInstance` (always), `Ampere.fromEnvironment` (only with a `database`).
- `jvmTest/.../api/FieldServiceTest.kt`, `jvmTest/.../observe/ObservationStoreTest.kt`,
  `jvmTest/.../commitment/CommitmentLedgerTest.kt`,
  `commonTest/.../knowledge/KnowledgeBeliefTest.kt`.

## Invariants

- **The stores are the fold, except for Actions.** Every W2 door publishes its event and writes its
  row in one step, so a Field is a read over the stores at an instant, never a replay of the log.
  Taken Actions are the exception: they are folded at read time from a *bounded* window of the
  newest tool events, never the whole log, and never stored.
- **The retypes are fold-side (Q5).** No event class carries an Action or a Lesson. A
  `ToolExecutionStarted` / `ToolExecutionCompleted` pair reads as one `TAKEN` Action: the actor
  is the start's event source, the executor its `executorId`, the goal the root goal of the
  start's envelope run (`GoalStore.forRun`) or `null`, the description the tool name, the
  provenance the start, and the result `Succeeded` or `Failed(errorMessage)`. Outcome knowledge
  reads as a `LESSON`. No Event subtype was added or changed to say either.
- **Unknown is `null`, never guessed.** No source records an Action's reversibility or cost, so
  both are `null`, and nothing may read `null` as reversible or as free. A failure is a taken
  Action whose result failed; `ActionState` has no state after `TAKEN`, and only a `TAKEN`
  Action may have a result.
- **A held call is not taken.** A completion with `held = true` is waiting on a person, so the
  fold leaves it out rather than give it a result. A completion whose start is outside the
  window (rotated out, or outlasting `2 × actionLimit` other starts) is left out too: without the
  start there is no provenance.
- **Every Belief states its kind.** `BeliefKind` has no default. A knowledge row's kind is its
  `knowledge_type` through an exhaustive `when` (`FROM_OUTCOME` → `LESSON`, the rest their
  namesakes); an Observation is always `OBSERVATION`.
- **A view is per viewer (F12).** Beliefs come from `findKnowledgeVisibleTo`, whose predicate —
  the same one every Subject's knowledge read uses — sits in SQL ahead of the `LIMIT`. Another
  Subject's `PRIVATE` and `PROTECTED` beliefs are never in a snapshot; a `null` viewer sees `PUBLIC`
  only. Observations come from `ObservationStore.visibleTo`, which carries the same predicate in
  the same place. Subjects, Relationships and Goals carry no sensitivity and are the same in every
  view.
- **Observations are their holder's, and never knowledge.** A row is written only by the Subject
  that perceived the entity, keyed `(created_by_event, subject, holder)` so a re-store writes
  nothing, with `created_by_event` the `CanonPerceivedEvent` it folds — no other event announces
  it. Observations are never written to `KnowledgeStore`, so recall never returns one. A replay
  read (`replayable`) never returns a `PROTECTED` row, which by default is every Observation of a
  person. `beliefLimit` bounds the beliefs and the Observations separately.
- **The Observation table is bounded, write-side (AMPR-465).** Every `insert` counts rows added,
  and on the store's first insert and then every `ObservationBudget.SWEEP_INTERVAL_INSERTS` rows
  it sweeps, in one transaction after the insert's own has committed: rows older than `MAX_AGE`
  by `created_at` go, rows whose `Decay.ExpiresAt` is at or before now go, and only if the table
  is still over `MAX_ROWS` the oldest rows go down to `PRUNE_TO_ROWS`. Oldest-first by
  `created_at`, never by sensitivity — F12 is a visibility rule, not a retention rule. A pass that
  dropped anything writes one `ObservationStorePruning` row (counts by age, expiry and cap, rows
  before and after); a pass that dropped nothing writes none. Time is the store's `Clock`, never
  `Clock.System` inline. No row is exempt for being cited: `Provenance.createdBy` on every
  primitive names an event, never an Observation row, so a Belief or Commitment never dangles
  when one is dropped. The default Observation `decay` is `HalfLife(MAX_AGE)`, so the row says
  what the store will do to it; a caller's explicit `ExpiresAt` is honoured by the same sweep.
- **Signals are derived, never stored.** No priority, urgency, trust, Potential or Resistance is
  computed here or carried on a `Field`; how they combine is F28, open.
- **`field/` stays a leaf.** `Field.kt` imports nothing outside `field`; the service and the
  `KnowledgeEntry` mapper live outside it, and `Field.GoalState` mirrors `goal.GoalStatus` through
  an exhaustive `when` in the service rather than importing it.
- **No invented provenance.** A knowledge row with no `created_by_event` — every row older than
  `10.sqm` — is not a belief; the snapshot skips it and counts it on `unmappedBeliefs`.
- **What a belief is about is the service's choice, not the row's.** The knowledge table has no
  subject column. A row written under a run that formed a goal tree is about that tree's root goal;
  any other row is about its holder (self-knowledge). The default is AMPR-328 question Q6; changing
  it changes `DefaultFieldService.aboutOf` only, never the mapper.
- **Any store's failure is the snapshot's failure.** A Field with one branch silently missing reads
  as a world where that branch is empty.
- **A Commitment is a fold of the ticket events, with no event of its own.** `TicketAssigned` on a
  ticket with a due date forms one, held by the assignee; the same event resolves any other
  holder's open one on the ticket (unassigning resolves them all). `TicketStatusChanged` to Done
  resolves every open one. The row's `created_by_event` / `resolved_by_event` are those events,
  stamped with their envelopes' `recorded_at`, and folding an event twice changes nothing.
- **Every publisher of those two events folds into the same ledger.** Today that is
  `TicketOrchestrator` (`assignTicket`, `transitionTicketStatus`, and `blockTicket` when it hands
  the ticket on) and `TicketActionService`, each right after the door records the event; a
  fold failure is the method's failure. A path that forms commitments the other cannot resolve is
  the two-of-everything bug the Field exists to remove.
- **A snapshot carries open Commitments only, and judges none.** Resolved ones are history, read
  from the store. Overdue, kept, late and trust are derived (F32, W5), never stored on the row.
- **`asOfSequence` is an anchor, not a cut.** It is read first; the stores are read after it, so a
  row whose event is newer may appear, and a knowledge row (written before its event) may appear
  before its event does. SQLite here gives no cross-read isolation to make it exact.

## Common operations

- **Read the Field as an agent sees it** — `ampere.field?.snapshot(viewer = Ref(agentId))`.
- **Read what anyone may see** — `snapshot(viewer = null)`: public beliefs only.
- **Map one knowledge row** — `entry.toBelief(subject)`: `Settled` with a confidence, `Unsettled`
  without; holder and sensitivity from the row; provenance = `(createdByEvent, timestamp)`.
- **Put a perceive in the Field** — `source.recordedPerceive(query, eventApi)`, then
  `perception.store(holder, SqlDelightObservationStore(database))`. Nothing in Ampere does this
  on a production path; the consumer that perceives chooses the holder.
- **Forget what was observed about an entity** — `store.forgetSubject(ref)` (every holder's rows
  for that subject), or `store.forgetHolder(holder)` for everything one Subject observed; both
  return the row count. Never raw SQL against the table.
- **Pin the sweep in a test** — construct `SqlDelightObservationStore(database, clock = …)` with
  a clock near the rows' `created_at`; the bounds are constructor parameters too, so a cap test
  writes a dozen rows, not 300 000.
- **Add a branch to the snapshot** (Constraints) — give it a store first; the snapshot reads
  stores, so a branch with no store has nothing to contribute. Actions are the one exception,
  because Q5 put their retype in the fold and the tool events already carry everything a taken
  Action needs.
- **Read what an agent did** — `snapshot(viewer).actions`, newest first: one `TAKEN` Action per
  completed tool call, with `result` saying whether it worked. Never read the log for an
  `ActionTaken`; there is none.
- **Read an agent's lessons** — `snapshot(viewer).beliefs.filter { it.kind == BeliefKind.LESSON }`.
- **Create a ticket that forms a Commitment** — `ampere.tickets.create("…") { dueDate(instant) }`,
  then `assign`. `TicketActionService.createTicket(…, dueDate)` underneath.
- **Add a ticket publisher** — take a `CommitmentLedger?`, wire it from
  `EnvironmentService.commitmentLedger`, and call `assigned` / `statusChanged` with the
  `StoredEvent.recordedAt` that `publish` returned.

## Anti-patterns

- *Filtering `getAllKnowledge` (or any wider read) by sensitivity in Kotlin.* A `LIMIT` before the
  filter hands a viewer a short page, and the unscoped read is one forgotten call away from a leak.
- *Defaulting a missing provenance to the row's id or the snapshot's time.* Where a belief came
  from cannot be recovered later; a made-up one is worse than a count of the rows that lack it.
- *Putting `FieldService` under `field/`.* It reads `agents.domain` and `agents.events`, which
  `FieldBoundaryTest` forbids the leaf to import. `ObservationStore` is outside it for the same
  reason: it imports the database.
- *Writing Observations into `KnowledgeStore` because the columns fit.* Holder, confidence,
  sensitivity and provenance all fit, but every perceived entity would surface in recall.
- *Storing a computed ranking on `Field` "for convenience".* That is a signal stored, and F28 has
  not decided the arithmetic.
- *Publishing a `CommitmentFormed` event, or forming a commitment from a bus subscriber.* The
  ticket events already are the record; a second event is a second source of truth, and a
  subscriber folds after the publisher has returned, so the caller cannot see the fold fail.
- *Building a `TicketActionService` without `commitmentLedger`.* It still publishes, so the event
  log looks right while no commitment forms. Every SDK and CLI site passes
  `environmentService.commitmentLedger`.
- *Reassigning with `ticketRepository.assignTicket` and no `TicketAssigned`.* Nothing folds a
  repository write, so the previous holder's commitment stays open. `TicketOrchestrator.blockTicket`
  did this until AMPR-449; a blocker that hands the ticket to someone new now publishes a
  `TicketAssigned` caused by its `TicketBlocked` and folds it. Its move to Blocked is announced by
  `TicketBlocked` alone, which is safe here only because Blocked resolves nothing.
- *Publishing an `ActionTaken`, `ActionFailed` or `BeliefFormed` event.* Q5 ruled the retypes
  fold-side. A second event for a tool call or a lesson is a second source of truth for the same
  fact.
- *Filling an Action's `reversibility` or `cost` with a plausible default.* Nothing records either
  yet. `REVERSIBLE` or `Cost(0.0)` as a default would let a later gate admit an Action because of a
  value that nobody measured.
- *Adding a `FAILED` `ActionState`.* States only move forward. A failure is a `TAKEN` Action whose
  `result` is `Failed`.
- *Pairing each completion with a per-invocation `payload LIKE` lookup.* `invocationId` lives only
  in the payload, so each lookup scans every start row in the store. The fold reads one bounded,
  newest-first window of starts and pairs them in memory.
- *Constructing a store over old fixture rows with the default clock.* The first insert sweeps,
  and a row recorded in 2023 against `Clock.System` is past `MAX_AGE`; the test's rows vanish
  on write and the failure reads like a broken insert. Pass a `FixedClock`.
- *Exempting rows a Belief "cites" from the sweep.* Nothing cites an Observation row in 0.20.0;
  a reference column to protect rows nothing points at is a table rebuild for a guarantee nothing
  needs. If a later F-row adds evidence links, the exemption is added then.
- *Keeping `PROTECTED` rows longer (or shorter) than `PRIVATE` ones.* Sensitivity decides who may
  read a row, not how long it lives.
- *Treating `findKnowledgeVisibleTo`'s interface default as a working read.* It fails with
  `UnsupportedOperationException` so a repository that predates it — a consumer's `MemoryStore`
  override — makes the snapshot fail loudly rather than report a world with no beliefs.
