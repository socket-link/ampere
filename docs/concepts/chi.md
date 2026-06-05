---
concept: ChiProtocol
status: experimental
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/execution/tools/ToolAskHuman.kt
  - ampere-core/src/jvmMain/kotlin/link/socket/ampere/agents/execution/tools/ToolAskHuman.jvm.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/events/messages/AgentMessageApi.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/events/escalation/EscalationEventHandler.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/events/escalation/DefaultEscalationPolicy.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/pause/AgentPause.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/HumanInteractionEvent.kt
related: [Emission, AgentPause, MessageEvent, EventSerialBus]
last_verified: 2026-06-04
---

# CHI (Computer-Human Interface)

## What it is

CHI — **Computer-Human Interface** — is the runtime protocol by which the
*computer* initiates contact back to the human. It is the inverse of HCI:
where HCI is the design discipline for how humans reach into computers, CHI
is the protocol for how the system reaches out to a person. Every
uncertainty escalation, confirmation request, ambient notification,
decision query, or sensor report is an instance of CHI. Today, CHI exists
architecturally but has no coherent implementation; four independent paths
each implement a slice of it. The target state is for all four to collapse
into a single `Emission`-with-affordances primitive carried over the
`EventSerialBus`.

## Why it exists

Without a named umbrella, the four implementations drift further apart with
every feature: each ticket touches the path that is closest to hand, and
the lifecycle assumptions accumulate as silent biases. Naming CHI as the
protocol — and the future `EmissionKind.Decision` as its single carrier —
gives every contributor a shared frame for "the computer is asking a
person something." Concretely:

1. **Routing.** A unified CHI lets the system pick a channel (push, voice,
   in-app card, public link, console) from one policy instead of four.
2. **Observability.** Every CHI event would flow over the bus and be
   logged with `run_id`, restoring trace continuity that ad-hoc paths
   currently break.
3. **Pairing.** A single correlation discipline (request ↔ response) is
   the only way a human's reply can be causally attributed to the request
   that prompted it — today this is held weakly across three different
   identifier fields.
4. **Substitution.** Once CHI is the contract, surfaces (CLI, mobile,
   voice) become interchangeable renderers. No surface owns the protocol.

This Concept Cell is descriptive (current four paths) and aspirational
(target collapse). It does **not** unify any code; the Wave 1c unification
agent will do that, and needs this load-bearing context first so it does
not rediscover each path's wrinkles from scratch.

## Where it lives

The four current CHI paths:

- **Path 1 — `ToolAskHuman` (`ask_human` tool).**
  - `ampere-core/.../execution/tools/ToolAskHuman.kt` — `expect` factory + `ASK_HUMAN_TOOL_ID`.
  - `ampere-core/.../execution/tools/ToolAskHuman.jvm.kt` — JVM `actual`: prints a console banner, calls `GlobalHumanResponseRegistry.instance.waitForResponse(requestId, 30.minutes)`, returns `ExecutionOutcome.NoChanges.Success` or `.Failure` on timeout.
  - `ampere-core/.../execution/tools/human/GlobalHumanResponseRegistry.kt` — process-wide singleton paired by `requestId`.
  - **Shape:** blocking suspend, no bus emission, console-only surface, 30-min hard-coded timeout.

- **Path 2 — `MessageEvent.EscalationRequested` (thread escalation).**
  - `ampere-core/.../agents/events/messages/AgentMessageApi.kt:151` — `escalateToHuman(threadId, reason, context, awaitReply)` transitions the thread to `EventStatus.WaitingForHuman` and publishes `EscalationRequested` + `ThreadStatusChanged`.
  - `ampere-core/.../agents/domain/event/MessageEvent.kt:86` — the event itself (`threadId`, `reason`, `context`, `urgency`).
  - `ampere-core/.../agents/events/escalation/EscalationEventHandler.kt` — subscribes to `EscalationRequested` and calls `humanNotifier.notifyEscalation(...)`.
  - `ampere-core/.../agents/events/escalation/DefaultEscalationPolicy.kt` — keyword-based classification into the `Escalation` sealed hierarchy.
  - **Shape:** explicit lifecycle. `awaitReply = false` is the legacy fire-and-forget thread escalation where status transition is the durable record; the default `awaitReply = true` also emits `HumanInteractionEvent.InputRequested` and suspends for the paired reply.

