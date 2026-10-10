---
concept: StartupReconciliation
status: experimental
tracked_sources:
  - ampere-core/src/jvmMain/kotlin/link/socket/ampere/agents/execution/dispatch/StartupReconciler.kt
  - ampere-core/src/jvmMain/kotlin/link/socket/ampere/agents/execution/dispatch/WorktreeRepair.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/execution/dispatch/ReconciliationOutcome.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/execution/dispatch/ClaimRegistry.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/execution/dispatch/MergeRequestLookup.kt
  - ampere-cli/src/jvmMain/kotlin/link/socket/ampere/ReconcileCommand.kt
  - ampere-work-linear/src/commonMain/kotlin/link/socket/ampere/work/linear/LinearClaimRegistry.kt
related: [DispatchJournal, CancellationAddress, ChassisSpi, TicketConventions]
last_verified: 2026-10-09
---

# StartupReconciliation

## What it is

The ordered recovery pass a supervisor runs before it dispatches anything, and that
`ampere reconcile` runs on demand. It reads every supervisor journal in the state
directory, and for each dispatch a journal cannot account for it reaps the agent's
process group, repairs the git worktree and branch that agent was writing to,
releases the work-source claim no timeout will ever clear, and records what it did —
or reports what only a person can decide. AMPR-291 named it mechanism **M-C**;
AMPR-310 built it.

It is the consumer of the two halves of the dispatch substrate:
[DispatchJournal](dispatch-journal.md) says *what* was in flight, and
[CancellationAddress](cancellation-address.md) says *how* to stop it.

## Why it exists

AMPR-291's fate table established that a SIGKILLed supervisor leaves recoverable
residue at every layer at once: work-source claims no timeout clears, git worktrees
locked mid-creation, stale `index.lock` files that block every later git operation,
orphan branches, agent process groups still running and still spending tokens, and
merge requests that may or may not have been created. No single mechanism addresses
them, and the order matters more than any individual step — so the recovery is one
named pass with a ratified sequence rather than cleanup scattered across the dispatch
loop.

Its ordering principle, from the findings:

> **Kill writers before judging what they wrote; repair local before shared; query
> before create.**

Each clause is a verified failure, not a preference. Experiment E1 watched an
orphaned `git` child *finish a checkout after its parent was killed*, so inspecting a
worktree whose agent may still be alive reads a moving target. Releasing a claim
before the local repair finishes lets the ticket be redispatched into a repository
mid-surgery. And merge-request creation is not idempotent, so a blind retry opens a
second pull request for the same branch (cell note 16).

Three things it deliberately does **not** do. It does not resume: per the AMPR-281
run-based lifecycle verdict, a suspect dispatch is redispatched from scratch, so the
pass restores nothing of the agent's own state — reverting the ticket to its queued
state *is* the requeue. It does not resume *itself* from a progress log either: a
progress log is one more thing that can be torn, so instead every step is a read, an
idempotent write, or a compensation guarded by a read-back. And it does not delete
work: uncommitted or unpushed residue is held and reported for a human redispatch
decision.

## Where it lives

- `agents/execution/dispatch/StartupReconciler.kt` (JVM) — the pass. Phase-major:
  `reconcile()` reaps every orphan, *then* repairs every worktree, *then* writes to
  the work source.
- `agents/execution/dispatch/WorktreeRepair.kt` (JVM) — the git surgery, plus
  `GitRunner`/`ProcessGroupGitRunner` and the `git worktree list --porcelain` parser.
- `agents/execution/dispatch/ReconciliationOutcome.kt` (common) — the report:
  `ReconciliationOutcome`, one `DispatchDisposition` per suspect dispatch, the four
  verdict enums, and `ReconciliationMark` (what goes in the journal).
- `agents/execution/dispatch/ClaimRegistry.kt` (common) — the work-source port:
  `releaseClaim`, `claimedTickets`, `ClaimRelease`, `ClaimScan`.
- `agents/execution/dispatch/MergeRequestLookup.kt` (common) — the forge port;
  `integrations/issues/github/GhMergeRequestLookup.kt` is the `gh pr list --head`
  implementation.
- `ampere-work-linear/.../LinearClaimRegistry.kt` — binds AMPR-305's adapter to the
  port; `LinearWorkSource.release` is the release protocol and
  `SupervisoryComment.Release` the retraction it posts.
- `ampere-cli/.../ReconcileCommand.kt` — `ampere reconcile`.
- Tests: `StartupReconcilerTest` (the ordering, every verdict, idempotency, a real
  process group reaped from a journal), `WorktreeRepairTest` (real repositories),
  `ClaimReleaseTest` and `LinearClaimRegistryTest` (the work-source half).

