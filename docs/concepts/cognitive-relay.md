---
concept: CognitiveRelay
status: stable
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/routing/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/reasoning/AgentLLMService.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/RoutingEvent.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/ProviderCallStartedEvent.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/ProviderCallCompletedEvent.kt
related: [PropelLoop, EventSerialBus, CognitionTrace]
last_verified: 2026-10-02
---

# CognitiveRelay

## What it is

`CognitiveRelay` is a provider-agnostic LLM router that sits between
`AgentLLMService` and the actual model call. It evaluates declarative
`RoutingRule`s in order against a `RoutingContext` (cognitive phase,
agent identity, task hints) and resolves an `AIConfiguration` to use.
The first matching rule wins; if no rule matches, the agent's default
configuration is used as fallback. Every routing decision emits a
`RoutingEvent`.

## Why it exists

The cognitive layer must not import provider SDKs directly. Three
consequences fall out of that single rule:

1. **Multi-provider portability.** The same agent code runs against
   Anthropic, OpenAI, Google, and any future provider, because the choice
   of provider is *data* (`AIConfiguration`), not *imports*.
2. **Phase-aware model selection.** The PROPEL loop has phases with very
   different latency / quality / cost profiles. Routing rules can use
   `CognitivePhase` in the context to send `PERCEIVE` to a cheap fast
   model and `PLAN` to a more capable one — without the agent code
   knowing or caring.
3. **Observable, replayable decisions.** Every routing decision is an
   event. `ArcTraceProjection` reads `routingReason` from the matched
   `ProviderCallStartedEvent` to show in the trace *why a particular
   model was chosen for a particular phase*. Bypassing the relay means
   bypassing this story, and the trace shows a model call with no
   explanation.

The relay also decouples agent code from configuration churn: switching
the `PERCEIVE` phase to a different model is a config change, not a code
change.

## Where it lives

- `agents/domain/routing/CognitiveRelay.kt` — the interface; `resolve` and `resolveWithMetadata`.
- `agents/domain/routing/CognitiveRelayImpl.kt` — production implementation that matches rules.
- `agents/domain/routing/CognitiveRelayPassthrough.kt` — test/dev helper that always returns the fallback.
- `agents/domain/routing/RelayConfig.kt` — the rule set.
- `agents/domain/routing/RoutingRule.kt` — predicate + target configuration.
- `agents/domain/routing/RoutingContext.kt` — what rules match against (`CognitivePhase`, `agentId`, hints, optional `requirements`).
- `agents/domain/routing/RoutingDecision.kt` — the event payload.
- `agents/domain/routing/capability/` — capability vocabulary (`ProviderCapability`, `CapabilityRequirement`, `CapabilityRung`) and the SDK-free `ModelDescriptor` + `ModelDescriptorRegistry` the `ByCapability` rule matches against, keyed per *model* rather than per provider (AMPR-214). Parallel to `domain/ai/model/AIModel` (left untouched).
- `agents/domain/routing/capability/ModelDescriptorSource.kt` — where a registry's catalog comes from. The framework ships the interface and `DefaultModelDescriptorSource` (the bundled cloud catalog); a consumer supplies its own.
- `agents/domain/routing/local/` — the on-device side. `LocalInferenceEngine` + `LocalCapacity` are the engine contract a platform binds; `InferenceLocality` + `InferenceLocalityClassifier` say where a call ran; `OnDeviceInferenceProjection` folds the relay's and the service's existing events into an `OnDeviceInferenceState`, which `OnDeviceInferenceMonitor` holds live for a surface (AMPR-327). `LocalUnavailableReason` is the one declaration of the reason codes an engine reports.
- `agents/domain/reasoning/AgentLLMService.kt` — the only legitimate caller. `call` returns the text; `callDetailed` returns the text plus the provider, model and routing reason that produced it.
- `llm/DispatchingUpstreamLlmClient.kt` — executes the relay's choice on the bound engine or the cloud transport, refuses a local-designated choice with `LocalEngineNotBoundException` when no engine is bound, and is the `InferenceLocalityClassifier` that knows which.
- `llm/OnDeviceInferenceSession.kt` — the smallest complete local-first path: `ask` through `AgentLLMService`, `state` from the monitor. Built on `OnDeviceAssistantAgent`, the one bundled definition whose floor is rung `ZERO`.
- `agents/domain/event/RoutingEvent.kt`, `ProviderCallStartedEvent.kt`, `ProviderCallCompletedEvent.kt` — observability events.

## Invariants

