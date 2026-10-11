---
concept: Field
status: experimental
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/field/Field.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/api/service/FieldService.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/api/internal/DefaultFieldService.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/knowledge/KnowledgeBelief.kt
related: [SubjectRegistry, GoalBranch, MemoryProvenance, DomainCanon, EventSerialBus]
last_verified: 2026-10-11
---

# Field

## What it is

Ampere's world state read as one value (AMPR-439, F-register row F6): `FieldService.snapshot(viewer)`
returns a `field.Field` — every registered Subject, every Relationship touching one, each
Subject's Goals with their state, and the Beliefs the viewer may see — read from the stores the W2
doors write, with the log's newest sequence (`asOfSequence`) as its anchor. It is exposed on the
instance as `AmpereInstance.field`, which is `@AmpereExperimentalApi`.

## Why it exists

W2 shipped the Field's primitives (`field/`, AMPR-415) and a store per branch — `SubjectStore` and
`RelationshipStore` (AMPR-416), belief columns on `KnowledgeStore` (AMPR-417), `GoalStore`
(AMPR-419) — and nothing read them back together. A reader wanting "the world as this agent sees
it" had five tables, three row types and a visibility rule to re-implement, and the visibility
rule is the one that leaks when re-implemented (F12). Replaying the log was never an option: the
event store prunes under `EventStoreBudget`, and `CanonPerceivedEvent` carries ids, not entities.

## Where it lives

- `field/Field.kt` — `Field`, `Field.HeldGoal`, `Field.GoalState`. Data only, inside the leaf.
- `api/service/FieldService.kt` — the public read: `snapshot(viewer, beliefLimit)`.
- `api/internal/DefaultFieldService.kt` — the read over `SubjectStore`, `RelationshipStore`,
  `GoalStore`, `KnowledgeRepository` and `EventRepository`; `DefaultFieldService.over(database, …)`.
- `agents/domain/knowledge/KnowledgeBelief.kt` — `KnowledgeEntry.toBelief(subject)` and
  `UnprovenancedKnowledgeException`.
- `KnowledgeStore.sq` `findKnowledgeVisibleTo` / `KnowledgeRepository.findKnowledgeVisibleTo` — the
  viewer-scoped list read; `EventStore.sq` `latestSequence` / `EventRepository.latestSequence`.
- Wiring: `DefaultAmpereInstance` (always), `Ampere.fromEnvironment` (only with a `database`).
- `jvmTest/.../api/FieldServiceTest.kt`, `commonTest/.../knowledge/KnowledgeBeliefTest.kt`.

## Invariants

- **The stores are the fold.** Every W2 door publishes its event and writes its row in one step,
  so a Field is a read over the stores at an instant, never a replay of the log.
- **A view is per viewer (F12).** Beliefs come from `findKnowledgeVisibleTo`, whose predicate —
  the same one every Subject's knowledge read uses — sits in SQL ahead of the `LIMIT`. Another
  Subject's `PRIVATE` and `PROTECTED` beliefs are never in a snapshot; a `null` viewer sees `PUBLIC`
  only. Subjects, Relationships and Goals carry no sensitivity and are the same in every view.
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
- **`asOfSequence` is an anchor, not a cut.** It is read first; the stores are read after it, so a
  row whose event is newer may appear, and a knowledge row (written before its event) may appear
  before its event does. SQLite here gives no cross-read isolation to make it exact.

## Common operations

- **Read the Field as an agent sees it** — `ampere.field?.snapshot(viewer = Ref(agentId))`.
- **Read what anyone may see** — `snapshot(viewer = null)`: public beliefs only.
- **Map one knowledge row** — `entry.toBelief(subject)`: `Settled` with a confidence, `Unsettled`
  without; holder and sensitivity from the row; provenance = `(createdByEvent, timestamp)`.
- **Add a branch to the snapshot** (Observations, Actions, Constraints) — give it a store first;
  the snapshot reads stores, so a branch with no store has nothing to contribute.

## Anti-patterns

- *Filtering `getAllKnowledge` (or any wider read) by sensitivity in Kotlin.* A `LIMIT` before the
  filter hands a viewer a short page, and the unscoped read is one forgotten call away from a leak.
- *Defaulting a missing provenance to the row's id or the snapshot's time.* Where a belief came
  from cannot be recovered later; a made-up one is worse than a count of the rows that lack it.
- *Putting `FieldService` under `field/`.* It reads `agents.domain` and `agents.events`, which
  `FieldBoundaryTest` forbids the leaf to import.
- *Storing a computed ranking on `Field` "for convenience".* That is a signal stored, and F28 has
  not decided the arithmetic.
- *Treating `findKnowledgeVisibleTo`'s interface default as a working read.* It fails with
  `UnsupportedOperationException` so a repository that predates it — a consumer's `MemoryStore`
  override — makes the snapshot fail loudly rather than report a world with no beliefs.