- **Path 3 — `AgentPause` (typed pause contract).**
  - `ampere-core/.../pause/AgentPause.kt` — `correlationId: PauseCorrelationId`, `reason`, `urgency: PauseUrgency` (`Routine` | `Important` | `Critical`), `suggestedChannels: List<EscalationChannel>` (ordered fallback), `timeoutMillis`, optional `fallbackUrl`.
  - `ampere-core/.../pause/AgentPauseResponse.kt` — `Approved` / `Rejected` / `TimedOut`.
  - `ampere-core/.../pause/EscalationChannel.kt` — `Push`, `Voice`, `InAppCard`, `PublicLink`.
  - **Shape:** type contract only as of W0.3. Bus events deferred. Channel selector is W1.5.

- **Path 4 — `HumanInteractionEvent` (request/response event pair).**
  - `ampere-core/.../agents/domain/event/HumanInteractionEvent.kt` — sealed interface with `InputRequested(requestId, agentId, question, context, ticketId?, taskId?)`, `InputProvided(requestId, agentId, response, respondedBy?)`, `RequestTimedOut(requestId, agentId, timeoutMinutes)`.
  - **Shape:** emitted by `EmissionScope.askHuman`; `AgentMessageApi.escalateToHuman(awaitReply = true)` uses this path. Pairing is by `requestId` plus the Emission id.

### Path comparison

| Dimension          | `ToolAskHuman`                    | `MessageEvent.EscalationRequested` | `AgentPause`                          | `HumanInteractionEvent`            |
|--------------------|-----------------------------------|------------------------------------|---------------------------------------|------------------------------------|
| Entry point        | Tool dispatch                     | `AgentMessageApi.escalateToHuman`  | (W0.3: contract only)                 | `EmissionScope.askHuman`           |
| Payload            | `instructions` string             | `reason` + `context: Map`          | `reason` + `urgency` + channels       | `question` + `context: Map`        |
| Correlation field  | `requestId` (UUID)                | `threadId` + `eventId`             | `correlationId: PauseCorrelationId`   | `requestId` (UUID)                 |
| Persistence        | In-memory registry (singleton)    | `EventStore` + thread status row   | None yet                              | `EventStore` (when emitted)        |
| Urgency model      | None (hard-coded)                 | `domain.Urgency` (defaults `HIGH`) | `PauseUrgency` (Routine/Important/Critical) | `domain.Urgency`               |
| Timeout            | `30.minutes` hard-coded           | None                               | `timeoutMillis` per pause             | `timeoutMinutes` per event         |
| Channel selection  | Console only                      | `humanNotifier` (single sink)      | Ordered `suggestedChannels` fallback  | None — surface decides             |
| Response delivery  | `provideResponse(requestId, ...)` | None (out-of-band human action)    | `AgentPauseResponse` (target)         | `InputProvided` event (paired)     |
| Calling-side block | Blocks calling coroutine          | Explicit: default suspend-until-response; `awaitReply = false` fire-and-forget | Suspends agent (target)               | Fire-and-forget (target)           |

## Invariants

- **Every CHI request must carry an identifier that uniquely pairs it with its response.** This is the load-bearing CHI invariant: a human's reply must causally link back to the request that prompted it. Today this is held weakly across three fields — `ToolAskHuman.requestId`, `AgentPause.correlationId` (`PauseCorrelationId`), and `HumanInteractionEvent.requestId`. The unification target is one `correlationId` semantics across all four paths.
- **A CHI request must declare its lifecycle.** "Blocks the calling coroutine" (`ToolAskHuman`), "fire-and-forget over the bus" (`MessageEvent.EscalationRequested`), and "suspends the agent until response" (`AgentPause` target) are not interchangeable. Any unification must keep the lifecycle explicit, not paper over it.
- **A CHI request must declare its timeout posture.** A missing timeout is not the same as an infinite timeout; both differ from a fixed 30-minute wall. The current paths span all three.
- **Channel selection is policy, not payload.** `AgentPause.suggestedChannels` is an *ordered preference*; the channel selector (W1.5) decides what actually fires. CHI requests must not assume any specific surface — adding "send a Slack message" inside `ToolAskHuman` is the canonical violation.
- **Thread state transitions on `EscalationRequested` are owned by `AgentMessageApi.escalateToHuman`, not by handlers.** `EscalationEventHandler` only notifies; the `EventStatus.WaitingForHuman` transition is committed inside the API before the event is published. Handlers must not re-transition.
- **`HumanInteractionEvent` is the bus-side CHI contract for human input.** Treating `InputRequested` / `InputProvided` as deprecated would break the target unification.

