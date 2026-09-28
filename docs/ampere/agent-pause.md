# AgentPause

`AgentPause` is Ampere's primitive for **paused-and-awaiting-human-input state**. It is the OS-native escalation contract that Plug code (commonMain), the channel-selector (W1.5), and per-Arc override UI (W2.2) all build against.

Where [`AgentSurface`](agent-surface.md) models a Plug asking the platform to render a specific UI, `AgentPause` models the higher-level intent: *"this agent is stuck and needs a person."* The pause primitive carries channel preferences; the channel-selector consumes the preferences plus runtime availability and decides how to actually reach the user.

## Design goals

- **Typed and serializable.** Every variant is `@Serializable`; pauses are persisted, replayed, and shipped over a wire.
- **Durable.** A raised pause is a row, not a coroutine. See [Durability](#durability).
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
| `PauseStore` | `link.socket.ampere.pause` | Persistence boundary: `raise`, `get`, `listOpen`, `resolve`, `expire`, `delete`. `SqlDelightPauseStore` is the real one; `InMemoryPauseStore` is for tests. |
| `PauseRecord` | `link.socket.ampere.pause` | A stored pause plus `raisedAt`, `expiresAt`, and the `AgentPauseResponse` it settled to. |
| `PauseResolution` | `link.socket.ampere.pause` | What `resolve` reports: the response that landed, when, and whether it was the caller's. |

## Channel-fallback ordering

`AgentPause.suggestedChannels` is an **ordered** list. The channel-selector (W1.5) walks it from first to last and uses the first channel that satisfies all of:

1. The variant appears in `ChannelAvailability.available()` for the current device.
2. The user's per-Arc override (W2.2) does not exclude it.
3. Any platform-level permission gate (e.g., notification permission) is satisfied.

If no channel in `suggestedChannels` is reachable, the selector falls through to `EscalationChannel.PublicLink(url = AgentPause.fallbackUrl)` if `fallbackUrl` is non-null. If `fallbackUrl` is null, the pause is treated as unrouteable and resolves as `AgentPauseResponse.TimedOut` once `timeoutMillis` elapses.

The fallback ordering is deliberately *channel-priority-first, not urgency-first*. A `Routine` pause that lists `Voice` first will still attempt voice before the in-app card — urgency only sets defaults; the Plug author always has the final say on ordering.

## Urgency-to-default-channel mapping

When a Plug author does not provide explicit `suggestedChannels`, the channel-selector synthesises a default list from `urgency`. These defaults are intended for the channel-selector implementation in W1.5; the type system does not enforce them.

| `PauseUrgency` | Default channel order |
| --- | --- |
| `Routine` | `InAppCard` → `Push` → `PublicLink` |
| `Important` | `Push` → `InAppCard` → `PublicLink` |
| `Critical` | `Voice` → `Push` → `InAppCard` → `PublicLink` |

`PublicLink` always tail-anchors the default list when `AgentPause.fallbackUrl` is set. A pause without a `fallbackUrl` simply omits it.

## Lifecycle

```
Plug                       Channel selector (W1.5)               Renderer
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

W0.3 shipped only the type contract on the left and right of the diagram. AMPR-370 added the store
underneath it (see [Durability](#durability)). The channel-selector logic in the middle, plus the
bus-level events that connect the two, are still tracked in W1.5 and W2.2 — a raised pause is durable
today, but nothing yet reaches out to a person on its own.

## Durability

`AgentPause` is the contract; `PauseStore` is what makes a pending decision survive the process that
raised it (AMPR-370). One row per pause in the `PauseStore` table, keyed by `correlationId`:

```kotlin
// The raiser. Store first, then stop — never the other way round.
pauseStore.raise(pause).getOrThrow()

// A responder, in another process, on another device, an hour later.
val resolution = pauseStore.resolve(
    AgentPauseResponse.Approved(correlationId = "deploy-prod", payload = "ship-it"),
).getOrThrow()
if (!resolution.settledByThisCall) {
    // Something settled it first — usually the expiry sweep. resolution.response is that answer.
}

// A supervisor, at startup: settle everything whose deadline passed while the host was down.
pauseStore.expire().getOrThrow()
```

### What resumes

Per [AMPR-274](https://linear.app/miley/issue/AMPR-274) Contract 4 and its D4 position, **resume is a
fresh perceive over durable committed state, never loop resurrection.** The store holds the decision,
never the coroutine:

- `resolve` returning means the answer is committed, and nothing else. It does not notify, unblock or
  restart anything, and it never waits for an awaiter.
- The raiser is free to die. `get`, `resolve` and `expire` work from the `PauseCorrelationId` alone —
  no in-memory handle, which is why that id is the whole address.
- Work continues when something performs a new Perceive, reads the settled row, and acts on it.

### Expiry is an outcome

A pause past its deadline settles durably to `AgentPauseResponse.TimedOut`. `expire()` sweeps every
open pause that is due; `get` and `listOpen` also settle what they find due before answering, so an
open record is never a stale one even on a host where no sweep ever runs. Reads in this store write,
deliberately: the alternative is an indefinitely-open row a later reader cannot tell from a live
decision. A `null` response means *open*, never *expired* — `PauseRecord.isTimedOut` is the question
to ask.

### Settled once

`raise` refuses a `correlationId` that already names a different pause, or one whose decision has
settled, rather than upserting over it — reusing an id would silently reopen a decision a person
already made. A byte-identical re-raise of a still-open pause succeeds, so a raiser that died without
learning whether its write landed can retry safely. Settling is first-writer-wins, guarded in the
`UPDATE` statement rather than by transaction isolation, which JDBC SQLite does not provide.

### Version skew

Adding an `EscalationChannel` variant is a breaking change for readers, so an older binary can meet a
pause it cannot decode. The operations that keep the table honest never decode a payload: `resolve`
and `expire` write through the `correlation_id`, `expires_at` and `settled_at` columns alone, so an
unreadable pause can still be answered and still times out on schedule. `listOpen` skips a row it
cannot read and announces it as a `StoreRowUndecodableEvent` (AMPR-364); `get` returns a typed
`UndecodablePauseException`.

## Relationship to standing consent

`AgentPause` answers *"how do we ask?"* — not *"has the user already said yes?"*. Those stay separate
concerns, and `PauseStore` deliberately does not store the second one.

- Standing consent for a *class* of operation lives in the grant tables (`PlugGrants`, `LinkGrants`,
  read through `PlugPermissionGate`). `PauseStore` holds one decision about one operation, and never
  generalises it.
- When a permission gate refuses and a fresh decision is needed, raise an `AgentPause` rather than
  inlining escalation logic at the refusal site.
- An `AgentPauseResponse.Approved` may be *converted* into a standing grant by the caller. The
  `payload` field is opaque to the pause primitive, so that conversion is Plug code's to write; the
  store records the answer and stops there.

Earlier versions of this document described a `ConsentRepository` and a
`ConsentAwarePromptService`. Neither exists in the repository — see `PlugPermissions` in
[`docs/concepts/plug-permissions.md`](../concepts/plug-permissions.md) for what actually gates a
dispatch today.

## Plug example

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

`AgentPause`, `EscalationChannel`, `AgentPauseResponse`, `PauseUrgency`, `ChannelAvailability`,
`PauseStore`, `PauseRecord`, and `PauseResolution` are part of the public Ampere SDK. Adding a new `EscalationChannel` variant is a breaking change for renderers (their `when` becomes non-exhaustive at compile time), which is the intended forcing function: every renderer must explicitly opt in to a new channel before it ships.

Adding optional fields with defaults to existing variants is source-compatible. Removing fields, or changing field types, is a breaking change.
