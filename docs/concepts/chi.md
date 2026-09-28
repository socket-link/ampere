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
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/pause/PauseStore.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/pause/PauseRecord.kt
  - ampere-core/src/commonMain/sqldelight/link/socket/ampere/db/pause/PauseStore.sq
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/HumanInteractionEvent.kt
related: [Emission, AgentPause, MessageEvent, EventSerialBus, CancellationAddress]
last_verified: 2026-09-28
---

# CHI (Computer-Human Interface)

## What it is

CHI — **Computer-Human Interface** — is the runtime protocol by which the
*computer* initiates contact back to the human. It is the inverse of HCI:
where HCI is the design discipline for how humans reach into computers, CHI
is the protocol for how the system reaches out to a person. Every
uncertainty escalation, confirmation request, ambient notification,
decision query, or sensor report is an instance of CHI. `HumanInteractionEvent`
— the request/response event pair for human input — was already collapsed
into the `EmissionEvent` family by [AMPR-180](https://linear.app/miley/issue/AMPR-180)
(`db0329a2`): it is a sealed interface extending `EmissionEvent`, with
`InputRequested : EmissionEvent.Produced` and `InputProvided :
EmissionEvent.Resolved`. Three paths remain genuinely uncoordinated:
`ToolAskHuman`, `MessageEvent.EscalationRequested`, and `AgentPause`. The
target state is for these three to collapse onto the same
`Emission`-with-affordances shape `HumanInteractionEvent` already carries,
over the `EventSerialBus`.

## Why it exists

Without a named umbrella, the three remaining implementations drift further
apart with every feature: each ticket touches the path that is closest to
hand, and the lifecycle assumptions accumulate as silent biases. Naming CHI
as the protocol — and `EmissionKind.Decision` (already the carrier behind
`HumanInteractionEvent`) as its eventual single carrier — gives every
contributor a shared frame for "the computer is asking a person something."
Concretely:

1. **Routing.** A unified CHI lets the system pick a channel (push, voice,
   in-app card, public link, console) from one policy instead of three.
2. **Observability.** Every CHI event would flow over the bus and be
   logged with `run_id`, restoring trace continuity that ad-hoc paths
   currently break.
3. **Pairing.** A single correlation discipline (request ↔ response) is
   the only way a human's reply can be causally attributed to the request
   that prompted it — today this is held weakly across three different
   identifier fields.
4. **Substitution.** Once CHI is the contract, surfaces (CLI, mobile,
   voice) become interchangeable renderers. No surface owns the protocol.

This Concept Cell is descriptive (current three uncoordinated paths, plus
the already-collapsed `HumanInteractionEvent`) and aspirational (target
collapse of the remaining three). It does **not** unify the remaining
paths; the Wave 1c unification agent will do that, and needs this
load-bearing context first so it does not rediscover each path's wrinkles
from scratch.

## Where it lives

**Already collapsed — `HumanInteractionEvent` (request/response event pair).**

- `ampere-core/.../agents/domain/event/HumanInteractionEvent.kt` — sealed
  interface extending `EmissionEvent`, with `InputRequested(requestId,
  agentId, question, context, ticketId?, taskId?) : EmissionEvent.Produced`,
  `InputProvided(requestId, agentId, response, respondedBy?) :
  EmissionEvent.Resolved`, and `RequestTimedOut(requestId, agentId,
  timeoutMinutes)`.
- **Shape:** emitted by `EmissionScope.askHuman`;
  `AgentMessageApi.escalateToHuman(awaitReply = true)` uses this path.
  Pairing is by `requestId` plus the Emission id. Collapsed by
  [AMPR-180](https://linear.app/miley/issue/AMPR-180) (`db0329a2`); pinned
  by `HumanInteractionEventSpecializationTest`.

The three remaining uncoordinated CHI paths:

- **Path 1 — `ToolAskHuman` (`ask_human` tool).**
  - `ampere-core/.../execution/tools/ToolAskHuman.kt` — the factory and `ASK_HUMAN_TOOL_ID`. It now
    goes through the `emission { }` DSL: `askHuman(...)` publishes
    `HumanInteractionEvent.InputRequested` through the `AgentEventApi` door and suspends on
    `GlobalEmissionReplyRegistry` for the paired reply, with a 30-minute default timeout;
    `EmissionTimeout` becomes `ExecutionOutcome.NoChanges.Failure`. The run comes off
    `ExecutionRequest.runId` (AMPR-351).
  - `ampere-core/.../execution/tools/ToolAskHuman.jvm.kt` — `ToolAskHumanJvm`, the console floor
    surface: the same factory with an `onInputRequested` callback that prints the banner naming the
    emission id for `ampere respond`. Not an `actual` — a JVM-only wrapper.
  - **Shape:** blocking suspend, event persisted through the door, console as the floor surface,
    30-min default timeout. `GlobalHumanResponseRegistry` — named in earlier versions of this cell —
    no longer exists; pairing is `GlobalEmissionReplyRegistry`, which is in-memory and
    process-scoped.

- **Path 2 — `MessageEvent.EscalationRequested` (thread escalation).**
  - `ampere-core/.../agents/events/messages/AgentMessageApi.kt` — `escalateToHuman(threadId, reason, context, awaitReply, causedBy): Result<Unit>` transitions the thread to `EventStatus.WaitingForHuman` and publishes `EscalationRequested` + `ThreadStatusChanged` through the `AgentEventApi` door (persisted to `EventStore` before dispatch, F1; `causedBy` links the request to the event that prompted it, F2). A persist failure is returned, not retried.
  - `ampere-core/.../agents/domain/event/MessageEvent.kt:86` — the event itself (`threadId`, `reason`, `context`, `urgency`).
  - `ampere-core/.../agents/events/escalation/EscalationEventHandler.kt` — subscribes to `EscalationRequested` and calls `humanNotifier.notifyEscalation(...)`.
  - `ampere-core/.../agents/events/escalation/DefaultEscalationPolicy.kt` — keyword-based classification into the `Escalation` sealed hierarchy.
  - **Shape:** explicit lifecycle. `awaitReply = false` is the legacy fire-and-forget thread escalation where status transition is the durable record; the default `awaitReply = true` also emits `HumanInteractionEvent.InputRequested` and suspends for the paired reply.

- **Path 3 — `AgentPause` (typed pause contract).**
  - `ampere-core/.../pause/AgentPause.kt` — `correlationId: PauseCorrelationId`, `reason`, `urgency: PauseUrgency` (`Routine` | `Important` | `Critical`), `suggestedChannels: List<EscalationChannel>` (ordered fallback), `timeoutMillis`, optional `fallbackUrl`.
  - `ampere-core/.../pause/AgentPauseResponse.kt` — `Approved` / `Rejected` / `TimedOut`.
  - `ampere-core/.../pause/EscalationChannel.kt` — `Push`, `Voice`, `InAppCard`, `PublicLink`.
  - `ampere-core/.../pause/PauseStore.kt` — the durable side (AMPR-370): `raise` / `get` /
    `listOpen` / `resolve` / `expire` / `delete`, addressed by `PauseCorrelationId`.
    `SqlDelightPauseStore` over the `PauseStore` table (`8.sqm`), `InMemoryPauseStore` for tests.
  - `ampere-core/.../pause/PauseRecord.kt` — `PauseRecord` (the stored pause plus `raisedAt`,
    `expiresAt`, and the `AgentPauseResponse` it settled to) and `PauseResolution` (what `resolve`
    reports, including whether the caller's response is the one that landed).
  - **Shape:** the pending decision is now durable and answerable from anywhere, and expiry settles
    to `TimedOut` in the table rather than being inferred. Still no dispatch and no bus events of its
    own: the channel selector is W1.5. Zero production raisers as of AMPR-370 — Socket's write gate
    is the first, filed on SCKT-664.

### Path comparison

| Dimension          | `ToolAskHuman`                    | `MessageEvent.EscalationRequested` | `AgentPause`                          | `HumanInteractionEvent` (already collapsed) |
|--------------------|-----------------------------------|-------------------------------------|---------------------------------------|----------------------------------------------|
| Entry point        | Tool dispatch                     | `AgentMessageApi.escalateToHuman`  | `PauseStore.raise`                    | `EmissionScope.askHuman`                     |
| Payload            | `instructions` string             | `reason` + `context: Map`          | `reason` + `urgency` + channels       | `question` + `context: Map`                  |
| Correlation field  | `requestId` (UUID)                | `threadId` + `eventId`             | `correlationId: PauseCorrelationId`   | `requestId` (UUID)                           |
| Persistence        | `EventStore` + in-memory reply registry | `EventStore` + thread status row | `PauseStore` table, one row per pause | `EventStore`                                 |
| Urgency model      | None (hard-coded)                 | `domain.Urgency` (defaults `HIGH`) | `PauseUrgency` (Routine/Important/Critical) | `domain.Urgency`                        |
| Timeout            | `30.minutes` hard-coded           | None                               | `timeoutMillis` per pause             | `timeoutMinutes` per event                   |
| Channel selection  | Console only                      | `humanNotifier` (single sink)      | Ordered `suggestedChannels` fallback  | None — surface decides                       |
| Response delivery  | `InputProvided` via the reply registry | None (out-of-band human action) | `PauseStore.resolve(AgentPauseResponse)` | `InputProvided` event (paired)            |
| Calling-side block | Blocks calling coroutine          | Explicit: default suspend-until-response; `awaitReply = false` fire-and-forget | None — the store holds the decision, not the coroutine (AMPR-274 D4) | Fire-and-forget (already the shape the other three target) |

## Invariants

- **Every CHI request must carry an identifier that uniquely pairs it with its response.** This is the load-bearing CHI invariant: a human's reply must causally link back to the request that prompted it. Today this is held weakly across three fields — `ToolAskHuman.requestId`, `AgentPause.correlationId` (`PauseCorrelationId`), and `HumanInteractionEvent.requestId`. The unification target is one `correlationId` semantics across the three remaining paths, matching the pairing `HumanInteractionEvent` already achieves.
- **A CHI request must declare its lifecycle.** "Blocks the calling coroutine" (`ToolAskHuman`), "fire-and-forget over the bus" (`MessageEvent.EscalationRequested`), and "suspends the agent until response" (`AgentPause` target) are not interchangeable. Any unification must keep the lifecycle explicit, not paper over it.
- **A CHI request must declare its timeout posture.** A missing timeout is not the same as an infinite timeout; both differ from a fixed 30-minute wall. The current paths span all three.
- **Channel selection is policy, not payload.** `AgentPause.suggestedChannels` is an *ordered preference*; the channel selector (W1.5) decides what actually fires. CHI requests must not assume any specific surface — adding "send a Slack message" inside `ToolAskHuman` is the canonical violation.
- **Thread state transitions on `EscalationRequested` are owned by `AgentMessageApi.escalateToHuman`, not by handlers.** `EscalationEventHandler` only notifies; the `EventStatus.WaitingForHuman` transition is committed inside the API before the event is published. Handlers must not re-transition.
- **Every CHI request records its causal parent and its principal ([AMPR-283](https://linear.app/miley/issue/AMPR-283)).** The `emission { }` DSL requires both. `ToolAskHuman` and `escalateToHuman` open root scopes (`parentEmissionId = null`) under `Principal.Ambient`, since neither has a parent Emission or a resolved identity to hand. `InputProvided.respondedBy` is still attribution only: nothing yet carries the authority of the human who replied, which D5 owns.
- **A CHI request that stops a run must be durable before anything acts on it.** `AgentPause` is
  raised into `PauseStore` first, because the decision is useful precisely when the process that
  raised it is gone. A pause dispatched to a channel but never stored is the lost-decision failure
  AMPR-370 closed.
- **A pause store holds the decision, never the coroutine ([AMPR-274](https://linear.app/miley/issue/AMPR-274) Contract 4, D4).**
  `PauseStore.resolve` returning means the answer is committed and nothing more. Resume is a fresh
  perceive over the settled row; nothing in the store may start, unblock or notify a suspended loop.
- **Expiry is an outcome written down, not a silence.** A pause past its deadline settles to
  `AgentPauseResponse.TimedOut` in the table — by the `expire` sweep, or by the first `get` /
  `listOpen` that finds it due. A reader can always tell "expired" from "still open"; an
  indefinitely-open row is a bug.
- **`HumanInteractionEvent` is the bus-side CHI contract for human input, already collapsed into `EmissionEvent` ([AMPR-180](https://linear.app/miley/issue/AMPR-180)).** Treating `InputRequested` / `InputProvided` as deprecated would break `EmissionScope.askHuman` and `escalateToHuman(awaitReply = true)`, both of which depend on it today.

## Common operations

Today (use the path that fits the lifecycle, do not mix them):

- **Block an executing tool waiting on a person** — call `ToolAskHuman` from a tool definition with `requiredAgentAutonomy` set; the JVM `actual` will print to console and block on `GlobalHumanResponseRegistry`. Respond out-of-band with `./ampere-cli/ampere respond <requestId> "<text>"`.
- **Escalate a conversational thread** — `agentMessageApi.escalateToHuman(threadId, reason, context, awaitReply, causedBy)`. Status transitions to `WaitingForHuman`, two message events persist and publish through the door, and `EscalationEventHandler` fires `humanNotifier.notifyEscalation(...)`. With the default `awaitReply = true`, the API also emits `HumanInteractionEvent.InputRequested` and suspends for the paired reply; use `awaitReply = false` when the caller must return after the durable transition.
- **Stop a run on a decision a person has to make** — build an `AgentPause(correlationId, reason, urgency, suggestedChannels, timeoutMillis, fallbackUrl?)` and `pauseStore.raise(pause)` *before* dispatching it anywhere. Then return; do not hold the coroutine.
- **Answer a pause from anywhere** — `pauseStore.resolve(AgentPauseResponse.Approved(correlationId))`. Check `PauseResolution.settledByThisCall`: false means something settled it first (usually expiry) and `response` is that earlier answer.
- **Pick the work back up** — `pauseStore.get(correlationId)` (or `listOpen()` for the queue) from a fresh perceive. The channel-selector dispatch that reaches a person is still W1.5.
- **Reconcile at startup** — `pauseStore.expire()` once, so every pause whose deadline passed while the host was down settles to `TimedOut` rather than looking live.
- **Classify an escalation reason** — `DefaultEscalationPolicy` maps free-text reasons into the `Escalation` sealed hierarchy (`Discussion`, `Decision`, `Budget`, `Priorities`, `Scope`, `External`) and an `EscalationProcess`.

Target (post-unification, sketch only):

- **Emit a CHI request** — publish `Emission(kind = EmissionKind.Decision, affordances = ..., provenance = EmissionProvenance(...), surface = ...)`. The router decides channels from `affordances` and `provenance.urgency`; the response surfaces as a paired `Emission` (or a typed `EmissionResponse`) carrying the original `correlationId`.

### Mapping the remaining paths onto `EmissionKind.Decision`

Each remaining path maps cleanly onto the Emission shape `HumanInteractionEvent` already carries, **provided the Emission contract preserves the extra metadata each lifecycle currently encodes**. Concrete deltas the Wave 1c unification must preserve:

| Path                                | Required Emission metadata to preserve fidelity                                                                                                                                |
|-------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ToolAskHuman`                      | Calling-coroutine lifecycle (suspend-until-response), default-30-min timeout, "console" as the floor surface when no richer channel is reachable.                              |
| `MessageEvent.EscalationRequested`  | `threadId` link + the `EventStatus.WaitingForHuman` transition (CHI emission must trigger or be triggered by the status change, not race it).                                  |
| `AgentPause`                        | Ordered `suggestedChannels`, `PauseUrgency` → channel default mapping, optional `fallbackUrl` semantics, and the `Approved`/`Rejected`/`TimedOut` response trichotomy.         |

`HumanInteractionEvent` already provides the reference shape: strict `requestId` pairing between request and response Emissions, plus the optional `ticketId` / `taskId` / `respondedBy` attribution fields.

## Anti-patterns

- **Treating the three remaining paths as interchangeable.** Each was built for a different lifecycle (blocking tool, thread escalation, typed pause). Picking whichever path is closest at hand and "wrapping" the others around it breaks the response-pairing invariant in subtle ways. Pick the path whose lifecycle matches your need; do not bridge them ad hoc.
- **Adding a new fourth uncoordinated CHI path.** If you find yourself writing a new "ask the human" primitive instead of extending `ToolAskHuman`, `MessageEvent.EscalationRequested`, or `AgentPause`, you are deepening the problem this concept cell exists to flag. Extend one of the three, or use `HumanInteractionEvent` directly via `EmissionScope.askHuman`.
- **Calling `ToolAskHuman` from `escalateToHuman` (or vice versa) to "get both".** The console wait and the thread status transition are not composable — you end up blocking a coroutine inside an event handler and dropping the response pairing.
- **Hand-writing a `requestId` and not propagating it.** Every CHI request must carry an identifier that the response can quote back. Generating a UUID and discarding it (or logging it without persistence) breaks causal linkage and the trace projection.
- **Dispatching a pause before storing it.** The channel fires, the process dies, and the decision exists only in a notification nobody can correlate. `raise` first; it is the cheap half.
- **Awaiting a pause response in the raiser's coroutine.** That is the `ArcWriteConfirmer` shape AMPR-370 exists to replace — an in-process `CompletableDeferred` with no durable pending state. The run stops; a later fresh perceive reads the settled row.
- **Treating a `null` response as "expired".** It means *open*. Expiry is `AgentPauseResponse.TimedOut` written to the row, and the two must never be conflated — see `PauseRecord.isTimedOut`.
- **`INSERT OR REPLACE` on a pause row.** Every other grant-shaped table in the repo upserts; `PauseStore` refuses instead, because reusing a correlation id would silently reopen a decision a person already made.
- **Hard-coding a surface inside a CHI emitter.** `ToolAskHuman.jvm.kt` prints to `println` because it predates the channel selector; that is a known wart, not a pattern to copy. New code must declare *intent* (`suggestedChannels`, `PauseUrgency`, affordances) and leave surface choice to the selector.
- **Treating `HumanInteractionEvent` as dead or aspirational.** It is not: `EmissionScope.askHuman` emits `InputRequested`/`RequestTimedOut` and `EmissionReplyRegistry` consumes `InputProvided` today, and `escalateToHuman(awaitReply = true)` depends on it. Removing or bypassing it would break both callers.
