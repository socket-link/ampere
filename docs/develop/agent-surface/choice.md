# AgentSurface.Choice

A **Choice** is a single- or multi-select picker over a fixed set of options.
Use it when the answer is "one of these" — never when the answer is open
text. Picking from a list is easier for the user and tighter for the trace
than parsing free-form input.

## Constructor

```kotlin
AgentSurface.Choice(
    correlationId: CorrelationId,
    title: String,
    description: String? = null,
    options: List<AgentSurface.Choice.Option>,
    multiSelect: Boolean = false,
    minSelections: Int = if (multiSelect) 0 else 1,
    maxSelections: Int = if (multiSelect) options.size else 1,
)
```

| Prop | Type | Default | Notes |
| --- | --- | --- | --- |
| `correlationId` | `CorrelationId` | required | Pairs request and response. |
| `title` | `String` | required | Single-line heading shown above the options. |
| `description` | `String?` | `null` | Optional context shown below the title. |
| `options` | `List<Option>` | required | Rendered in the order given. |
| `multiSelect` | `Boolean` | `false` | If `false`, exactly one option may be chosen. |
| `minSelections` | `Int` | `0` (multi) / `1` (single) | Lower bound on selections. |
| `maxSelections` | `Int` | `options.size` (multi) / `1` (single) | Upper bound on selections. |

### `Option`

```kotlin
AgentSurface.Choice.Option(
    id: String,
    label: String,
    description: String? = null,
    enabled: Boolean = true,
)
```

| Prop | Type | Default | Notes |
| --- | --- | --- | --- |
| `id` | `String` | required | The id reported back in the response. Stable across renderers. |
| `label` | `String` | required | The visible label. |
| `description` | `String?` | `null` | Optional helper text per option. |
| `enabled` | `Boolean` | `true` | If `false`, the option renders but cannot be selected. |

## Validation

The renderer enforces selection counts before publishing `Submitted`:

| Rule | Triggers when |
| --- | --- |
| required-when-single | single-select with no selection |
| minSelections | `selections.size < minSelections` |
| maxSelections | `selections.size > maxSelections` |
| unknown ids | a selected id isn't present in `options` |

Disabled options are not selectable; if a renderer somehow submits one, treat
the response as malformed and reject it on the Plugin side.

## Response shape

A `Submitted` response from a `Choice` is **always** encoded as a
`SelectionValue` under the well-known key
`AgentSurfaceResponse.SELECTION_KEY` (`"selection"`):

```kotlin
AgentSurfaceResponse.Submitted(
    correlationId = correlationId,
    values = mapOf(
        AgentSurfaceResponse.SELECTION_KEY to AgentSurfaceFieldValue.SelectionValue(
            selectedIds = listOf("main"),
        ),
    ),
)
```

For single-select, `selectedIds` has exactly one element. For multi-select,
it has between `minSelections` and `maxSelections` elements. Read it like
any other typed value:

```kotlin
val selection = response.values[AgentSurfaceResponse.SELECTION_KEY]
    as? AgentSurfaceFieldValue.SelectionValue
val picked: List<String> = selection?.selectedIds.orEmpty()
```

`Cancelled` and `TimedOut` carry no selection — they signal that no choice
was made.

## Examples

### Single-select

```kotlin
val correlationId = generateUUID(agentId, "pick-base-branch")

bus.emitSurfaceRequest(
    surface = AgentSurface.Choice(
        correlationId = correlationId,
        title = "Pick a base branch",
        description = "The PR will target this branch.",
        options = listOf(
            AgentSurface.Choice.Option(id = "main", label = "main"),
            AgentSurface.Choice.Option(id = "develop", label = "develop"),
            AgentSurface.Choice.Option(
                id = "release",
                label = "release",
                description = "Frozen during the release window.",
                enabled = false,
            ),
        ),
    ),
    eventSource = EventSource.Agent(agentId),
)
```

### Multi-select

```kotlin
val correlationId = generateUUID(agentId, "pick-reviewers")

bus.emitSurfaceRequest(
    surface = AgentSurface.Choice(
        correlationId = correlationId,
        title = "Pick reviewers",
        options = teamMembers.map { member ->
            AgentSurface.Choice.Option(id = member.handle, label = member.name)
        },
        multiSelect = true,
        minSelections = 1,
        maxSelections = 3,
    ),
    eventSource = EventSource.Agent(agentId),
)
```

## Constraints

- **Option order is the contract.** Don't sort by label in the renderer.
  The Plugin chose the order; if there's a "primary" option, it's first.
- **Option ids are stable.** They appear in `selectedIds` and in tests.
  Renaming is a breaking change.
- **Disabled is not the same as absent.** A disabled option still renders;
  use it to *show* a path the user can't take right now and *why*. To hide
  an option, leave it out of the list.
- **`Choice` is for "one of these," not "yes / no".** A `Choice` with two
  options labelled "Yes" and "No" is a `Confirmation` in disguise. Use the
  variant that matches the question's shape.

<!-- platform-notes: pending W1.3 / W1.4 -->
<!-- example: pending W2.1 -->
