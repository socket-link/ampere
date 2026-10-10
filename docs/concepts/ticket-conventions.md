---
concept: TicketConventions
status: experimental
tracked_sources:
  - docs/templates/ticket-description.md
  - ampere-work-linear/src/commonMain/kotlin/link/socket/ampere/work/linear/SupervisoryComment.kt
  - ampere-work-linear/src/commonMain/kotlin/link/socket/ampere/work/linear/SupervisoryLifecycle.kt
related: [ChassisSpi, LifecycleTypes, DomainCanon]
last_verified: 2026-10-09
---

# Ticket Conventions

## What it is

The metadata a ticket has to carry for a machine to dispatch it, and the exact
format of each piece. Seven conventions:

| # | Convention | Where it lives on the ticket |
| --- | --- | --- |
| M1 | Machine-readable scope declaration | an `ampere-scope` fenced block in the description |
| M2 | Wave membership | the label `wave:<id>` |
| M3 | Verdict-gate marker | the label `gate:awaiting-verdict` |
| M4 | Verification-manifest pointer | **nowhere — a deliberate non-convention** |
| M5 | Claim-owner identity | a comment `claim:<issue>:<instance-id>`, retracted by `release:<issue>:<instance-id>` |
| M6 | Escalation payload | a comment `esc:<issue>:<instance-id>` + blank line + body, with `gate:escalated` |
| — | Model + effort recommendation | a bolded first line in the description |

`M1`–`M6` are the gap identifiers from the AMPR-289 work-source recon, whose
verdict approved turning them into conventions; the model/effort line came out
of the AMPR-288 dispatch ethnography. This cell is the **authoring** side. The
**reading** side is already shipped in `ampere-work-linear`, and where the two
could disagree, the adapter's constants win — see *Invariants*.

## Why it exists

Dispatch is only automatable on facts a machine can find. Three pressures fix
these seven shapes, and none of them is aesthetic:

**Scope is knowable at authoring time and nowhere later.** Contract 3 of the
Four Contracts track (AMPR-274) is that dependencies bind at the earliest
moment that can know them. The person writing a ticket knows which files it
touches; by the time two agents are editing the same file, the dependency has
stopped being a dependency and become a merge conflict. M1 is that contract
applied to parallel dispatch: the Switchboard (AMPR-308) refuses to run two
overlapping tickets at once, and it can only do that if overlap is declared.

