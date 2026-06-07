# CognitiveRelay Recon Report
**Branch / commit:** `claude/cognitive-relay-recon-Npm4j` @ `456e491f11e3792d4e14e1bdfdc87ced01c80034`
(tip: `AMPR-185 #532: ampere-eval 3 — Meter & Tolerance grading interface (#541)`, 2026-06-04)
**Date:** 2026-06-07

## Summary (5 bullets max)
- **Capability-routing readiness:** *Designed differently than assumed — the relay is fully wired, but as a **rule-based config selector**, not a capability matcher.* There is no constraint→capability matching layer at all.
- **Phase model:** **Matches PROPEL exactly.** Single `CognitivePhase` enum: `PERCEIVE / RECALL / OBSERVE / PLAN / EXECUTE / LEARN`. No `OPTIMIZE`/`EVALUATE` anywhere in the repo. No second/routing-specific phase enum.
- **Declaration side present:** **No.** Providers declare `id/name/availableModels/apiToken/client` only — no capability manifest, no descriptor registry beyond a hardcoded 3-provider list.
- **Cost/Watt model present:** **Partially — but request/usage-side, not provider-side.** `WattCostAggregator` (tokens→Watts × account `UsageTier`) and a `ProviderPricingCalculator` (USD via external catalog) exist; neither lives on the `AIProvider` interface.
- **Runtime availability concept present:** **No.** Zero health/availability/reachability concept in routing or provider code. All providers assumed always-on.

> ⚠️ **Vocabulary mismatch up front:** Several seed symbols **do not exist as named** in the codebase. `CognitiveRequest`, `RoutingConstraints`, `ProviderCapability`, `CapabilityTier` → **not found** (verified by word-boundary grep, zero matches). `ProviderId` exists but is a `typealias String` in the *provider* package, not routing. `EventSerializerBus` → actual name is **`EventSerialBus`**. The routing package is **`link.socket.ampere.agents.domain.routing`**, not `link.socket.ampere.routing`. The relay's method is **`resolve(...)` / `resolveWithMetadata(...)`**, not `relay(...)`.

---

## Findings by target (A–I)

### A. Phase model — reconciled to PROPEL? ✅ **Yes, fully.**

There is **one** phase enum, used by both the loop and routing. `ampere-core/.../cognition/sparks/PhaseSpark.kt:292`:

```kotlin
@Serializable
enum class CognitivePhase {
    PERCEIVE,
    RECALL,
    OBSERVE,
    PLAN,
    EXECUTE,
    LEARN,
}
```

Routing imports this exact enum — no separate routing phase enum, no mapping table needed. `RoutingContext.kt:5` and `RoutingRule.kt:5`:

```kotlin
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
```

A repo-wide grep for `OPTIMIZE|EVALUATE` returned **no matches**. The drift you feared (carrying `OPTIMIZE`/`EVALUATE`, lacking `OBSERVE`/`LEARN`) is **already resolved**.
*Interpretation: phase model is ground-truth-correct; no reconciliation work needed.*

### B. Relay implementation status — interface only or wired? **Wired (as a selector).**

Interface — `routing/CognitiveRelay.kt:17`. Note the method is `resolve`, **not `relay`**, and it returns an `AIConfiguration`, it does **not** execute the call:

```kotlin
interface CognitiveRelay {
    val config: RelayConfig
    suspend fun resolve(
        context: RoutingContext,
        fallbackConfiguration: AIConfiguration,
    ): AIConfiguration
    suspend fun resolveWithMetadata(
        context: RoutingContext,
        fallbackConfiguration: AIConfiguration,
    ): RoutingResolution = RoutingResolution(
        configuration = resolve(context, fallbackConfiguration),
        reason = "relay",
    )
    suspend fun updateConfig(newConfig: RelayConfig)
}
```

Concrete impl — `routing/CognitiveRelayImpl.kt:26` (first-match rule eval, mutex-guarded hot-swap, emits event):

