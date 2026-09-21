---
concept: CancellationAddress
status: experimental
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/execution/process/CancellationAddress.kt
  - ampere-core/src/jvmMain/kotlin/link/socket/ampere/agents/execution/process/ProcessGroups.kt
related: [EventSerialBus, ChassisSpi]
last_verified: 2026-09-21
---

# CancellationAddress

## What it is

The handle you need to stop a delegated piece of work — including everything it spawned —
without holding the object that started it. For local subprocesses it is
`CancellationAddress(leaderPid, processGroupId, leaderStartedAt)`: the spawned command runs
as the leader of its own process group, and signalling that group reaches every descendant
that stayed in it. `ProcessGroups` (JVM) spawns with one and terminates by one.

## Why it exists

D4's rule on the Four Contracts track (AMPR-274) is *no delegation without a cancellation
address*. It was written with remote jobs in mind, but AMPR-291 showed the same failure is
local: a child survives its parent's SIGKILL, reparented to PID 1, and in one experiment an
orphaned `git` child finished a checkout after its parent was killed. Nothing recorded a PID
or PGID anywhere, so a restarted supervisor could not find, adopt or kill its orphans — the
fate table's unsafe cells (agent worktree edits × supervisor SIGKILL). AMPR-299 extends
the rule: **any subprocess that can outlive its spawner is spawned in its own process group,
and its address goes to the caller at spawn.**

`Process.destroy()` is not enough even while the parent is alive: it signals one PID,
so wrappers like `./gradlew`, `npx` or `sh -c` die and leave the real work running.

The address is `@Serializable` in `commonMain` so its owner can make it durable. The first
planned consumer is the CLI supervisor's claim-record journal (AMPR-291 mechanism M-A/M-B,
built in the AMPR-286 wave), which records one address per dispatch and reaps them in its
startup reconciliation pass.

## Where it lives

- `ampere-core/.../agents/execution/process/CancellationAddress.kt` — the address and
  `TerminationOutcome` (common, serializable).
- `ampere-core/.../agents/execution/process/ProcessGroups.kt` — JVM `start` (spawn in a new
  group), `run` (spawn, and terminate the group if the coroutine is cancelled or the block
  throws), `terminate(address)`, `isAlive(address)`; `GroupedProcess` pairs the `Process` with
  its address.
- Callers: `GitCliProvider.execute`, `GitHubCliProvider.executeGh`,
  `ToolRunTests.jvm.kt` (via `run`); `StdioProcessHandler.jvm.kt` (via `start`, long-lived).
- `ampere-core/src/jvmTest/.../agents/execution/process/ProcessGroupsTest.kt` — behaviour
  pinned against real processes.

How the group is created: `/bin/bash` job control (the command runs as a background job, so
bash puts it in a new group whose ID is its PID, and writes that PID to a temp file for the
JVM); otherwise `setsid` on the PATH; otherwise (Windows) no group — the address has a null
`processGroupId` and termination walks the leader's live process tree instead.

## Invariants

- **Every JVM subprocess that can outlive its caller is spawned through `ProcessGroups`.**
  No `ProcessBuilder.start()` for agent, git, gh, build or MCP work. The only exemptions
  are read-only probes that return in milliseconds and write nothing
  (`RepositoryDetector`'s `git remote get-url`, the REPL's `stty size`). The Android
  `actual`s still spawn directly; the `run`/`start` helpers are JVM-only for now.
- **Termination is SIGTERM to the group → bounded wait → SIGKILL.** Never a single PID;
  never SIGKILL first.
- **The address alone is sufficient.** `ProcessGroups.terminate(address)` needs no in-memory
  handle, so a recorded address is enough for a restarted process to reap.
- **A reused PID is never signalled.** If the leader PID is alive with a different start
  time than recorded, `terminate` returns `REFUSED_ADDRESS_REUSED` and sends nothing. A dead
  leader whose group is still alive is still ours: POSIX does not reuse a PID that is the ID
  of a live group.
- **Recording is the owner's job.** `ProcessGroups` surfaces the address; persisting it
  somewhere that survives the spawner is up to whoever owns recovery for that work.
- **Normal completion leaves the group alone.** `run` only terminates on cancellation or a
  thrown block; anything deliberately left running (e.g. a Gradle daemon) survives.

## Common operations

- **Run a command to completion, cancellable** —
  ```kotlin
  ProcessGroups.run(ProcessBuilder("git", "status").directory(dir)) { grouped ->
      grouped.process.inputStream.bufferedReader().readText()
  }
  ```
- **Hold a long-lived child** — `val grouped = ProcessGroups.start(builder)`; stop it with
  `grouped.terminate()`, not `grouped.process.destroy()`.
- **Make it recoverable** — persist `grouped.address` (it's `@Serializable`) with the work it
  belongs to; after a restart, `ProcessGroups.terminate(address)` for every record without a
  clean-exit marker.

## Anti-patterns

- *`process.destroy()` on a `GroupedProcess`* — on the bash path that kills only the
  wrapper; the group keeps running with nobody holding it.
- *Keeping the address only in memory for work that must survive a supervisor crash* —
  that is the AMPR-291 unsafe cell again; the address has to be written before the work can
  do anything irreversible.
- *Catching `CancellationException` as a generic failure around `run`* — it swallows the
  cancel after the group was killed, so the caller reports a failed run instead of a stopped one.
- *Letting a grouped command prompt on the terminal* — on the bash path the command is a
  background group of the caller's session, so reading `/dev/tty` (a git credential
  prompt, say) stops it with SIGTTIN until it is terminated (untested; inferred from
  job-control semantics). Run grouped commands non-interactively (`GIT_TERMINAL_PROMPT=0`,
  `GH_PROMPT_DISABLED=1`).
- *Using `sh -c 'set -m; …'` for the group* — dash ignores `set -m` without a terminal, so on
  Debian/Ubuntu the child silently stays in the parent's group.
