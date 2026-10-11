---
concept: Emission
status: experimental
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/emission/Emission.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/emission/EmissionKind.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/emission/EmissionPayload.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/emission/Affordance.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/emission/EmissionProvenance.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/emission/EmissionScope.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/emission/kinds/DomainEmissionKind.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/emission/kinds/recipe/Recipe.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/Principal.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/EmissionEvent.kt
related: [ChiProtocol, EventSerialBus, AgentSurface, PropelLoop, EmissionDedup]
last_verified: 2026-10-11
---

# Emission

## What it is

An `Emission` is one moment of computer-initiated human contact — the
typed unit of CHI (see [ChiProtocol](chi.md)). Each Emission carries a
`kind` — one of the four core kinds (`Prose`, `Decision`, `Confirmation`,
`Sensor`) or `Domain(id)`, the one variant that carries any consumer-defined
kind as data (see [Domain kinds](#domain-kinds)) — a typed `payload`,
optional `affordances`, an optional `Confidence`, full `provenance`, an
optional content-deterministic `dedupKey`, and a `producedAt` timestamp.
Provenance answers two questions as data: *what caused this*
(`parentEmissionId`, the Emission it was produced in service of) and *on
whose authority* (`principal`).
Emissions ride the `EventSerialBus` inside the `EmissionEvent` family
(`Produced` when AMPERE surfaces one, `Resolved` when an affordance
reply arrives).

## Why it exists

Four uncoordinated mechanisms each implement a slice of CHI today —
`ToolAskHuman`, `MessageEvent.EscalationRequested`, `AgentPause`, and
`HumanInteractionEvent`. Each one has its own correlation pattern, its
own lifecycle, and its own renderer. The result is that "the moment the
computer needs the human" is not a single thing the system can reason
about — it's four overlapping things.

Emission is the unifying primitive. AMPERE defines the noun (this
domain object) and the verb (`EmissionEvent`). A consumer such as Socket
defines the adjective and adverb — surface arbitration, rendering,
affordance interaction, push delivery. Splitting the protocol this way
preserves AMPERE's substrate-free positioning: any consumer can adopt
the protocol; AMPERE has no knowledge of `Surface`,
`EmissionRendererRegistry`, or platform UI types.

Three design pressures shape the Wave 0 cut:

1. **One event type with a `kind` field beats per-kind event types.**
   `EventSerialBus` subscribes by exact `EventType`. At expected volumes
   a single `EmissionEvent.Produced` carrying an `EmissionKind` tag is
   the simpler interface for consumers.
2. **Dedup must not be overloaded onto `EmissionId`.** AMPERE ids are
   random UUIDs; dedup is content-deterministic and lives in
   `dedupKey`. See the [EmissionDedup](emission-dedup.md) cell.
3. **Provenance is a first-class field, not metadata.** Every Emission
   must be attributable to the `runId`, `workflowId`, source event,
   tool invocation, plug, and model that produced it — and, since
   AMPR-283, to the Emission it serves and the principal it ran under.
   D4's descendant traversal ("stop this and everything it caused") and
   D5's identity-on-the-edge both presuppose that edge, so it is carried
   on the record rather than reconstructed from bus ordering.

## Where it lives

- `agents/domain/emission/Emission.kt` — the data class (with the `Domain` pairing check in `init`) and `computeDedupKey` extension.
- `agents/domain/emission/EmissionKind.kt` — the four core kinds as sealed `data object`s, plus `Domain(id)`, the data-class variant every domain kind rides.
- `agents/domain/emission/EmissionPayload.kt` — sealed payload variants (one per core kind, plus `Domain(kindId, schemaVersion, body, fallbackText)`).
- `agents/domain/emission/kinds/DomainEmissionKind.kt` — the descriptor a domain kind is defined through (`kind`, `encode`, `decodeOrNull`).
- `agents/domain/emission/kinds/recipe/Recipe.kt` — `RecipeKind`, the first domain kind, and its payload types.
- `agents/domain/emission/Affordance.kt` — response options attached to an Emission.
- `agents/domain/emission/EmissionProvenance.kt` — the provenance schema, plus the surrogate serializer that reads pre-edge payloads.
- `agents/domain/emission/EmissionScope.kt` — the authoring DSL (`emission { }`, `ask`, `confirm`, `emit`, `sense`, `present`, `askHuman`, `inServiceOf`).
- `agents/domain/Principal.kt` — the principal carrier. `Principal.Ambient` is its only variant until D5.
- `agents/domain/emission/EmissionDigest.kt` — `inputDigest(payload)` helper.
- `agents/domain/emission/EmissionIds.kt` — `EmissionId` and `AffordanceId` typealiases.
- `agents/domain/event/EmissionEvent.kt` — `Produced` and `Resolved` bus events.

## Invariants

- **Immutable once published.** Treat `dedupKey`, `provenance`, and `id` as fixed at construction time. Subscribers may copy, never mutate.
- **Dedup is content-based, never identity-based.** `dedupKey` is the dedup signal. `EmissionId` is a random UUID — using it as a dedup key is a category error.
- **Every Emission carries provenance.** `EmissionProvenance` is non-nullable on the data class. An Emission without `inputDigest` cannot exist. Since AMPR-240, `EmissionScope` accepts an ambient `runId` and injects it into `EmissionProvenance.runId` for every Emission it builds a default provenance for (previously always `null` — digest-only). Callers that supply their own `EmissionProvenance` still override this default.
- **Every Emission states its causal parent and its principal (AMPR-283).** `parentEmissionId` and `principal` have no defaults on `EmissionProvenance`, on `EmissionScope`, or on `emission(...)`. So a `null` parent always means "this is a root", never "nobody passed it". A scope stamps both into every provenance it builds, and `inServiceOf(parentId) { … }` opens a child scope, so the nesting of the DSL is the causal tree. A child scope keeps its enclosing scope's principal: nesting records causality, not delegation.
- **`parentEmissionId` is the causal edge; `sourceEventId` is not.** `sourceEventId` points at the triggering *event*, `parentEmissionId` at the parent *Emission*. They are different id spaces, and the event envelope's `causedBy` (event → event) is a third edge.
- **The principal is a carrier, not a policy.** What a principal means is D5's to decide. Until then `Principal.Ambient`, meaning "no identity was resolved; ran under process authority", is the only honest value, and every site that stamps it is a site D5 must revisit. Both production `emission { }` call sites (`ToolAskHuman`, `AgentMessageApi.escalateToHuman`) open root scopes under `Principal.Ambient`.
- **The edge is always written, and old payloads still read.** `EmissionProvenanceSerializer` goes through a surrogate that `@EncodeDefault`s both fields, so every new payload carries `parentEmissionId` (explicit `null` for a root) and `principal` whatever the consumer's `encodeDefaults`. Payloads from before AMPR-283 carry neither key and decode as a root under `Principal.Ambient`. That is accurate: nothing recorded a parent then, and nothing ran under anything but ambient authority. `Principal` variants follow the `Principal.<Name>` `@SerialName` rule below.
- **`EmissionEvent.Resolved` references the originating Emission by id and the chosen affordance by id.** Both ids are stable for the lifetime of the Emission.
- **`@SerialName`s are wire format.** Every sealed variant carries a stable `@SerialName`. Renames are a wire-format change that must be co-ordinated with consumers. Adding a *core* kind is likewise a wire-format change (a new variant on two sealed hierarchies, a minor release); adding a *domain* kind is not, because it rides `Domain`.
- **A `Domain` kind and a `Domain` payload always travel together, with one id.** `Emission.init` rejects `EmissionKind.Domain` paired with a core payload, a core kind paired with `EmissionPayload.Domain`, and two `Domain`s whose ids differ. Core-kind pairing is *not* checked (AMPR-427 outcome 4): enforcing it could reject existing producers, and that is a separate decision.
- **Every domain payload carries prose a consumer can show without knowing the kind.** `EmissionPayload.Domain.fallbackText` is required and never blank. Console, voice and digest surfaces print it; a consumer whose build predates the kind shows it instead of a JSON blob.
- **A domain body is encoded once, canonically.** `DomainEmissionKind.encode` writes `body` with one private `Json` (defaults on, no pretty print), so equal values give byte-equal bodies and `inputDigest` is stable. Hand-built `EmissionPayload.Domain`s skip that guarantee.
- **No platform types in this package.** Emissions live in `commonMain`; rendering, surface arbitration, and push delivery are Socket-side concerns.

## Common operations

- **Produce an Emission** — construct an `Emission`, call `computeDedupKey()` for effect-bearing kinds, then `bus.publish(EmissionEvent.Produced(...))`. Or author through the DSL: `emission(source, eventApi, principal = Principal.Ambient, parentEmissionId = null) { emit(...) }`.
- **Produce an Emission caused by another** — inside an `emission { }` block, `inServiceOf(parent.id) { confirm(...) }`. For work handed a parent id from outside the block (a reply handler, say), open the block with `parentEmissionId = thatId` rather than as a root.
- **Walk what an Emission caused** — collect the Emissions whose `provenance.parentEmissionId` is its id, then recurse. The edge is only in the payload JSON for now; there is no `EventStore` column or index for it yet. A traversal policy that uses the edge is D4's to define.
- **Render or log on the consumer side** — subscribe to `EmissionEvent.Produced.EVENT_TYPE` on the `EventSerialBus`.
- **Resolve an affordance** — when a human selects an affordance, publish `EmissionEvent.Resolved(emissionId, affordanceId, replyContext)`. The `replyContext` carries the affordance's `signalPayload` opaquely back to the originator.
- **Compute the content digest manually** — `inputDigest(payload)` returns the 16-char hex SHA-256 used by both `EmissionProvenance.inputDigest` and `dedupKey`.
- **Define a domain kind** — declare a `@Serializable` payload type with stable `@SerialName`s in your own package, then `val MyKind = DomainEmissionKind("my-kind", 1, MyPayload.serializer()) { it.title }`. See [Domain kinds](#domain-kinds).
- **Publish a domain kind** — inside an `emission { }` block, `present(MyKind, value)`. Fire-and-forget, like `emit` and `sense`.
- **Read a domain kind** — `MyKind.decodeOrNull(event.emission.payload)`; `null` means "not mine, or newer than I can read", so show `fallbackText` or skip.

## Domain kinds

The four core kinds are substrate-free, and `EmissionKind` / `EmissionPayload`
are `sealed`, so a consumer cannot add a variant from its own module. Before
AMPR-427 that left two bad options: add a product-specific kind to core (a
recipe is not substrate), or smuggle one through `Prose` and have every
text surface print JSON. Socket's Blueprint work matched on prompt text
instead, which is the shape of the problem.

The fix is **one** core variant that carries any domain kind as data:

- `EmissionKind.Domain(id)` — the tag. `id` is a lowercase slug (`recipe`, `meal-plan`), validated in `init`.
- `EmissionPayload.Domain(kindId, schemaVersion, body: JsonElement, fallbackText)` — the body, encoded with the kind's own serializer, plus the prose every text-only surface shows.
- `DomainEmissionKind<T>(id, schemaVersion, serializer, fallbackText)` — the descriptor a kind is *defined* through. It is an ordinary class in any package or module: `kind` gives the tag, `encode(value)` builds the payload, `decodeOrNull(payload)` reads it back.

Because the body is plain `JsonElement` to everything in between, the kind
travels through the bus, the `EventStore` and `inputDigest` unchanged, and
**no `Json` anywhere needs a `SerializersModule` for it** — `DEFAULT_JSON`,
Socket's derived `Json`s and the private `DigestJson` all decode a kind they
have never heard of. That is the property the un-seal alternative could not
give: there, one unregistered kind would fail decoding of the whole event.

**Versioning.** `decodeOrNull` returns `null` for a `schemaVersion` newer
than the descriptor's, and reads same-or-older bodies leniently (unknown keys
ignored, missing defaulted fields take their defaults). So bump
`schemaVersion` only for a change an older reader genuinely cannot survive —
a removed or retyped field. An additive field with a default is not such a
change.

**When to add a core kind instead.** A core kind is for a *shape of
contact* every consumer must understand — narration, a question, a gate on
an effect, a reading. If the thing is a domain object a product renders
(a recipe, a meal plan, a shopping list), it is a domain kind, however
central it is to that product. If in doubt, it is a domain kind: promoting
one later is additive; demoting a core kind is a wire-format break.

**Worked example — `Recipe`** (`agents/domain/emission/kinds/recipe/Recipe.kt`,
the first domain kind, for Socket's Personal Chef Arc, SCKT-346):

```kotlin
val RecipeKind = DomainEmissionKind(
    id = "recipe",
    schemaVersion = 1,
    serializer = RecipePayload.serializer(),
    fallbackText = { "${it.title} (${it.ingredients.size} ingredients, ${it.steps.size} steps)" },
)

// producer, inside emission { }
present(RecipeKind, carbonara)

// consumer
RecipeKind.decodeOrNull(event.emission.payload)?.let { render(it) }
    ?: showText((event.emission.payload as EmissionPayload.Domain).fallbackText)
```

`RecipePayload(title, description?, baseServings = 4, ingredients, steps,
notes?)` carries amounts for `baseServings`; a renderer scales them for the
servings the person picks and runs a timer from `RecipeStep.timerSeconds`.
Step `content` may contain `{ingredientId}` tokens the renderer substitutes.
AMPERE validates the data in `init` (unique ids, declared tokens,
`baseServings >= 1`, `amount >= 0`, `timerSeconds > 0`) and never renders.

## Anti-patterns

- **Mutating an Emission after publishing.** Subscribers may have copies; later changes won't propagate, but bus replay will surface the originals.
- **Deriving `dedupKey` from `EmissionId`.** Two semantically identical Emissions get different random ids; dedup must look at content.
- **Treating Emissions as request/response RPC.** Emissions are observation events — `Produced` is one-way. The reply, if any, is a separate `Resolved` event. Anything resembling `awaitEmission(...)` belongs in a consumer.
- **Embedding rendering decisions in payload** (font, colour, layout). Surface is a Socket concept; `EmissionPayload` describes *what*, never *how*.
- **Skipping provenance because "this one is trivial".** Provenance is what makes Emissions auditable — the lazy case is the one that bites first in a trace review.
- **Giving `parentEmissionId` or `principal` a default to save call sites.** A default turns "root" back into "nobody passed it", which is the failure the required parameters exist to prevent. The legacy-read default lives only on the private wire surrogate.
- **Opening a fresh root `emission { }` while handling another Emission's reply.** The follow-up was produced in service of that Emission. Pass its id as `parentEmissionId`, or use `inServiceOf`, or the causal chain breaks silently at exactly the hop D4 needs.
- **Reading `Principal.Ambient` as "the device owner" or "a human approved this".** It means that no identity was resolved. Pinning it to a meaning ahead of D5 would decide D5 by accident.
- **Putting domain logic in the core types.** A recipe, a meal plan or an invoice does not belong beside `Prose` and `Sensor`, however central it is to one product. Core kinds are shapes of contact; everything else is a `DomainEmissionKind` in the owning package or module.
- **A domain kind without a meaningful `fallbackText`.** `"recipe"`, the kind id, or a JSON dump is not a fallback. It is what the console, voice, the digest and every consumer that predates the kind will show — write the sentence a person would want to read there.
- **Bumping `schemaVersion` for an additive, defaulted field.** `decodeOrNull` applies defaults and ignores unknown keys, so an older reader already copes. A needless bump makes every older consumer drop to `fallbackText` for no reason; reserve it for a change an older reader cannot survive.