```kotlin
class CognitiveRelayImpl(
    initialConfig: RelayConfig = RelayConfig(),
    private val eventBus: EventSerialBus? = null,
) : CognitiveRelay {
    ...
    override suspend fun resolveWithMetadata(
        context: RoutingContext,
        fallbackConfiguration: AIConfiguration,
    ): RoutingResolution {
        val currentConfig = mutex.withLock { _config }
        val matchedRule = currentConfig.rules.firstOrNull { rule -> rule.matches(context) }
        val selectedConfig = matchedRule?.configuration
            ?: currentConfig.defaultConfiguration
            ?: fallbackConfiguration
        ...
        emitRouteSelected(context, decision)
        return RoutingResolution(configuration = selectedConfig, reason = ruleDescription)
    }
```

**Four implementations of the interface exist** (flag divergence):
- `CognitiveRelayImpl` (production) — `routing/CognitiveRelayImpl.kt`
- `CognitiveRelayPassthrough` (object, always returns fallback) — `routing/CognitiveRelayPassthrough.kt:11`
- `PlaybackRelay` (deterministic replay for eval/CI) — `ampere-eval/.../eval/relay/PlaybackRelay.kt:94`
- `SpyRelay` (test double) — `ampere-eval/.../PlaybackRelayTest.kt:189`

**Call sites of `resolve`/`resolveWithMetadata`:** exactly **one production caller** — `AgentLLMService.call(...)` (`reasoning/AgentLLMService.kt:167`), and it is **opt-in / nullable**:

```kotlin
val routingResolution = if (routingContext != null) {
    agentConfiguration.cognitiveRelay?.resolveWithMetadata(
        context = routingContext,
        fallbackConfiguration = agentConfiguration.aiConfiguration,
    ) ?: RoutingResolution(
        configuration = agentConfiguration.aiConfiguration,
        reason = "agent_configuration",
    )
} else { ... }
```

`AgentConfiguration.cognitiveRelay` defaults to `null` (`config/AgentConfiguration.kt:20`, `@Transient val cognitiveRelay: CognitiveRelay? = null`). So unless a caller both sets a relay *and* passes a `routingContext`, calls fall straight through to the agent's own `aiConfiguration`. All other `resolve*` references are tests. The docs claim "All LLM calls go through the relay" (`docs/concepts/cognitive-relay.md:65`) — that's an aspirational invariant, not enforced; the relay is bypassable by design today.
*Interpretation: the routing seam is real and exercised, but it routes among **pre-configured `AIConfiguration`s**; it never executes inference and is off by default.*

### C. Constraint vocabulary — what exists today?

`RoutingConstraints` → **not found.** The closest merged concept is `RoutingRule` (a sealed predicate→config) plus `RoutingContext` (the matchable inputs).

`RoutingContext.kt:24`:
```kotlin
@Serializable
data class RoutingContext(
    val phase: CognitivePhase? = null,
    val agentId: AgentId? = null,
    val agentRole: String? = null,
    val workflowId: String? = null,
    val preferredReasoning: RelativeReasoning? = null,
    val preferredSpeed: RelativeSpeed? = null,
    val tags: Set<String> = emptySet(),
)
```

`RoutingRule.kt:17` — five variants: `ByPhase`, `ByAgent`, `ByRole`, `ByFeatures`, `ByTag`, each carrying a target `AIConfiguration`.

`ProviderCapability` / `CapabilityTier` → **not found** (zero word-boundary matches). The nearest "tier" concepts are unrelated:
- `RelativeReasoning { LOW, NORMAL, HIGH }` and `RelativeSpeed { SLOW, NORMAL, FAST }` — `model/AIModelFeatures.kt:16` (model *features*, the only quality axes routing can express today, via `ByFeatures` / `preferredReasoning`/`preferredSpeed`).
- `UsageTier { FREE, TIER_1..TIER_5 }` — `domain/ai/UsageTier.kt` (account billing tier, fed to the Watt aggregator).
- `Capability` interface — `domain/capability/Capability.kt:5` — is an **agent-tool/function** capability (`val impl: Pair<String, FunctionProvider>`), **unrelated** to provider routing.