**The query surface has a one-positive-label ceiling.** Server-side filtering
on this work source covers project, state, team and *one* positive label
(verified in the AMPR-289 probe). So exactly one supervisory fact can be pushed
down into the ready-queue query, and M2 spends it: `wave:<id>`. Everything else
a ready queue needs is a negative ("not gated") or a relation ("no open
blocker"), both inexpressible server-side and both filtered client-side — see
`readyQueueRule` in `WorkSourceIssueSource.kt` and [ChassisSpi](chassis-spi.md)
on `residual`. That is also why M3 and M6 are *labels* and not states: a gate
has to be a term the ready-queue rule can negate.

**There is no compare-and-swap.** Two writes to the same issue both succeed,
last-write-wins, with no conflict reported (verified). Comments, by contrast,
are append-only with a server-assigned total order. M5 exists because that
asymmetry is the only arbitration primitive available: the claim *comment*
decides who owns a ticket and the state transition follows. The assignee field
cannot do it — every write this adapter makes shares one API identity, so the
work source cannot say *which* supervisor claimed a ticket.

The model/effort line has a weaker justification and is honest about it: nothing
in this repo parses it. It is advice from the agent that had the context to the
agent or human that will not.

## Where it lives

- `docs/templates/ticket-description.md` — the template to copy. The section order there is load-bearing (see *Invariants*).
- `ampere-work-linear/.../SupervisoryComment.kt` — `SupervisoryComment.Claim` / `.Release` / `.Escalation`, their `render`/`parse`, and the `claim:` / `release:` / `esc:` prefix constants. The authority for M5 and M6.
- `ampere-work-linear/.../SupervisoryLifecycle.kt` — `WorkSourceLabels.WAVE_PREFIX`, `GATE_AWAITING_VERDICT`, `GATE_ESCALATED`, `GATES`, and `SupervisoryStatusMapping`. The authority for M2 and M3.
- `ampere-work-linear/.../WorkSourceIssueSource.kt` — `readyQueueRule`, the predicate list a ticket has to satisfy to be offered.
- `.ampere/verify.yml` — where the verification manifest *will* live, per decision D3 on AMPR-286. **No such file exists in this repo yet**, and M4 is the convention that no ticket ever points at it.
- Nothing in `ampere-core` reads any of this. The vocabulary is the adapter's and the authoring rules are this file's.

## The seven conventions

### M1 — `ampere-scope`

A fenced block tagged `ampere-scope` in the description, listing every
repository and repo-relative path glob the ticket may write. Schema and worked
examples below.

```ampere-scope
repos:
  - repo: socket-link/ampere
    paths:
      - docs/concepts/ticket-conventions.md
      - docs/templates/ticket-description.md
      - docs/concepts/_index.md
      - AGENTS.md
exclusive: true
```

That is this ticket's own block. `AGENTS.md` and `_index.md` are files every
other documentation ticket also edits, which is exactly why it is exclusive.

### M2 — `wave:<id>`

One label, e.g. `wave:w0`. Read back with `WorkSourceLabels.waveId(label)`;
written with `WorkSourceLabels.wave(id)`.

### M3 — `gate:awaiting-verdict`

Applied when the ticket reaches a human-verdict STOP gate. A ticket carrying it
is never ready: `readyQueueRule` emits one `Not(Equals(label, …))` per member of
`WorkSourceLabels.GATES`, and this is one of the two members. It maps to
`CanonWorkStatus.VERDICT_REQUESTED` (AMPR-314).

### M4 — the non-convention

The verification manifest is **in-repo**, at `.ampere/verify.yml`. Tickets do
not point at it, name it, or inline it. Written down here only so nobody
reinvents a `verify:` field: a manifest pointer on a ticket would be a second
copy of a fact the repo already states, and the two would drift the first time
a branch changed its own gates.

### M5 — `claim:` and `release:`

```
claim:AMPR-305:supervisor-7f3a
release:AMPR-305:supervisor-7f3a
```

One header line, three colon-separated fields, no body, either form. A claim is
posted **before** the state transition, so the server's timestamp on it is the
claim's position in the total order; earliest timestamp wins, comment id breaks
ties.

A claim is **live** until a `release:` naming the same issue and instance follows
it in that order, and only live claims arbitrate. The retraction exists because
comments are append-only: without it a supervisor that died holding a ticket
would stay the earliest claimant forever, and the ticket the AMPR-310
reconciliation pass just put back in the queue could never be claimed again.
Two writers post one: the reconciliation pass, for a dead instance's claim, and
a losing claimant for its own (added in AMPR-310 — a losing claim left live is
promoted to holder the moment the winner's is released).

### M6 — `esc:`

```
esc:AMPR-305:supervisor-7f3a

Both candidate mappings lose a fact the other keeps, and choosing which fact to
lose is a product decision.

Tried: deriving the status from the state category alone (drops the
verdict gate); carrying both (two sources of truth for one ticket).
Needs: a decision on which fact is allowed to be lost.
```

Same header, then one blank line, then free-form markdown. The body *is* the
escalation record: what failed, what was tried, what decision is needed. Posted
together with the `gate:escalated` label — the label is what a query can filter
on, the body is what the human needs to act.

### Model + effort recommendation

The first line of the description, bolded:

```
**Model/effort recommendation:** Sonnet, low — documentation and templates; all decisions are already ratified.
```

Heuristic from the AMPR-288 verdict: stronger models and higher effort for
recon, planning and anything whose output is a decision; smaller models and low
effort for implementation tickets whose decisions are already made. The
rationale clause is not decoration — it is what lets a reader disagree with the
recommendation on the same evidence.

## The `ampere-scope` schema

```yaml
repos:
  - repo: <owner>/<name>
    paths:
      - <repo-relative glob>
exclusive: true
```

| Key | Required | Type | Meaning |
| --- | --- | --- | --- |
| `repos` | yes | non-empty list | one entry per repository the ticket may write |
| `repos[].repo` | yes | `<owner>/<name>` | the repository as the forge names it |
| `repos[].paths` | yes | non-empty list of strings | repo-relative path globs this ticket may write |
| `exclusive` | no, defaults to `true` | boolean | `false` asserts this ticket's writes commute with an overlapping ticket's |

Glob rules, all of them authoring rules rather than matcher features:

- Repo-relative. No leading `/`, no `..`, no absolute paths.
- Write `/**` when you mean a subtree. Do **not** write a bare directory and
  expect it to cover its contents.
- Do not write a glob whose meaning depends on `*` versus `**`. The matchers
  already in this repo do not agree on one — `scripts/validate-concepts.sh`
  normalises `**` to `*` on purpose — so such a glob has an unknown scope.
- Quote any glob that starts with a YAML indicator: `*`, `&`, `!`, `%`, `{`,
  `[`. `- **/*.kt` is a YAML scanner error, not a glob.
- `paths` covers what the ticket **writes**. Reading is unrestricted, so a
  ticket does not declare the files it reads.

### Example 1 — single repo, one module

An implementation ticket adding a Probe and its test:

```ampere-scope
repos:
  - repo: socket-link/ampere
    paths:
      - ampere-core/src/commonMain/kotlin/link/socket/ampere/probe/**
      - ampere-core/src/commonTest/kotlin/link/socket/ampere/probe/**
      - docs/concepts/probe.md
```

No `exclusive` key, so exclusive. The concept cell is in scope because
[AGENTS.md](../../AGENTS.md) makes updating it part of the same diff.

### Example 2 — overlap-safe, `exclusive: false`

```ampere-scope
repos:
  - repo: socket-link/ampere
    paths:
      - ampere-core/src/commonTest/kotlin/link/socket/ampere/canon/CanonProseBoundsTest.kt
exclusive: false
```

Every declared path is a file this ticket **creates**. An overlapping ticket
would have to declare the same new file, which makes it a duplicate rather than
a conflict. That is the whole of the escape hatch: `exclusive: false` is a claim
that concurrent writes in these paths commute, and the only two cases that
qualify are *files this ticket creates* and *appends to a structure with no
shared ordering*.

A "new section at the end of a shared document" looks like the second case and
usually is not — a `CHANGELOG.md` `Unreleased` section is the canonical
counter-example, and is why [AGENTS.md](../../AGENTS.md) bans the file outright.

### Example 3 — multi-repo

A ticket that moves a type down into the framework and updates the consumer
that was declaring its own:

```ampere-scope
repos:
  - repo: socket-link/ampere
    paths:
      - ampere-core/src/commonMain/kotlin/link/socket/ampere/lifecycle/**
      - ampere-core/src/commonTest/kotlin/link/socket/ampere/lifecycle/**
      - docs/concepts/lifecycle-types.md
  - repo: socket-link/phosphor
    paths:
      - src/commonMain/kotlin/link/socket/phosphor/scene/SceneGate.kt
exclusive: true
```

One `repos` entry per repository, each with its own paths. The globs in each
entry are relative to *that* repository's root, so the same `src/**` string can
appear under two repos and mean two different sets of files.

## Invariants

- **A ticket with no `ampere-scope` block has unknown scope, not empty scope.** Unknown scope overlaps everything, so such a ticket can only be dispatched alone. This is what makes lazy retrofitting safe rather than silently unsafe.
- **One `ampere-scope` block per ticket, in the description.** Not in a comment: comments are append-only and a reader would have to decide which of five is current, whereas the description is a single statement that edits in place.
- **The block sits above the Task Sequence.** A ticket description projects onto `CanonWorkItem.description` through `CanonProse.bounded`, which keeps the **first** 8,000 UTF-16 units and drops the rest. A scope block below a long task sequence can be truncated out of the canon projection, and a supervisor reading scope from that projection would see a ticket with no scope — i.e. the fail-safe above, triggered by prose length.
- **`paths` is non-empty.** A repo entry with no paths is a scope nobody can check. A ticket that genuinely touches a whole repository writes `- '**'` and accepts that it conflicts with everything.
- **`exclusive` absent means `true`.** The default is the safe one, because the cost of guessing wrong the other way is two agents writing the same file.
- **A diff may not write outside its ticket's scope.** Discovering more work means widening the block on the ticket first — an edit a human and the Switchboard can both see — and never quietly editing the extra file.
- **At most one `wave:` label.** `WorkSourceLabels.waveId` reads one label at a time, and `readyQueueRule` pushes one wave equality down; a ticket in two waves is offered by two ready queues.
- **No automated path removes `gate:awaiting-verdict` while the ticket is stopped.** `SupervisoryStatusMapping.gateEdit` strips gate labels only as part of a transition to a non-stopped status — which is what acting on a verdict looks like. A supervisor that removed the label to unblock itself would be granting its own verdict.
- **A stop never tidies away another stop.** Moving to `VERDICT_REQUESTED` or `ESCALATED` removes no existing gate label, so a ticket can legitimately carry both; the escalation is the one a human has to act on first.
- **`gate:escalated` and an `esc:` comment are written together.** `SupervisoryStatusMapping.PROTOCOL_STATUSES` names `ESCALATED` and `CLAIMED` as protocols rather than writes, and `LinearWorkSource.markStatus` refuses them, precisely so half an expression cannot be written. The label alone hands someone a stopped ticket and no reason.
- **An issue identifier and an instance id carry no colon and no newline.** Both are enforced in code (`SupervisoryComment.validateIssue`, `SupervisorInstanceId.init`), because an ambiguous claim comment is a lost race that reads as a won one.
- **`claim:` and `release:` comments never carry a body.** `SupervisoryComment.parse` returns null for one that does; a malformed claim that parsed anyway would enter arbitration with a guessed field, and a misread release retracts a claim nobody retracted.
- **A `release:` retracts only its own instance's claims, and only the ones it follows.** Scoped per instance because a release says "*my* claim is off" — a race loser tidying up must not free the winner's ticket — and ordered because a re-claim posted after a release is live again.
- **No ticket field points at `.ampere/verify.yml`.** M4.
- **Every convention has to survive a public mirror.** Each ticket and comment syncs to a public GitHub issue (verified). The formats here carry no prose the framework composes, and an escalation body is screened against `WorkSourceIssueSink.forbiddenTerms` before it leaves.
- **Where this file and the adapter disagree, the adapter is right and this file is a bug.** The prefixes and labels are a wire format between supervisor processes; a rename makes every in-flight claim read as "nobody claimed this" rather than as a parse error.

## Common operations

- **Author a dispatch-ready ticket** — copy [`docs/templates/ticket-description.md`](../templates/ticket-description.md), replace every `<…>`, add exactly one `wave:<id>` label, add no gate label.
- **Declare scope for a ticket you are about to file** — list the files you expect to write, not the module they live in. If you cannot name them, the ticket is a recon ticket and its scope is its deliverable (`.context/` or a doc path), not the code it is about.
- **Widen a scope mid-flight** — edit the block on the ticket, then continue. If the ticket is already dispatched, that edit is also the signal to whoever is holding an overlapping one.
- **Mark a verdict gate** — add `gate:awaiting-verdict`; move the ticket to the review state. Leave the wave label alone.
- **Give a ticket back** — post `release:<issue>:<instance>` and then revert the state to the queued one, in that order: the comment is the durable half, so a process killed in between finds its own release on the next run and finishes the revert rather than commenting twice. `LinearWorkSource.release` is the whole protocol.
- **Escalate** — post `esc:<issue>:<instance>` with a body that answers *what failed*, *what was tried*, *what decision is needed*, and add `gate:escalated`. Do not change the state: a ticket escalates from wherever it stopped, and overwriting the state destroys the record of where that was.
- **Check whether a ticket is ready** — `wave:` label present, state is the queued one, neither member of `WorkSourceLabels.GATES` present, no open blocking relation. The first two push down to the server; the rest are client-side.

## Anti-patterns

- **A glob starting with `*`, unquoted.** `- **/*.kt` raises a YAML scanner error, so the whole block fails to parse and the ticket reads as having *no* scope — the one failure mode that looks like success. Quote it.
- **A bare directory as a path.** `ampere-core/src/commonMain` is one path entry, not a subtree. Write `ampere-core/src/commonMain/**`.
- **Prose scope.** "Touches the probe package and its tests" is not machine-readable, and the Switchboard's only options are to parse English or to treat the ticket as unscoped.
- **`exclusive: false` to get dispatched sooner.** It is a claim about commutativity, not a priority flag. Declaring it falsely converts a refused dispatch — a visible, cheap outcome — into a lost write.
- **Scoping by module because the file list is long.** A long file list is information; a module glob throws it away and conflicts with every other ticket in that module.
- **A `gate:` marker written as prose.** "BLOCKED: waiting on verdict" in the title or description is invisible to `readyQueueRule`, so the ticket stays in the ready queue and gets dispatched while a human is still deciding.
- **A second `wave:` label to put a ticket in two waves.** See *Invariants*. If it belongs to both, it belongs to the earlier one.
- **Reading `gate:awaiting-verdict` as "needs code review".** It is the [LifecycleTypes](lifecycle-types.md) sense of a gate: stopped, waiting on an act that has not happened. `VERIFYING` is the review state.
- **Recording the claim in the assignee field.** Every write shares one API identity (verified), so the field cannot distinguish two supervisors, and a claim that cannot be attributed cannot be arbitrated.
- **Transitioning first and commenting after.** The comment's server timestamp *is* the claim. Transition-first produces a ticket that reads back as plain `IN_PROGRESS` and a race nobody won.
- **Reverting another process's transition to take a ticket.** Rule B4 of the AMPR-291 verdict: read state history first, and defer rather than overwrite. An un-reverted ticket is visible mess a reconciliation pass cleans up; a revert that stomps a human's move destroys the record of an intervention.
- **A model/effort line that names no model** — "the strongest available", "whatever is cheapest". The recommendation exists so a reader can disagree with it; a line with no model and no rationale is a line with nothing to disagree with.
- **Adding a `verify:` field to a ticket.** M4 is a non-convention on purpose.
