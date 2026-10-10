---
concept: EventSerialBus
status: stable
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/events/bus/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/events/api/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/events/EventRouter.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/events/relay/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/events/utils/EventLogger.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/events/utils/SignificanceAwareEventLogger.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/events/subscription/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/**
  - ampere-core/src/commonMain/sqldelight/link/socket/ampere/db/events/**
related: [PropelLoop, AgentSurface, CognitionTrace, MemoryProvenance, LinkLayer]
last_verified: 2026-10-09
---

# EventSerialBus

## What it is

The EventSerialBus (ESB) is AMPERE's nervous system: a thread-safe,
Kotlin Multiplatform-compatible publish/subscribe bus that carries typed
`Event`s between agents, services, and human-facing surfaces. Subscribers
register against an `EventType`; the bus snapshots its handler list under a
mutex on each `publish` and dispatches handlers asynchronously on a shared
`CoroutineScope`. The bus itself only dispatches — persistence is delegated
to higher-level APIs (`EventStore`, `SignificanceAwareEventLogger`).

## Why it exists

Agents in AMPERE coordinate **through convergence, not through RPC**. A direct
method call between two agents creates a synchronous coupling that:
(a) hides the interaction from observers (the glass brain goes opaque),
(b) bakes a topology into the code (agent A *must know about* agent B),
and (c) prevents new subscribers from joining without modifying the caller.

By making `Event` the universal coordination primitive, we get four
properties for free:

1. **Observability.** Every interesting state change passes through the bus
   and is recorded by `EventLogger` / `EventStore`. The CLI and trace
   projection read the same stream the agents write.
2. **Late binding.** A new agent can subscribe to existing events without
   any publisher being aware of it. Coordination is additive.
3. **Replay & time-travel.** Persisted events form an append-only log keyed
   by `run_id`; `ArcTraceProjection` can reconstruct *what happened* in any
   past Arc run from this log alone.
4. **Cross-platform.** ESB lives in `commonMain` and targets JVM, Android,
   iOS, and Desktop with a single contract. There is no platform-specific
   coordination layer.

## Where it lives

- `ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/events/bus/EventSerialBus.kt` — the bus itself; `publish`, `publishAsync`, `subscribe`/`subscribeSuspending`, `unsubscribe`/`unsubscribeSuspending`.
- `agents/events/relay/EmissionStream.kt` — `EventSerialBus.emissions(runId)`, the per-run Emission Flow the Swift Arc bridge streams progress through.
- `agents/events/bus/EventSerialBusFactory.kt` — wiring the bus into the application graph.
- `agents/events/api/AgentEventApi.kt` — the agent-facing facade for emitting events without touching the bus directly.
- `agents/events/api/TaskLifecycle.kt` — `AgentEventApi.openTaskLifecycle(...)` and the handle it returns: one unit of work's `TaskCreated` → `TaskStarted` → `TaskProgressed`/`SubtaskCreated`/`TaskBlocked` → `TaskCompleted`/`TaskFailed`, all under one `runId`, terminal exactly once.
- `agents/events/EventRouter.kt` — fans `TaskCreated`, `QuestionRaised` and `CodeSubmitted` out to registered agents as `NotificationEvent.ToAgent`s. Registration is `EnvironmentService.routeEventsToAgent(agentId, eventType)`; `EnvironmentOrchestrator.start()` calls `startRouting()`.
- `agents/events/relay/EventRelayService.kt` — out-of-process bridging (e.g., CLI streaming).
- `agents/events/utils/EventLogger.kt`, `SignificanceAwareEventLogger.kt` — pluggable logging implementations. `Console`, significance-filtered.
- `agents/events/subscription/EventSubscription.kt`, `Subscription.kt` — the handle returned to subscribers.
- `agents/domain/event/Event.kt` and the `event/` package — the sealed `Event` hierarchy.
- `agents/domain/event/CognitivePhaseEvent.kt` — phase transition events emitted by `PhaseSparkManager` when a bus is wired.
- `agents/domain/event/TaskEvent.kt` — task lifecycle (started, progressed, completed, failed, blocked, subtask created), folded with `Event.TaskCreated` into the `WorkItem` checklist by `WorkspaceStateStore`. The creating events carry the work's `WorkPhase` and `ExecutionAssignment` (model, effort); `TaskStarted` can assign an `ExecutionAssignment` but carries no phase, so starting a task cannot promote it from read-only to writing (AMPR-369).
- `agents/domain/event/LinkEvent.kt` — Link lifecycle (granted, revoked, resolved, resolution failed); see [LinkLayer](link-layer.md).
- `agents/domain/event/ProbeEvent.kt` — `VerdictReached`, one Probe's judgement of one identified subject; see [Probe](probe.md).
- `agents/domain/event/RoomEvent.kt` — `RoomOpened`, `ThreadOpened`, `Posted`, `ThreadResolved`, `ReviewRequested`, `ReviewCompleted`: what a Room write *meant*, each `causedBy` the `MessageEvent` the thread primitive fired for the same write; see [TeamLayer](team-layer.md).
- `agents/domain/event/ArcRunEvent.kt` — run-level signals from the Arc runtime itself. `CompletionManifestRecorded` carries a cancelled or failed run's manifest into the store, published through an `AgentEventApi` under `CompletionManifestSink.DEFAULT_AGENT_ID`; see [CognitionTrace](cognition-trace.md).
- `agents/domain/event/SupervisorEvent.kt` — `DispatchRecorded`, `CleanShutdownMarked`, `JournalLineQuarantined`: what the supervisor's claim-record journal wrote, published after the line is renamed into place so an event on the bus always has a durable record behind it; see [DispatchJournal](dispatch-journal.md).
- `agents/domain/event/EventRegistry.kt` — the hand-maintained list of every event type, and the only thing `subscribeToAll`, the relay, and `TraceRecorder` enumerate.
- `ampere-core/src/commonMain/sqldelight/link/socket/ampere/db/events/EventStore.sq` — persistence schema (with `run_id` indexes for trace queries).

## Invariants

- **Direct agent-to-agent method calls are forbidden for coordination.** If agent A needs to influence agent B, A publishes; B subscribes. The only direct calls allowed are within an agent's own services or into stateless helpers. (Read: tests around `AgentReasoning` injecting fakes are fine; an agent calling another agent's `handleX(...)` is not.)
- **Every event type has a serializer, a registration in the event hierarchy, and a CLI display handler.** New event types must satisfy all three before merge — see the "Agent System Rules" in `AGENTS.md`. Registration means an entry in `EventRegistry.allEventTypes`: an event missing from that list reaches no subscriber that did not name its type and appears in no recorded trace. Fourteen declared events had drifted out of it (AMPR-321); `EventRegistryCompletenessTest` now walks the sealed hierarchy in both directions so the next omission fails there. "Has a serializer" means *registered in `Event`'s polymorphic scope*, which the plugin builds by walking sealed subtypes: an intermediate interface that is not sealed ends the walk, and every event under it fails to encode. `SparkEvent` was open for exactly that reason, so `SparkAppliedEvent` / `SparkRemovedEvent` / `CognitiveStateSnapshot` reached bus subscribers and were never persisted — registered in `allEventTypes`, present in `EventRenderer`, and absent from every trace. Sealing it (AMPR-386) fixed all three at once, and the tripwire now asserts that no open branch exists.
- **Handler exceptions never propagate to the publisher.** The bus swallows and logs handler failures. Publishers cannot rely on subscriber success; if a downstream effect is required, it gets its own event.
- **The bus does not persist; loggers and stores do.** A change that makes `EventSerialBus.publish` write to a database directly violates the layering — persistence belongs to `EventStore` invoked by an event-aware logger or projector.
- **`run_id` is propagated through the event chain.** Events emitted within an Arc run carry the originating `run_id` so trace projection can find them — on the *envelope*, via `AgentEventApi.publish(event, runId = …)`, which is the column `ArcTraceProjection` joins on. Inside an agent, the value to pass is `Agent.currentRunId` (AMPR-386). Lossy event handlers that strip `run_id` break time-travel.
- **No mutex held across handler invocation.** The bus snapshots handlers under the mutex and releases before launching coroutines. A change that holds `mutex` while running handlers would serialize the entire system.
- **A subscription is a handle, not a label.** `unsubscribe(subscription)` removes exactly the handler that subscription was minted for, matched by object identity. Identity, not equality: two subscribers with the same `agentId` on the same event type produce equal `subscriptionId`s, so a value comparison would release both. The `unsubscribe(eventType)` overload is the blunt instrument — it removes every handler for that type — and exists only for callers that genuinely own the type.
- **A Flow built on the bus tears down only itself.** Any adapter that turns bus callbacks into a `Flow` must release per subscription on cancellation. Unsubscribing by event type from a cancelled collector silences every other subscriber on those types — with no filters, every type in `EventRegistry`, including the emission reply routers an Arc depends on to receive replies.
- **A subscriber registry is read at dispatch, not at registration time.** `EventRouter` registers one bus handler per routable event type and looks up who wants it when an event arrives. The shape that fails is the obvious one: iterate the registry at startup and register a handler per agent found in it. Nothing has registered yet at startup, so that loop runs over an empty map and the router silently routes nothing for the life of the process (AMPR-404). One handler per type is also what keeps `startRouting` idempotent — `NotificationEvent.ToAgent.eventId` is derived from the routed event's id and the recipient, so a duplicate handler collides on the primary key rather than producing a duplicate notification.
- **A unit of work publishes its whole lifecycle, not just its creation.** A bare `Event.TaskCreated` adds a `Pending` item to `WorkspaceStateStore` and nothing ever moves it, and `MilestoneTracker` — which listens for `TaskCompleted` and `TaskFailed` — has nothing to count. Open a `TaskLifecycle` instead; it is terminal exactly once, so the `finally` that covers a throw, an early return and a cancellation is a no-op when the work already reported its outcome.
- **Overflow is a policy, never an accident.** A bus→Flow adapter buffers with an explicit `BufferOverflow` and reports what it dropped. A bare `trySend` into a default-capacity channel discards its own return value, so a slow consumer loses events with no signal at all.

## Common operations

- **Publish an event** — `agentEventApi.publish(SomeEvent(...))`. The api wraps the bus and is the agent-facing entry point.
- **Publish from a synchronous boundary hook** — use `EventSerialBus.publishAsync(event)` only when the caller cannot suspend, such as phase boundary hooks. Prefer `AgentEventApi.publish` elsewhere.
- **Subscribe** — `bus.subscribe(eventType, scope) { event, subscription -> ... }`. Hold onto the returned `EventSubscription` and pass *that instance* to `unsubscribe` on shutdown.
- **Subscribe from a coroutine** — `bus.subscribeSuspending(...)` / `unsubscribeSuspending(...)`. The non-suspending overloads take the bus mutex under `runBlockingCompat`, which blocks the calling thread and throws outright on JS/WasmJS. Anything that can suspend — every Flow builder, every Swift-facing bridge — uses the suspending pair.
- **Stream one Arc's Emissions** — `bus.emissions(runId, capacity, onDropped)` in `events/relay/EmissionStream.kt`. Per-collector subscription, `DROP_OLDEST`, and a running count of what was lost.
- **Publish a unit of work's task lifecycle** — `eventApi.openTaskLifecycle(taskId, description, runId, taskType = …)`, then exactly one of `completed(summary)` / `failed(reason)`. Pass a `taskType` that names the *kind* of work rather than the instance, or `MilestoneTracker` reports a `FIRST_SUCCESS` for every task.
- **Route an event type to an agent** — `environmentService.routeEventsToAgent(agentId, eventType)`, and `stopRoutingEventsToAgent` to undo it. Order against `EnvironmentService.start()` does not matter. Routing is a *second* delivery of an event the agent could subscribe to directly; use it when the agent should react to a type without knowing who produces it.
- **Add a new event type** — extend `Event` (or the appropriate sub-sealed family in `agents/domain/event/`), register a `@Serializable` subclass with a stable `@SerialName`, add a CLI display handler, and add a logger summary in `Event.getSummary` if relevant.
- **Observe phase transitions** — subscribe to `CognitivePhaseEvent.PhaseEntered.EVENT_TYPE` and/or `PhaseExited.EVENT_TYPE`; use `nestingDepth == 0` for outermost phase changes only.
- **Publish an agent milestone** — call `AgentEventApi.reachMilestone(...)` or, for observable agents, `agent.reachMilestone(...)`. Milestones emit `MemoryEvent.MilestoneReached`, a low-volume sibling event to routine memory writes.
- **Persist for trace** — events flow into `EventStore` via the configured logger. New event types are picked up automatically; just verify `run_id` is set on the emitter.

## Anti-patterns

- **"Just call the other agent's method directly, the event is annoying."** This is how AMPERE became opaque the first time. The cost of an event is one serialized struct; the cost of bypassing one is invisibility.
- **Catching exceptions inside a handler and silently dropping them.** The bus already swallows handler errors and logs them. Adding a second swallow inside the handler hides real failures from the logger.
- **Using `runBlocking` inside a handler.** Handlers run on the bus's `CoroutineScope`. Blocking that scope blocks the next dispatch loop. Suspend functions only.
- **Emitting events outside an agent's `AgentEventApi`.** Direct `bus.publish` calls in domain code skip the source-tagging the api adds, which means the trace can't attribute the event to an agent. A publisher that is genuinely not agent-owned — `ProbeSuite`, which a consumer may drive with no agent in sight — takes an explicit `eventSource` instead, so attribution is carried rather than lost. Taking the bus without taking a source is the actual anti-pattern.
- **Persisting state in the bus.** The bus is a router. Anything that needs persistence belongs in a store one layer up.
- **Unsubscribing by event type from a shared consumer.** It reads like "stop listening" and behaves like "nobody listens." Use the `Subscription` handle unless you are certain you are the only subscriber on that type, and say so in a comment if you are.
- **Taking a clean `:ampere-core:*` compile as proof a new event type is wired.** "A CLI display handler" above is two exhaustive `when`s, both in `ampere-cli` jvmMain — `EventCategorizer.categorizeInternal` and `EventRenderer.getIconAndColor` — alongside `SignificanceAwareEventLogger.categorizeEvent` in core and the `EventRegistry` entry. Core compiles and its tests pass without the CLI pair, so the omission surfaces as a broken `:ampere-cli:compileKotlinJvm` in CI rather than locally (AMPR-301, again in AMPR-187). A `when` that already matches the whole family — `is BenchEvent ->` — needs no edit, which is why grepping for an `else ->` branch is not a reliable check either: both CLI files nest `when`s that have their own.
- **Holding a `HashMap` entry across a mutation of its map.** `handlerMap.entries.toList()` copies entry *references*; on Kotlin/Native an entry read after the map changed throws `ConcurrentModificationException`, and because `release` runs in a `finally` under `NonCancellable` the throw escapes the coroutine and terminates the process. The JVM's entries carry their key and value, so the JVM suite never sees it — it surfaced as an iOS test crash the first time a session with three subscriptions was closed (AMPR-374). Snapshot the pairs (`toMap()`) before mutating.
- **Publishing `TaskCreated` and stopping there.** It reads like "the task is on the record" and behaves like "the task never finished." Every production publisher did exactly this before AMPR-404, which is how a `WorkspaceStateStore` full of permanently-`Pending` items and a `MilestoneTracker` that had never fired both went unnoticed: the one event that *was* published looked right in the stream.
- **Taking a reachable orchestrator as proof a subsystem is reachable.** `EnvironmentOrchestrator` exposed `eventRouter` and called `startRouting()` on it, so grepping for callers found both. What had no caller was the registration method, and `EnvironmentService` — the documented way in — did not expose the router at all. The question to ask of a publish/subscribe mechanism is which side has no caller, not whether the class is constructed.
- **Exposing `subscribe` across the FFI boundary.** It calls `runBlockingCompat`, so a Swift call from the main thread blocks the UI and can deadlock on Kotlin/Native. Swift gets a Flow or a callback facade, never the bus.