*Interpretation: the on-device manifest cannot be designed "against" existing `ProviderCapability`/`CapabilityTier` enums — they don't exist. The existing quality vocabulary is `RelativeReasoning`/`RelativeSpeed`/`SupportedInputs`.*

### D. Declaration side — the half we think is missing. **Confirmed missing.**

Providers declare only identity + models + auth + client. `domain/ai/provider/AIProvider.kt:15`:

```kotlin
typealias ProviderId = String

sealed interface AIProvider<
    TD : AITool,
    L : AIModel,
    > {
    val id: ProviderId
    val name: String
    val availableModels: List<L>
    val apiToken: String
    val client: Client
    companion object Companion {
        val ALL_PROVIDERS = listOf(
            AIProvider_Anthropic,
            AIProvider_Google,
            AIProvider_OpenAI,
        )
        ...
```

There is **no `ProviderDescriptor`, no capability manifest, no dynamic provider registry** — just the hardcoded `ALL_PROVIDERS` list of three cloud providers (`sealed interface`, so it is closed — adding a provider is a source change). The runtime variant `RuntimeAIProvider.kt:7` only varies `baseUrl`; it still constructs an OpenAI-compatible `client` and declares no capabilities.

**Selection logic:** the relay does **not** match against provider-declared capabilities, and it is **not** a `constraints→ProviderId` table either. It matches a `RoutingContext` against ordered `RoutingRule` predicates; each rule statically carries a full `AIConfiguration` (provider+model). First match wins, else `defaultConfiguration`, else the caller's fallback (see B). E.g. `RoutingRule.kt:30`:

```kotlin
@Serializable
data class ByPhase(
    val phase: CognitivePhase,
    override val configuration: AIConfiguration,
) : RoutingRule {
    override fun matches(context: RoutingContext): Boolean =
        context.phase == phase
}
```

*Interpretation: routing today is "operator wires phase/role/tag → a specific configured model." The provider has no voice. The entire "providers declare what they can do, relay matches requirement to declaration" half is **greenfield**.*

### E. Cost / Watt model. **Present but request-side, not provider-side.**

No cost field on `AIProvider` or `AIConfiguration`. Two adjacent mechanisms exist:

**Watts** — `trace/WattCostAggregator.kt:12`, derived from *observed token usage* × account tier multiplier, not declared by a provider:
```kotlin
class WattCostAggregator(
    private val usageTier: UsageTier = UsageTier.TIER_1,
) {
    fun costFor(usage: TokenUsage): WattCost {
        ...
        return WattCost(
            ...
            watts = totalTokens.toDouble() / TOKENS_PER_WATT * multiplierFor(usageTier),
        )
    }
    companion object {
        const val TOKENS_PER_WATT: Double = 1_000.0
        fun multiplierFor(usageTier: UsageTier): Double = when (usageTier) {
            UsageTier.FREE -> 0.5
            UsageTier.TIER_1 -> 1.0
            ...
```

**USD** — `domain/ai/pricing/ProviderPricingCalculator.kt:3`, keyed by `providerId/modelId` against a separate `BundledProviderPricingCatalog` (per-million-token tiered pricing). Provider-*keyed*, but external to the provider object.

*Interpretation: there is a Watt unit and a token→Watt mapping, but it measures **what a call cost after the fact**. A 0W on-device surface would need a **provider-side declared** cost (e.g. `watts = 0`), which has no home in the current `AIProvider` interface. This is decisive: the "0W execution surface" framing has no merged anchor yet.*

### F. Runtime availability. **Not found.**

