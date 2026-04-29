# Getting started with AgentSurface

Plugins running on AMPERE never draw pixels. They describe what they want the
user to see and let the platform decide how to render it. The piece of the SDK
that carries that description from your Plugin to the platform is
`AgentSurface`.

This guide walks from an empty Plugin to one that asks a person to confirm a
destructive action and then handles the reply. It takes about five minutes,
and every code sample is valid against the `AgentSurface` contract that ships
with `ampere-core`.

> **Audience.** Third-party Plugin developers building on AMPERE. You should
> already be comfortable with `commonMain`, suspending functions, and the
> `EventSerialBus` (the same bus that carries every other typed event in the
> system).

## What you're going to build

A Plugin that, before doing something irreversible, emits a typed
`Confirmation` surface, suspends until the user responds, and decides what to
do based on whether the user approved, rejected, or never replied.

You're going to write four pieces of code:

1. Generate a `correlationId` that pairs the request with its response.
2. Build the `AgentSurface.Confirmation` value.
3. Emit the surface and suspend until a response arrives.
4. Branch on the typed response variant.

That's the entire surface lifecycle. Everything else in `AgentSurface` is
either a different variant (Form, Choice, Card) or a different field type
inside Form — same shape, different payload.

## Prerequisites

Your Plugin module needs:

- A reference to `EventSerialBus` (the bus you publish to and subscribe from).
- An `AgentId` that identifies the Plugin's owning agent. This is what the
  bus uses to scope subscriptions.

Both are part of the standard Plugin runtime. If you can already publish an
`Event`, you can already use `AgentSurface`.

## Step 1 — Generate a correlation id

A `correlationId` is what makes a surface request and its response a single
transactional unit. The Plugin generates it; the renderer echoes it back
verbatim. Without it, an asynchronous reply has no idea which request it
belongs to.

```kotlin
import link.socket.ampere.agents.events.surface.CorrelationId
import link.socket.ampere.agents.events.utils.generateUUID

val correlationId: CorrelationId = generateUUID(agentId, "delete-branch")
```

`generateUUID` is deterministic given its inputs. Use a label that's unique to
the *thing the user is deciding about* — not to the moment in time. If the
Plugin retries the same decision, the same id pairs the new request with the
new response.

## Step 2 — Build the surface

`AgentSurface.Confirmation` is the smallest variant: a prompt, a severity, and
two button labels. Severity is the only intentional channel through which
styling leaks from the contract into the renderer.

```kotlin
import link.socket.ampere.agents.events.surface.AgentSurface

val surface = AgentSurface.Confirmation(
    correlationId = correlationId,
    prompt = "Delete the branch 'feature/old'? This cannot be undone.",
    severity = AgentSurface.Confirmation.Severity.Destructive,
    confirmLabel = "Delete",
    cancelLabel = "Keep",
)
```

Three things to notice:

- **The prompt is a complete sentence.** Renderers treat it as the question
  the user is being asked. Don't lead with "Are you sure" — it teaches the
  reader nothing about *what* they're confirming.
- **`Destructive` is not a styling hint.** It's the contract telling every
  platform: this is the path where someone loses work. Renderers map it to
  whatever destructive affordance the platform already has (a red button on
  Android, system-red on iOS, a warning glyph on a CLI).
- **Labels are imperatives, not "OK / Cancel".** The label is what the user
  is committing to. "Delete" and "Keep" describe the outcomes; "OK" and
  "Cancel" describe the dialog.

## Step 3 — Emit and suspend

`emitSurfaceRequest` publishes an `AgentSurfaceEvent.Requested` carrying the
surface. `awaitSurfaceResponse` subscribes to the matching
`AgentSurfaceEvent.Responded`, suspends the calling coroutine, and returns
the typed response when one arrives.

```kotlin
import kotlin.time.Duration.Companion.seconds
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.surface.awaitSurfaceResponse
import link.socket.ampere.agents.events.surface.emitSurfaceRequest

bus.emitSurfaceRequest(
    surface = surface,
    eventSource = EventSource.Agent(agentId),
)

val response = bus.awaitSurfaceResponse(
    awaiterAgentId = agentId,
    correlationId = correlationId,
    timeout = 30.seconds,
)
```

