---
concept: Switchboard
status: experimental
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/execution/dispatch/DispatchWorkSource.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/execution/dispatch/SwitchboardOutcome.kt
  - ampere-core/src/jvmMain/kotlin/link/socket/ampere/agents/execution/dispatch/Switchboard.kt
  - ampere-core/src/jvmMain/kotlin/link/socket/ampere/agents/execution/dispatch/ReadyQueueEvaluator.kt
  - ampere-core/src/jvmMain/kotlin/link/socket/ampere/agents/execution/dispatch/TicketScope.kt
  - ampere-core/src/jvmMain/kotlin/link/socket/ampere/agents/execution/dispatch/ModelRecommendation.kt
  - ampere-core/src/jvmMain/kotlin/link/socket/ampere/agents/execution/dispatch/HumanVerdicts.kt
  - ampere-core/src/jvmMain/kotlin/link/socket/ampere/agents/execution/dispatch/WorktreeProvisioner.kt
  - ampere-core/src/jvmMain/kotlin/link/socket/ampere/agents/execution/dispatch/TicketContext.kt
  - ampere-core/src/jvmMain/kotlin/link/socket/ampere/agents/execution/dispatch/AgentLauncher.kt
  - ampere-core/src/jvmMain/kotlin/link/socket/ampere/agents/execution/dispatch/RunSupervisor.kt
  - ampere-work-linear/src/commonMain/kotlin/link/socket/ampere/work/linear/LinearDispatchWorkSource.kt
  - ampere-cli/src/jvmMain/kotlin/link/socket/ampere/DispatchCommand.kt
related: [DispatchJournal, CancellationAddress, StartupReconciliation, TicketConventions, ChassisSpi]
last_verified: 2026-10-31
---

# Switchboard

## What it is

The supervisor's automated dispatch loop (AMPR-308): `ampere dispatch --wave <id>`
drains a wave's ready queue into agent runs, each claimed on the work source, given
its own git worktree and the ticket as context, launched in its own process group,
journaled at every phase, and left alone until it stops — with one automated kill.
It replaces the operator's manual path from "this ticket is ready" to "an agent is
working on it", step for step as the AMPR-288 dispatch ethnography traced it.

## Why it exists

Every step it automates was a step the operator did by hand, and the ethnography
(AMPR-288) found nearly all of them mechanical: read the queue, check the blockers,
flip the ticket to In Progress, create the worktree on the tracker's branch name,
attach the ticket, pick the model, start the agent, wait. The dispatch substrate
(AMPR-305's adapter, AMPR-307's journal and process groups, AMPR-310's
reconciliation) made the steps recoverable; this is the loop that walks them.

Three pieces of judgement were deliberately *not* automated, and the design turns on
keeping them out: **verdicts**, **gates** and **merges** are human writes. The
ratified trust boundaries (Act 7 planning, recorded on AMPR-287) say the Switchboard
never authors a verdict comment, never removes `gate:awaiting-verdict`, never moves a
verdict-gated or `research` ticket to Done, and never merges. Those are enforced as
absences — there is nothing in the Switchboard's reach that could do them — rather
than as checks someone might later skip.

It names its electrical grammar: switches route. A telephone switchboard's operator
plugged calls into sockets by hand until automatic switching let the network scale
past what operators could reach.

## Where it lives

- `agents/execution/dispatch/DispatchWorkSource.kt` (common) — the port: the only
  work-source operations the Switchboard may perform, and the data they return.
  `SwitchboardOutcome.kt` (common) — `RefusalReason`, `AgentStop`.
- `agents/execution/dispatch/Switchboard.kt` (JVM) — the loop, its settlement and
  graceful shutdown; `SwitchboardShutdownHook` (SIGTERM/SIGINT); `Escalations` (every
  `esc:` body it can post).
- `ReadyQueueEvaluator.kt` — the ready rule. `TicketScope.kt` — `ampere-scope` parsing
  and overlap. `HumanVerdicts.kt` — what counts as a recorded verdict.
  `ModelRecommendation.kt` — the model/effort line.
- `WorktreeProvisioner.kt` — `git worktree add` on the suggested branch.
  `TicketContext.kt` — the ticket file at `.context/attachments/…`.
  `AgentLauncher.kt` — the launch template, the gate, the worktree pin.
  `RunSupervisor.kt` — the silence rule.
- `ampere-work-linear/.../LinearDispatchWorkSource.kt` — binds the adapter to the port.
- `ampere-cli/.../DispatchCommand.kt` — `ampere dispatch`; `WorkSourceConnection.kt`
  opens the adapter (shared with `ampere reconcile`).
- Events: `SupervisorEvent.DispatchRefused` and `.AgentRunStopped`, beside the
  journal's `DispatchRecorded` (one per phase).