Grep for `health|isAvailable|reachable|Availability|circuit|offline|online` across `**/routing/**` → **no matches**. `AIProvider` has no `isAvailable()`/health concept. `RoutingRule.matches()` predicates examine only the `RoutingContext`, never provider state. `RoutingEvent` *does* define a `RouteFallback` event (`event/RoutingEvent.kt:66`, with `failedProvider`/`failureReason`) — but nothing in `CognitiveRelayImpl` ever **emits** it (only `RouteSelected` is emitted, `CognitiveRelayImpl.kt:91`). So even the failure/fallback vocabulary is designed-but-unwired.
*Interpretation: there is no device-gating / conditional-availability concept. For on-device (must be gated on device support, model presence, thermal/battery), this must be built from scratch — and `RouteFallback` is a dangling, unemitted hook you could adopt.*

### G. The experimental local / non-API provider seam.

There is **no local/on-device LLM provider** in the tree. Grep for `on-device|local inference|llama|ollama|gguf|non-API|relay-local` (case-insensitive) hit only knowledge/memory-store files ("local" knowledge scope) — **no LLM seam**.

The non-API injection contracts that **do** exist are two layered seams (documented verbatim in `llm/UpstreamLlmClient.kt:8-55`):

1. **`LlmProvider` typealias** — `domain/llm/LlmProvider.kt:23`, the simplest "bring your own backend" seam:
```kotlin
typealias LlmProvider = suspend (prompt: String) -> String
```
Set on `AgentConfiguration.llmProvider`; in `AgentLLMService.call` it **short-circuits before the relay and before the upstream client** (`AgentLLMService.kt:125`, `agentConfiguration.llmProvider?.let { provider -> ... return provider(combinedPrompt) }`).

2. **`UpstreamLlmClient`** — `llm/UpstreamLlmClient.kt:57`, the message-shaped seam, runs *after* relay selection:
```kotlin
interface UpstreamLlmClient {
    suspend fun call(
        request: ChatCompletionRequest,
        configuration: AIConfiguration,
    ): ChatCompletion
}
object BundledUpstreamLlmClient : UpstreamLlmClient {
    override suspend fun call(
        request: ChatCompletionRequest,
        configuration: AIConfiguration,
    ): ChatCompletion = configuration.provider.client.chatCompletion(request)
}
```
Note both seams are non-streaming and `UpstreamLlmClient` returns an OpenAI `ChatCompletion` type — i.e. the "non-API" path still speaks the OpenAI request/response shape unless you use the simpler `LlmProvider` string seam.

The Koog path (`domain/koog/KoogAgentFactory.kt:21`) is **hardcoded to the 3 cloud executors** and returns `null` for anything else — no local executor:
```kotlin
val promptExecutor = when (provider.id) {
    AIProvider_Anthropic.id -> simpleAnthropicExecutor(provider.apiToken)
    AIProvider_Google.id -> simpleGoogleAIExecutor(provider.apiToken)
    AIProvider_OpenAI.id -> simpleOpenAIExecutor(provider.apiToken)
    else -> return null
}
```

**Working example / test:** Yes for the `LlmProvider` seam — `CustomLlmProviderIntegrationTest.kt` (jvmTest) **executes** a fake provider end-to-end through `AgentLLMService` (`it.kt:46` "custom provider is called when configured", returns `"Mock response from custom provider"`). It is a closure mock, not a real local model, but the **execution path is real and verified**, not stubbed. There is **no** test exercising a real non-API/local *model*.
*Interpretation: the cheapest contract a local surface must satisfy today is the `LlmProvider` string seam (which bypasses routing entirely) or `UpstreamLlmClient` (which honors routing but expects OpenAI shapes). Neither integrates with the relay's selection-by-capability because that doesn't exist yet.*

### H. Observability. **`ProviderRoutedEvent` not found by that name; `RoutingEvent.RouteSelected` is defined and IS emitted.**

No symbol `ProviderRoutedEvent` exists. The merged equivalent is `RoutingEvent` — `event/RoutingEvent.kt:19`, with two variants `RouteSelected` and `RouteFallback`. `RouteSelected` **is actually emitted** through the bus inside the relay path — `CognitiveRelayImpl.kt:86`:

