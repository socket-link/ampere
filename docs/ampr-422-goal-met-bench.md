# Goal-met bench — can a model tell whether a hosted run met its goal?

**Issue:** [#955](https://github.com/socket-link/ampere/issues/955) — Spike (STOP gate): can a model tell whether a hosted run met its goal?
**Linear:** [AMPR-422](https://linear.app/miley/issue/AMPR-422)
**Author:** @miley
**Date:** 2026-10-15

---

## Executive Summary

A hosted run today reports `Succeeded` when no step failed (`RosterHostedRun.currentStatus`),
and the Arc path marks a goal complete on any success (`FlowPhase.evaluateGoalCompletion`).
Neither reads what the steps returned. AMPR-421 added the record-only question — a run asks
`RunQuestions.goalMet` about `RunObservation.asDecisionState(clip)` after each executed cycle
and nothing reads the answer. This spike measures **that question on that state shape**, through
the adapters as shipped, so the numbers describe what a consumer gets.

**This spike sets the bar. There was none to clear.** The two rules in production were
measured first, on a constructed corpus of 288 cases (144 met, 144 unmet, labelled by
construction):

| Arm | False met | False not met | What it is |
| -- | -- | -- | -- |
| `any-success` (Arc path) | **120 / 144 (83.3%)** | 0 / 144 | met when at least one step succeeded |
| `no-failure` (hosted run) | **120 / 144 (83.3%)** | 48 / 144 (33.3%) | met when no step failed |

Five of the six unmet kinds — a step short, the wrong target, an empty result, an error
marked succeeded, a claim with no evidence — contain no failed step, so both rules call every
one of them met. The hosted run's rule is strictly worse: it also calls a retried run unmet.

Then the live arms, each through the adapter as shipped, in two question forms, three repeats:

| Arm | False met | False not met | Note |
| -- | -- | -- | -- |
| `claude-sonnet-5`, `noul` (shipped form) | **65 / 432 (15.0%)** | 2 / 432 (0.5%) | self-reported, so no sweep; weakest kind `empty_result` at 43.1% |
| `claude-sonnet-5`, `choice` | **47 / 432 (10.9%)** | 10 / 432 (2.3%) | `cannot_tell` counted as not met |
| `claude-haiku-4-5`, both forms | — | — | **0 of 1,728 replies parsed**: the shipped adapter's parser fails on a fenced answer followed by prose |
| `jev-1.13`, `clef`, `clef-flash` | not run | not run | `OPENROUTER_API_KEY` / `CLOUDFLARE_*` absent from the environment; harness built and tested, one command to run |

So the one thing this spike could not deliver is the thing J9 needs most: **a measured arm** —
a distribution a threshold can be fitted on. Every answered call here is a generative model's
self-report, which J5 forbids fitting. Sonnet is far better than the rules in production and
still calls one unmet run in seven done; whether a System One model clears a zero-false-met bar
at a useful recall is exactly what the three unrun arms would say.

The report ends at a STOP (§5): six plain-language questions for Miley, each with its evidence
and a recommended default. No ticket that lets a judgment act is drafted before she answers.

---

## 1. The bench

`link.socket.ampere.eval.decide`, in `ampere-eval`'s `commonMain`:

| Type | What it is |
| -- | -- |
| `DecisionCase(id, kind, state, question, expected, family)` | A rendered state, the question asked about it, and the one correct answer key. Labelled by whoever constructed the state. |
| `DecisionArm(id, client, configuration)` | One `UpstreamDecisionClient` under measurement. |
| `DecisionBench.run(cases, arms, repeats, positiveKeys, parallelism, onRecord)` | One `DecisionRequest` per case, one question under `goal_met`; records answer, distribution, latency and usage per case × arm × repeat. A call that throws is a record with an error. |
| `DecisionBenchReport` | Confusion counts (false met kept apart from false not met; a missing answer reads as not met and is counted), per-kind and per-family tables, repeat stability, p50/p95 latency, usage — and, only for an arm whose every answer `BandFitGuard.isFittable` accepts, the 21-point sweep and the zero-false-met operating point. |
| `PlaybackDecisionClient` | Serves recorded answers by case id and repeat (what the bench does) or by state digest through `decide` (the seam). A miss throws. A recorded failure replays as a failure. |
| `GoalMetForm` | The shipped `noul`, and the three-option `choice` (`met`, `not_met`, `cannot_tell`) derived from its wording. `cannot_tell` counts as not met. |
| `GoalMetArms` | `any-success` and `no-failure` as `DeterministicDecisionClient`s, reading only the state's status words. |

The **zero-false-met operating point** is defined as: the largest `P(met)` any unmet sample
reached (the rule being *met when P(met) > threshold*), and beside it the share of met samples
still above it. It is computed on the mean of each case's repeats and on every single call —
a run asks once, a bench asks three times. The sweep is 21 thresholds, `0.00 … 1.00`, with the
rule *met when P(met) ≥ t*.

Validation: `DecisionBenchTest` (commonTest), a ten-case toy suite with hand-computed
expectations, including "a self-reported arm gets no sweep".

## 2. The corpus

`ampere-eval/src/jvmTest/resources/decide/goal-met/cases.json`, written by `GoalMetCorpus`
(jvmTest) and never by hand. Every state is `RunObservation.asDecisionState(400)` — the run's
default clip — over a `Task.Step` goal and `StepOutcome`s, so a case is byte-for-byte what a
hosted run hands its transport.

**Eight families × six targets × nine kinds.** The three met kinds render for all six targets,
the six unmet kinds for the first three, so the corpus is exactly half met and a met and an
unmet case of the same target share their goal and most of their text.

| Family | Steps | Example goal |
| -- | -- | -- |
| `find-and-summarise` | 2 | Find the latest version of the onboarding handbook and summarise its key points in three bullets |
| `schedule-and-notify` | 3 | Schedule a design review with the three platform engineers for 30 minutes next week and notify everyone invited |
| `file-and-link` | 2 | File the signed office lease under Records/2026 and link it from the records overview page |
| `convert-and-save` | 2 | Convert the meeting notes to PDF and save the PDF beside the original file |
| `compare-and-recommend` | 2 | Compare the three storage plans on monthly cost and the stated requirement, and recommend one with the reason |
| `fix-failing-check` | 3 | Make the date-parsing test pass again without disabling it |
| `tidy-and-report-counts` | 3 | Archive everything older than 90 days in the shared downloads folder and report how many items were archived |
| `draft-and-send` | 2 | Draft a note to the facilities team about the broken door sensor and send it |

| Kind | Met | Cases | Rule |
| -- | -- | -- | -- |
| `clean` | yes | 48 | every step succeeded; every result shows the outcome |
| `redundant_failure` | yes | 48 | one step failed, a retry of it succeeded; every part covered; cycle 2 |
| `terse` | yes | 48 | every step succeeded with a shorter result that still states the outcome |
| `partial` | no | 24 | the last step never ran |
| `wrong_target` | no | 24 | every step succeeded, on the next target over |
| `empty_result` | no | 24 | lookup intact; every later step succeeded with `""` |
| `error_as_success` | no | 24 | lookup intact; every later step marked succeeded, its text an error or a refusal |
| `unverifiable` | no | 24 | lookup intact; every later step claims an attempt and shows no outcome |
| `all_failed` | no | 24 | every step failed; cycle 2 |

The first step of every run is the lookup; the unmet kinds that alter results alter *every*
later step, so a three-step family never keeps a middle step that already completed the goal.

Validation: `GoalMetCorpusValidatorTest` — every committed case obeys its kind's rule (checked
against the `clean` sibling of the same target), no two states are equal, every kind and family
is present, and `GoalMetCorpus.encode(GoalMetCorpus.generate())` equals the committed bytes.

Constructed data only: no person, product or company is named.

## 3. Results

### 3.1 The deterministic arms (CI gate: `GoalMetDeterministicBenchTest`)

Noul form, one repeat, 288 calls each. One-hot, so both are fittable; both operating points
sit at threshold 1.0 and recognise 0% of met cases — a rule that is confidently wrong has no
threshold that saves it.

| Arm | Calls | False met | False not met | Met called met | Unmet called unmet | Missing |
| -- | -- | -- | -- | -- | -- | -- |
| `any-success` | 288 | 120 (83.3%) | 0 (0.0%) | 144 | 24 | 0 |
| `no-failure` | 288 | 120 (83.3%) | 48 (33.3%) | 96 | 24 | 0 |

Per kind, both rules: `partial`, `wrong_target`, `empty_result`, `error_as_success` and
`unverifiable` are 100% false met; `all_failed` is 0%. `redundant_failure` is 0% false not met
under `any-success` and 100% under `no-failure`. Weakest kind: any of the five, tied.

These numbers follow from the construction and are asserted as such in the gate; they are not
read off a run.

### 3.2 The live arms

Run on 2026-10-15 from a developer machine with `./gradlew :ampere-eval:decideBenchLive`.
Three repeats, both forms, 864 calls per arm (432 on met cases, 432 on unmet).

| Arm | Ran | Why not |
| -- | -- | -- |
| `claude-sonnet-5` (noul, choice) | yes | — |
| `claude-haiku-4-5` (noul, choice) | yes — every call failed to parse, see 3.2.2 | — |
| `jev-1.13` via OpenRouter (noul, choice) | **no** | `OPENROUTER_API_KEY` absent from the environment |
| `clef`, `clef-flash` on Workers AI (noul, choice) | **no** | `CLOUDFLARE_ACCOUNT_ID` / `CLOUDFLARE_AUTH_TOKEN` absent |

So the only measured (fittable) arms in this report are the two deterministic rules. **No arm
with a distribution was measured**, and therefore no threshold sweep and no operating point
below 1.0 exists yet. The three System One arms are the ones that would produce one; the
harness for them is built and tested against a mock engine, and running them is one command
with three environment variables.

#### 3.2.1 `claude-sonnet-5` through `ModelBackedDecisionClient`

Both forms, three repeats, 864 calls each, every call answered. The adapter is
**self-reported** — a generative model asked for JSON, no distribution — so by J5 there is no
sweep and no operating point for this arm, and its `confidence` is the F10 mapping of the word
it chose about itself.

| Form | False met (own answer) | False not met | Missing | Same answer on all 3 repeats | Majority of 3: false met / false not met |
| -- | -- | -- | -- | -- | -- |
| `noul` (shipped) | **65 / 432 (15.0%)** | 2 / 432 (0.5%) | 0 | 279 / 288 (96.9%) | 22 / 144 (15.3%) / 0 / 144 |
| `choice` | **47 / 432 (10.9%)** | 10 / 432 (2.3%) | 0 | 271 / 288 (94.1%) | 16 / 144 (11.1%) / 2 / 144 |

Against the production rules' 83.3% false met this is a different order of error, and it is
nowhere near zero. The two forms trade the two errors: the choice form (with `cannot_tell`,
answered 31 times, at most 10 of them on a met case) calls fewer unmet runs met and more met
runs unmet.

**Per kind** (false met over 72 unmet calls per kind, false not met over 144 met calls):

| Kind | noul false met | choice false met | noul false not met | choice false not met |
| -- | -- | -- | -- | -- |
| `clean` | — | — | 0 (0.0%) | 0 (0.0%) |
| `redundant_failure` | — | — | 0 (0.0%) | 4 (2.8%) |
| `terse` | — | — | 2 (1.4%) | 6 (4.2%) |
| `partial` | 15 (20.8%) | 10 (13.9%) | — | — |
| `wrong_target` | 9 (12.5%) | 3 (4.2%) | — | — |
| **`empty_result`** | **31 (43.1%)** | **28 (38.9%)** | — | — |
| `error_as_success` | 0 (0.0%) | 0 (0.0%) | — | — |
| `unverifiable` | 10 (13.9%) | 6 (8.3%) | — | — |
| `all_failed` | 0 (0.0%) | 0 (0.0%) | — | — |

**The weakest kind is `empty_result`, in both forms:** a step marked succeeded with an empty
result is read as done two times in five. The model is perfect on the two kinds whose text says
"failed" or "error" — exactly the kinds a deterministic rule could also catch — and weakest on
the kinds where the evidence is *absent* rather than contrary: an empty result, a missing last
step, a hedge. Those are the kinds a run cannot see today, and the kinds this judgment is for.

**Per family** (false met over 54 unmet calls, false not met over 54 met calls):

| Family | noul false met | choice false met | noul false not met | choice false not met |
| -- | -- | -- | -- | -- |
| `compare-and-recommend` | 0 | 0 | 2 | 4 |
| `convert-and-save` | 12 | 3 | 0 | 0 |
| `draft-and-send` | 13 | 12 | 0 | 0 |
| `file-and-link` | 0 | 0 | 0 | 1 |
| `find-and-summarise` | 0 | 0 | 0 | 1 |
| `fix-failing-check` | 20 | 13 | 0 | 4 |
| `schedule-and-notify` | 12 | 12 | 0 | 0 |
| `tidy-and-report-counts` | 8 | 7 | 0 | 0 |

The false-met calls sit in the three-step families and the "send"/"save" families — where an
empty or hedged last step follows a convincing earlier one. `fix-failing-check` is also the one
family where `wrong_target` fools the model (9 of 9 noul calls): a result saying a *different*
check passes is read as this check passing.

**Self-reported confidence does not separate right from wrong.** On the noul form 787 of 864
answers said `high` (0.75); of the 67 wrong answers 57 said `high`. Wrong rate by reported
level: `high` 7.2%, `medium` 13.0% (noul); `high` 4.1%, `medium` 20.1% (choice). There is a
little signal in `medium`, but 91% of calls are `high`, so a rule such as "only act on high"
would act on nearly every call, wrong ones included. This is why no band is fitted on a
self-report (J5), and why Q3 below asks for a measured arm.

**Latency and cost** (catalog prices, `provider_pricing.v1.json` 2026-09-20; tokens as the
provider reported them):

| Form | p50 | p95 | Mean tokens in / out | USD per 1,000 calls | Spend for this run |
| -- | -- | -- | -- | -- | -- |
| `noul` | 1,965 ms | 4,011 ms | 539 / 40 | 1.48 | 1.28 |
| `choice` | 2,186 ms | 5,134 ms | 570 / 68 | 1.82 | 1.57 |

One goal question per executed cycle at these figures adds about two seconds and a sixth of
a cent to a cycle.

#### 3.2.2 `claude-haiku-4-5` through `ModelBackedDecisionClient`: 0 of 1,728 answers parsed

Every one of Haiku's 1,728 replies (both forms, three repeats) failed with
`JsonDecodingException: Expected EOF after parsing`. The model answers the JSON it was asked
for inside a fenced block and then keeps writing — an explanation of the goal and the steps.
`LLMResponseParser.cleanJsonResponse` removes only a leading and a trailing fence, so the prose
after the closing fence reaches `parseToJsonElement` and the whole call throws. The shipped
adapter therefore yields **no judgment at all** from this model: 864 missing per arm, which
the bench counts as "not met" on every case (432 false not met, 0 false met).

This is a finding about the adapter as shipped, not about the model's judgment, and the bench
did not work around it: a parser shaped for this bench would measure the bench. It is also a
production defect for any consumer that names `ModelBackedDecisionClient` over a model that
explains itself — the call fails rather than answering — and it costs the tokens anyway
(the bench books usage only on answered calls, so Haiku's spend is not in the records; at the catalog price and the same prompt it is roughly USD 0.6–0.8 per 1,000 calls). A follow-up should extract the first JSON object from the reply instead of
assuming the reply *is* the object. Latency is reported for completeness: p50 2,860 ms,
p95 3,830 ms (noul); p50 3,009 ms, p95 4,600 ms (choice).

#### 3.2.3 What the first recording found about the corpus

The first live run (same arms, same day) was discarded and the corpus revised before the
committed one. Sonnet called **clean** cases unmet in two families, and reading the cases it was
right: the summary bullets in `find-and-summarise` described a budget whatever document the goal
named, and `compare-and-recommend` recommended "the middle option" of two. Both texts were made
target-neutral (the generator changed, the corpus regenerated, every arm re-recorded). The rest
of the first run's picture — where the false-met calls fall — survived the revision unchanged and
is what 3.2.1 reports. The lesson is the ticket's own: *how the corpus is built decides what the
numbers mean*, and a model disagreeing with a constructed label is the first thing to read, not
the first thing to count.

## 4. Technical Guidelines

- **Adapters as shipped.** The hosted arms POST exactly the body `HostedSystemOneDecisionClient`
  builds; the model-backed arms render exactly the prompt `ModelBackedDecisionClient` renders.
  Two things sit around them and change no request: a metering wrapper on the chat transport
  (the model-backed adapter books usage on telemetry events, and a bench has no door) and a
  retry on transient transport failures (429, 5xx, timeout) — never on a malformed answer.
- **Question from `RunQuestions`.** The `choice` form's texts are derived from the shipped noul;
  rewording the question there rewords both forms and invalidates every recording, which the
  recorded test detects through the question's answer keys and the state digests.
- **Nothing in CI reaches the network.** `jvmTest`, `evalReplay` and CI run the two
  deterministic arms and replay the recorded files. `decideBenchLive` does nothing unless its
  task set the directory property; running it is the explicit approval for the calls it makes.
- **No new commonMain dependency.** Ktor's mock engine and the chat client's types are on the
  jvmTest classpath only.
- **Convict, not acquit.** A failure on a constructed case is real. A pass on 288 constructed
  cases justifies a record-only period and a next ticket, not acting in production.

## Appendix

### A. Running it

```bash
./gradlew :ampere-eval:jvmTest --tests "link.socket.ampere.eval.decide.*"   # gates, offline
./gradlew :ampere-eval:generateGoalMetCorpus                                 # regenerate; empty diff expected
./gradlew :ampere-eval:decideBenchLive -PdecideDryRun=true                   # print one request, send nothing
OPENROUTER_API_KEY=… CLOUDFLARE_ACCOUNT_ID=… CLOUDFLARE_AUTH_TOKEN=… ./gradlew :ampere-eval:decideBenchLive
```

### B. Where things are

| | |
| -- | -- |
| Bench | `ampere-eval/src/commonMain/kotlin/link/socket/ampere/eval/decide/` |
| Corpus generator | `ampere-eval/src/jvmTest/kotlin/link/socket/ampere/eval/decide/GoalMetCorpus.kt` |
| Corpus | `ampere-eval/src/jvmTest/resources/decide/goal-met/cases.json` |
| Recorded answers | `ampere-eval/src/jvmTest/resources/decide/goal-met/recorded/<model>.<form>.jsonl` |
| Live runner | `ampere-eval/src/jvmTest/kotlin/link/socket/ampere/eval/decide/DecideBenchLive.kt` |
| Gates | `GoalMetCorpusValidatorTest`, `GoalMetDeterministicBenchTest`, `GoalMetRecordedBenchTest` |
| The question and the state | `ampere-core/src/commonMain/kotlin/link/socket/ampere/propel/RunQuestions.kt` |

### C. Price source

Per-token prices for the model-backed arms come from this repository's catalog,
`ampere-core/src/commonMain/composeResources/files/provider_pricing.v1.json` (`publishedAt`
2026-09-20): `claude-sonnet-5` USD 2.00 in / 10.00 out per million tokens, `claude-haiku-4-5`
USD 1.00 / 5.00. The hosted System One arms report `cost` on the wire.

---

## 5. STOP — six questions for Miley

Written for a reader who has not followed the J-register. Each has the evidence beside it and a
recommended default. Nothing below is decided here; no ticket that lets a judgment act is drafted
before these are answered.

**Beside them, one line that needs only a yes (row J7):** a hard rule stays code, and no
judgment ever turns a hard refusal into a pass. The bench has nothing to say about this because
it measured no hard rule; it is the premise every question below assumes.

### Q1 — May a "not met" keep a run going inside its cycle cap?

*What it means.* Today a hosted run stops when its plan is empty or its cycles run out. If the
goal question answered "not met" after a cycle, the run could plan one more cycle (still under
the cap) instead of stopping.

*Evidence.* Sonnet's "not met" on a met run (false not met) is 0.5% in the shipped form and
2.3% in the choice form — 2 and 10 of 432 calls — and zero on `clean` cases in both. On an unmet
run it says "not met" 85.0% / 89.1% of the time. The production rules never say "not met" on a
met run either, but only because they say "met" to almost everything.

*Cost of being wrong.* A false "not met" costs one more cycle, bounded by the cap the consumer
already set. It cannot make the run do more than the cap allows.

*Recommended default.* **Yes, record-only first, then on.** This is the safe direction: the
answer can only spend cycles the consumer already agreed to spend. Ship it as a `GoalCheck`
value above `Record` that lets "not met" re-plan, never one that lets "met" stop.

### Q2 — May a "met" end a run early?

*What it means.* The opposite: if the question answers "met" after a cycle, the run stops before
its cap, and LEARN stores the run as a success.

*Evidence.* At its own answer Sonnet calls 15.0% (noul) / 10.9% (choice) of unmet runs met;
a majority of three repeats does not help (15.3% / 11.1%). On the weakest kind — a step marked
succeeded with an empty result — it is wrong two times in five. Of the 65 false-met calls on the
shipped form, at least 55 came with `high` self-reported confidence, so "stop only when
confident" would not have filtered them. No arm with a measured distribution ran, so no
zero-false-met operating point below 1.0 exists; the production rules sit at 83.3% false met.

*Cost of being wrong.* This is the costly error. A false "met" ends the run, stores knowledge
from a success that was not one, and tells a person it is done.

*Recommended default.* **No, not yet.** Nothing measured here may end a run. The bar this spike sets is: *zero
false met on this corpus at a stated threshold, with the met share recognised there written
down* — and only a measured arm can be held to it. Revisit when a measured arm holds a
zero-false-met operating point that still recognises a useful share of met runs, on this
corpus *and* on recorded production states.

### Q3 — May low confidence raise an escalation to a person?

*What it means.* `UncertaintyEscalationEvaluator` is built and has no producer. A judgment's
`1 − p(answer)` could be that producer: when the model is unsure whether the goal is met, a
person is asked.

*Evidence.* On the only arm that answered, the self-reported level carries a little signal
(wrong rate 7.2% at `high` against 13.0% at `medium` on the shipped form; 4.1% against 20.1%
on the choice form) but 91% of answers are `high`, so an escalation on anything below it would
fire on roughly one call in ten and catch 10 of 67 (noul) or 27 of 57 (choice) errors. The
choice form's `cannot_tell` (31 of 864 answers, mostly on unmet cases) is the shape an
escalation input wants — "I cannot say" — and a measured `P(met)` near 0.5 is its number.

*Cost of being wrong.* A needless ask. It never ends a run or stores a false success.

*Recommended default.* **Yes, from measured arms only, record-only first.** A self-reported
confidence (the model-backed adapter) must not feed it: `BandFitGuard` already refuses it, and the numbers above show why — the
word `high` is not a probability.

### Q4 — Should a verifier seat's questions become Probe verdicts?

*What it means.* A roster's verifier seat can already declare its own questions (AMPR-421);
they are recorded and nothing reads them. Row J6 would map a judgment onto the existing
`Verdict` (act band → `Holds` / `Violated`, middle band → `Undetermined`) so a
decision-backed `Probe` can sit beside the deterministic ones.

*Evidence.* The bench did not measure a verifier question, only the run's own. What it shows
about the mapping: the choice form's `cannot_tell` is exactly the `Undetermined` the new
`UndeterminedCause` would carry, and it was the right answer more often than not (at most 10 of
31 on met cases). A `Holds` from a self-reported arm would be the 15% false met above wearing a
verdict's name.

*Recommended default.* **Yes to the type, no to acting.** Add the `Probe` adapter with the new
`UndeterminedCause` so a consumer can *see* a judgment as a verdict in the same place as its
other probes; keep `Undetermined` as the only verdict a judgment may produce until Q2's bar
is met. Nothing in OBSERVE acts on a Probe verdict today, so this changes no run.

### Q5 — Who stores thresholds?

*What it means.* A threshold (the operating point above) has to live somewhere. Row J10 proposes
an SPI the consumer implements, on the `EstimateCalibrationSource` precedent; AMPERE reads a
band and a sample count and never stores the pairs.

*Evidence.* The same model gives 15.0% false met in one form and 10.9% in the other, with
the opposite trade on false not met; a second model (Haiku) gives nothing at all through the
shipped adapter; and the arms that would carry a threshold at all (Jev, Clef) have not run.
Whatever number the first measured run produces will be true of one model, one form, one state
shape and one day.

*Recommended default.* **The consumer, through an SPI; AMPERE ships the corpus-derived number
only as a documented starting value, never as a default that acts.** The bench shows the
threshold is a property of the model *and* the question form *and* the state shape; a number
baked into AMPERE would be wrong the first time any of the three changed.

### Q6 — What may be judged off the device?

*What it means.* Every arm here ran in the cloud; the state left the machine. Row J11 says a
state carrying a `Protected` belief may be judged only `ON_DEVICE`, and resolves to
`Undetermined` when no on-device judge is bound.

*Evidence.* Every answered call here went to `anthropic` over the public network, and the
three arms that would measure a System One model go to OpenRouter and Cloudflare. A hosted run's state today is the goal text and the step results —
tool output, which is exactly where a person's data would appear.

*Recommended default.* **Keep J11 as written; the consumer chooses the transport and so chooses
the locality.** No on-device decision arm exists to measure (the JVM cannot bind an engine, and
`ModelBackedDecisionClient` over an on-device model is not yet a thing), so there is no
evidence that an on-device judge clears any bar. Until there is, a `Protected` state is not
asked.

---

*Document generated as part of spike for issue #955 (AMPR-422)*