## Common operations

Today (use the path that fits the lifecycle, do not mix them):

- **Block an executing tool waiting on a person** — call `ToolAskHuman` from a tool definition with `requiredAgentAutonomy` set; the JVM `actual` will print to console and block on `GlobalHumanResponseRegistry`. Respond out-of-band with `./ampere-cli/ampere respond <requestId> "<text>"`.
- **Escalate a conversational thread** — `agentMessageApi.escalateToHuman(threadId, reason, context, awaitReply)`. Status transitions to `WaitingForHuman`, two message events publish, and `EscalationEventHandler` fires `humanNotifier.notifyEscalation(...)`. With the default `awaitReply = true`, the API also emits `HumanInteractionEvent.InputRequested` and suspends for the paired reply; use `awaitReply = false` when the caller must return after the durable transition.
- **Construct a typed pause descriptor** — build an `AgentPause(correlationId, reason, urgency, suggestedChannels, timeoutMillis, fallbackUrl?)`. The dispatching infrastructure ships in a later wave; for now the contract is consumed by per-Arc override UI and unit tests.
- **Classify an escalation reason** — `DefaultEscalationPolicy` maps free-text reasons into the `Escalation` sealed hierarchy (`Discussion`, `Decision`, `Budget`, `Priorities`, `Scope`, `External`) and an `EscalationProcess`.

Target (post-unification, sketch only):

- **Emit a CHI request** — publish `Emission(kind = EmissionKind.Decision, affordances = ..., provenance = EmissionProvenance(...), surface = ...)`. The router decides channels from `affordances` and `provenance.urgency`; the response surfaces as a paired `Emission` (or a typed `EmissionResponse`) carrying the original `correlationId`.

### Mapping current paths onto `EmissionKind.Decision`

Each existing path maps cleanly onto the target Emission, **provided the Emission contract carries the extra metadata each lifecycle currently encodes**. Concrete deltas the Wave 1c unification must preserve:

| Path                                | Required Emission metadata to preserve fidelity                                                                                                                                |
|-------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ToolAskHuman`                      | Calling-coroutine lifecycle (suspend-until-response), default-30-min timeout, "console" as the floor surface when no richer channel is reachable.                              |
| `MessageEvent.EscalationRequested`  | `threadId` link + the `EventStatus.WaitingForHuman` transition (CHI emission must trigger or be triggered by the status change, not race it).                                  |
| `AgentPause`                        | Ordered `suggestedChannels`, `PauseUrgency` → channel default mapping, optional `fallbackUrl` semantics, and the `Approved`/`Rejected`/`TimedOut` response trichotomy.         |
| `HumanInteractionEvent`             | Strict `requestId` pairing between request and response Emissions, plus the optional `ticketId` / `taskId` / `respondedBy` attribution fields.                                 |

## Anti-patterns

- **Treating the four paths as interchangeable.** Each was built for a different lifecycle (blocking tool, thread escalation, typed pause, bus event pair). Picking whichever path is closest at hand and "wrapping" the others around it breaks the response-pairing invariant in subtle ways. Pick the path whose lifecycle matches your need; do not bridge them ad hoc.
- **Adding a new fifth CHI path.** If you find yourself writing a new "ask the human" primitive, you are deepening the problem this concept cell exists to flag. Extend one of the four, or wait for the Emission collapse.
- **Calling `ToolAskHuman` from `escalateToHuman` (or vice versa) to "get both".** The console wait and the thread status transition are not composable — you end up blocking a coroutine inside an event handler and dropping the response pairing.
- **Hand-writing a `requestId` and not propagating it.** Every CHI request must carry an identifier that the response can quote back. Generating a UUID and discarding it (or logging it without persistence) breaks causal linkage and the trace projection.
- **Hard-coding a surface inside a CHI emitter.** `ToolAskHuman.jvm.kt` prints to `println` because it predates the channel selector; that is a known wart, not a pattern to copy. New code must declare *intent* (`suggestedChannels`, `PauseUrgency`, affordances) and leave surface choice to the selector.
- **Treating `HumanInteractionEvent` as dead because nothing emits it today.** It is the future bus-side contract for CHI; removing it would force the Wave 1c unification to reinvent the same shape.