```kotlin
private suspend fun emitRouteSelected(
    context: RoutingContext,
    decision: RoutingDecision,
) {
    eventBus?.publish(
        RoutingEvent.RouteSelected(
            eventId = generateUUID("routing"),
            timestamp = Clock.System.now(),
            eventSource = context.agentId?.let { EventSource.Agent(it) } ?: EventSource.Human,
            agentId = context.agentId,
            phase = context.phase,
            decision = decision,
        ),
    )
}
```

The bus is **`EventSerialBus`** (not "EventSerializerBus"): `agents/events/bus/EventSerialBus.kt`. Emission is verified by `CognitiveRelayImplTest.kt:108` ("emits RouteSelected event through EventBus"). Caveats: emission is conditional on a non-null `eventBus` being injected, and `RouteFallback` is **defined but never emitted** anywhere. Separately, `AgentLLMService` emits richer `ProviderCallStartedEvent`/`ProviderCallCompletedEvent` (with `routingReason`, `providerId`, `modelId`, `cognitivePhase`) around the actual call (`AgentLLMService.kt:361,383`).
*Interpretation: route-selection observability is wired and tested; route-*failure*/fallback observability is designed-but-unwired.*

### I. Module & platform structure.

- Routing code lives in **`ampere-core`**, package **`link.socket.ampere.agents.domain.routing`** (path `ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/routing/`). Modules: `ampere-core, -android, -compose, -desktop, -cli, -phosphor, -eval` (`settings.gradle.kts`).
- **Is `ampere-core` framework-free?** It is **commonMain (KMP)** with `jvmMain/iosMain/jsMain/wasmJsMain` source sets, so no *platform* (Android/JVM-only) imports leak into common. **However, `commonMain` is NOT SDK-free**: it imports third-party LLM SDKs directly — `com.aallam.openai.*` (e.g. `AIProvider.kt:5`, `UpstreamLlmClient.kt:3`) and `ai.koog.*` (`KoogAgentFactory.kt:1`). The doc invariant "cognition code never imports a provider SDK" holds for `agents/domain/routing` & `reasoning` (they touch only `AIConfiguration`), but the lower `domain/ai` and `domain/koog` layers are SDK-coupled within common.
- **Clean `expect`/`actual` reference** for the future `:ampere-relay-local-{platform}` bindings — `util/Dispatchers.kt` (common) + `util/Dispatchers.jvm.kt` (actual):

```kotlin
// commonMain — Dispatchers.kt
expect val ioDispatcher: CoroutineDispatcher
```
```kotlin
// jvmMain — Dispatchers.jvm.kt
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
```
(Many more exist: `util/UUID`, `util/FileSystem`, `io/FileOperations`, `pause/ChannelAvailability`, `api/Ampere` — all follow the same `X.kt` expect / `X.<platform>.kt` actual convention.)

---

## Designed-vs-merged discrepancy table

| Item | Design (our assumption) | Merged reality | Gap |
|---|---|---|---|
| Package | `link.socket.ampere.routing` | `link.socket.ampere.agents.domain.routing` | Rename your mental model; no top-level routing pkg |
| Entry method | `relay(...)` executes routing | `resolve()` / `resolveWithMetadata()` **select an `AIConfiguration`**; never execute | Relay is a *chooser*, not an *executor* |
| `CognitiveRequest` | exists | **not found** | Inputs are `RoutingContext`; no request type |
| `RoutingConstraints` | exists | **not found** | Closest is `RoutingRule` (predicate) + `RoutingContext` |
| `ProviderCapability` enum | exists, to design manifest against | **not found** | Must be created; only `RelativeReasoning/RelativeSpeed/SupportedInputs` exist |
| `CapabilityTier` enum | exists | **not found** | Unrelated `UsageTier` (billing) is the only tier enum |
| `ProviderId` | a routing-layer type | `typealias String` in provider pkg | Just a string |
| Phase enum | maybe drifted (`OPTIMIZE`/`EVALUATE`) | **PROPEL-correct**, single enum | **No gap — already done** |
| Two phase enums (loop vs routing) | possible | **One** shared `CognitivePhase` | No mapping needed |
| Selection mechanism | capability-match OR constraints→`ProviderId` table | ordered `RoutingRule` predicates → static `AIConfiguration`, first-match | Capability matching is greenfield |
| Provider declares capabilities | yes (descriptor/registry) | **No** — hardcoded `ALL_PROVIDERS` (3), sealed | Declaration side entirely missing |
| Provider-side cost / Watts | yes | request-side only (`WattCostAggregator` + external pricing catalog) | No declared/0W cost on provider |
| Runtime availability/health | maybe | **None** | Must build; `RouteFallback` is an unemitted hook |
| `ProviderRoutedEvent` via `EventSerializerBus` | yes | `RoutingEvent.RouteSelected` via **`EventSerialBus`**, emitted & tested | Name differs; `RouteFallback` unwired |
| Local/non-API provider | experimental seam present | **No local LLM**; only `LlmProvider` string seam + `UpstreamLlmClient` (OpenAI-shaped); Koog hardcodes 3 cloud | On-device provider is greenfield |
| All calls go through relay | invariant enforced | relay is **nullable, opt-in**, bypassable; 1 prod call site | Doc invariant ≠ code reality |

