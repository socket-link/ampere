---
concept: DecideSeam
status: experimental
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/llm/decide/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/reasoning/Confidence.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/reasoning/ConfidenceSource.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/CognitiveEvent.kt
related: [CognitiveRelay, CognitionTrace, PropelLoop, EventSerialBus, Probe]
last_verified: 2026-10-17
---

# Decide Seam

## What it is

The Decide seam is AMPERE's second call kind. Beside the generative call
(`UpstreamLlmClient`: messages in, text out) sits `UpstreamDecisionClient`
(`llm/decide/`): a state plus typed `Question`s in, `Judgment`s out. A
decision model generates no text — every answer it may give is declared on
the question up front (`Noul` yes/no, `Choice` one-of, `Score` ordered
rubric), and it returns a probability per declared answer. `AgentReasoning.decide`
is the one entry point; it routes through the injected client and publishes
one `CognitiveEvent.JudgmentRecorded` per judgment through the agent's event
door. The seam shipped shadow-only in `0.18.0` (AMPR-384) and has its first
callers in `0.19.0`: a hosted run asks whether its goal is met, and a verifier
seat asks its own declared questions (AMPR-421). Both are record-only — no
call site reads a judgment.

## Why it exists

AMPERE's judgment seams were empty. Goal completion was "any success"; agent
order was the configured order; nothing produced the `uncertaintyValue` the
escalation evaluator was built to read; and every existing model call was
open generation, with confidence a `high | medium | low` string the generating
model wrote about itself (AMPR-381, G1). Three pressures shaped the seam:

1. **A decision request cannot travel on the chat seam.** `UpstreamLlmClient`
   is typed to `ChatCompletionRequest → ChatCompletion`. A decision call has
   no messages and returns no text, and tunnelling it through structured
   output keeps one seam at the cost of the distribution — the one thing the
   call is for (J1).
2. **A band cannot be fitted on a self-report.** A probability a decision
   model measured and a `high` a generative model said about itself are
   different quantities. Giving `Confidence` a `source` and recording every
   judgment with its distribution is what makes a later threshold fit
   possible at all (J4, J5).
3. **The consumer that proxies every model call has nothing to address
   until this exists.** Socket routes the chat seam through its own backend
   and must do the same here (SCKT-793); AMPERE-first sequencing means this
   artifact is cut as a release before that work starts.

## Where it lives

