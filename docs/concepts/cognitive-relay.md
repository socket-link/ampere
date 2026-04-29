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
last_verified: 2026-04-29
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
- `agents/domain/routing/RoutingContext.kt` — what rules match against (`CognitivePhase`, `agentId`, hints).
- `agents/domain/routing/RoutingDecision.kt` — the event payload.
- `agents/domain/reasoning/AgentLLMService.kt` — the only legitimate caller.
- `agents/domain/event/RoutingEvent.kt`, `ProviderCallStartedEvent.kt`, `ProviderCallCompletedEvent.kt` — observability events.

## Invariants

- **Cognition code never imports a provider SDK.** Anthropic, OpenAI, Google clients live below `domain/ai/` and are addressed exclusively via `AIConfiguration`. A `import com.anthropic` (or equivalent) anywhere under `agents/domain/reasoning/` or `agents/domain/cognition/` is a violation.
- **All LLM calls go through the relay.** `AgentLLMService` is the single entry point. Direct construction of an `AIConfiguration` and a model client in domain code bypasses routing, observability, and rule precedence.
- **Routing rules are evaluated in declared order; first match wins.** Reordering rules changes routing behaviour. The relay is intentionally not "best match" — it is "first match", because predictability beats cleverness.
- **Every resolved call emits `ProviderCallStartedEvent` and `ProviderCallCompletedEvent`.** With `cognitivePhase`, `providerId`, `modelId`, and `routingReason` populated. `ArcTraceProjection` joins these to build `ModelInvocationTrace`; missing events produce gaps in the trace.
- **Hot-swap goes through `updateConfig`.** Mutating a `RelayConfig` field in place is not supported. `updateConfig` exists so changes can be observed by long-running reasoning sessions.
- **The fallback is not optional.** A relay that can return null on no-match would make every `AgentLLMService` caller responsible for a fallback decision, defeating the point. Every call must produce some `AIConfiguration`.

## Common operations

- **Add a routing rule** — extend `RelayConfig.rules` with a new `RoutingRule` whose predicate examines `RoutingContext`. Order matters; more specific rules earlier.
- **Route by phase** — `RoutingContext.phase` is a `CognitivePhase`; rules can switch on it directly.
- **Inspect a routing decision in tests** — call `resolveWithMetadata` instead of `resolve`. The returned `RoutingResolution.reason` is a free-form tag describing which rule matched ("phase=PLAN" / "default fallback").
- **Trace which model handled a phase** — `ArcTraceProjection.project(runId)` returns `ModelInvocationTrace`s keyed by phase, with `routingReason` populated from the routing event.

## Anti-patterns

- **Constructing an `AIConfiguration` in domain code "just for this one call".** That call won't appear in the trace with any routing reason, and the next person who needs to change the model has to find every such call.
- **Encoding routing in `if/else` inside `AgentLLMService`.** The relay exists *because* this gets unmanageable. Predicates belong in `RoutingRule`s where they're inspectable, ordered, and observable.
- **Importing a provider SDK from cognition code.** Even "temporarily" or "for a quick test" — the import lingers, and now `agents/domain/...` is provider-coupled.
- **Treating `routingReason` as an internal detail.** It is read by the trace and displayed to humans debugging cognitive runs. A blank or unhelpful reason ("matched") is a regression in observability.
- **Mutating `RelayConfig` fields directly to "hot-update" rules.** The `updateConfig` path emits the changes; direct mutation is invisible to subscribers and to the trace.
