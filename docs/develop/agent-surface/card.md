# AgentSurface.Card

A **Card** is structured, slot-based content. Use it to *show* a result, an
artifact, or a summary — and optionally let the user choose a follow-up
action. Cards are the variant for "here's what I did" or "here's what I
found"; they are not for asking the user to fill anything in.

## Constructor

```kotlin
AgentSurface.Card(
    correlationId: CorrelationId,
    title: String,
    slots: List<Slot>,
    actions: List<Action> = emptyList(),
)
```

| Prop | Type | Default | Notes |
| --- | --- | --- | --- |
| `correlationId` | `CorrelationId` | required | Pairs request and response. |
| `title` | `String` | required | Single-line heading shown above the slots. |
| `slots` | `List<Slot>` | required | Rendered in the order given. |
| `actions` | `List<Action>` | empty | Optional buttons. If empty, the card is read-only and the response is whatever the user does to dismiss it. |

## Slots

`Card.Slot` is a sealed interface. Five slot kinds cover the content space.
New slot kinds extend the sealed hierarchy — there is no `Custom(any)`
escape hatch by design.

### `Heading`

```kotlin
Slot.Heading(text: String, level: Int = 2)
```

A subheading inside the card. `level` mirrors `<h2>`/`<h3>` — renderers map
it to whatever heading scale they support.

### `Body`

```kotlin
Slot.Body(text: String)
```

A paragraph of plain text. **Plain.** No HTML, no markdown that the
renderer is expected to parse, no embedded `<script>`. Slots are typed for
a reason — if you need bold, that's a future slot kind, not a workaround.

### `KeyValue`

```kotlin
Slot.KeyValue(key: String, value: String)
```

A labelled field. Renderers typically display these as a two-column row.
Use `KeyValue` for metadata like commit shas, ticket numbers, durations.

### `Image`

```kotlin
Slot.Image(url: String, altText: String? = null)
```

A displayable image. `altText` is the accessibility caption — set it for
every image that conveys information, leave it null only for purely
decorative images.

### `Code`

```kotlin
Slot.Code(source: String, language: String? = null)
```

A code block. `language` is a hint for syntax highlighting (`"kotlin"`,
`"sql"`, `"diff"`). Renderers may choose not to highlight; the source must
display verbatim regardless.

## Actions

```kotlin
AgentSurface.Card.Action(
    id: String,
    label: String,
    severity: AgentSurface.Confirmation.Severity = Severity.Info,
)
```

| Prop | Type | Default | Notes |
| --- | --- | --- | --- |
| `id` | `String` | required | Reported as `chosenAction` in the response. Stable. |
| `label` | `String` | required | The visible button text. Imperative. |
| `severity` | `Severity` | `Info` | Reuses `Confirmation.Severity` so destructive actions on cards style the same way. |

Cards with no actions are read-only displays; the user can dismiss them and
the response will be `Cancelled`. Cards with one or more actions let the
user pick a follow-up; the response is `Submitted` with `chosenAction` set
to the picked action's id.

## Response shape

A `Submitted` response from a `Card`:

```kotlin
AgentSurfaceResponse.Submitted(
    correlationId = correlationId,
    values = emptyMap(),
    chosenAction = "open-pr",
)
```

- `values` is empty. Cards don't collect input.
- `chosenAction` is the id of the action the user picked, or null if the
  card had no actions and was dismissed without input (in practice, that
  outcome is usually delivered as `Cancelled` instead).

`Cancelled` and `TimedOut` mean what they always mean.

## Example

```kotlin
val correlationId = generateUUID(agentId, "show-pr-result", prNumber.toString())

bus.emitSurfaceRequest(
    surface = AgentSurface.Card(
        correlationId = correlationId,
        title = "Pull request opened",
        slots = listOf(
            AgentSurface.Card.Slot.Body(
                text = "PR #$prNumber is open against main.",
            ),
            AgentSurface.Card.Slot.KeyValue(key = "Title", value = prTitle),
            AgentSurface.Card.Slot.KeyValue(key = "Author", value = author),
            AgentSurface.Card.Slot.KeyValue(key = "Reviewers", value = reviewers.joinToString()),
            AgentSurface.Card.Slot.Heading(text = "Diff summary", level = 3),
            AgentSurface.Card.Slot.Code(
                source = diffSummary,
                language = "diff",
            ),
        ),
        actions = listOf(
            AgentSurface.Card.Action(
                id = "open-in-browser",
                label = "Open in browser",
            ),
            AgentSurface.Card.Action(
                id = "close-pr",
                label = "Close PR",
                severity = AgentSurface.Confirmation.Severity.Destructive,
            ),
        ),
    ),
    eventSource = EventSource.Agent(agentId),
)

val response = bus.awaitSurfaceResponse(
    awaiterAgentId = agentId,
    correlationId = correlationId,
    timeout = 5.minutes,
)

when (response) {
    is AgentSurfaceResponse.Submitted -> when (response.chosenAction) {
        "open-in-browser" -> openBrowser(prUrl)
        "close-pr" -> closePullRequest(prNumber)
        else -> Unit
    }
    is AgentSurfaceResponse.Cancelled,
    is AgentSurfaceResponse.TimedOut -> Unit
}
```

## Constraints

- **Slot order is the contract.** The order is the reading order; don't
  reflow.
- **Slot kinds are sealed.** A new kind of content needs a new variant in
  the sealed hierarchy and a renderer update on every platform. There is
  no `Body(text = "<html>...")` shortcut for embedding markup — the slot
  taxonomy exists precisely so platforms can render natively.
- **Actions are imperatives.** `"Open in browser"`, `"Close PR"`. Avoid
  `"More..."` or `"Continue"` — they teach the user nothing about what
  they're picking.
- **Use `Destructive` severity sparingly on cards.** If a card has a
  destructive action, the renderer's destructive affordance fires every
  time the user sees the card. Reserve it for cases where confirming the
  destructive button means *don't ask again, do it now*; otherwise emit a
  follow-up `Confirmation` to gate the destructive path.

<!-- platform-notes: pending W1.3 / W1.4 -->
<!-- example: pending W2.1 -->
