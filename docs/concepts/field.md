---
concept: Field
status: experimental
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/field/Field.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/api/service/FieldService.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/api/internal/DefaultFieldService.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/knowledge/KnowledgeBelief.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/observe/ObservationStore.kt
  - ampere-core/src/commonMain/sqldelight/link/socket/ampere/db/field/ObservationStore.sq
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/field/Commitment.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/commitment/CommitmentStore.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/commitment/CommitmentLedger.kt
  - ampere-core/src/commonMain/sqldelight/link/socket/ampere/db/field/CommitmentStore.sq
related: [SubjectRegistry, GoalBranch, MemoryProvenance, DomainCanon, EventSerialBus]
last_verified: 2026-10-11
---

# Field

## What it is

Ampere's world state read as one value (AMPR-439, F-register row F6): `FieldService.snapshot(viewer)`
returns a `field.Field` — every registered Subject, every Relationship touching one, each
Subject's Goals with their state, the Beliefs the viewer may see, the Observations the viewer
may see (the Environment branch, AMPR-447), and every open Commitment (AMPR-449) — read from the
stores the W2 doors and the ticket publishers write, with the log's newest sequence
(`asOfSequence`) as its anchor. It is exposed on the
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

## Where it lives

- `field/Field.kt` — `Field`, `Field.HeldGoal`, `Field.GoalState`. Data only, inside the leaf.
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
- `observe/ObservationStore.kt` — `ObservationStore` / `SqlDelightObservationStore`: the
  Environment branch's rows. A non-leaf package, because it imports the database.
  `db/field/ObservationStore.sq`, created by `12.sqm`. Written by
  `RecordedPerception.store(holder, store)` (`plug/spi/RecordedPerceive.kt`).
- `agents/domain/knowledge/KnowledgeBelief.kt` — `KnowledgeEntry.toBelief(subject)` and
  `UnprovenancedKnowledgeException`.
- `KnowledgeStore.sq` `findKnowledgeVisibleTo` / `KnowledgeRepository.findKnowledgeVisibleTo` — the
  viewer-scoped list read; `EventStore.sq` `latestSequence` / `EventRepository.latestSequence`.
- Wiring: `DefaultAmpereInstance` (always), `Ampere.fromEnvironment` (only with a `database`).
- `jvmTest/.../api/FieldServiceTest.kt`, `jvmTest/.../observe/ObservationStoreTest.kt`,
  `jvmTest/.../commitment/CommitmentLedgerTest.kt`,
  `commonTest/.../knowledge/KnowledgeBeliefTest.kt`.

## Invariants

- **The stores are the fold.** Every W2 door publishes its event and writes its row in one step,
  so a Field is a read over the stores at an instant, never a replay of the log.
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
- **Add a branch to the snapshot** (Actions, Constraints) — give it a store first; the snapshot
  reads stores, so a branch with no store has nothing to contribute.
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
- *Treating `findKnowledgeVisibleTo`'s interface default as a working read.* It fails with
  `UnsupportedOperationException` so a repository that predates it — a consumer's `MemoryStore`
  override — makes the snapshot fail loudly rather than report a world with no beliefs.