The emit is fire-and-forget. The await is the suspending half: the coroutine
parks until the renderer publishes a `Responded` event with the same
`correlationId`. If `timeout` elapses first, you get an
`AgentSurfaceResponse.TimedOut` rather than an exception — failure is part of
the type, not a control-flow surprise.

You can pass `timeout = null` to wait indefinitely. Only do that if you have
some other deadline in scope; an unbounded await with no signal-of-life is a
hang.

## Step 4 — Handle the response

`AgentSurfaceResponse` is a sealed interface with three variants. A `when`
over them is exhaustive; the compiler tells you when you've forgotten one.

```kotlin
import link.socket.ampere.agents.events.surface.AgentSurfaceResponse

when (response) {
    is AgentSurfaceResponse.Submitted -> deleteBranch("feature/old")
    is AgentSurfaceResponse.Cancelled -> log("User declined: ${response.reason}")
    is AgentSurfaceResponse.TimedOut -> log("No reply within ${response.timeoutMillis}ms")
}
```

For a `Confirmation`, `Submitted` means the user pressed the confirm button.
The `chosenAction` field on `Submitted` is null for `Confirmation` (there's
only one confirm path); it carries an action id only for `Card` variants.
`Cancelled.reason` is optional context from the renderer — usually null.

## The whole thing

```kotlin
import kotlin.time.Duration.Companion.seconds
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.surface.AgentSurface
import link.socket.ampere.agents.events.surface.AgentSurfaceResponse
import link.socket.ampere.agents.events.surface.awaitSurfaceResponse
import link.socket.ampere.agents.events.surface.emitSurfaceRequest
import link.socket.ampere.agents.events.utils.generateUUID

suspend fun confirmDeleteBranch(
    bus: EventSerialBus,
    agentId: AgentId,
    branchName: String,
): Boolean {
    val correlationId = generateUUID(agentId, "delete-branch", branchName)

    bus.emitSurfaceRequest(
        surface = AgentSurface.Confirmation(
            correlationId = correlationId,
            prompt = "Delete the branch '$branchName'? This cannot be undone.",
            severity = AgentSurface.Confirmation.Severity.Destructive,
            confirmLabel = "Delete",
            cancelLabel = "Keep",
        ),
        eventSource = EventSource.Agent(agentId),
    )

    return when (val response = bus.awaitSurfaceResponse(
        awaiterAgentId = agentId,
        correlationId = correlationId,
        timeout = 30.seconds,
    )) {
        is AgentSurfaceResponse.Submitted -> true
        is AgentSurfaceResponse.Cancelled,
        is AgentSurfaceResponse.TimedOut -> false
    }
}
```

That function is the smallest meaningful Plugin↔user interaction. Substitute
a different surface variant and a different response branch and you have the
shape of every UI request a Plugin will ever make.

## What you can rely on

The `AgentSurface` contract is `commonMain` Kotlin. It carries no Compose,
SwiftUI, UIKit, or terminal-rendering types. Your Plugin compiles on every
target AMPERE supports without per-platform shims. When platform renderers
ship, they translate the same typed values into whatever native UI the host
provides.

Two guarantees worth making explicit:

- **Validation is a pure function.** `AgentSurfaceField.validate(value)`
  returns `FieldValidationResult.Valid` or `Invalid(errors)` and runs
  identically on every target. Renderers validate before submission; you
  should validate again on receipt. The pure-Kotlin predicates make that
  cheap.
- **Responses are typed, not stringly-keyed.** A `Submitted` response from a
  `Form` carries a `Map<String, AgentSurfaceFieldValue>` keyed by field id;
  a `Submitted` from a `Choice` carries a `SelectionValue` under the
  well-known key `AgentSurfaceResponse.SELECTION_KEY`; a `Submitted` from a
  `Card` or `Confirmation` carries a `chosenAction` string. The shape per
  variant is documented on each variant's reference page.

## What's next

- **One variant per page**: [Form](agent-surface/form.md),
  [Choice](agent-surface/choice.md),
  [Confirmation](agent-surface/confirmation.md),
  [Card](agent-surface/card.md). Each page lists every prop, every
  validation rule, and the exact response shape.
- **[Designing for agents that render UI](agent-surface-design-patterns.md)**
  — when to ask versus when to act, how urgency maps to variant choice, and
  the writing rules for prompts and validation copy.

<!-- platform-notes: pending W1.3 / W1.4 -->
<!-- example: pending W2.1 -->
