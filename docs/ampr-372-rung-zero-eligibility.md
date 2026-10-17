# Rung ZERO eligibility — which agent work may run on the on-device model

**Issue:** [#852](https://github.com/socket-link/ampere/issues/852) — Decide which agent work is eligible for Rung 0, and declare it on the Arc manifest
**Linear:** [AMPR-372](https://linear.app/miley/issue/AMPR-372)
**Author:** @miley
**Date:** 2026-10-17 (measured 2026-10-16, ratified 2026-10-17)

---

## Executive Summary

Every model call site at HEAD `c7bca53c` was run on Apple's on-device model, on real hardware,
with the exact prompts the real builders produce, sent the way the production engine sends
them. 120 battery replies were graded by hand, and the AMPR-422 goal-met corpus (288 cases)
went through the shipped Decide adapter.

**The window is not what keeps work off the device. Quality is.** The device answers in the
right format — 77 of 78 JSON replies in the battery and 396 of 396 goal-met replies parsed with
Ampere's shipped parsers — and fails on content wherever the answer has to be derived rather
than restated:

| Work | Sound | Wrong | What goes wrong |
| -- | -- | -- | -- |
| Single-fact or single-source text (a log's cause, a tone label, a run summary, a date or a path stated in the prompt) | **33 / 33** | 0 | nothing |
| Anything that selects, compares, counts, attributes or infers across items | **5 / 33** | 27 | drops items, picks the plan that fails the requirement, miscounts, gives an action to the wrong person, invents an issue number |
| PLAN on a judgment task | 0 / 6 | 6 | says no person is needed; packs two tools into one field; pushes to main |
| LEARN (outcome evaluation) | 1 / 18 | 15 | stores a false learning, usually at `high` confidence |
| PERCEIVE | 15 / 24 | 5 | inverts a recalled learning in 3 of 3 runs |
| Goal met? (classification, 288 cases) | — | **59.0% false met** | vs **15.0%** for Sonnet 5 on the same prompts, and 83.3% for the rule in production |

**The ratified rule (2026-10-17):** rung ZERO serves **display text** only — words a person
reads, which nothing in the run acts on, decides on or remembers. Every PROPEL phase call and
every judgment stays at rung ONE or above. The rest of this record is the evidence.

---

## 1. Method

- **Hardware.** An M1 Pro Mac on macOS 27.0 (build 26A428), Apple Intelligence on,
  `SystemLanguageModel.default` available with `contextSize` 4,096 — the same window AMPR-373
  measured on iOS 26.4.1, iOS 27.1 and macOS 27.0. No iPhone was run.
- **The production engine path, exactly.** A Swift harness did what
  `FoundationModelsLocalInferenceEngine.generate` does: a fresh `LanguageModelSession()` per
  call, no instructions, no `GenerationOptions`, and the whole flattened `System: …\n\nUser: …`
  prompt as `LocalUpstreamLlmClient.flattenToPrompt` builds it. Ampere's requested temperature
  (0.3) and output cap never reach the engine on this path.
- **Real prompts.** A scratch JVM capture (deleted afterwards, never committed) drove the real
  builders — `PerceptionEvaluator`, `PlanGenerator`, `OutcomeEvaluator`,
  `ToolExecutionEngine`'s parameter strategies, `SparkBasedAgent.executeReasoningStep` and
  `ModelBackedDecisionClient` — over a recording `LocalUpstreamLlmClient`. Arc agents came from
  `ChargePhase` (`pm`, `code`, `qa` with project and role sparks). The prompts are byte-for-byte
  what the device would be handed.
- **Cases.** 40 constructed cases with a known answer each, run 3 times (120 replies), plus the
  AMPR-422 goal-met corpus: 288 cases once, then 54 stratified cases twice more (396 replies).
- **Grading.** Parse: a port of `LLMResponseParser.cleanJsonResponse` plus strict JSON, and of
  `ModelBackedDecisionClient.normaliseAnswer`. Content: every battery reply was read in full
  and graded against its case. *Sound* means correct and grounded; *weak* means true but
  generic; *wrong* means it states something false about its input, or produces an argument,
  plan or learning that would do or store the wrong thing. The grader was an LLM, not a person.
- **Not run.** No cloud baseline on the 40-case battery (no API spend was approved; the goal-met
  corpus has one, recorded by AMPR-422). No iPhone run. No live end-to-end Arc on the device.
  The cause of the stalls in §7 was not isolated.

---

## 2. Results by call site (120 replies)

| Call site | Replies | Sound | Weak | Wrong | Unparsed | p50 latency | Calls > 60 s |
| -- | -- | -- | -- | -- | -- | -- | -- |
| PERCEIVE | 24 | 15 | 4 | 5 | 0 | 6.2 s | 3 |
| PLAN | 18 | 11 | 1 | 6 | 0 | 8.7 s | 2 |
| LEARN | 18 | 1 | 1 | 15 | 1 | 10.5 s | 4 |
| EXECUTE, tool arguments | 18 | 10 | 0 | 8 | 0 | 2.4 s | 2 |
| EXECUTE, reasoning step | 15 | 6 | 0 | 9 | 0 | 1.6 s | 1 |
| Prose, on-device assistant prompt | 18 | 12 | 1 | 5 | 0 | 1.3 s | 0 |
| Prose, `callLLM` shape | 9 | 4 | 0 | 5 | 0 | 1.6 s | 0 |
| **Total** | **120** | **59** | **7** | **53** | **1** | | **12** |

What the wrong answers were:

- **PERCEIVE.** All three runs of one case turned the recalled learning "the 2.x API renamed
  `Logger.warnf`; a bulk replace caught every call" into "bulk find-and-replace failed because
  of API changes". One run said tests had not run when the plan showed them completed. One said
  an item rated medium impact was low.
- **PLAN.** The four fully specified tasks were planned well (11 of 12). Both judgment cases
  failed every run: a "should the team migrate to GraphQL" task set `requiresHumanInput: false`
  in two runs; a "ship the README fix" task with a recalled "branch, push, open a PR" constraint
  nominated `"git_stage, git_commit"` in one field (which fails the step) in two runs and pushed
  to main in the third.
- **LEARN.** Every outcome a non-code tool produces is rendered `✓ No Changes (Success)`. In 12
  of the 17 replies that parsed, and in the one that did not, the model read that label as a
  finding ("no-change executions correlate with success"), usually at `high` confidence with the
  full evidence count. On four unrelated failures, all three runs claimed a shared cause at
  `high` confidence, one inventing tool names. Each would be written to the knowledge store and
  recalled into later plans.
- **Tool arguments.** Two of three runs dropped a `scope` the step required; all three runs of
  a three-file commit left out the third file; all three runs of a commit with no issue in the
  context invented `"issueNumber": 1`. Dates resolved from a prior step, file paths and a
  one-file commit message were exact.
- **Reasoning steps.** "Decide the approach" and "draft a note from one fact" were 6 of 6. The
  rest failed every run: a search summary kept 1 of 3 matches; "pick the cheapest plan with at
  least 800 GB" picked the 100 GB plan; "count items older than 90 days" answered 3 (or replied
  in Swift) when the count is 5.
- **Prose.** A run summary, a log's cause and a tone label were 9 of 9 on the assistant prompt.
  Meeting action items were 6 of 6 wrong on both shapes (an item given to the wrong person, the
  right person's dropped); a three-document summary invented result counts once; 3 of 6
  progress lines were wrong and 1 off.

Format compliance is not quality: a parse-rate gate would have passed every candidate here
(473 of 474 JSON replies parsed).

---

## 3. The split that explains it

The same 66 replies, sorted by what the answer required:

| The answer… | Sound |
| -- | -- |
| is stated in the prompt: one fact, one source, or a short list restated in order | **33 / 33** |
| must be selected, compared, counted, attributed or inferred across items | **5 / 33** |

The device is reliable as a typist and unreliable as a reasoner. The rule therefore keys on
**who consumes the output** — which the framework can know — not on whether a prompt merely
restates, which it cannot check. The measured boundary goes beside it as advice: display text
loses or misattributes items when it aggregates, so a host should hand the device
single-source text.

---

## 4. Classification on the goal-met corpus

The AMPR-422 corpus, through `ModelBackedDecisionClient`'s prompt as shipped
(`RunQuestions.goalMet`, a `Noul`):

| | On the device | Sonnet 5 (AMPR-422) | The rule in production (`no-failure`) |
| -- | -- | -- | -- |
| False met (an unmet run called met) | **85 / 144 (59.0%)** | 65 / 432 (15.0%) | 120 / 144 (83.3%) |
| False not met | 12 / 144 (8.3%) | 2 / 432 (0.5%) | 48 / 144 (33.3%) |
| Unparsed | 0 / 288 | 0 | — |
| Verdict flips across 3 repeats | 3 / 54 cases | — | — |
| Latency p50 / p95 | 1.3 s / 6.8 s | 2.0 s / 4.0 s | — |

By kind, the device catches an unmet run only when its text says something went wrong:

| Kind | Wrong |
| -- | -- |
| `error_as_success`, `all_failed` | 0 / 48 |
| `wrong_target` | 22 / 24 false met |
| `partial` | 21 / 24 false met |
| `empty_result` | 21 / 24 false met |
| `unverifiable` | 21 / 24 false met |
| `redundant_failure` (met, retried) | 12 / 48 false not met |
| `clean`, `terse` | 0 / 96 |

It notices text that says something alarming and misses structure that is wrong. A judgment is
never display text, so a model-backed decision never runs at rung ZERO.

---

## 5. Two engine variants tried on the 15 hardest cases

| Variant | Replies | Sound | Weak | Wrong | Unparsed |
| -- | -- | -- | -- | -- | -- |
| As shipped (same 15 cases, 3 repeats) | 45 | 1 | 1 | 42 | 1 |
| System message as `LanguageModelSession(instructions:)` | 30 | 2 | 1 | 27 | 0 |
| Greedy decoding (`GenerationOptions(samplingMode: .greedy)`) | 30 | 0 | 0 | 30, the same answer on both repeats of every case | 0 |

Neither moves the verdict: these are model limits, not adapter mistakes. Greedy decoding does
remove the run-to-run variation (3 of 54 goal-met cases flip under default sampling), so it is
worth having for repeatability, not for accuracy. The `instructions` form is still the better
engine contract, and passing it is a separate ticket under AMPR-327.

---

## 6. What a ZERO floor on an Arc step does today

Measured by running the real `ArcSession.create(…, engine, cloud)` path with every step
floored at ZERO, the device reporting its real 4,096 window, and a recording cloud transport
(canned answers, no network):

| Call | Where it went |
| -- | -- |
| PERCEIVE ×3, PLAN ×3 | the device |
| EXECUTE reasoning steps ×6 | **`gemini-2.5-flash`**, because their 4,000-token default output cap cannot fit |

Two consequences:

- A ZERO step floor makes **every** phase of the step eligible, PLAN included.
- **ZERO means "any rated model", not "eligible for the device".** When the device cannot take
  a call, the same floor routes it cheapest-capable over the whole catalog — to
  `gemini-2.5-flash` (rung TWO) — not to the agent's configured model. A step with no floor
  would have used its agent's configured model, and the CLI's code agent (floor THREE) gets
  `gemini-2.5-pro`. Declaring a step eligible for the device also downgrades its cloud fallback.
  An unrated cloud model (`rung = null`) does *not* satisfy a ZERO floor
  (`ModelDescriptor.satisfies`), which is the one difference from declaring no floor at all.

Three call sites can never fit the device whatever their floor, because they set no output cap
and inherit the 4,000-token default: tool-less reasoning steps, `SparkBasedAgent.callLLM` and
`ModelBackedDecisionClient`. Their prompts are small (360–710 tokens); the cap alone fills the
window. The sites that fit (PERCEIVE, PLAN, LEARN, tool arguments) need 1,250–2,100 tokens with
their own caps.

---

## 7. The chars/4 estimate, latency and stalls

- **`PromptTokenEstimator` undercounts digit-dense text.** Per call site the median real count
  was 0.89–1.02× the chars/4 estimate (goal-met prompts 0.85–0.96×), so the estimate usually
  runs slightly high, the safe direction. The worst single prompts ran the other way: a log
  with timestamps was 298 real tokens against an estimate of 192 (**1.55×**), and a search
  listing 1.17×. Sizing the window fit with the engine's own token count is a separate ticket
  under AMPR-372.
- **Latency p50:** 1.3 s for a label or one-line answer, 6–10 s for a 300-token JSON answer
  (PERCEIVE, PLAN, LEARN). Sonnet 5's p50 on the goal-met prompt was 2.0 s.
- **Stalls.** 24 of 516 calls took between 60 s and 17 minutes, from a background command-line
  process on a shared Mac under memory pressure; the cause was not isolated. An earlier run of
  1,260 calls on this model had a p95 of 1.6 s, so this is the machine or the process's
  priority, not the model. Every `SparkBasedAgent` phase call is cut at a per-call timeout
  (60 s by default), so such a stall becomes a failed step.

---

## 8. The ratified rule

Four positions, ratified on AMPR-372 on 2026-10-17:

1. **Rung ZERO serves display text only.** Display text is words a person reads, which nothing
   in the run acts on, decides on or remembers. Every PROPEL phase call — PERCEIVE, PLAN,
   EXECUTE tool arguments and tool-less reasoning steps, LEARN — and every judgment stays at
   rung ONE or above. Of the ticket's candidates: Emission prose, summarisation *for display*
   and a classification *shown to a person* are eligible; perception evaluation, outcome
   evaluation, and any classification a run records or acts on are not.

   | Candidate | Verdict | Why |
   | -- | -- | -- |
   | Emission prose | Eligible | display text; single-source text was 33 of 33 |
   | Summarisation | Eligible for display; not as a step's result | a reasoning step's summary feeds later steps and kept 1 of 3 matches |
   | Classification | Eligible as a label shown to a person; not as a judgment | goal-met: 59.0% false met |
   | Perception evaluation | Not eligible | the planner consumes it; an inverted recalled learning in 3 of 3 runs |
   | Outcome evaluation | Not eligible | durable: 15 of 18 would store a false learning |
   | PLAN, EXECUTE arguments, reasoning steps | Not eligible | the run acts on them |

2. **Eligibility is per kind of call, not per agent or Arc step.** The one eligible kind is not
   a PROPEL phase, so no Arc step has eligible work to declare. Display text runs under the
   identity that already declares ZERO: `OnDeviceAssistantAgent`, through
   `OnDeviceInferenceSession.ask` (a 2,048-token output cap and a requirement sized to the
   window). Arc steps, and any agent that runs the PROPEL loop, declare a floor of ONE or above.
   If a second kind is ever ratified, the declaration names the output's *audience* (a person or
   the run), not a phase.
3. **A per-call requirement never lowers a floor.** Floors compose as `maxOf` (AMPR-229
   stands). A floor is a promise about quality made by whoever configured the agent or the Arc;
   a call site is code someone else wrote, and letting code lower configuration inverts who
   decides.
4. **A consumer app's quality tiers map to floors, and device eligibility is decided by kind,
   not by tier:** QUICK → ONE, SMART → THREE, BRILLIANT → FOUR. A tier whose definition is
   structured reasoning is where the device fails (§2–§4); a "trivial summarise or draft" tier
   is display text only when a person reads the result before anything acts on it.

The rule is policy as of this record. Where it is not yet enforced in code, a cell says it is a
rule, and the enforcement ticket under AMPR-372 is the one that makes something refuse.

---

*Document condensed from the AMPR-372 findings comment and verdict (issue #852)*
