# AgentSurface

`AgentSurface` is Ampere's primitive for **Plugin-emitted UI render requests**. It is the common contract that Plugin code (commonMain) and platform renderers (iOS, Android Compose Multiplatform, Desktop) build against. This document is the source of truth for the W1.3 / W1.4 platform renderers and the W2.1 reference Plugins.

## Design goals

- **Typed and serializable.** Every variant is `@Serializable`; surface requests can be persisted, replayed, and shipped over a wire.
- **commonMain-only.** No iOS, Android, or Compose imports leak into the type definitions. Renderers translate the contract into native UI.
- **No reactive framework dependencies.** `kotlinx.coroutines.flow` is acceptable; Compose, SwiftUI, and React are not.
- **Stable correlation.** Every surface and every response carries a `correlationId` so a Plugin can `awaitSurfaceResponse` without coupling to bus internals.

## Type hierarchy

| Type | Location | Purpose |
| --- | --- | --- |
| `AgentSurface` | `link.socket.ampere.agents.events.surface` | Sealed render request (`Form`, `Choice`, `Confirmation`, `Card`). |
| `AgentSurfaceField` | `link.socket.ampere.agents.events.surface` | Sealed input field for `Form` (`Text`, `Number`, `Toggle`, `DateTime`, `Selection`, `Secret`). |
| `AgentSurfaceFieldValue` | `link.socket.ampere.agents.events.surface` | Typed value returned per field. |
| `AgentSurfaceResponse` | `link.socket.ampere.agents.events.surface` | Sealed reply (`Submitted`, `Cancelled`, `TimedOut`). |
| `FieldValidationResult` | `link.socket.ampere.agents.events.surface` | `Valid` / `Invalid(errors)` from `AgentSurfaceField.validate`. |
| `AgentSurfaceEvent` | `link.socket.ampere.agents.domain.event` | `Requested` / `Responded` events flowing through `EventSerialBus`. |
| `CorrelationId` | `link.socket.ampere.agents.events.surface` | `typealias CorrelationId = String`. |

## Lifecycle

```
Plugin                          EventSerialBus                       Renderer
  |                                    |                                |
  | bus.emitSurfaceRequest(surface)    |                                |
  |----------------------------------->|                                |
  |                                    | publish(Requested)             |
  |                                    |------------------------------->|
  |                                    |                                | render UI
  | awaitSurfaceResponse(correlationId)|                                | gather response
  |==suspended=========================|                                |
  |                                    | publish(Responded)             |
  |                                    |<-------------------------------|
  |                                    |                                |
  |<================ AgentSurfaceResponse                               |
```

1. **Emit.** The Plugin calls `bus.emitSurfaceRequest(surface, eventSource)` (or constructs `AgentSurfaceEvent.Requested` directly and publishes it).
2. **Render.** A platform-specific subscriber to `AgentSurfaceEvent.Requested` translates the surface into native UI.
3. **Respond.** When the user submits, cancels, or the renderer's own timeout elapses, the renderer publishes `AgentSurfaceEvent.Responded` with the same `correlationId`.
4. **Resume.** `awaitSurfaceResponse(awaiterAgentId, correlationId, timeout)` returns the `AgentSurfaceResponse`. If `timeout` elapses first, the helper returns an `AgentSurfaceResponse.TimedOut` rather than throwing.

## Validation

Field validation is the canonical responsibility of `AgentSurfaceField.validate(value)`. It runs in pure Kotlin so renderers, Plugin code, and integration tests share the exact same predicates.

| Field | Rules |
| --- | --- |
| `Text` | `required`, `minLength`, `maxLength`, optional `pattern` (Kotlin `Regex`). |
| `Number` | `required`, `min`, `max`, `integerOnly`. |
| `Toggle` | `required` means the toggle must be `true` to pass. |
| `DateTime` | `required`, `notBefore`, `notAfter`. |
| `Selection` | `required`, `minSelections`, `maxSelections`, unknown ids rejected. |
| `Secret` | `required`, `minLength`, `maxLength`. Renderers must avoid logging the value beyond the originating Plugin. |

`validate` returns `FieldValidationResult.Valid` or `FieldValidationResult.Invalid(errors)` with human-readable messages. Renderers may surface failures inline, but they must not submit a response that fails validation.

## Renderer requirements

Every platform renderer (iOS native pass-through, Android Compose Multiplatform, Desktop Compose, Web) **must**:

1. Subscribe to `AgentSurfaceEvent.Requested.EVENT_TYPE` on the bus.
2. Dispatch on `event.surface` exhaustively over the four `AgentSurface` variants — adding a variant in `commonMain` is a breaking change that fails the renderer's `when` at compile time.
3. Render fields in the order they appear in `AgentSurface.Form.fields`.
4. Run `AgentSurfaceField.validate` before publishing `Submitted`. Submission with invalid values is a contract violation.
5. Publish exactly one `AgentSurfaceEvent.Responded` per `Requested`, carrying the originating `correlationId`.
6. Treat `AgentSurfaceField.Secret` values as ephemeral: never persist, log, or echo them outside the renderer.
7. Map `AgentSurface.Confirmation.Severity.Destructive` to the platform's destructive affordance (red action button on Android, system-red on iOS, etc.) — this is the only intended way severity is allowed to leak into renderer styling.

Renderers **must not**:

- Mutate or extend the surface payload before forwarding it elsewhere.
- Reference Compose, SwiftUI, or other UI types from the `commonMain` contract.
- Drop a `Requested` silently — if no renderer is registered, the bus simply has no subscribers and the awaiter will time out, which is the intended degradation.

## Plugin example

```kotlin
suspend fun choosePullRequestBranch(
    bus: EventSerialBus,
    agentId: AgentId,
): AgentSurfaceResponse {
    val correlationId = generateUUID(agentId, "pr-branch")
    bus.emitSurfaceRequest(
        surface = AgentSurface.Choice(
            correlationId = correlationId,
            title = "Pick a base branch",
            options = listOf(
                AgentSurface.Choice.Option(id = "main", label = "main"),
                AgentSurface.Choice.Option(id = "develop", label = "develop"),
            ),
        ),
        eventSource = EventSource.Agent(agentId),
    )
    return bus.awaitSurfaceResponse(
        awaiterAgentId = agentId,
        correlationId = correlationId,
        timeout = 30.seconds,
    )
}
```

## Versioning

`AgentSurface`, `AgentSurfaceField`, `AgentSurfaceFieldValue`, and `AgentSurfaceResponse` are part of the public Ampere SDK. Adding a new variant is a breaking change for renderers (their `when` becomes non-exhaustive at compile time), which is the intended forcing function: every renderer must explicitly opt in to a new surface type before it ships.

Adding optional fields with defaults to existing variants is source-compatible. Removing fields, or changing field types, is a breaking change.
