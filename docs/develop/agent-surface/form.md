# AgentSurface.Form

A **Form** is a multi-field input. The Plugin describes the fields it needs;
the renderer presents them in order; the response carries a typed value per
field. Use it whenever you need more than a single yes/no, a single pick from
a list, or a static piece of content.

## Constructor

```kotlin
AgentSurface.Form(
    correlationId: CorrelationId,
    title: String,
    description: String? = null,
    fields: List<AgentSurfaceField>,
    submitLabel: String = "Submit",
    cancelLabel: String = "Cancel",
)
```

| Prop | Type | Default | Notes |
| --- | --- | --- | --- |
| `correlationId` | `CorrelationId` (`String`) | required | Pairs request and response. Generate per-decision, not per-attempt. |
| `title` | `String` | required | The single-line heading the renderer shows above the fields. |
| `description` | `String?` | `null` | Optional one-paragraph context shown below the title. |
| `fields` | `List<AgentSurfaceField>` | required | Rendered in order. Order is part of the contract. |
| `submitLabel` | `String` | `"Submit"` | Imperative for the confirm action. Replace with the verb that matches the outcome. |
| `cancelLabel` | `String` | `"Cancel"` | Imperative for the dismiss action. |

## Field types

`AgentSurfaceField` is sealed. Six variants cover the input space; they all
share the four common props on the interface (`id`, `label`, `helpText`,
`required`) and add type-specific props.

### Common to every field

| Prop | Type | Default | Notes |
| --- | --- | --- | --- |
| `id` | `String` | required | The key under which the field's value appears in `Submitted.values`. |
| `label` | `String` | required | The visible field label. |
| `helpText` | `String?` | `null` | Optional helper text below the field. |
| `required` | `Boolean` | `false` | If `true`, validation fails when the value is missing. |

### `Text`

Free-form single- or multi-line text. The `pattern` is a Kotlin `Regex` source.

| Prop | Type | Default |
| --- | --- | --- |
| `placeholder` | `String?` | `null` |
| `multiline` | `Boolean` | `false` |
| `minLength` | `Int` | `0` |
| `maxLength` | `Int?` | `null` |
| `pattern` | `String?` | `null` |

| Validation | Triggers when |
| --- | --- |
| required | value is null or empty |
| minLength | `text.length < minLength` |
| maxLength | `text.length > maxLength` |
| pattern | `Regex(pattern).matches(text)` is false |

### `Number`

Numeric input. `integerOnly` rejects non-integral doubles.

| Prop | Type | Default |
| --- | --- | --- |
| `min` | `Double?` | `null` |
| `max` | `Double?` | `null` |
| `integerOnly` | `Boolean` | `false` |

| Validation | Triggers when |
| --- | --- |
| required | value is null |
| integerOnly | the number has a non-zero fractional part |
| min | `number < min` |
| max | `number > max` |

### `Toggle`

Boolean switch. `required` here means *the toggle must be on* — the same
shape as a "I accept the terms" checkbox.

| Prop | Type | Default |
| --- | --- | --- |
| `default` | `Boolean` | `false` |

| Validation | Triggers when |
| --- | --- |
| required | value is `false` or null |

### `DateTime`

A point in time, expressed as `kotlinx.datetime.Instant`. `includeTime = false`
hints to the renderer that a date picker is enough.

| Prop | Type | Default |
| --- | --- | --- |
| `notBefore` | `Instant?` | `null` |
| `notAfter` | `Instant?` | `null` |
| `includeTime` | `Boolean` | `true` |

| Validation | Triggers when |
| --- | --- |
| required | value is null |
| notBefore | `instant < notBefore` |
| notAfter | `instant > notAfter` |

### `Selection`

A single- or multi-pick from a fixed set of options, rendered inline within
the form (as opposed to `AgentSurface.Choice`, which is a top-level surface).
Reuses `AgentSurface.Choice.Option`.

| Prop | Type | Default |
| --- | --- | --- |
| `options` | `List<AgentSurface.Choice.Option>` | required |
| `multiSelect` | `Boolean` | `false` |
| `minSelections` | `Int` | `1` (single-select) / `0` (multi-select) |
| `maxSelections` | `Int` | `1` (single-select) / `options.size` (multi-select) |

| Validation | Triggers when |
| --- | --- |
| required | no options selected |
| minSelections | fewer selections than the floor |
| maxSelections | more selections than the ceiling |
| unknown ids | a selected id isn't present in `options` |

