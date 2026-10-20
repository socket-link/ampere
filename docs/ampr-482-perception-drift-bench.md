# Perception-drift bench — can a decision model tell which changes in the Field bear on a goal?

**Issue:** [#1068](https://github.com/socket-link/ampere/issues/1068) — W0 · N12 · Spike (STOP gate): can a decision model tell which changes in the Field bear on a goal?
**Linear:** [AMPR-482](https://linear.app/miley/issue/AMPR-482)
**Author:** @miley
**Date:** 2026-10-20

---

## Executive Summary

After each executed cycle, a hosted run under `PerceiveCadence.Record` asks its host seat's
decision transport `RunQuestions.perceptionDrift` — *would a fresh perception read the situation
differently?* — over `RunDrift.asDecisionState(400)`: the goal, the cycle, what the host last
perceived, what moved in the Field since (`FieldDelta.between` of the PERCEIVE snapshot and the
after-cycle one), and the cycle's results (AMPR-479, AMPR-480, AMPR-481). Nothing reads the
answer. Before a consumer may pass a `moveAbove` to a `WhenDrifted` cadence (epic AMPR-478 row
N12), this spike measures **that question on that state shape**, through the adapters as shipped.

The costly error is the **opposite** of the goal-met bench's: **false settled** — answering
"beside the goal" when a change bore on it, which leaves the run perceiving a world that has
moved. A **false moved** costs one needless re-perceive. So the operating point is *zero false
settled at the highest settled recognition*, and `cannot_tell` in the choice form sits on the
bears side.

**The corpus is 112 cases, half bears and half beside, labelled by construction**, every state
rendered by production's own function over real `Field` values folded by `FieldDelta.between`.
Two things the construction found before any model was asked:

- **Production already answers three of the seven beside kinds.** `own_run_actions_only`,
  `cosmetic_reorder` and `nothing_changed` render **byte-identical** states, because the fold
  leaves every seat's own Actions out and keys every list on identity. A judge never sees the
  run's own work or a re-read in another order; what it is asked to tell is the other four beside
  kinds — an unrelated belief, an unrelated observation, an unrelated commitment, bookkeeping
  subjects — from the seven bears kinds.
- **The state cannot show what a commitment is about.** A commitment renders as its committer's
  name, its `ticketId` and its due date, never its `goal`. On a consumer whose ticket ids are
  opaque, the two commitment kinds are unanswerable from the state; the corpus uses descriptive
  slugs so they can be measured at all.

The two deterministic references, measured first (the CI gate):

| Arm | False settled | False moved | What it is |
| -- | -- | -- | -- |
| `empty-only` | **0 / 56 (0.0%)** | 32 / 56 (57.1%) | bears unless `changes` is empty |
| `own-and-cosmetic` | **0 / 56 (0.0%)** | 32 / 56 (57.1%) | bears unless empty, own-only or a cosmetic re-render — on a production state, the same rule |

Both are never false-settled, by construction, and both re-perceive on every beside change that
has content: zero false settled at 42.9% settled recognition, which is the rule "re-perceive
whenever anything moved" with the fold doing the work.

Then the live arms, each through the adapter as shipped, two question forms, three repeats:

| Arm | False settled | False moved | Note |
| -- | -- | -- | -- |
| `claude-sonnet-5`, `noul` (shipped form) | **58 / 168 (34.5%)** | 1 / 168 (0.6%) | self-reported, so no sweep |
| `claude-sonnet-5`, `choice` | **63 / 168 (37.5%)** | 1 / 168 (0.6%) | `cannot_tell` counted as bears |
| `jev-1.13`, `clef`, `clef-flash` | not run | not run | `OPENROUTER_API_KEY` / `CLOUDFLARE_*` absent from this machine; harness built and tested, one command to run |

**Sonnet reads text and misses structure.** It is perfect on every beside kind (one needless
re-perceive in 336 calls) and perfect on the four bears kinds whose change carries text about the
target — a lesson, a constraint, an action, an observation: 0 false settled in 192 calls. Every
one of its false-settled calls is in the three kinds whose change is *structural*: a goal the plan
depends on abandoned (21 of 24 noul calls read as settled), a commitment opened on the goal's
ticket (16 of 24) and one resolved (21 of 24). The state renders a goal's description and its two
states, and a commitment's committer, ticket and date — and nowhere how either relates to the
run's goal. The judge is being asked to infer a relation the renderer left out, and it does not.

So on the costly error the production rule beats the model: `empty-only` (the fold, then
"re-perceive if anything moved") is never false-settled; Sonnet is false-settled on one bears
call in three. No measured arm ran, so no `moveAbove` below 1.0 holds zero false settled on this
corpus, and the one that does is the deterministic rule's.

The report ends at a STOP (§5): three plain-language questions for Miley, each with its
evidence and a recommended default.

---

## 1. The bench

The decide bench of AMPR-422 (`link.socket.ampere.eval.decide`, `ampere-eval` `commonMain`:
`DecisionCase`, `DecisionArm`, `DecisionBench`, `DecisionBenchReport`, `PlaybackDecisionClient`)
is reused unchanged. Two things are new:

| Type | What it is |
| -- | -- |
| `PerceptionDriftForm` | The shipped `noul` (`RunQuestions.perceptionDrift`), and the three-option `choice` (`bears`, `beside`, `cannot_tell`) derived from its wording. `settledKeys` is what the bench is handed as `positiveKeys`; `bearsKeys` is everything else, so `cannot_tell` is bears. |
| `PerceptionDriftReport` | The generic report read in this bench's words. The bench's "positive" side is *settled*, so `DecisionBenchReport`'s false met is **false settled** and its zero-false-met operating point is the **zero-false-settled** one; this object renders them so and puts the threshold on `P(bears)`. |
| `PerceptionDriftArms` | `empty-only` and `own-and-cosmetic` as `DeterministicDecisionClient`s, reading only the counts under `changes` and the `by` of each action. |

The **zero-false-settled operating point** is: the lowest `P(bears)` any bears sample reached
(`moveAbove`; the rule being *bears when P(bears) ≥ moveAbove*, so no bears sample reads as
settled), and beside it the share of beside samples strictly below it — what the arm still
recognises as settled once it is forbidden to be wrong the costly way. Computed on the mean of
each case's repeats and on every single call, as AMPR-422 does. The sweep is 21 thresholds on
`P(bears)`, `0.00 … 1.00`. For both forms `P(bears) = 1 − P(settled)` because `cannot_tell` is
on the bears side. `BandFitGuard` governs every sweep: a self-reported arm gets none (AMPR-381 J5).

## 2. The corpus

`ampere-eval/src/jvmTest/resources/decide/perception-drift/cases.json`, written by
`PerceptionDriftCorpus` (jvmTest) through `./gradlew :ampere-eval:generatePerceptionDriftCorpus`
and never by hand. Every state is `RunDrift.asDecisionState(400)` — the run's default clip —
over a `FieldDelta.between(before, after, ownActors = every seat)` of two real `Field` values,
with the `names` lookup built as `RosterHostedRun` builds it (the Subjects and Goals of both
snapshots), so a case is byte-for-byte what a hosted run hands its transport.

**Eight families × fourteen kinds.** The eight goal families of AMPR-422's corpus, each with one
goal, one prior perception (the sentence the host seat wrote at PERCEIVE), one cycle's two
results, and one *before* Field: the run's own seats (`Host`, `Worker`) and three other subjects
(`Scheduler`, `Reviewer`, `Platform team`); three goals held open — the run's own, a dependency
of its plan, and an unrelated one; the host's perception as a belief; and one commitment open
on the goal's ticket. Every case of a family shares all of that; what separates a bears case
from a beside one is only what the kind changed.

| Family | Goal |
| -- | -- |
| `find-and-summarise` | Find the latest version of the onboarding handbook and summarise its key points in three bullets |
| `schedule-and-notify` | Schedule the design review with the three platform engineers for 30 minutes next week and notify everyone invited |
| `file-and-link` | File the signed office lease under Records/2026 and link it from the records overview page |
| `convert-and-save` | Convert the meeting notes to PDF and save the PDF beside the original file |
| `compare-and-recommend` | Compare the three storage plans on monthly cost and the stated requirement, and recommend one with the reason |
| `fix-failing-check` | Make the date-parsing test pass again without disabling it |
| `tidy-and-report-counts` | Archive everything older than 90 days in the shared downloads folder and report how many items were archived |
| `draft-and-send` | Draft a note to the facilities team about the broken door sensor and send it |

| Kind | Bears | What the after Field holds that the before did not | What renders |
| -- | -- | -- | -- |
| `goal_state_changed` | yes | the dependency goal moved `OPEN` → `ABANDONED` | one entry under `goals` |
| `commitment_on_goal_opened` | yes | another subject opened a commitment on the goal's ticket | one under `commitments.opened` |
| `commitment_on_goal_resolved` | yes | the commitment open on the goal's ticket is `RESOLVED` | one under `commitments.resolved` |
| `lesson_about_goal_subject` | yes | another subject's `lesson` about the goal, naming the target | one under `beliefs` |
| `other_actor_on_goal_target` | yes | another subject's Action on the target, succeeded | one under `actions` |
| `observation_of_target_changed` | yes | an observation that the target changed | one under `observations` |
| `constraint_added` | yes | another subject's `plan` or `task` belief about the goal | one under `beliefs` |
| `unrelated_subject_belief` | no | another subject's `lesson` about the unrelated goal | one under `beliefs` |
| `own_run_actions_only` | no | two Actions, by `Host` and `Worker` | **nothing**: the fold leaves own actors out |
| `cosmetic_reorder` | no | the same Field with every list reversed | **nothing**: the fold keys on identity |
| `observation_of_unrelated_entity` | no | an observation of an entity the goal is not about | one under `observations` |
| `unrelated_commitment` | no | another subject's commitment on an unrelated ticket | one under `commitments.opened` |
| `bookkeeping_subjects_added` | no | two subjects registered | two under `subjects` |
| `nothing_changed` | no | nothing | nothing |

So 56 bears and 56 beside, and in every family three beside cases render one state. That is a
deviation from the ticket's "no two states equal except the `cosmetic_reorder` pair", and it is
the right one: making `own_run_actions_only` render differently would mean a state production
never produces. The validator pins the triple instead.

Two more things the construction decided:

- **A commitment is told apart by its ticket id's text alone.** `RunDrift.asDecisionState`
  renders `by`, `on` (the `ticketId`) and `due`, and not the commitment's `goal` reference. The
  corpus uses slugs — `records/file-signed-office-lease` against `kitchen/cleaning-schedule` — so
  the two commitment kinds carry a signal. A consumer whose tracker issues `AMPR-9` gives the
  judge none, and the renderer would need to add the goal's name for the question to be
  answerable there. Out of scope here; worth a ticket.
- **An observation's `about` is a raw reference.** Production's `names` hold Subjects and Goals
  only, so an observation of an entity renders as `canon/link-1/entity-5`, and its content is
  the only thing a judge can read. The corpus renders the same: opaque reference, content that
  names the target or does not.

Validation: `PerceptionDriftCorpusValidatorTest` — every committed case obeys its kind's rule
against its `nothing_changed` sibling (same goal, perception and cycle results; exactly one
list under `changes` touched, holding what the kind says), the render-empty triple is equal and
nothing else is, every kind and family is present, no id or timestamp reaches the state, the
render-empty kinds are empty *because of the fold* (the generator's own `after` Fields are
checked against a fold with no own actors), and `encode(generate())` equals the committed bytes.

Constructed data only: no person, product or company is named.

## 3. Results

### 3.1 The deterministic arms (CI gate: `PerceptionDriftDeterministicBenchTest`)

Noul form, one repeat, 112 calls each. One-hot, so both are fittable.

| Arm | Calls | False settled | False moved | Bears called bears | Beside called settled | Missing |
| -- | -- | -- | -- | -- | -- | -- |
| `empty-only` | 112 | 0 (0.0%) | 32 (57.1%) | 56 | 24 | 0 |
| `own-and-cosmetic` | 112 | 0 (0.0%) | 32 (57.1%) | 56 | 24 | 0 |

Per kind: every bears kind is 0% false settled; the three render-empty beside kinds are 0% false
moved; the four beside kinds with content (`unrelated_subject_belief`,
`observation_of_unrelated_entity`, `unrelated_commitment`, `bookkeeping_subjects_added`) are
100% false moved. Zero-false-settled operating point: `moveAbove = 1.00`, settled recognised
42.9% (24 of 56), on single calls and on the mean of repeats alike.

**`own-and-cosmetic` is `empty-only`.** The two rules agree on every one of the 112 cases in both
forms, because the fold has already removed what the second rule's extra clauses look for. The
rule a consumer could ship with no judge is therefore the simplest one: re-perceive whenever the
delta is non-empty. It is never false-settled and it re-perceives needlessly on 57% of the beside
cases here — every change that has content and is beside the goal.

These numbers follow from the construction and are asserted as such in the gate.

### 3.2 The live arms

Run on 2026-10-20 from a developer machine with `./gradlew :ampere-eval:perceptionDriftBenchLive`.
Three repeats, both forms, 336 calls per arm (168 on bears cases, 168 on beside).

| Arm | Ran | Why not |
| -- | -- | -- |
| `claude-sonnet-5` (noul, choice) | yes | — |
| `jev-1.13` via OpenRouter (noul, choice) | **no** | `OPENROUTER_API_KEY` absent from this machine's environment and `local.properties` |
| `clef`, `clef-flash` on Workers AI (noul, choice) | **no** | `CLOUDFLARE_ACCOUNT_ID` / `CLOUDFLARE_AUTH_TOKEN` absent |

The ticket's context says the keys now exist; they are not on the machine this ran on. The
harness binds all three arms, is tested against a mock engine, and runs them with one command
and three environment variables. **No arm with a distribution was measured**, so, as in
AMPR-422, no sweep and no operating point below `moveAbove = 1.0` exists yet.

#### 3.2.1 `claude-sonnet-5` through `ModelBackedDecisionClient`

Both forms, three repeats, 336 calls each, every call answered (AMPR-463's parser fix holds: no
fenced reply failed). The adapter is **self-reported** — a generative model asked for JSON, no
distribution — so by J5 there is no sweep and no operating point for this arm.

| Form | False settled (own answer) | False moved | Missing | Same answer on all 3 repeats | Majority of 3: false settled / false moved |
| -- | -- | -- | -- | -- | -- |
| `noul` (shipped) | **58 / 168 (34.5%)** | 1 / 168 (0.6%) | 0 | 105 / 112 (93.8%) | 20 / 56 (35.7%) / 0 / 56 |
| `choice` | **63 / 168 (37.5%)** | 1 / 168 (0.6%) | 0 | 106 / 112 (94.6%) | 22 / 56 (39.3%) / 0 / 56 |

The two forms do not trade the two errors here as they did on goal-met: the choice form is
slightly worse on the costly side and no better on the other. `cannot_tell` was answered **once**
in 336 choice calls, on a beside case (`find-and-summarise/bookkeeping_subjects_added`), which
is also the one false-moved call of each form: two subjects named `Index bot` and `Docs team`
registered under a goal about the handbook on the shared drive.

**Per kind** (false settled over 24 bears calls per kind, false moved over 24 beside calls):

| Kind | noul false settled | choice false settled | noul false moved | choice false moved |
| -- | -- | -- | -- | -- |
| **`goal_state_changed`** | **21 (87.5%)** | **18 (75.0%)** | — | — |
| **`commitment_on_goal_opened`** | **16 (66.7%)** | **23 (95.8%)** | — | — |
| **`commitment_on_goal_resolved`** | **21 (87.5%)** | **22 (91.7%)** | — | — |
| `lesson_about_goal_subject` | 0 (0.0%) | 0 (0.0%) | — | — |
| `other_actor_on_goal_target` | 0 (0.0%) | 0 (0.0%) | — | — |
| `observation_of_target_changed` | 0 (0.0%) | 0 (0.0%) | — | — |
| `constraint_added` | 0 (0.0%) | 0 (0.0%) | — | — |
| `unrelated_subject_belief` | — | — | 0 (0.0%) | 0 (0.0%) |
| `own_run_actions_only` | — | — | 0 (0.0%) | 0 (0.0%) |
| `cosmetic_reorder` | — | — | 0 (0.0%) | 0 (0.0%) |
| `observation_of_unrelated_entity` | — | — | 0 (0.0%) | 0 (0.0%) |
| `unrelated_commitment` | — | — | 0 (0.0%) | 0 (0.0%) |
| `bookkeeping_subjects_added` | — | — | 1 (4.2%) | 1 (4.2%) |
| `nothing_changed` | — | — | 0 (0.0%) | 0 (0.0%) |

**The split is exact.** The four bears kinds whose change carries text about the target are
never missed; the three whose change is a structural fact — a goal's state moved, a commitment
opened or resolved — are missed two times in three or worse. Reading the three:

- `goal_state_changed` renders `{"goal":"Restore search indexing on the shared drive","from":"OPEN","to":"ABANDONED"}`
  under a goal to find and summarise the handbook on that drive. The state says a goal was
  abandoned; it does not say the run's plan depends on it. The corpus wrote the dependency goal as
  the goal's own precondition and the model still read it as another subject's business.
- The two commitment kinds render `{"by":"Reviewer","on":"docs/summarise-onboarding-handbook","due":"2026-10-27"}`.
  The ticket slug is the only tie to the goal, and a slug was not enough; on an opaque ticket id
  there would be nothing at all (§2).

This is the goal-met finding in a new place (AMPR-422 §3.2.1, "notices text, misses structure"):
the kinds a judgment exists for are the ones where the evidence is a relation, and the state
does not render the relation. Per family the misses are even (4–9 of 21 bears calls in every
family, both forms), so it is the kind, not the goal.

**Self-reported confidence separates here, and still cannot be fitted.** On the noul form,
answers marked `medium` (0.5) were wrong 39 of 61 times and `high` (0.75) 20 of 275; 38 of the 58
false-settled calls said `medium`. On the choice form, `medium` wrong 32 of 49, `high` 32 of 287,
31 of 63 false-settled at `medium`. Unlike goal-met, the word carries signal — a rule "move unless
a *high* beside" would cut the noul false-settled count from 58 to 20 (11.9%) at the cost of 61
re-perceives. It is still a word the model said about itself, J5 forbids a band on it, and 20 of
168 is not zero; it is evidence that a measured arm has something to measure.

**Latency and cost** (catalog prices, `provider_pricing.v1.json` 2026-09-20; tokens as the
provider reported them):

| Form | p50 | p95 | Mean tokens in / out | USD per 1,000 calls | Spend for this run |
| -- | -- | -- | -- | -- | -- |
| `noul` | 1,332 ms | 3,696 ms | 757 / 102 | 2.54 | 0.85 |
| `choice` | 917 ms | 3,901 ms | 790 / 85 | 2.43 | 0.82 |

One drift question per executed cycle at these figures adds about a second and a quarter of a
cent to a cycle; the state is ~40% longer than the goal question's, since it carries the
perception and the changes beside the results.

#### 3.2.2 What the corpus construction found about the state

Two things a judge cannot read from `RunDrift.asDecisionState` as shipped, both of which the
numbers above land on:

1. **A goal's relation to the run's goal.** `GoalChange` renders description, `from`, `to`. A
   goal's `parent`, and any `Rule.Dependency` over goals (GoalBranch), are not rendered, so a
   dependency abandoned and a stranger's goal abandoned read the same. The corpus has no
   "unrelated goal changed" kind precisely because, rendered, it would be indistinguishable from
   `goal_state_changed`, and a label the state cannot support is not a label.
2. **What a commitment is on.** `Commitment.goal` is not rendered; `ticketId` is. Opaque ticket
   ids make both commitment kinds unanswerable; descriptive ones (this corpus) were still missed
   two times in three.

Both are renderer changes in `ampere-core` (`RunDrift.asDecisionState`), out of this ticket's
scope and the first thing to do before this corpus is measured again: render `"depends_on":true`
(or the parent goal's description) on a goal change, and the goal's description on a commitment
that has one. The validator's rule for the two kinds would then read the new field rather than
the slug.

## 4. Technical Guidelines

- **Adapters as shipped.** The model-backed arm renders exactly the prompt
  `ModelBackedDecisionClient` renders; the hosted arms would POST exactly the body
  `HostedSystemOneDecisionClient` builds. The metering and retry wrappers are AMPR-422's and
  change no request.
- **State function from production.** Every state is `RunDrift.asDecisionState`, never a copy;
  the delta is `FieldDelta.between` with `ownActors` as `RosterHostedRun` passes it. A change to
  either moves every digest and the recorded test says so.
- **Question from `RunQuestions`.** The `choice` form's texts are derived from the shipped noul;
  rewording the question there rewords both forms and invalidates every recording.
- **Nothing in CI reaches the network.** `jvmTest` runs the two deterministic arms and replays
  the recordings. `perceptionDriftBenchLive` does nothing unless its task set the directory
  property; running it is the explicit approval for the calls it makes.
- **Convict, not acquit.** A failure on a constructed case is real. A pass on 112 constructed
  cases justifies a record-only period and a next ticket, not acting in production.

## Appendix

### A. Running it

```bash
./gradlew :ampere-eval:jvmTest --tests "*PerceptionDrift*"                      # gates, offline
./gradlew :ampere-eval:generatePerceptionDriftCorpus                            # regenerate; empty diff expected
./gradlew :ampere-eval:perceptionDriftBenchLive -PdecideDryRun=true             # print one request, send nothing
OPENROUTER_API_KEY=… CLOUDFLARE_ACCOUNT_ID=… CLOUDFLARE_AUTH_TOKEN=… ./gradlew :ampere-eval:perceptionDriftBenchLive
```

The goal-met arms of AMPR-422 are a separate ticket; `./gradlew :ampere-eval:decideBenchLive`
with the same three variables runs them.

### B. Where things are

| | |
| -- | -- |
| Forms, report words, deterministic arms | `ampere-eval/src/commonMain/kotlin/link/socket/ampere/eval/decide/PerceptionDrift{Form,Arms}.kt` |
| Corpus generator | `ampere-eval/src/jvmTest/kotlin/link/socket/ampere/eval/decide/PerceptionDriftCorpus.kt` |
| Corpus | `ampere-eval/src/jvmTest/resources/decide/perception-drift/cases.json` |
| Recordings | `ampere-eval/src/jvmTest/resources/decide/perception-drift/recordings/<model>.<form>.jsonl` |
| Live runner | `ampere-eval/src/jvmTest/kotlin/link/socket/ampere/eval/decide/PerceptionDriftBenchLive.kt` |
| Gates | `PerceptionDriftCorpusValidatorTest`, `PerceptionDriftDeterministicBenchTest`, `PerceptionDriftRecordedBenchTest` |
| The question and the state | `ampere-core/src/commonMain/kotlin/link/socket/ampere/propel/RunQuestions.kt`, `RunDrift.kt` |

### C. Price source

Per-token prices for the model-backed arm come from this repository's catalog,
`ampere-core/src/commonMain/composeResources/files/provider_pricing.v1.json` (`publishedAt`
2026-09-20): `claude-sonnet-5` USD 2.00 in / 10.00 out per million tokens.

---

## 5. STOP — three questions for Miley

Written for a reader who has not followed the N-register. Each has the evidence beside it and a
recommended default. Nothing below is decided here; no ticket that lets a drift judgment act is
drafted before these are answered.

### Q1 — What `moveAbove` should the consumer start with, and on which arm?

*What it means.* `PerceiveCadence.WhenDrifted(moveAbove)` (W1) would re-run PERCEIVE when the
drift judgment's `P(bears)` is at or above `moveAbove`. The number is the zero-false-settled
operating point of a *measured* arm: the lowest `P(bears)` any bears case reached.

*Evidence.* No measured arm ran: the three System One arms need keys this machine does not have.
The only arm with a distribution is the deterministic `empty-only`, one-hot, whose operating point
is `moveAbove = 1.00` at 42.9% settled recognised — that is just the rule "re-perceive whenever
the delta is non-empty", and it is never false-settled. The one model that ran is self-reported
(no threshold may be fitted, J5) and false-settled on 34.5% of bears calls at its own answer;
every miss is a structural kind the state does not render the relation for (§3.2.2).

*Cost of being wrong.* A `moveAbove` too high leaves the run perceiving a world that has moved —
the costly error, and one the run cannot see from inside. Too low costs one PERCEIVE call per
changed cycle.

*Recommended default.* **No model threshold yet. Start `WhenDrifted` on `empty-only` (any
`moveAbove` in `(0, 1]`, since the rule is one-hot), which is zero false settled by construction
and re-perceives on every non-empty delta.** Fit the first model number only after (a) the
renderer carries a goal's relation and a commitment's goal (§3.2.2), (b) the corpus is
regenerated on that state, and (c) a measured arm — Jev or Clef, one command with the keys — is
recorded on it. A number from Sonnet's self-report is not a number.

### Q2 — Does `cannot_tell` stay on the bears side?

*What it means.* In the choice form, a model may answer `cannot_tell`. Counting it as bears means
"not sure" re-perceives; counting it as settled means "not sure" lets the run carry on.

*Evidence.* Sonnet answered it once in 336 choice calls, on a beside case, so on this corpus the
choice costs one needless re-perceive and protects nothing — but it protected nothing only
because the model never used it on the 63 bears calls it got wrong (it said `beside` with
`medium` confidence instead). A model that *does* say "cannot tell" on a structural change is
exactly the one this placement is for.

*Cost of being wrong.* On the bears side, a needless PERCEIVE. On the settled side, the costly
error wearing a different name.

*Recommended default.* **Yes, it stays on the bears side.** It is the only placement under which
the costly error cannot come from an answer that admits uncertainty, and the measured cost here
is one call in 336.

### Q3 — Is `own-and-cosmetic` good enough to ship a cadence with no judge?

*What it means.* A `WhenDrifted` that consults no model: re-perceive when the delta is non-empty,
after the fold has removed the run's own work and any re-read in another order.

*Evidence.* On production states `own-and-cosmetic` **is** `empty-only`: the fold does its second
and third clauses before any rule sees the state (§3.1), and the two agree on all 112 cases in
both forms. The rule is zero false settled by construction. Its cost is a needless PERCEIVE on
every beside change that has content: 32 of 56 beside cases here (57.1%) — an unrelated belief,
an unrelated observation, an unrelated commitment, bookkeeping subjects. Against it, the one
model that ran is zero on exactly those four kinds and false-settled on one bears call in three.

*Cost of being wrong.* Shipping it: a PERCEIVE call (one generative call, plus a Field snapshot
the run already takes) on cycles where something unrelated moved. Not shipping it: the run keeps
perceiving once per run, which is today.

*Recommended default.* **Yes — ship it as the no-judge `WhenDrifted`, and as the W1 default.**
It is strictly safer than any arm measured here on the error that matters, its cost is bounded
by the consumer's cycle cap, and a judge can be slotted in behind the same cadence value once a
measured arm clears zero false settled on a state that renders the relations. Name it
`empty-only` in the code: `own-and-cosmetic` describes the fold, not a rule.

**Beside the three, one follow-up that needs only a yes:** a ticket for the two renderer fields
in §3.2.2 (`ampere-core`, `RunDrift.asDecisionState` and `FieldDelta`'s `GoalChange`), with this
corpus's validator extended to read them. Without it, re-running the System One arms measures the
same gap.

---

*Document generated as part of spike for issue #1068 (AMPR-482)*
