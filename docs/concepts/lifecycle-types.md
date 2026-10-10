---
concept: LifecycleTypes
status: experimental
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/lifecycle/**
related: [DomainCanon, Probe, PlugPermissions, ChiProtocol]
last_verified: 2026-10-10
---

# Lifecycle Types

## What it is

Three serializable value types for decomposing work through a human-judgment
stop: `ReconFinding` (a claim, its evidence, and a `verified` / `inferred` /
`untested` label), `DecisionRegister` (an ordered list of `LifecycleDecision`s,
each with options, an explicit default, and a lock), and `LifecycleGate` (a
stop on a unit of work that is `Open`, `AwaitingPerson`, or `Closed` with a
`GateOutcome`). They are plain data: nothing here runs, waits, or publishes.

## Why it exists

Socket's Lifecycle Arcs (SCKT-664) coordinate through work-item state: a recon
files findings, a gate turns them into a proposed register, a person locks it,
and locked decisions become implementation work. Its decision L8 puts generic
work-decomposition types in Ampere so Socket never re-declares them (AMPR-368).

The design pressure was naming. Every obvious name was taken by something
adjacent but different, and the R5 recon found the requested inventory wrong in
both directions — so the rationale for each name is recorded on the type and
repeated under Anti-patterns below. `Wave` was requested and deliberately not
cut: its defining property (tasks are parallel-safe because their file paths do
not overlap) is a property of a diff, nothing in Ampere expresses it, and
nothing has proven the type necessary.

## Where it lives

- `lifecycle/ReconFinding.kt` — `ReconFinding`, `ReconFindingId`, `ReconConfidence`, `ReconEvidence`.
- `lifecycle/DecisionRegister.kt` — `DecisionRegister`, `LifecycleDecision`, `DecisionId`, `DecisionOption`, `DecisionLock`.
- `lifecycle/LifecycleGate.kt` — `LifecycleGate` (`Open`, `AwaitingPerson`, `Closed`), `GateOutcome`.
- `agents/domain/Principal.kt` — the type of `DecisionLock.lockedBy` and `LifecycleGate.Closed.closedBy`. Not owned here.
- `commonTest/.../lifecycle/` — `LifecycleSerializationTest` (round trips, pinned wire names), `LifecycleTypesTest` (the rules below).

## Invariants

- **None of these is a canon entity.** No provider ships a finding, a register, or a gate, so none clears the canon's admission gates (see [DomainCanon](domain-canon.md)). They point *at* canon — every `subject` is a same-Link `CanonId` under the cross-reference contract — and are never members of `CanonType` or `CanonEntity`.
- **They always decode.** No `init` validation anywhere, for the reason `CanonWorkGraph` gives: a defect must be representable so it can be recorded and then caught. The rules are exposed as properties (`ReconFinding.isSubstantiated`, `LifecycleDecision.isCoherent`, `DecisionRegister.isCoherent`) and enforced only on the write-side paths (`choose`, `lock`, `close`), which return `Result`.
- **`@SerialName`s are wire contracts.** `lifecycle.*` for types, snake_case for enum members. A gate is written by one process and read by another; a rename strands every gate already recorded.
- **`VERIFIED` is the only label that obliges evidence**, and the label is not a score. There is no threshold and no arithmetic on it.
- **`UNTESTED` is not `Verdict.Undetermined`.** Untested means no check was run; undetermined means a check ran and the evidence was absent.
- **Evidence is a reference, never content.** `ReconEvidence.excerpt` is a bounded `CanonProse`; the source stays where it is. `ReconEvidence` is `Observed`, so its age is a `FreshnessProbe` parameter and never a field (see [Probe](probe.md)).
- **A default is explicit, and silence is an answer.** A decision locked with nothing chosen resolves to `defaultOption` and keeps `chosenOption` null, so the record still says nobody chose.
- **A lock is never re-stamped and a closed gate is never reopened.** Locking a register leaves existing locks untouched; work that must stop again raises a new gate.
- **An empty register is not locked.** Nothing proposed is not the same as everything settled.
- **`Open` means unresolved, not passable.** Every gate state blocks downstream work except `Closed(PASSED)`. `blocksDownstream` is the one property downstream work reads.
- **A gate holds no coroutine, `Deferred`, or timeout.** It outlives the process that raised it.

## Common operations

- **File a finding** — `ReconFinding(id, claim, confidence = VERIFIED, evidence = listOf(ReconEvidence(source, observedAt, revision, locator)))`. Stamp `observedAt` when the source is read, not when the finding is filed.
- **Check how old a finding's evidence is** — `FreshnessProbe(maxAge, now).evaluate(evidence)`.
- **Propose, choose, lock** — construct `LifecycleDecision` with a `defaultOption`; `choose("B")`; `register.lock(at, by)` locks every open decision in one act, all-or-nothing.
- **Read what was decided** — `register[DecisionId("L8")]?.effectiveOption`.
- **Move a gate** — `Open(subject, name, raisedAt).awaitPerson(since, waitingFor)`, then `close(outcome, at, by, note)`.
- **Ask whether work may proceed** — `!gate.blocksDownstream`.

## Anti-patterns

- **Naming the finding `Finding`.** `agents/definition/qa/QualityState.kt` already declares one in the same artifact — a code-quality defect with a severity and no evidence or confidence.
- **Adding a provenance field to `Verdict` instead.** A Probe's verdict is first-hand by construction, so the field would read `verified` forever while changing the wire shape of every recorded `ProbeReport`. `INFERRED` is also exactly the soft pass `Undetermined` forbids.
- **Treating a `*Gate` permission check as a lifecycle gate, or the reverse.** `GateResult`, `LinkResolutionGate` and `ToolExecutionPermissionGate` answer *may this proceed right now*; a lifecycle gate waits for an act that has not happened.
- **Implementing the gate as a Probe.** Probes evaluate synchronously over present evidence and carry no pending state. A Probe can test whether a gate has passed.
- **Reading `Open` as "the gate is open, go ahead".** It is the ticket sense of the word.
- **Putting a name string beside `lockedBy` or `closedBy`.** `Principal` has one variant (`Ambient`) until the principal contract lands (AMPR-274), so a lock cannot yet name a person. A free-form name would become the identity everyone reads, and the one nothing verifies.
- **Deriving `CanonWorkStatus.VERDICT_REQUESTED` from a gate inside `ampere-core`, or a gate from the status.** Keeping a provider's status and labels in step with a gate is a binding's job.
- **Reaching for `LifecycleDecision` to model a plan step, or `Task.Step` to model a decision.** They look alike from a distance — both are ordered, both carry an identity, both can be waiting on something — and they are opposites in the one way that matters. A plan step (`agents/domain/task/Task.kt`, AMPR-410: a description, a nominated `toolId`, the seat in `assignedTo`, the tool's `arguments`) is work a machine runs, and its vocabulary lives with [PropelLoop](propel-loop.md). A `LifecycleDecision` is a question only a person closes, and holding one open is the point. A plan step placed with a seat is not a gate, and a locked decision is not a step: the register is what a run is *built from*, not what it *does*.
- **Adding a `Wave` type.** See *Why it exists*.
