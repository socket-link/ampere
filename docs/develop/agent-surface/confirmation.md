# AgentSurface.Confirmation

A **Confirmation** is the smallest variant: a single prompt with an accept
and reject affordance. Use it when the question is binary and the user is
making a real decision — usually because the next step is irreversible,
expensive, or visible to other people.

## Constructor

```kotlin
AgentSurface.Confirmation(
    correlationId: CorrelationId,
    prompt: String,
    severity: Severity = Severity.Info,
    confirmLabel: String = "Confirm",
    cancelLabel: String = "Cancel",
)
```

| Prop | Type | Default | Notes |
| --- | --- | --- | --- |
| `correlationId` | `CorrelationId` | required | Pairs request and response. |
| `prompt` | `String` | required | The complete question the user is being asked. Sentence form. |
| `severity` | `Severity` | `Info` | The contract's signal that the path is destructive, attention-needed, or routine. |
| `confirmLabel` | `String` | `"Confirm"` | Imperative for the accept action. Replace with the verb of the outcome. |
| `cancelLabel` | `String` | `"Cancel"` | Imperative for the reject action. |

### `Severity`

```kotlin
enum class Severity { Info, Warning, Destructive }
```

| Value | When to use | Renderer mapping |
| --- | --- | --- |
| `Info` | Routine confirmation — sending an email, scheduling a meeting. | Neutral default. |
| `Warning` | Attention-needed but recoverable — overwriting an unsaved draft. | Platform's elevated/warning affordance. |
| `Destructive` | Cannot be undone — deleting data, force-pushing, sending production traffic. | Platform's destructive affordance (red action button on Android, system-red on iOS, etc.). |

Severity is **the only intentional channel** through which styling leaks
from the contract into the renderer. Don't try to push styling through
prompt text or labels (`"⚠️ Are you sure? ⚠️"` is a contract violation by
spirit, even if not by compile-time check).

## Validation

There is no field-level validation. The user accepts or rejects.

## Response shape

`Confirmation` produces:

- `AgentSurfaceResponse.Submitted` — the user pressed the confirm action.
  `chosenAction` is `null`; `values` is empty.
- `AgentSurfaceResponse.Cancelled` — the user pressed cancel or dismissed
  the prompt. `reason` is optional context from the renderer.
- `AgentSurfaceResponse.TimedOut` — the renderer didn't reply within the
  awaiter's timeout window.

In practice, the Plugin treats `Cancelled` and `TimedOut` the same way:
*don't proceed*. They differ only in what you can tell the user about why
the next step didn't happen.

## Example

```kotlin
val correlationId = generateUUID(agentId, "force-push", branchName)

bus.emitSurfaceRequest(
    surface = AgentSurface.Confirmation(
        correlationId = correlationId,
        prompt = "Force-push '$branchName'? Anyone with this branch checked out " +
            "will need to reset to the new history.",
        severity = AgentSurface.Confirmation.Severity.Destructive,
        confirmLabel = "Force-push",
        cancelLabel = "Stop",
    ),
    eventSource = EventSource.Agent(agentId),
)

val response = bus.awaitSurfaceResponse(
    awaiterAgentId = agentId,
    correlationId = correlationId,
    timeout = 60.seconds,
)

if (response is AgentSurfaceResponse.Submitted) {
    forcePush(branchName)
}
```

## Constraints

- **Prompts are complete sentences.** A renderer surfaces the prompt
  verbatim; a fragment like `"Confirm?"` is uninformative no matter what
  the title above it says.
- **Labels describe the outcome.** `"Delete"` and `"Keep"`, not `"OK"` and
  `"Cancel"`. The user is committing to the verb on the button — make sure
  it matches what's about to happen.
- **`Destructive` means irreversible.** Don't use it for "this is mildly
  important." If `Cmd+Z` would undo the next step, severity is `Info` or
  `Warning`. The renderer's destructive affordance should mean the same
  thing every time the user sees it.
- **A `Confirmation` is not a notification.** If the user has nothing to
  decide, don't ask. Render a `Card` (or, eventually, surface the result
  through whatever notification primitive the platform provides).

<!-- platform-notes: pending W1.3 / W1.4 -->
<!-- example: pending W2.1 -->