### `Secret`

A masked input. Same length validations as `Text`. Renderers must not log,
echo, or persist the value beyond the originating Plugin's scope.

| Prop | Type | Default |
| --- | --- | --- |
| `minLength` | `Int` | `0` |
| `maxLength` | `Int?` | `null` |

| Validation | Triggers when |
| --- | --- |
| required | value is null or empty |
| minLength | `text.length < minLength` |
| maxLength | `text.length > maxLength` |

## Validation contract

`AgentSurfaceField.validate(value: AgentSurfaceFieldValue?)` returns:

- `FieldValidationResult.Valid` — the value passes every constraint on the
  field.
- `FieldValidationResult.Invalid(errors: List<String>)` — one or more
  human-readable error messages, suitable to surface inline in the renderer.

The predicate runs in pure Kotlin, identically on every target. That gives
you three things:

1. Renderers can validate before publishing `Submitted`. The contract says
   they **must** — submission with invalid values is a contract violation.
2. The Plugin can re-validate on receipt without trusting the wire. Don't
   skip this. Validation runs twice by design.
3. Tests share the predicates with the production path. There's no "test
   helper" copy of the rules to drift.

A value of the wrong variant — passing a `NumberValue` to a `Text` field,
say — is treated as missing. `validate(NumberValue(1.0))` on a required
`Text` field returns `Invalid` with the "is required" error.

## Response shape

A `Submitted` response from a `Form` carries:

```kotlin
AgentSurfaceResponse.Submitted(
    correlationId: CorrelationId,
    values: Map<String, AgentSurfaceFieldValue>,
    chosenAction: String? = null,
)
```

- `values` is keyed by `AgentSurfaceField.id`. The `AgentSurfaceFieldValue`
  variant matches the field variant (`Text` ↔ `TextValue`, `Number` ↔
  `NumberValue`, etc.).
- `chosenAction` is null for forms. It's used by `Card`.

`Cancelled` and `TimedOut` are the same as for any other surface.

## Example

```kotlin
val correlationId = generateUUID(agentId, "create-pr")

bus.emitSurfaceRequest(
    surface = AgentSurface.Form(
        correlationId = correlationId,
        title = "Open a pull request",
        description = "Describe the change. Reviewers see the title and body verbatim.",
        fields = listOf(
            AgentSurfaceField.Text(
                id = "title",
                label = "Title",
                required = true,
                minLength = 3,
                maxLength = 72,
                placeholder = "Short, imperative summary",
            ),
            AgentSurfaceField.Text(
                id = "body",
                label = "Description",
                multiline = true,
                helpText = "Markdown is fine.",
            ),
            AgentSurfaceField.Toggle(
                id = "draft",
                label = "Open as draft",
                default = false,
            ),
        ),
        submitLabel = "Open PR",
    ),
    eventSource = EventSource.Agent(agentId),
)

val response = bus.awaitSurfaceResponse(
    awaiterAgentId = agentId,
    correlationId = correlationId,
    timeout = 2.minutes,
)

when (response) {
    is AgentSurfaceResponse.Submitted -> {
        val title = (response.values["title"] as AgentSurfaceFieldValue.TextValue).value
        val body = (response.values["body"] as? AgentSurfaceFieldValue.TextValue)?.value.orEmpty()
        val draft = (response.values["draft"] as? AgentSurfaceFieldValue.ToggleValue)?.value ?: false
        openPullRequest(title = title, body = body, draft = draft)
    }
    is AgentSurfaceResponse.Cancelled -> Unit
    is AgentSurfaceResponse.TimedOut -> log("PR form timed out at ${response.timeoutMillis}ms")
}
```

## Constraints

- **Field order is the contract.** Don't sort, group, or reorder fields in
  the renderer. The Plugin chose the order for a reason.
- **Field ids are stable.** They appear in `Submitted.values` and they
  appear in tests. Treat them like serialised wire keys: you can add new
  ones, but renaming is a breaking change.
- **A field with no value still has a key on the response.** Optional fields
  the user left blank either have no entry in `values` or have an entry
  whose `String`/`Boolean`/etc. is empty/false. Read defensively; don't `!!`.

<!-- platform-notes: pending W1.3 / W1.4 -->
<!-- example: pending W2.1 -->