- Tests (`ampere-core/src/jvmTest/.../dispatch/`): `ReadyQueueEvaluatorTest` (every
  refusal), `SwitchboardTest` (the pipeline against a real repository and real process
  groups), `SwitchboardShutdownProcessTest` (a real SIGTERM to a child JVM),
  `AgentLauncherTest` (the gate, the pin), `DispatchWorkSourceSurfaceTest` (the trust
  boundaries as a method set), `TicketScopesTest`, `ModelRecommendationsTest`,
  `HumanVerdictsTest`; `LinearDispatchWorkSourceTest` (the wire).

## Invariants

- **The Switchboard holds `DispatchWorkSource`, never the adapter.** The port has no
  method that removes a label, writes a raw state or posts a free-form comment, and
  `ampere-core` cannot see the adapter. `DispatchWorkSourceSurfaceTest` pins the method
  set; widening it is a reviewed change, not a convenience.
- **Every comment it causes opens with a supervisory header** (`claim:`, `release:`,
  `esc:`), so nothing it writes can read as a human verdict, whose test is the first
  line.
- **A ticket is ready only on a read taken immediately before the claim.** The wave
  label, gate labels, queued state and blockers the queue already checked are checked
  again there. A gated ticket reaching a claim is the failure the boundaries exist to
  prevent.
- **A `research` blocker counts only when it is Done *and* carries a human verdict.**
  Cancelled is not Done; Done with no verdict is the incident the rule was ratified
  for.
- **Unknown scope overlaps everything** — a ticket without a readable `ampere-scope`
  block runs only when nothing else is in flight, and an in-flight ticket without one
  blocks every candidate. In-flight means everything the work source shows claimed,
  in progress or in review, whoever dispatched it.
- **Overlap fails toward "yes".** Two scopes are disjoint only when no reading of
  their globs could share a path.
- **Claim to launch is not interruptible.** A shutdown landing between the claim
  comment and the journal would leave a claim nothing records.
- **The process-group address is journaled before the agent runs.** The agent is
  spawned behind a gate that `exec`s it only after `AGENT_RUNNING` is on disk; a
  supervisor that dies first closes the gate's stdin and the agent never starts.
- **The agent's working directory is the worktree**, `PWD` says so, and a worktree
  whose Ampere config points the workspace outside it is refused (AMPR-300's F2).
- **One automated kill.** Ten minutes of no output terminates the group and
  escalates; nothing else interrupts a run.
- **A clean exit is settled by asking the forge, not the agent.** An open or merged
  request for the branch journals `MR_OPENED` then `DONE`; anything else escalates.
  The ticket is never transitioned after the claim.
- **The clean-shutdown marker means nothing is owed.** Every dispatch is settled
  (escalated, merged-request-backed, or released unstarted) before it is written;
  one that cannot be leaves the journal unmarked for `ampere reconcile`.
- **Escalation bodies are public and composed** — fixed text, a branch name, numbers.
  Never a local path, a command's output, or anything the agent wrote.

## Common operations

- **Dispatch a wave once** — `ampere dispatch --wave w1 --work-source <uri>`: one pass,
  then supervise until every launched agent stops, then exit.
- **Keep dispatching** — add `--watch` (`--poll <seconds>`); Ctrl+C or SIGTERM shuts
  down gracefully.
- **Use another agent** — `AgentLaunchSpec(command = listOf(…, "{model}", "{prompt}"))`
  with the placeholders `{model}`, `{effort}`, `{prompt}`, `{ticket}`, `{worktree}`,
  `{context}`. The default is Claude Code, headless, streaming JSON so the silence rule
  has output to measure.
- **Add a ready rule** — a `RefusalReason` member and a check in
  `ReadyQueueEvaluator.evaluate`, in the order the enum states; the candidate is
  refused for the first rule it fails. Never a work-source write.
- **Add a way a run can end** — a branch in `Switchboard.settle` that ends in
  `escalate` or a forge-confirmed `DONE`, and an `Escalations` body. Never a ticket
  transition.

## Anti-patterns

- *Giving the Switchboard the adapter "for one more read"* — the adapter can remove
  gate labels and close tickets, and the boundary is that the Switchboard cannot.
  Widen the port with a read instead.
- *Measuring silence on plain `--print` output* — a non-streaming agent writes nothing
  until it finishes, so every long run looks hung and the one kill rule kills it.
- *Treating the spawn wrapper's exit as the agent's* — a terminal's Ctrl+C reaches the
  wrapper, which shares the supervisor's group, while the agent runs on in its own; the
  run is over when the group's leader exits.
- *Releasing a run that was interrupted mid-work* — its worktree holds partial work
  and its branch is taken, so a redispatch would fail on the branch and lose the
  record; it is escalated instead. Only a claim whose agent never ran is released.
- *Escalating with git's error message* — it names local paths, and the comment is
  public. The message goes to the operator's console; the escalation says what kind of
  failure it was.
- *Reading "Done" as "verdict recorded" for a research blocker* — that is the operator
  practice the stricter rule replaced.
