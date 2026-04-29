---
concept: EventSerialBus
status: stable
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/events/bus/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/events/api/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/events/relay/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/events/utils/EventLogger.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/events/utils/SignificanceAwareEventLogger.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/events/subscription/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/**
  - ampere-core/src/commonMain/sqldelight/link/socket/ampere/db/events/**
related: [PropelLoop, AgentSurface, CognitionTrace, MemoryProvenance]
last_verified: 2026-04-29
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

- `ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/events/bus/EventSerialBus.kt` — the bus itself; `publish`, `subscribe`, `unsubscribe`.
- `agents/events/bus/EventSerialBusFactory.kt` — wiring the bus into the application graph.
- `agents/events/api/AgentEventApi.kt` — the agent-facing facade for emitting events without touching the bus directly.
- `agents/events/relay/EventRelayService.kt` — out-of-process bridging (e.g., CLI streaming).
- `agents/events/utils/EventLogger.kt`, `SignificanceAwareEventLogger.kt` — pluggable logging implementations. `Console`, significance-filtered.
- `agents/events/subscription/EventSubscription.kt`, `Subscription.kt` — the handle returned to subscribers.
- `agents/domain/event/Event.kt` and the `event/` package — the sealed `Event` hierarchy.
- `ampere-core/src/commonMain/sqldelight/link/socket/ampere/db/events/EventStore.sq` — persistence schema (with `run_id` indexes for trace queries).

## Invariants

- **Direct agent-to-agent method calls are forbidden for coordination.** If agent A needs to influence agent B, A publishes; B subscribes. The only direct calls allowed are within an agent's own services or into stateless helpers. (Read: tests around `AgentReasoning` injecting fakes are fine; an agent calling another agent's `handleX(...)` is not.)
- **Every event type has a serializer, a registration in the event hierarchy, and a CLI display handler.** New event types must satisfy all three before merge — see the "Agent System Rules" in `AGENTS.md`.
- **Handler exceptions never propagate to the publisher.** The bus swallows and logs handler failures. Publishers cannot rely on subscriber success; if a downstream effect is required, it gets its own event.
- **The bus does not persist; loggers and stores do.** A change that makes `EventSerialBus.publish` write to a database directly violates the layering — persistence belongs to `EventStore` invoked by an event-aware logger or projector.
- **`run_id` is propagated through the event chain.** Events emitted within an Arc run carry the originating `run_id` so trace projection can find them. Lossy event handlers that strip `run_id` break time-travel.
- **No mutex held across handler invocation.** The bus snapshots handlers under the mutex and releases before launching coroutines. A change that holds `mutex` while running handlers would serialize the entire system.

## Common operations

- **Publish an event** — `agentEventApi.publish(SomeEvent(...))`. The api wraps the bus and is the agent-facing entry point.
- **Subscribe** — `bus.subscribe(eventType, scope) { event, subscription -> ... }`. Hold onto the returned `EventSubscription` so you can `unsubscribe` on shutdown.
- **Add a new event type** — extend `Event` (or the appropriate sub-sealed family in `agents/domain/event/`), register a `@Serializable` subclass with a stable `@SerialName`, add a CLI display handler, and add a logger summary in `Event.getSummary` if relevant.
- **Persist for trace** — events flow into `EventStore` via the configured logger. New event types are picked up automatically; just verify `run_id` is set on the emitter.

## Anti-patterns

- **"Just call the other agent's method directly, the event is annoying."** This is how AMPERE became opaque the first time. The cost of an event is one serialized struct; the cost of bypassing one is invisibility.
- **Catching exceptions inside a handler and silently dropping them.** The bus already swallows handler errors and logs them. Adding a second swallow inside the handler hides real failures from the logger.
- **Using `runBlocking` inside a handler.** Handlers run on the bus's `CoroutineScope`. Blocking that scope blocks the next dispatch loop. Suspend functions only.
- **Emitting events outside an agent's `AgentEventApi`.** Direct `bus.publish` calls in domain code skip the source-tagging the api adds, which means the trace can't attribute the event to an agent.
- **Persisting state in the bus.** The bus is a router. Anything that needs persistence belongs in a store one layer up.
