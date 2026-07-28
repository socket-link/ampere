# RECON-bench: Arc invocation & Link injection seam

**Issue:** AMPR-190 — ampere-eval 4·recon
**Feeds:** AMPR-186 — ampere-eval 4 (Bench harness)
**Date:** 2026-07-28

---

## A. Arc invocation entry point

An Arc is not a class with a `run()` method — it's data (`ArcConfig`) driven by an
orchestrator, `AmpereRuntime`:

- `ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/arc/AmpereRuntime.kt:36-41`
  ```kotlin
  class AmpereRuntime(
      private val arcConfig: ArcConfig,
      private val projectDir: Path,
      private val fileSystem: FileSystem = systemFileSystem,
      private val maxFlowTicks: Int = 100,
  )
  ```
- `AmpereRuntime.kt:54` — `suspend fun execute(userGoal: String): ArcExecutionResult` drives
  **Charge → Flow → Pulse**:
  - `executeCharge` (line 97): builds `ChargePhase(arcConfig, projectDir, fileSystem)`,
    calls `.execute(userGoal)` → spawns agents via `ArcAgentSpawner` (`ChargePhase.kt:290`),
    which uses a default-constructed `agentFactory: SparkAgentFactory = SparkAgentFactory()`
    (`ChargePhase.kt:291`).
  - `executeFlow` (line 106): builds `FlowPhase(arcConfig, agents = chargeResult.agents, goalTree, maxTicks)`,
    calls `.execute()`.
  - `executePulse` (line 116): builds `PulsePhase(...)`.

**No parameter in this call chain accepts a `CognitiveRelay`.** `AmpereRuntime`,
`ChargePhase`, `ArcAgentSpawner`, and `FlowPhase` have zero constructor params/fields for a
relay. `ArcAgentSpawner.spawn()` (`ChargePhase.kt:293`) calls
`agentFactory.createAgent(id, affinity)` → `SparkAgentFactory.createAgent`
(`SparkAgentFactory.kt:165-181`), which builds a bare `SparkBasedAgent` with no
`_cognitiveRelay` argument — relay stays `null`.

This is a **different factory** from the one wired up in AMPR-219
(`agents/definition/AgentFactory.kt`), which is not used by the Arc path. The Arc invocation
path currently has **no relay-injection seam** — it's dormant even for real runs.

## B. CognitiveRelay

Interface: `ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/routing/CognitiveRelay.kt:18-55`
```kotlin
interface CognitiveRelay {
    val config: RelayConfig
    suspend fun resolve(context: RoutingContext, fallbackConfiguration: AIConfiguration): AIConfiguration
    suspend fun resolveWithMetadata(context: RoutingContext, fallbackConfiguration: AIConfiguration): RoutingResolution
    suspend fun updateConfig(newConfig: RelayConfig)
}
```

`PlaybackRelay` (`ampere-eval/src/commonMain/kotlin/link/socket/ampere/eval/relay/PlaybackRelay.kt:88-173`)
implements this interface directly, replaying a recorded `Trace`'s routing decisions in call
order, with `MissPolicy.Error`/`Delegate` and an optional `liveDelegate: CognitiveRelay?`.

Production wiring (AMPR-219, commit `61c2311b`) is **manual constructor injection**, not DI:
`agents/definition/AgentFactory.kt:144-145` lazily builds
`effectiveCognitiveRelay: CognitiveRelay = cognitiveRelay ?: CognitiveRelayImpl(...)`, passed
only into the `AgentType.CODE` branch of `createAgent` (`AgentFactory.kt:263`). This flows
through `SparkBasedAgent`'s `_cognitiveRelay` param (`SparkBasedAgent.kt:88-101`) into
`AgentConfiguration.cognitiveRelay`, used by `AgentLLMService` to resolve LLM calls.

Since the Arc path uses `SparkAgentFactory`, not `AgentFactory`, and
`SparkAgentFactory.createAgent` has no relay parameter, **Bench cannot inject a
`PlaybackRelay`/relay into an Arc run today** without either:
1. Threading an optional `cognitiveRelay: CognitiveRelay?` through
   `AmpereRuntime → ChargePhase → ArcAgentSpawner → SparkAgentFactory.createAgent →
   SparkBasedAgent`, mirroring the `AgentFactory`/`SparkBasedAgent._cognitiveRelay` precedent
   (mechanical, multi-file, low risk since it's purely additive/optional), or
2. Bypassing `AmpereRuntime` and driving `ChargePhase`/`FlowPhase` manually with agents built
   via the AMPR-219-style `AgentFactory` instead of `SparkAgentFactory`.

**Recommendation: option 1** — it's additive (new optional constructor param defaulting to
`null`/current behavior), doesn't fork the Arc execution path, and reuses the exact pattern
already proven in production by AMPR-219.

## C. Link execution model ("Execute step")

There is no class literally named `Link` in the repo. The "Execute step" referred to in
AMPR-186 is `ExecuteStep`:

`ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/propel/ExecuteStep.kt:33-80`
— single entry point for plugin/MCP tool invocation during the PROPEL "Execute" phase:
```kotlin
class ExecuteStep(private val context: PluginContext, private val userGrantProvider: ...) {
    suspend fun execute(toolId: ToolId, arguments: JsonElement?): ExecuteResult
}
```
Dispatches `McpTool` calls through `context.mcpClientFor(tool).callTool(...)` (line 75).

The broader tool abstraction is `Tool`/`FunctionTool`/`McpTool`
(`agents/execution/tools/`), executed via `ToolExecutionEngine`
(`agents/execution/ToolExecutionEngine.kt`) → `Executor` interface
(`agents/execution/executor/Executor.kt`; implementations `FunctionExecutor`, `McpExecutor`).
Concrete `FunctionTool`s with real side effects: `ToolWriteCodeFile` (writes files),
`ToolCreateIssues` (creates GitHub issues), `git/GitTools` (shells out to git),
`ToolRunTests` (runs test processes), `ToolAskHuman` (notifies a human via `Notifier`).
Read-only tools (`ToolReadCodeFile`, `ToolReadCodebase`, `ToolPlanSteps`,
`KnowledgeQueryTool`) are already effect-free.

**Existing seam:** `Executor` is already an interface (strategy-pattern-ready via
`ToolExecutionEngine.registerStrategy`), but **no no-op/dry-run `Executor` implementation
exists today** and no global dry-run flag exists anywhere in the tool/executor stack.

**Assessment: this is the hard part**, as flagged in the ticket. Cleanest approach: introduce
a `NoOpExecutor : Executor` in `ampere-eval` that intercepts calls before they reach
`FunctionExecutor`/`McpExecutor`, records a `Reading`-relevant no-op result, and never invokes
the real side-effecting code paths. Thread it into `ToolExecutionEngine` per-agent for
bench-mode runs (mirrors how `Executor` is already selected per tool type). This requires new
code but no changes to existing `Executor` implementations — a clean boundary, not a fork.

## D. Run lifecycle / event bus

Bus: `EventSerialBus` (`ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/events/bus/EventSerialBus.kt:29-`),
`publish(event: Event)` (line 45) dispatches to subscribers by `event.eventType` +
`parentEventTypes`. Base hierarchy: `agents/domain/event/Event.kt`. Normally published via
`AgentEventApi.publish(event)` (`agents/events/api/AgentEventApi.kt:71-74`).

Existing lifecycle-style sealed event families to copy the pattern from: `TaskEvent`
(`TaskStarted`/`TaskProgressed`/`TaskCompleted`, `agents/domain/event/TaskEvent.kt:20+`,
published via `AgentEventApi.publishTaskStarted` etc., lines 311-368) and `RoutingEvent`
(`RouteSelected`/`RouteFallback`/`RouteResolved`/`RouteFloorUnmet`,
`agents/domain/event/RoutingEvent.kt:21+`).

**`AmpereRuntime`, `ChargePhase`, and `FlowPhase` publish no events today** — grep for
`eventApi|emit|publish|bus\.` across `FlowPhase.kt` returns nothing, and
`ArcAgentSpawner`'s default `SparkAgentFactory()` leaves `eventApiFactory = null`. An Arc run
today emits zero bus events by default.

This means Bench isn't overriding existing lifecycle instrumentation — it's adding new
instrumentation. The `TaskEvent`/`AgentEventApi.publish` pattern is directly copyable for a
new `BenchEvent` sealed interface (`BenchRunStarted`/`ProbeGraded`/`BenchRunCompleted`) living
in `ampere-eval`, published by `Bench` itself around each probe run — Bench does not need
`AmpereRuntime`/`FlowPhase` to emit anything, since Bench wraps the whole `Arc.execute()`
call and knows start/completion by construction. Per-tick/step events are optional/deferred
since nothing upstream provides that granularity today.

## Effect-free strategy assessment

Two of three seams need new (additive) code; one is a straightforward reuse:

1. **Relay injection** — moderately painful but low-risk: thread an optional
   `cognitiveRelay: CognitiveRelay?` through `AmpereRuntime → ChargePhase → ArcAgentSpawner →
   SparkAgentFactory → SparkBasedAgent`, mirroring the AMPR-219 `AgentFactory` precedent.
   Purely additive — default `null` preserves current behavior.
2. **Effect-free Links** — the hard part, as the ticket predicted. No existing dry-run
   capability. Introduce `NoOpExecutor : Executor` in `ampere-eval`, thread it into
   `ToolExecutionEngine` for bench-mode agent construction. Clean interface boundary, new
   code, no changes to existing executors.
3. **Lifecycle events** — easy: Bench owns its own `BenchEvent` sealed family and publishes
   around each probe's `Arc.execute()` call via the existing `AgentEventApi`/bus pattern; no
   upstream instrumentation gap to fill for the ticket's required granularity
   (start/probe-graded/completion).

**Recommended implementation order for AMPR-186:**
`4.1` (types) → thread relay injection through the Arc path (prerequisite for `4.3`) →
`4.2`/`NoOpExecutor` (effect-free Links) → `4.3` (Bench replay mode) → `4.5` (lifecycle events
+ report) → `4.4` (Bench live mode, flag-gated).