---

## Open questions surfaced for the architect

1. **The relay is a selector, not an executor.** It picks an `AIConfiguration`; the actual call happens later in `AgentLLMService` via `UpstreamLlmClient`/`BundledUpstreamLlmClient`. "Local inference as a 0W execution surface routed by cognitive requirement" therefore splits into two unrelated places today: (a) *selection* (relay/rules) and (b) *execution* (`UpstreamLlmClient` or the `LlmProvider` short-circuit). Which seam should the on-device surface plug into? The `LlmProvider` string seam is easiest but **bypasses the relay and all routing observability** — so a local surface wired there would be invisible to `RouteSelected`/trace.

2. **There is no provider-capability vocabulary to design "against."** You'll be defining `ProviderCapability`/`CapabilityTier`/cost from zero. Decide whether to (a) bolt declarations onto the `sealed interface AIProvider` (forces opening the sealed hierarchy / touching the 3 cloud providers) or (b) introduce a parallel `ProviderDescriptor` registry that the relay consults — the latter keeps `AIProvider` (SDK-coupled) untouched and is more KMP-friendly for a local module.

3. **Routing is "first-match rule," explicitly anti-"best-match"** (doc invariant, `cognitive-relay.md:66`). Capability/requirement matching is inherently best-match. Adding it is a **philosophy change**, not just a feature — needs an explicit decision and probably a new `RoutingRule.ByCapability` variant rather than retrofitting the existing predicates.

4. **0W has no anchor.** Watts are computed post-hoc from token usage × billing tier. A device-local model produces tokens too — so naïvely it would accrue Watts. You need an explicit provider-declared cost override (and the Watt aggregator currently has no path to read provider-declared cost).

5. **Device-gating needs a new availability concept** *and* a wiring of the already-defined-but-dead `RoutingEvent.RouteFallback`. Consider whether availability is a `RoutingContext` input (device state) or a provider/descriptor predicate — the current `matches()` only sees context, not provider state.

6. **`RelayConfig` and `RoutingRule` are `@Serializable` and embed full `AIConfiguration`s**, but `AIConfiguration`/`AgentConfiguration.cognitiveRelay`/`llmProvider` are `@Transient`/non-serializable in practice (provider carries a live `client`). Clarify how a local-provider routing config is meant to be persisted/declared (YAML? the `ampere.example.yaml`?) — the serialization story for provider-bearing configs is currently half-transient.

7. **Module placement:** the suggested `:ampere-relay-local-{platform}` would depend on `ampere-core`'s routing interfaces (clean, common) — but to *execute* it must satisfy `UpstreamLlmClient` (OpenAI `ChatCompletion` shape) or `LlmProvider` (string). Confirm the binding target before designing the `expect`/`actual` split; `util/Dispatchers.kt` is the reference pattern to mirror.

*(No code was modified. This is a read-only report.)*