- **Cognition code never imports a provider SDK.** Anthropic, OpenAI, Google clients live below `domain/ai/` and are addressed exclusively via `AIConfiguration`. A `import com.anthropic` (or equivalent) anywhere under `agents/domain/reasoning/` or `agents/domain/cognition/` is a violation.
- **All LLM calls go through the relay.** `AgentLLMService` is the single entry point. Direct construction of an `AIConfiguration` and a model client in domain code bypasses routing, observability, and rule precedence.
- **Routing rules are evaluated in declared order; first match wins.** Reordering rules changes routing behaviour. The relay is intentionally not "best match" — it is "first match", because predictability beats cleverness. The one principled exception is cost: when the first match is a `ByCapability`, selection is cheapest-capable across the capable candidates (still deterministic — a total order on cost-per-Watt with a `providerId` tie-break, not a scoring engine).
- **Every resolved call emits `ProviderCallStartedEvent` and `ProviderCallCompletedEvent`.** With `cognitivePhase`, `providerId`, `modelId`, and `routingReason` populated. `ArcTraceProjection` joins these to build `ModelInvocationTrace`; missing events produce gaps in the trace.
- **Hot-swap goes through `updateConfig`.** Mutating a `RelayConfig` field in place is not supported. `updateConfig` exists so changes can be observed by long-running reasoning sessions. `updateConfig` swaps *rules*; `ModelDescriptorRegistry.refresh` swaps the *catalog*. They are separate seams and neither substitutes for the other.
- **A failed catalog load never empties the registry.** `refresh` replaces the catalog atomically or not at all: a `Result.failure` from the source leaves the previous catalog fully intact and surfaces the error. A registry that silently emptied itself would turn a transient network blip into blanket `FloorUnmet` — the exact silent downgrade the rung floor exists to prevent. A source that cannot produce a catalog must return `failure`, not an empty list.
- **Where a call ran is decided by what executed it, not by what was selected.** `ModelDescriptor.executesLocally` is the single predicate behind both dispatch and display, and `DispatchingUpstreamLlmClient.localityOf` adds the one fact the catalog cannot know: whether an engine is bound. "On-device" is a claim a person relies on, so anything not provably on the device is `CLOUD` — including a local-designated model with no engine to run on, which the dispatching client refuses rather than runs.
- **A local-designated configuration never leaves on a cloud transport.** When the resolved model `executesLocally` and no engine is bound, `DispatchingUpstreamLlmClient.call` throws `LocalEngineNotBoundException` before anything is sent, and `AgentLLMService` books it on `ProviderCallCompletedEvent.errorType` like any transport error (AMPR-371). `AIProvider_OnDevice.client` throws on read for the same reason: the stand-in provider has no host, and a default client would have POSTed the prompt to `api.openai.com` with an empty token. A model with no descriptor still goes to the bundled transport. Picking a cloud model *instead* is the availability gate's decision, made before the call.
- **An on-device failure is returned, never re-sent.** Availability is decided *before* the call, by the probe the relay's gate reads. Once a prompt has been routed to the device, a failed generation surfaces as a failure; it is not retried on a cloud model. A person shown "on-device" must not find out afterwards that their prompt left.
- **The on-device read model is commutative.** `EventSerialBus` launches each handler on its own coroutine, so a `ProviderCallCompletedEvent` can be folded before its `ProviderCallStartedEvent`. `OnDeviceInferenceProjection` counts rather than tracks open calls, and keeps the later-timestamped fact rather than the last-folded one, so any order ends in the same state.
- **The fallback is not optional.** A relay that can return null on no-match would make every `AgentLLMService` caller responsible for a fallback decision, defeating the point. Every call must produce some `AIConfiguration`.

## Common operations

