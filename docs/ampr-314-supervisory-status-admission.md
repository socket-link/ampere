# Supervisory status admission — `CanonWorkStatus` reopened

**Issue:** [#728](https://github.com/socket-link/ampere/issues/728) — Admit four supervisory lifecycle states into `CanonWorkStatus`: Claimed, Verifying, VerdictRequested, Escalated
**Linear:** [AMPR-314](https://linear.app/miley/issue/AMPR-314)
**Author:** @miley
**Date:** 2026-09-27

---

## Executive Summary

`CanonWorkStatus` goes from five members to nine. `CLAIMED`, `VERIFYING`,
`VERDICT_REQUESTED` and `ESCALATED` are admitted; no `CanonType` member, entity or
field changes, so the canon *noun* set is untouched and the count stays at 33.

The gap is the AMPR-289 recon's G2, ratified in that ticket's human verdict
(2026-08-23). Before this change the four states rode only in
`CanonWorkItem.providerStatus` (a free string) and `labels`, so they could not be
matched in typed code, did not survive provider translation, and were invisible to
an eval assertion.

Three things about this admission are different from the AMPR-262 wave and are the
reason this note exists:

| # | AMPR-262 (nouns) | AMPR-314 (status members) |
| -- | -- | -- |
| 1 | Cleared the **intersection** gate: ≥2 real providers ship the noun | Cleared a **translation** bar instead: *no* provider ships any of these as a status. Each is composed by the adapter out of a status plus a label or a comment |
| 2 | The producer is the provider | The producer is the **supervisory workflow**. The provider is only the medium it writes through |
| 3 | The tripwire is `CanonSerializationTest.samples()` | The tripwire is an exhaustive `when` in `SupervisoryStatusMapping.expressionFor` — the first place in the repo where admitting a canon member breaks the build until it is translated |

Three constraints in the ticket turned out not to bind, and one deliverable named
a thing that does not exist. Both are recorded in §5.

---

## 1. The four members

| Member | Wire name | Meaning | Distinction from its neighbour |
| -- | -- | -- | -- |
| `CLAIMED` | `claimed` | An automated worker holds the item and is working it | A **refinement of `IN_PROGRESS`**, not an alternative. The provider cannot tell them apart; the claim is the difference, and the claim is what stops a second worker taking the item |
| `VERIFYING` | `verifying` | Work is complete and definition-of-done gates are executing | **Machine-blocked.** Nobody is being waited on. `VERDICT_REQUESTED` shares its provider status and is human-blocked |
| `VERDICT_REQUESTED` | `verdict_requested` | Stopped at a human-judgment gate, awaiting a verdict | A **planned** stop. Not a failure, not dispatchable |
| `ESCALATED` | `escalated` | Automated handling hit its bounds; a human must intervene, with context attached | An **unplanned** stop — the inverse of `VERDICT_REQUESTED` on exactly that axis. The workflow cannot say what happens next, which is why the expression leaves the item's status where it was |

Wire names are snake_case, matching `in_progress`. They are contracts: a rename
breaks `PlaybackRelay` replay of every trace already recorded. Pinned in
`CanonWorkEntitiesTest.work status wire names are stable`, which now asserts set
equality against `CanonWorkStatus.entries` so a member admitted later has to be
named there.

Position in the enum is lifecycle order, with `CLAIMED` immediately after the
`IN_PROGRESS` it refines. Nothing persists an ordinal — kotlinx encodes enums by
`@SerialName`, and no `.sq` column holds a `CanonWorkStatus` — so the insertion is
safe.

---

## 2. Admission evidence

### Gate 1 — the noun test, restated

The noun test ("would two independent apps exchange it?") does not apply to a
status member. The applicable question is whether **two independent supervisor
processes** would exchange the state, and that is precisely what the claim
protocol does: a claim comment exists so that a *second* process can read it and
stand down. A state only one process can see is a state the protocol cannot use.

### Gate 2 — the intersection test, replaced by a translation bar

No provider ships any of these as a status. That is not a near miss on the
intersection gate; it is a different shape of evidence. The ratified bar from the
AMPR-289 verdict is that each member be **provider-translatable in both
directions**, and translation here means *composition*: a status the provider
does have, plus a label or a comment.

| Member | Linear (verified, AMPR-289) | Jira | GitHub Issues |
| -- | -- | -- | -- |
| `CLAIMED` | "In Progress" (`started`) + a `claim:` comment | a status in category `indeterminate` + a claim comment | `open` + a claim comment |
| `VERIFYING` | "In Review" (`started`) | an `indeterminate` status named "In Review" | `open` + a check-suite label |
| `VERDICT_REQUESTED` | "In Review" + `gate:awaiting-verdict` | `indeterminate` + a gate label (service projects ship "Waiting for customer") | `open` + a gate label |
| `ESCALATED` | *status unchanged* + `gate:escalated` + an `esc:` comment | unchanged + an escalation label | `open` + an escalation label |

The Linear column is the one that is *live*: every row of it is implemented in
`ampere-work-linear` and exercised against a work source modelled on recorded
responses. The Jira and GitHub columns are the translation argument, not shipped
code — no Jira or GitHub binding module exists in this repo, and when one lands
this table is the mapping it should adopt.

**What makes the composition honest rather than a re-run of `providerStatus`:**
the composition is *closed and typed*. `SupervisoryExpression` is a value with a
state name, a category, a label set and a claim bit;
`SupervisoryStatusMapping.expressionFor` maps canon → expression and
`canonStatusFor` maps back. A free-form `providerStatus` string had no second
direction at all.

### Gate 3 — the producer test

Live, and it is the whole reason the reopening was ratified: the work-source
adapter (AMPR-305, merged) and the CLI supervisor built on it. `LinearWorkSource`
is a real `PerceiveSource`/`ExecuteSink` pair over a real MCP Link.

### Gate 4 — the bulk test

Trivially clear. An enum member carries no payload. The 32 KiB projection budget
asserted in `CanonWorkEntitiesTest` is unaffected — the longest new wire value is
`"verdict_requested"`.

---

## 3. The mapping contract

Owned by the adapter, per the ticket: `ampere-core` gains vocabulary, not
translation. `SupervisoryStatusMapping` (`ampere-work-linear`) is the only place
this adapter decides a canonical status.

### Write direction — one-to-one

`expressionFor(status)` is an exhaustive `when` over `CanonWorkStatus`. A member
admitted to the canon and not translated is a **compile error**, which is what the
ticket asked for in place of an `else` branch.

`LinearWorkSource.markStatus(issue, status)` is the typed write. It transitions,
then edits gate labels — in that order, always, because the window it opens
matters: *moved but still gated* reads as stopped, which is safe, while *ungated
but not yet moved* reads as ready, which is not.

It **refuses** `CLAIMED` and `ESCALATED` with
`WorkSourceFailure.StatusNeedsProtocol`, because each is composed with a comment
that does real work. Writing the status half of a claim produces a claim nobody
can see — the ticket reads back as `IN_PROGRESS` — and writing the label half of
an escalation stops a human with no reason attached. `claim()` and `escalate()`
are the entry points that perform those protocols. Same shape as
`ExecuteSink.supportedPreconditions` being empty: the loud refusal is the correct
answer, not a gap to paper over.

### Read direction — many-to-one, with a stated precedence

`canonStatusFor(expression)` also answers for expressions no write of this
adapter's would produce: a `triage` category, a state name the workspace renamed,
a stale gate label on a closed ticket. The order is load-bearing:

1. **A terminal category wins outright.** A closed ticket still carrying a gate
   label is `DONE`. The label is an untidied record; the closure is the provider's
   own statement of finality. Reading it the other way round strands finished work
   in a queue waiting on a human with nothing left to do.
2. **Then the gates, escalation first.** An unplanned stop never tidies away the
   record of a planned one, so a ticket can legitimately carry both, and the
   unplanned one is what a human has to act on.
3. **Then the state name.** `IN_PROGRESS` and `VERIFYING` share the `started`
   category on every provider measured, so the name is the only signal that can
   separate them. This is the second place canon's existing "match the name first,
   fall back to the category" rule bites — the first was `BACKLOG`.
4. **Then the category**, for a state name this build does not know. No signal at
   all is `TODO`, never `BACKLOG`: `TODO` keeps work visible.

A workspace that renames "In Review" therefore *loses* `VERIFYING` and reports
`IN_PROGRESS` — coarse but true — rather than failing. That asymmetry is
deliberate: an unknown state **name** is someone's configuration, while an unknown
**category** is vendor drift, and `WorkItemStatusType.fromWire` still refuses the
latter loudly.

### Preserve-and-merge, applied to labels

`gateEdit(target, present)` computes the label write, under two rules:

- **Only `WorkSourceLabels.GATES` are ever removed.** A ticket's `wave:` tag and
  its topic labels are not this mapping's vocabulary. This is why the sink offers
  `AddLabels`/`RemoveLabels` and never the provider's whole-set `labels` argument.
- **A stop does not tidy away another stop.** Moving to `VERDICT_REQUESTED` or
  `ESCALATED` removes nothing. Moving anywhere else clears every gate the ticket
  carries — without which a finished ticket keeps a gate label forever and the
  ready queue never offers it again.

`remove` names only gates actually present: whether this work source treats
removing an absent label as a no-op is **not verified**, so the edit does not rely
on it.

### The one member a projection cannot produce

`ReadableCanonAdapter.project` is a function of **one** native object. A claim is
a *comment* — a different object, reached by a different tool — so
`WorkItemCanonAdapter` passes no claim evidence and a claimed ticket projects as
`IN_PROGRESS`: true, just coarse, and the honest answer for a read that did not
look.

`LinearWorkSource.readCanonWorkItem` is the read that does look, and
`SupervisoryStatusMapping.claimEvidenceMatters` keeps it cheap — the comment scan
is paid for only where the claimless answer is `IN_PROGRESS`, which is the only
status `CLAIMED` refines. A queued, gated or closed ticket costs exactly one read.

The refinement is a `copy(status = …)` on the projected entity rather than a new
`project` parameter, deliberately: widening the framework's read contract to carry
a provider-specific second read would put `ampere-core` in the business of knowing
that some providers keep their claims in comments.

**The consequence for every reader, stated once:** `IN_PROGRESS` does not mean
*not claimed*. It means the adapter had no claim evidence in hand. Same
ambiguity-by-design as a nullable `CanonId` cross-reference.

---

## 4. Test evidence

| Property | Where |
| -- | -- |
| `canonStatusFor(expressionFor(s)) == s` for all nine members | `SupervisoryStatusMappingTest.every canon status round-trips through its provider expression` |
| The ratified composition per member, as a literal table | `…the four supervisory members express the ratified composition` |
| Terminal category beats a stale gate label | `…a terminal category outranks a stale gate label` |
| Escalation beats a verdict gate | `…an escalation outranks a verdict gate` |
| Claim evidence moves nothing outside the in-progress state | `…claim evidence changes the answer only in the in-progress state` |
| Only gate labels are ever removed | `…only gate labels are ever removed` |
| The dispatch lifecycle and the vocabulary agree | `…the dispatch lifecycle and the canon mapping are one table` |
| **Write then read through the measured work source**, all nine | `CanonStatusLifecycleTest.every canon status survives a write and a read through the work source` |
| A claim is the entire difference between `IN_PROGRESS` and `CLAIMED` | `CanonStatusLifecycleTest.a claim is what makes a claimed ticket readable` |
| `markStatus` refuses the two protocol statuses and writes nothing | `…markStatus refuses the statuses whose expression is a protocol` |
| Wire names stable, set-equal to `entries` | `CanonWorkEntitiesTest.work status wire names are stable` |

The end-to-end round trip is the stronger of the two round-trip tests and the one
that matters: a table can agree with itself while the protocol that realises it
does not. It walks one ticket through all nine statuses in lifecycle order —
`markStatus` for the seven writable ones, `claim()` for `CLAIMED`, `escalate()`
for `ESCALATED` — and reads each back.

---

## 5. What the ticket assumed, and what is actually there

| Ticket said | Reality |
| -- | -- |
| "*Full test suite +* `verifyAmpereIsolation` *green*" | **No such Gradle task exists.** The merge-blocking neutrality check is `verifyCoreNeutrality` (`ampere-core/build.gradle.kts:365`), wired into `check`. It is unaffected by this change: `CanonWorkStatus` references no platform SDK |
| "*eval fixtures involving WORK_ITEM updated*" | **There are none.** Nothing under `ampere-eval` mentions `WORK_ITEM`, `CanonWorkItem` or `CanonWorkStatus`; the only fixtures are `ampere-core`'s serialization samples and `ampere-work-linear`'s recorded responses, both updated here |
| "*exhaustive* `when` *sites updated intentionally, not with* `else`" | There were **no** exhaustive `when` sites over `CanonWorkStatus` to update — the adapter's `when` was over `WorkItemStatusType`, the provider's vocabulary. So this change *creates* the tripwire the constraint assumes: `SupervisoryStatusMapping.expressionFor` is the first exhaustive `when` over `CanonWorkStatus` in the repo, and it is what makes the next member admission break the build |
| "*no speculative additions (e.g. no "Paused", no "Blocked")*" | Held. Nothing beyond the four was added. `WorkSourceStates` names the six work-source state names the adapter already wrote as literals — provider vocabulary, not canon |

---

## Appendix

### Wire names

`CanonWorkStatus`: `backlog`, `todo`, `in_progress`, `claimed`, `verifying`,
`verdict_requested`, `escalated`, `done`, `cancelled` — note `cancelled`
(double-l), matching `CanonServiceStatus.CANCELLED`.

### Files

| File | Change |
| -- | -- |
| `ampere-core/…/canon/CanonRing3Entities.kt` | four members + per-member KDoc + the two-kinds-of-member section |
| `ampere-work-linear/…/SupervisoryLifecycle.kt` | `WorkSourceStates`, `SupervisoryExpression`, `SupervisoryStatusMapping`, `GateEdit`; `SupervisoryState` gains `canonStatus` |
| `ampere-work-linear/…/WorkItemCanonAdapter.kt` | `status` composed through the mapping instead of derived from `statusType` alone |
| `ampere-work-linear/…/LinearWorkSource.kt` | `readCanonWorkItem`, `markStatus`; `requestVerdict` delegates |
| `ampere-work-linear/…/WorkSourceFailure.kt` | `StatusNeedsProtocol` |
| `docs/concepts/domain-canon.md` | the enum-member admission invariant and two anti-patterns |

### Existing patterns followed

| Pattern | Source |
| -- | -- |
| Coarse lifecycle + verbatim `providerStatus` | `CanonServiceStatus`, AMPR-252 |
| Match the provider's status name first, fall back to its category | `CanonWorkStatus` KDoc, AMPR-262 §2 |
| Ambiguity-by-design stated on the reader's side | `CanonWorkItem.projectId`, AMPR-266 |
| Additive label edits, never a whole-set write | `WorkSourceCommand.AddLabels`, AMPR-305 |
| A loud typed refusal beats an emulated capability | `ExecuteSink.supportedPreconditions`, AMPR-312 |

---

## Next Steps

1. Extend `CanonWorkStatus` with the four members and per-member KDoc. ✔
2. Add the bidirectional mapping in `ampere-work-linear`. ✔
3. Migrate the adapter's read path and add `markStatus`/`readCanonWorkItem`. ✔
4. Round-trip tests, table-level and end-to-end. ✔
5. Update `docs/concepts/domain-canon.md`. ✔
6. When a Jira or GitHub binding module lands, adopt §2's translation table and
   assert its own round trip. Not this ticket.

---

*Document generated as part of the canon admission for issue #728*