- `llm/decide/Question.kt` — sealed `Question` (`Noul`, `Choice`, `Score`). The serialized form *is* the System One wire body: `{type, instructions, criteria}` with `type` as the discriminator. `criteria` is required; a `Choice`'s options are its criteria keys, a `Score`'s levels are its criteria indices, a `Noul`'s are `true`/`false`. `answerKeys` is the one vocabulary an answer is drawn from; `Question.version` is the digest of the question as asked.
- `llm/decide/Judgment.kt` — `Judgment(answer, distribution, source, modelSnapshot, locality, confidence)` and `ModelSnapshot(providerId, modelId, revision, via)`. `distribution` is null when the adapter measured none; `confidence` is `p(answer)` or the F10 mapping of a self-report.
- `llm/decide/DecisionRequest.kt` — `DecisionRequest(state, questions by id)` and `DecisionResponse(judgments by id, usage)`.
- `llm/decide/UpstreamDecisionClient.kt` — the seam (`decide(request, configuration)`), `MissingUpstreamDecisionClientException`, `MalformedDecisionResponseException`.
- `llm/decide/HostedSystemOneDecisionClient.kt` — one Ktor POST, no SDK. Endpoint + credential + model snapshot + body extras (`"model"` for every vendor's route). Accepts the bare body and Cloudflare's `{"result": …, "success": true}` envelope. Measured, `CLOUD`. `HostedSystemOneDecisionClient.of(model, route, credential)` is the one way to bind a named model over a named route.
- `llm/decide/DecisionModel.kt` — `DecisionModel(makerId, modelId)` and `DecisionModels` (`Jev_1_13` from `typesafe`; `Clef`, `ClefFlash` from `cloudflare`); `DecisionRoute(via, endpoint, wireModelId, bodyExtras)` and `DecisionRoutes` (`jevViaOpenRouter()`, `jevViaTypeSafe(modelId)`, `clefOnWorkersAi(accountId, flash)`). Names only: a route holds no credential and naming one binds nothing (AMPR-435).
- `llm/decide/ModelBackedDecisionClient.kt` — the question rendered as a prompt to `AgentLLMService.callDetailed`, answered as JSON. `SELF_REPORTED`, no distribution; the model's own level lands on `confidence` through F10. The state reaches the prompt inside the one delimited data block the step-results renderer uses (`dataBlock`, AMPR-453) — a hosted run's state carries its step results, which are tool output — and the question follows the block, outside it. The digest is still of the state as given, not of the rendered block. The reply is read through `LLMResponseParser.cleanJsonResponse`'s first-value rule (AMPR-463): a reply that parses as it stands is read unchanged, and otherwise the first complete JSON value — inside the first fence, then anywhere — is the answer, so a fenced object followed by prose is an answer, not a parse failure. A requirement on the constructor's `RoutingContext` has its floor raised to rung `ONE` before it is forwarded, and a context with none is forwarded as it is (AMPR-459).
- `llm/decide/DeterministicDecisionClient.kt` — a `(state, question) -> answerKey` function. Measured one-hot, `ON_DEVICE`.
- `llm/decide/BandFitGuard.kt` — the one check at the mouth of a band fit; refuses `SELF_REPORTED` and distribution-less judgments.
- `llm/decide/Digests.kt` — `stateDigest(state)` (full SHA-256 hex) and `Question.version`.
- `agents/domain/reasoning/ConfidenceSource.kt`, `Confidence.kt` — `ConfidenceSource { MEASURED, SELF_REPORTED }`; every `Confidence` level has `source == SELF_REPORTED` and `asProbability()` is the F10 mapping (`0.25 / 0.5 / 0.75`).
- `agents/domain/reasoning/AgentReasoning.kt` — `decide(state, questions, phase, causedBy)` beside `callLLM` / `callLLMForJson`.
- `agents/domain/event/CognitiveEvent.kt` — `JudgmentRecorded`.
- `agents/config/AgentConfiguration.kt` — `upstreamDecisionClient`, nullable and `@Transient`, threaded from `Ampere.fromEnvironment`, `AgentFactory`, `SparkAgentFactory`, `AmpereRuntime` / `ChargePhase` and `ArcSession.create(…, decision)` exactly as the chat seam is.

## Invariants

- **A decision transport is opted into, never inherited.** `AgentConfiguration.upstreamDecisionClient` defaults to null, and `AgentReasoning.decide` throws `MissingUpstreamDecisionClientException` in that state. There is no default implementation and no silent fallback to the model-backed adapter; a consumer that wants its generative model to answer names `ModelBackedDecisionClient`. Injecting a chat transport does not conjure a decision one.
- **Every answer is declared before the call.** A `Judgment.answer` is one of its question's `answerKeys`, and every adapter rejects an answer outside them with `MalformedDecisionResponseException` rather than passing it through. `criteria` is required on every question type because it is the only place options are declared.
- **The wire format is the type.** `Question`'s serialized form, with `type` as discriminator, is the System One request body. Vendor differences are adapter configuration (`bodyExtras`, the endpoint), never a second type.
- **A model snapshot names the maker; the route is `via`.** `ModelSnapshot.providerId` and `modelId` are the maker's (`typesafe` / `jev-1.13.0`) whichever route carried the call, so records of one model reached two ways join on them. The carrier is `ModelSnapshot.via` — a gateway's id, or the maker's own for the direct route — and null means "direct, or not stated", which is every record written before AMPR-435. A gateway's spelling of the model (`typesafe/jev-1.13`) is wire configuration, `DecisionRoute.wireModelId`, and is recorded nowhere.
- **The distribution is optional; the source is not.** `Judgment.distribution` is null when the adapter measured none, and `source` says which kind of confidence the judgment carries. A generative model asked for JSON has no probability channel and must not be given a fabricated one.
- **No band is ever fitted on a self-reported value.** `BandFitGuard` is the entry to every fit; it refuses `SELF_REPORTED` and refuses a measured judgment with no distribution. Every `Confidence` level is self-reported by construction, and the F10 mapping (`asProbability()`) produces a self-reported number still.
- **One record per judgment, through the one door, and never the state.** `AgentReasoning.decide` publishes `JudgmentRecorded` via `AgentEventApi.publish` under the run id, carrying `stateDigest` (SHA-256), `questionId` + `questionVersion`, the distribution when there is one, the band (null in W1), model snapshot, locality, latency, usage and `causedBy`. The state text appears in no event. Without a door nothing is recorded, as with the `ProviderCall*` pair; a transport that throws leaves no record.
- **A judgment is filed under the phase that asked.** `JudgmentRecorded.cognitivePhase` is read by `ArcTraceProjection.phaseNameFor`; a call with no phase falls under whatever phase is active.
- **Record-only callers in the hosted run; no call site acts on a judgment.** The first callers are a hosted run's own "is the goal met?" on the host seat and the verifier seat's declared questions, both after an executed cycle and both opted into (AMPR-421, `HostedRun`). Every answer is recorded and discarded: `RunResult` is identical whatever came back, and mapping a judgment onto a `Verdict` (J6), onto an escalation (J8) or onto a goal-completion decision that ends a run (J9) has no verdict. No *existing* call site switched: `FlowPhase.evaluateGoalCompletion` is unchanged until J9. No new routing rule, rung or locality; `RoutingContext` is reused; metering stays `WattCostAggregator.costFor` (J13).
- **A model-backed judgment never runs on the device.** A judgment is never display text, and rung `ZERO` serves display text only (AMPR-372). `ModelBackedDecisionClient` raises the floor of any requirement on its routing context to `ONE` with `maxOf` — a stricter floor stands — which leaves the on-device model, rated `ZERO`, out of the candidates; a context with no requirement is forwarded unchanged, so the judgment keeps its configured model rather than being switched to cheapest-capable routing (AMPR-459). The adapter cannot see the floor of the agent behind its `AgentLLMService`, which `AgentLLMService` merges in; that one is the caller's to keep at `ONE` or above.
- **Every public parameter is constructible from Swift.** Strings, maps of strings, lists of strings, enums and data classes only. A Swift-implemented `UpstreamDecisionClient` would need the callback-style bridge the chat seam has; it is not in W1.

## Common operations

- **Ask a question** — `reasoning.decide(state, mapOf("done" to Question.Noul.of("Does the outcome satisfy the goal?", whenTrue = "…", whenFalse = "…")), phase = CognitivePhase.PLAN, causedBy = actionId)`. Read `response.judgments["done"]`.
- **Bind a hosted decision model** — `HostedSystemOneDecisionClient.of(DecisionModels.Jev_1_13, DecisionRoutes.jevViaOpenRouter(), credential)`, then `Ampere.fromEnvironment(…, upstreamDecisionClient = it)`. Jev on a TypeSafe key is `DecisionRoutes.jevViaTypeSafe()`; Clef is `of(DecisionModels.Clef, DecisionRoutes.clefOnWorkersAi(accountId), credential)`, and Clef-flash passes `DecisionModels.ClefFlash` and `flash = true`. Nothing checks that the route serves the model, so pair them as `DecisionRoutes` documents. The primary constructor still takes a hand-built snapshot for a model that has no name yet.
- **Turn a heuristic into an explicit judgment** — `DeterministicDecisionClient(name = "any-success") { state, question -> … }`. The record names the rule on `modelSnapshot.modelId`.
- **Make a self-reported confidence a number** — `confidence.asProbability()`. Do not put the result anywhere a band is fitted.
- **Have a hosted run ask whether it met its goal** — give the host seat a `HostedAgent.upstreamDecisionClient` and open the run with `RunPolicy(goalCheck = GoalCheck.Record)`. One decision call per executed cycle, one `JudgmentRecorded` under `questionId == RunQuestions.GOAL_MET_ID`, and nothing about the run changes. A verifier seat's own `HostedAgent.questions` are asked beside it, all of them in one request, on that seat's transport.
- **Store a confidence on a belief row** — pass a `BeliefConfidence` (`agents/domain/knowledge/BeliefConfidence.kt`, AMPR-417): `SelfReported(level)` is written as its F10 mapping, `Measured(probability)` as given. The `KnowledgeStore.confidence` column keeps the number and not the source, so nothing fits a band on it.
- **Stream the records** — `eventApi.onJudgmentRecorded { … }` or `events.judgmentRecordedEvents()`; filter on `source == MEASURED` before fitting anything.
- **Join a record back to its state** — compare `stateDigest(state)` to the record's `stateDigest`. The state itself is not recoverable from the record, by design.

## Anti-patterns

- *Falling back to `ModelBackedDecisionClient` when no decision transport is bound* — it turns a configuration gap into a generative call nobody opted into, with a self-reported confidence someone will later mistake for a measured one. AMPR-236 closed this door for the chat seam; it stays closed here.
- *Tunnelling a decision through `callLLMForJson` and calling it measured* — a generative model's JSON `probability` field is text it generated, not a distribution it computed. It is `SELF_REPORTED` or it is a lie.
- *Putting the state on the record "for debugging"* — state may hold a person's data, and the record is persisted and streamed. The digest is the join key.
- *Rendering a state that is unique per call* — the digest is the only join key back to what was asked about, so a state carrying a minted id or a timestamp makes every digest unique and the join worthless. `RunObservation.asDecisionState` renders a fixed key order and leaves both out for exactly that reason.
- *Declaring a `Choice`'s options anywhere but `criteria`* — the wire format has no separate options list, and a key without a description is a key the model decides against blind.
- *Running `ModelBackedDecisionClient` on the on-device model* — a judgment is never display text, so it never runs at rung `ZERO` (AMPR-372, [`docs/ampr-372-rung-zero-eligibility.md`](../ampr-372-rung-zero-eligibility.md)). Measured through the shipped adapter on the AMPR-422 goal-met corpus, the on-device model called 59.0% of unmet runs met (85 of 144) against 15.0% for Sonnet 5 on the same prompts; it notices text that says something went wrong and misses structure that is wrong (22 of 24 `wrong_target`, 21 of 24 `partial`, `empty_result` and `unverifiable`). Every one of its 288 replies parsed, so a parse-rate gate would have admitted it. The adapter now raises a forwarded requirement's floor to `ONE` (AMPR-459), so a context that asks for capability no longer puts a judgment on the device.
- *Building `ModelBackedDecisionClient` over a service whose agent is floored at `ZERO`* — `AgentLLMService` merges the agent's floor into every call, so a service built on `OnDeviceAssistantAgent`, the display-text identity, routes every judgment cheapest-capable at `ZERO`, onto the device, and the adapter never sees that floor to raise it. Build it on the service of an agent that runs the loop.
- *Treating the model-backed adapter's `confidence` as `p(answer)`* — it is the F10 mapping of a word. `distribution == null` is the tell; `BandFitGuard` is the guard.
- *Putting a gateway's id in `ModelSnapshot.providerId`* — records of one model reached two ways stop joining: Jev through OpenRouter and Jev direct would read as two models. The maker goes in `providerId`, the gateway in `via`; `HostedSystemOneDecisionClient.of` does both.
- *Reusing `Verdict`, `Decision`, `Reading`, `Meter` or `Calibration` for these types* — each is taken and means something else (`Decision` is an ask to a person). A model's answer is a `Judgment`.
- *Defaulting a `Score` answer from the expected score when probabilities are present* — the answer is the level the mass favours (argmax), and `expectedScore()` is a separate reading of the same distribution.
