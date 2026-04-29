# AgentPause

`AgentPause` is Ampere's primitive for **paused-and-awaiting-human-input state**. It is the OS-native escalation contract that Plugin code (commonMain), the channel-selector (W1.5), and per-Arc override UI (W2.2) all build against.

Where [`AgentSurface`](agent-surface.md) models a Plugin asking the platform to render a specific UI, `AgentPause` models the higher-level intent: *"this agent is stuck and needs a person."* The pause primitive carries channel preferences; the channel-selector consumes the preferences plus runtime availability and decides how to actually reach the user.

## Design goals

- **Typed and serializable.** Every variant is `@Serializable`; pauses can be persisted, replayed, and shipped over a wire.
- **commonMain-only.** No iOS, Android, or Compose imports leak into the type definitions. Platform code lives behind `expect`/`actual`.
- **Stable correlation.** Every pause and every response carries a `PauseCorrelationId` so awaiters can pair requests with responses.
- **Public-link fallback supported.** Even though `EscalationChannel.PublicLink` is the lowest-priority channel, the type definitions support it as a first-class variant so a pause that escapes Ampere can still be expressed.

## Type hierarchy

| Type | Location | Purpose |
| --- | --- | --- |
| `AgentPause` | `link.socket.ampere.pause` | Data class describing a paused agent and its escalation preferences. |
| `PauseUrgency` | `link.socket.ampere.pause` | Enum: `Routine`, `Important`, `Critical`. |
| `EscalationChannel` | `link.socket.ampere.pause` | Sealed hierarchy: `Push`, `Voice`, `InAppCard`, `PublicLink`. |
| `AgentPauseResponse` | `link.socket.ampere.pause` | Sealed reply: `Approved`, `Rejected`, `TimedOut`. |
| `ChannelAvailability` | `link.socket.ampere.pause` | `expect class` querying which channels are available on this device. |
| `PauseCorrelationId` | `link.socket.ampere.pause` | `typealias PauseCorrelationId = String`. |

## Channel-fallback ordering

`AgentPause.suggestedChannels` is an **ordered** list. The channel-selector (W1.5) walks it from first to last and uses the first channel that satisfies all of:

1. The variant appears in `ChannelAvailability.available()` for the current device.
2. The user's per-Arc override (W2.2) does not exclude it.
3. Any platform-level permission gate (e.g., notification permission) is satisfied.

If no channel in `suggestedChannels` is reachable, the selector falls through to `EscalationChannel.PublicLink(url = AgentPause.fallbackUrl)` if `fallbackUrl` is non-null. If `fallbackUrl` is null, the pause is treated as unrouteable and resolves as `AgentPauseResponse.TimedOut` once `timeoutMillis` elapses.

The fallback ordering is deliberately *channel-priority-first, not urgency-first*. A `Routine` pause that lists `Voice` first will still attempt voice before the in-app card — urgency only sets defaults; the Plugin author always has the final say on ordering.

## Urgency-to-default-channel mapping

When a Plugin author does not provide explicit `suggestedChannels`, the channel-selector synthesises a default list from `urgency`. These defaults are intended for the channel-selector implementation in W1.5; the type system does not enforce them.

| `PauseUrgency` | Default channel order |
| --- | --- |
| `Routine` | `InAppCard` → `Push` → `PublicLink` |
| `Important` | `Push` → `InAppCard` → `PublicLink` |
| `Critical` | `Voice` → `Push` → `InAppCard` → `PublicLink` |

`PublicLink` always tail-anchors the default list when `AgentPause.fallbackUrl` is set. A pause without a `fallbackUrl` simply omits it.

## Lifecycle

```
Plugin                       Channel selector (W1.5)               Renderer
  |                                    |                                |
  | emitPause(AgentPause)              |                                |
  |----------------------------------->|                                |
  |                                    | walk suggestedChannels         |
  |                                    | check ChannelAvailability      |
  |                                    | dispatch to platform renderer  |
  |                                    |------------------------------->|
  |                                    |                                | render channel
  |                                    |                                | gather response
  | awaitPauseResponse(correlationId)  |                                |
  |==suspended=========================|                                |
  |                                    | publish AgentPauseResponse     |
  |                                    |<-------------------------------|
  |<================ AgentPauseResponse                                 |
```

W0.3 ships only the type contract on the left and right of the diagram. The channel-selector logic in the middle, plus the bus-level events that connect the two, are tracked in W1.5 and W2.2.

## Relationship to ConsentRepository

`AgentPause` builds on the existing `ConsentRepository` and `ConsentAwarePromptService` rather than replacing them.

- `ConsentRepository` continues to store *whether* a user has granted standing consent for a class of operation. The pause primitive does not duplicate this storage.
- `ConsentAwarePromptService` continues to gate prompts against consent state. When that gate trips and a fresh decision is needed, the service should now emit an `AgentPause` instead of inlining its own escalation logic.
- An `AgentPauseResponse.Approved` may be persisted as a one-shot consent grant; an `AgentPauseResponse.Rejected` may be persisted as a one-shot denial. The `payload` field on `Approved` is opaque to the pause primitive — Plugin code converts it into the consent record that `ConsentRepository` needs.

This split keeps consent storage and escalation routing as separate concerns: the consent layer answers *"does the user already say yes?"*, the pause primitive answers *"how do we ask?"*.

## Plugin example

```kotlin
val pause = AgentPause(
    correlationId = "deploy-prod-${Clock.System.now().toEpochMilliseconds()}",
    reason = "Approve production deploy of v0.5.0",
    urgency = PauseUrgency.Critical,
    suggestedChannels = listOf(
        EscalationChannel.Voice(
            prompt = "Approve the v0.5.0 production deploy?",
            expectedResponseSeconds = 20,
        ),
        EscalationChannel.Push(
            notificationCategory = "deploy",
            title = "Approve production deploy",
            body = "v0.5.0 is ready to ship.",
            deeplink = "ampere://pause/deploy-prod",
        ),
        EscalationChannel.InAppCard(
            cardKind = EscalationChannel.InAppCard.CardKind.Modal,
            title = "Production deploy",
            body = "Approve to ship v0.5.0.",
        ),
    ),
    timeoutMillis = 5 * 60 * 1000L,
    fallbackUrl = "https://ampere.example/pause/deploy-prod",
)
```

## Versioning

`AgentPause`, `EscalationChannel`, `AgentPauseResponse`, `PauseUrgency`, and `ChannelAvailability` are part of the public Ampere SDK. Adding a new `EscalationChannel` variant is a breaking change for renderers (their `when` becomes non-exhaustive at compile time), which is the intended forcing function: every renderer must explicitly opt in to a new channel before it ships.

Adding optional fields with defaults to existing variants is source-compatible. Removing fields, or changing field types, is a breaking change.