- **Add a routing rule** — extend `RelayConfig.rules` with a new `RoutingRule` whose predicate examines `RoutingContext`. Order matters; more specific rules earlier.
- **Route by phase** — `RoutingContext.phase` is a `CognitivePhase`; rules can switch on it directly.
- **Route by capability** — set `RoutingContext.requirements` (a `CapabilityRequirement`) on the step and add `RoutingRule.ByCapability` rules ordered most-preferred-first. Each matches only when its target provider's `ProviderDescriptor` (looked up in the injected `ProviderDescriptorRegistry`) `satisfies` the requirement. The registry-aware `RoutingRule.matches(context, registry)` overload is `suspend` (the registry is mutex-guarded) and defaults to the pure `matches`, so the five non-capability rules are unaffected.
- **Route by cost (cheapest-capable)** — when the first matching rule is a `ByCapability`, the relay does not stop at first-match: it ranks *all* capable `ByCapability` candidates by `CostPolicy.usdPerWatt` (cost-per-Watt) and resolves to the cheapest, breaking ties stably by `providerId`. "Prefer on-device" is the limiting case — a local provider priced `CostPolicy.Free` (0W) always wins. The choice is observable via `RoutingEvent.RouteResolved` (chosen / runner-up / `savingsVsRunnerUp`), a sibling to `RouteFallback`. Single-candidate matches and all non-capability rules keep pure first-match. Rates are provider *data* in the registry; selection never hardcodes them. `RouteCostReporter` prints a deterministic dry-run of cheapest route + Watt cost per step across the launch Arcs.
- **Supply your own model catalog** — implement `ModelDescriptorSource` (a `fun interface`; `suspend fun load(): Result<List<ModelDescriptor>>`) and either build the registry from it up front with `InMemoryModelDescriptorRegistry.from(source)`, or pass it alongside a seed so routing works from the bundled catalog until the first load lands. Calling `refresh()` re-reads the source, so model→rung assignments change while the process runs — no relay reconstruction, no framework release.
- **Bind an on-device engine** — implement `LocalInferenceEngine` on the platform (on Apple, subclass `SwiftLocalInferenceEngine` in Swift and adapt it with `toLocalInferenceEngine()`), then hand it to a `DispatchingUpstreamLlmClient` that shares its `ModelDescriptorRegistry` with the relay. The engine's `LocalCapacity` must name the on-device `providerId`, or the availability gate never opens for it. `OnDeviceInferenceSession.create(engine, database)` does all of this for the single-prompt case.
- **Make work eligible for the device** — declare a floor the on-device model clears. It sits at rung `ZERO`, so only an agent or Arc step whose effective floor is `ZERO` (or that declares none and routes by capability) can reach it; floors only raise, so the code agent's `THREE` rules it out for every call that agent makes. Add `minContextTokens` for the prompt plus the output budget, so a prompt that will not fit is routed elsewhere instead of overflowing mid-generation.
- **Show when the on-device model is being used** — collect `OnDeviceInferenceMonitor.state` (or `OnDeviceInferenceSession.state`). `isInUse` is true from a call's start event to its completion; `availability` says whether the device could serve and carries the engine's reason code when it cannot. Construct the monitor with the dispatching client as its classifier. `ui/inference/OnDeviceInferenceIndicator` renders it in Compose.
- **Say where one answer came from** — `AgentLLMService.callDetailed` returns the provider and model with the text; pass them to the classifier. Do not read it back off the event stream, which is dispatched asynchronously.
- **Inspect a routing decision in tests** — call `resolveWithMetadata` instead of `resolve`. The returned `RoutingResolution.reason` is a free-form tag describing which rule matched ("phase=PLAN" / "default fallback").
- **Trace which model handled a phase** — `ArcTraceProjection.project(runId)` returns `ModelInvocationTrace`s keyed by phase, with `routingReason` populated from the routing event.

## Anti-patterns

- **Constructing an `AIConfiguration` in domain code "just for this one call".** That call won't appear in the trace with any routing reason, and the next person who needs to change the model has to find every such call.
- **Encoding routing in `if/else` inside `AgentLLMService`.** The relay exists *because* this gets unmanageable. Predicates belong in `RoutingRule`s where they're inspectable, ordered, and observable.
- **Importing a provider SDK from cognition code.** Even "temporarily" or "for a quick test" — the import lingers, and now `agents/domain/...` is provider-coupled.
- **Treating `routingReason` as an internal detail.** It is read by the trace and displayed to humans debugging cognitive runs. A blank or unhelpful reason ("matched") is a regression in observability.
- **Mutating `RelayConfig` fields directly to "hot-update" rules.** The `updateConfig` path emits the changes; direct mutation is invisible to subscribers and to the trace.
- **Labelling a call on-device because the relay selected the on-device model.** Selection is a decision; execution is a fact. With no engine bound, the selected configuration cannot run at all. Ask the client that ran it.
- **Treating the bundled transport as the "else" branch for every configuration.** It is the else branch for *cloud* configurations. A local-designated one with nothing to run on is a refusal, not a fallback; routing it to `bundled` because an engine is missing is exactly the egress AMPR-371 closed, and the 401 that came back hid the fact that the prompt had already left.
- **Building a placeholder `OpenAI` client for a provider that never dials out.** `createClient` defaults a missing url to OpenAI's host, so the placeholder is a live endpoint with an empty token. A provider with no legitimate host should fail on `client` read, not point somewhere.
- **Falling back to the cloud after the device failed.** It turns a visible failure into an invisible egress. If a step should run in the cloud when the device cannot, that is the availability gate's job, and it happens before the prompt goes anywhere.
- **Holding one `LanguageModelSession` for the engine's lifetime.** The contract is stateless; a session is not. A shared session accumulates transcript against the context window the prompt was measured for, leaks one call's content into the next, and traps when two calls overlap. One session per call.
- **Inventing an event to drive the indicator.** Everything the on-device read model needs is already emitted by the relay and `AgentLLMService`. A second signal for the same fact is a second thing to keep truthful.
- **Rebuilding the relay to change a model's rung.** The registry is the mutable surface — `refresh()` (whole catalog) or `register()` (one descriptor). Tearing down a live relay drops the rule set and every in-flight reasoning session along with it.
