---
concept: DecideSeam
status: experimental
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/llm/decide/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/reasoning/Confidence.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/reasoning/ConfidenceSource.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/CognitiveEvent.kt
related: [CognitiveRelay, CognitionTrace, PropelLoop, EventSerialBus, Probe]
last_verified: 2026-10-09
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
door. W1 (AMPR-384) is shadow only: the seam exists, records, and nothing in
the loop calls it yet.

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
- `llm/decide/Judgment.kt` — `Judgment(answer, distribution, source, modelSnapshot, locality, confidence)` and `ModelSnapshot(providerId, modelId, revision)`. `distribution` is null when the adapter measured none; `confidence` is `p(answer)` or the F10 mapping of a self-report.
- `llm/decide/DecisionRequest.kt` — `DecisionRequest(state, questions by id)` and `DecisionResponse(judgments by id, usage)`.
- `llm/decide/UpstreamDecisionClient.kt` — the seam (`decide(request, configuration)`), `MissingUpstreamDecisionClientException`, `MalformedDecisionResponseException`.
- `llm/decide/HostedSystemOneDecisionClient.kt` — one Ktor POST, no SDK. Endpoint + credential + model snapshot + body extras (`"model"` for OpenRouter's Jev or Workers AI's Clef). Accepts the bare body and Cloudflare's `{"result": …, "success": true}` envelope. Measured, `CLOUD`.
- `llm/decide/ModelBackedDecisionClient.kt` — the question rendered as a prompt to `AgentLLMService.callDetailed`, answered as JSON. `SELF_REPORTED`, no distribution; the model's own level lands on `confidence` through F10.
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
- **The distribution is optional; the source is not.** `Judgment.distribution` is null when the adapter measured none, and `source` says which kind of confidence the judgment carries. A generative model asked for JSON has no probability channel and must not be given a fabricated one.
- **No band is ever fitted on a self-reported value.** `BandFitGuard` is the entry to every fit; it refuses `SELF_REPORTED` and refuses a measured judgment with no distribution. Every `Confidence` level is self-reported by construction, and the F10 mapping (`asProbability()`) produces a self-reported number still.
- **One record per judgment, through the one door, and never the state.** `AgentReasoning.decide` publishes `JudgmentRecorded` via `AgentEventApi.publish` under the run id, carrying `stateDigest` (SHA-256), `questionId` + `questionVersion`, the distribution when there is one, the band (null in W1), model snapshot, locality, latency, usage and `causedBy`. The state text appears in no event. Without a door nothing is recorded, as with the `ProviderCall*` pair; a transport that throws leaves no record.
- **A judgment is filed under the phase that asked.** `JudgmentRecorded.cognitivePhase` is read by `ArcTraceProjection.phaseNameFor`; a call with no phase falls under whatever phase is active.
- **Shadow only in W1.** No existing call site switches to `decide`. `FlowPhase.evaluateGoalCompletion` is unchanged until J9. No new routing rule, rung or locality; `RoutingContext` is reused; metering stays `WattCostAggregator.costFor` (J13).
- **Every public parameter is constructible from Swift.** Strings, maps of strings, lists of strings, enums and data classes only. A Swift-implemented `UpstreamDecisionClient` would need the callback-style bridge the chat seam has; it is not in W1.

## Common operations

- **Ask a question** — `reasoning.decide(state, mapOf("done" to Question.Noul.of("Does the outcome satisfy the goal?", whenTrue = "…", whenFalse = "…")), phase = CognitivePhase.PLAN, causedBy = actionId)`. Read `response.judgments["done"]`.
- **Bind a hosted decision model** — `HostedSystemOneDecisionClient(endpoint, credential, model = ModelSnapshot("openrouter", "typesafe/jev-1.13"), bodyExtras = mapOf("model" to JsonPrimitive("typesafe/jev-1.13")))`, then `Ampere.fromEnvironment(…, upstreamDecisionClient = it)`. For Clef on Workers AI the endpoint is the `/ai/run/@cf/cloudflare/clef` URL and the extra is `"model": "clef"`.
- **Turn a heuristic into an explicit judgment** — `DeterministicDecisionClient(name = "any-success") { state, question -> … }`. The record names the rule on `modelSnapshot.modelId`.
- **Make a self-reported confidence a number** — `confidence.asProbability()`. Do not put the result anywhere a band is fitted.
- **Stream the records** — `eventApi.onJudgmentRecorded { … }` or `events.judgmentRecordedEvents()`; filter on `source == MEASURED` before fitting anything.
- **Join a record back to its state** — compare `stateDigest(state)` to the record's `stateDigest`. The state itself is not recoverable from the record, by design.

## Anti-patterns

- *Falling back to `ModelBackedDecisionClient` when no decision transport is bound* — it turns a configuration gap into a generative call nobody opted into, with a self-reported confidence someone will later mistake for a measured one. AMPR-236 closed this door for the chat seam; it stays closed here.
- *Tunnelling a decision through `callLLMForJson` and calling it measured* — a generative model's JSON `probability` field is text it generated, not a distribution it computed. It is `SELF_REPORTED` or it is a lie.
- *Putting the state on the record "for debugging"* — state may hold a person's data, and the record is persisted and streamed. The digest is the join key.
- *Declaring a `Choice`'s options anywhere but `criteria`* — the wire format has no separate options list, and a key without a description is a key the model decides against blind.
- *Treating the model-backed adapter's `confidence` as `p(answer)`* — it is the F10 mapping of a word. `distribution == null` is the tell; `BandFitGuard` is the guard.
- *Reusing `Verdict`, `Decision`, `Reading`, `Meter` or `Calibration` for these types* — each is taken and means something else (`Decision` is an ask to a person). A model's answer is a `Judgment`.
- *Defaulting a `Score` answer from the expected score when probabilities are present* — the answer is the level the mass favours (argmax), and `expectedScore()` is a separate reading of the same distribution.
