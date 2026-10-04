---
concept: DispatchJournal
status: experimental
tracked_sources:
  - ampere-core/src/jvmMain/kotlin/link/socket/ampere/agents/execution/dispatch/DispatchJournal.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/execution/dispatch/DispatchRecord.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/SupervisorEvent.kt
related: [CancellationAddress, EventSerialBus]
last_verified: 2026-10-04
---

# DispatchJournal

## What it is

The supervisor's claim-record journal: an append-only local file, one per supervisor
instance, holding one `DispatchRecord` per phase a dispatch reaches
(`{ticketId, supervisorInstanceId, worktreePath, branchName, phase, recordedAt,
agentAddress, mergeRequestUrl}`) and optionally ending in a clean-shutdown marker. A
read gives back the records, whether the marker is there, and every line that could
not be parsed. It stores facts; it decides nothing.

## Why it exists

AMPR-291's fate table found that a SIGKILLed supervisor leaves two things behind:
work-source claims that no timeout will ever clear, and agent subprocesses that keep
running — editing worktrees, spending tokens, able to push branches and open merge
requests. Neither could be *enumerated*, because no process id was persisted anywhere
in the CLI or core (verified by absence). Its verdict adopted three mechanisms; this
is M-A, and [CancellationAddress](cancellation-address.md) is M-B. The startup
reconciliation pass that consumes both is M-C.

The journal is the input every "for each in-flight dispatch…" recovery check needs.
Without it, recovery has nothing to iterate.

Why the *absence* of a marker rather than the presence of a "crashed" flag: a crashed
process writes nothing. Only the graceful path can leave a signal, so the signal has
to mean "nothing is owed" and its absence has to mean "assume everything is".

## Where it lives

- `agents/execution/dispatch/DispatchRecord.kt` (common) — the schema: `DispatchRecord`
  and `DispatchPhase` (`CLAIMED`, `WORKTREE_CREATED`, `AGENT_RUNNING`, `VERIFYING`,
  `MR_OPENED`, `DONE`, `ESCALATED`; the last two are `isTerminal`).
- `agents/execution/dispatch/DispatchJournal.kt` (JVM) — `open`, `append`,
  `markCleanShutdown`, `readAll`, `scanAll`, plus `JournalRead` (`entries`,
  `cleanShutdownAt`, `quarantined`, `latestPerDispatch()`, `suspectDispatches()`) and
  `QuarantinedLine`.
- `agents/domain/event/SupervisorEvent.kt` (common) — `DispatchRecorded`,
  `CleanShutdownMarked`, `JournalLineQuarantined` on the bus.
- `ampere-core/src/jvmTest/.../execution/dispatch/DispatchJournalTest.kt` — the
  contract; `DispatchJournalKillTest.kt` + `JournalWriterProcess.kt` — a real JVM
  writer, SIGKILLed mid-append.

JVM-only on purpose: `ProcessGroups` is too, and an `expect`/`actual` seam for file IO
across five targets is not free. The record schema is in `commonMain` because it is
serializable data a non-JVM reader could legitimately want.

## Invariants

- **Every write is a staged file, fsynced, then `ATOMIC_MOVE`d over the journal.**
  Never an in-place append. A reader sees the file either side of the rename and both
  are whole, so a torn record cannot arise from process death.
- **A line that does not parse is quarantined, never dropped and never thrown on.**
  Atomicity is a property of this writer, not of the file; a corrupted block or a
  foreign writer still has to be reportable. If a quarantined line *was* a claim
  record, a held claim or a live process group has become unenumerable — which is the
  original failure, so it is reported at `CRITICAL`.
- **One file per supervisor instance, and this object is its only writer.** The
  whole-file rewrite makes two writers on one file lose records. `instanceId` must be
  unique per supervisor process.
- **A record appended by one instance names that instance.** `append` refuses a record
  whose `supervisorInstanceId` differs, because the file's marker would otherwise
  vouch for a dispatch that another supervisor owns.
- **The clean-shutdown marker is the last line.** `append` after it fails, and a line
  found after it on disk is quarantined rather than read as live — a "clean" journal
  must not be able to hide an orphan.
- **A quarantined line survives a rewrite byte-for-byte.** The writer holds its lines
  as raw strings, so reopening a journal with a torn line and appending to it does not
  launder the evidence away.
- **The record is written before the work it describes.** A `CancellationAddress`
  recorded after the agent is spawned is lost by a crash in between — the window this
  mechanism exists to close.
- **No policy.** Whether to spawn, kill, release a claim or adopt a worktree is the
  caller's decision (Switchboard, the M-C reconciliation pass). `suspectDispatches()`
  reports; it does not act.

## Common operations

- **Record a dispatch as it advances** —
  ```kotlin
  val journal = DispatchJournal.open(stateDir, instanceId, eventApi = api)
  journal.append(record.copy(phase = DispatchPhase.CLAIMED))
  val grouped = ProcessGroups.start(agentBuilder)
  journal.append(record.copy(phase = DispatchPhase.AGENT_RUNNING, agentAddress = grouped.address))
  ```
- **Exit gracefully** — `journal.markCleanShutdown()`, after releasing claims.
- **Recover at startup** — `journal.scanAll()`, then for each read where
  `!cleanShutdown`, iterate `suspectDispatches()` and `ProcessGroups.terminate(it.agentAddress)`.
- **Choose the directory** — the supervisor's own state directory, conventionally
  `~/.ampere/supervisor/journal/`. Never `~/.ampere/Workspaces`, which is the
  sandbox-escape finding (AMPR-300).

## Anti-patterns

- *Storing a bare PGID instead of the `CancellationAddress`* — the address carries the
  leader PID and start time that let `terminate` return `REFUSED_ADDRESS_REUSED`
  instead of signalling whatever recycled that PID while the supervisor was dead.
- *Appending in place because the whole-file rewrite looks wasteful* — the rewrite is
  the atomicity. A journal holds tens of records per run; the cost is noise and the
  guarantee is the entire point.
- *Treating a missing clean-shutdown marker as "probably fine"* — it is the only
  evidence recovery gets, and a crash is exactly the case that produces nothing.
- *Sharing one journal file between supervisor instances* — the rewrite makes the last
  writer win, so records vanish silently and the marker stops meaning anything.
- *Re-reading the journal on a refresh loop* — each `readAll` republishes nothing it
  has already reported (per-journal dedup), but the file read is still O(journal).
  Recovery is a startup pass, not a poll.
- *Reading the journal with `Files.readAllLines`* — a record truncated inside a
  multi-byte character is malformed UTF-8, and that call throws on the whole file, so
  one bad line would make the journal unreadable instead of quarantining one line.