## Invariants

- **The absence of a clean-shutdown marker is what makes a dispatch suspect.** A
  crashed process writes nothing, so only the graceful path can leave a signal.
- **A journal is marked reconciled only when every one of its dispatches settled.**
  Held residue leaves the journal open so the next pass reports it again; a journal
  marked over the top of a hold hides it. A journal with any quarantined line is
  never marked — an unparseable line may name a claim or a process group nothing can
  enumerate, which is the original AMPR-291 failure.
- **Nothing inspects a worktree before its agent's group is confirmed stopped.**
  `TerminationOutcome.SURVIVED` and `REFUSED_ADDRESS_REUSED` both hold the worktree
  at `WorktreeVerdict.HELD_WRITER_ALIVE` and touch nothing.
- **The pass never reads its own journal.** A live supervisor is not residue, and a
  pass that reaped its own dispatches would kill the agents it is about to supervise.
- **Every classification fails towards *keep*.** An unreadable git status, a forge
  that does not answer, a branch whose merge state cannot be determined, a missing
  port — each holds the residue and reports it. "We could not ask" must never read as
  "there is nothing there", because that is the direction that deletes work.
- **Every step is re-runnable.** Terminating a dead group answers `ALREADY_GONE`;
  deleting an absent lock or branch is a no-op; `prune` is idempotent; the claim
  release reads the ticket's comments before writing one.
- **A destructive local action is only ever taken against a path or branch a journal
  recorded.** The journal, not the command line, is the authorization — so pointing
  the pass at an unrelated repository finds nothing to do rather than things to
  delete. A path the repository does not know is `OUTSIDE_REPOSITORY`, never removed.
- **A state a person set is never overwritten.** Rule B4 of the AMPR-291 verdict: the
  claim retraction is posted regardless (a dead supervisor's claim is unambiguously
  not held), but the status revert is refused whenever the evidence does not show
  this claim's own transition to be the write being undone.
- **The cold-start fallback reports and does not write, unless told otherwise.** A
  claim comment proves a supervisor took a ticket, not that it died, so a scan cannot
  tell a dead claimant from a supervisor running right now on another host.

## Common operations

- **Recover at startup, or on demand** —
  ```kotlin
  val journal = DispatchJournal.open(stateDir, instanceId, eventApi = api)
  val outcome = StartupReconciler(
      journal = journal,
      worktrees = WorktreeRepair(repositoryRoot),
      claims = LinearClaimRegistry(workSource),
      mergeRequests = GhMergeRequestLookup(repositoryRoot),
      eventApi = api,
  ).reconcile()
  if (!outcome.isSettled) report(outcome.held)
  ```
- **From the CLI** — `ampere reconcile`, plus `--repository`, `--work-source`
  (with `$AMPERE_WORK_SOURCE_TOKEN`), `--skip-worktrees`.
- **Decide whether a redispatch may open a merge request** —
  `DispatchDisposition.mayOpenMergeRequest`. Null means the question was not asked,
  which is not a yes.
- **Add a residue class** — a verdict member on the matching enum (each carries its
  own `isSettled`), the repair in `WorktreeRepair`, and the step in the phase of
  `reconcile()` the ordering principle puts it in. Never a new phase ordering.

## Anti-patterns

- *Walking the suspect dispatches one at a time, doing all seven steps for each* —
  that interleaves killing and judging, and writing shared state with repairing local
  state. The pass is phase-major for exactly this reason.
- *Reading a failed lookup as an absent one* — an unauthenticated `gh`, a git command
  that errored, a work source that timed out. Each one, taken as "nothing is there",
  licenses a deletion.
- *Marking a journal reconciled at the end of the pass regardless* — the marker is
  the skip signal for every later pass, so marking over a hold is how residue becomes
  invisible.
- *Making the pass resumable with a progress file* — it is already idempotent step by
  step, and a progress file is one more artifact a kill can tear.
- *Reverting a ticket because it is in the claimed state* — it has to be a state
  **this claim** set, which means comparing the open state-history span's start
  against the claim comment's server timestamp. A ticket moved away and back reads as
  claimed and is not.
- *Retracting a claim by deleting its comment* — comments are append-only. The
  retraction is a further comment (`release:<issue>:<instance>`), and the arbiter
  skips a claim a later release names.
- *Leaving a losing claim comment on the ticket* — it is live until retracted, so
  once the winner's claim can be released the stale loser is promoted to holder and
  the ticket sits in progress with nothing working on it. `claim` retracts its own
  losing claim for this reason.
- *Deleting lock files without reaping first* — deleting a lock a live git process
  holds is how a repository gets corrupted.
