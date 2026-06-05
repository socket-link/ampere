---
concept: Emission
status: experimental
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/emission/Emission.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/emission/EmissionKind.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/emission/EmissionPayload.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/emission/Affordance.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/emission/EmissionProvenance.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/EmissionEvent.kt
related: [ChiProtocol, EventSerialBus, AgentSurface, PropelLoop, EmissionDedup]
last_verified: 2026-06-04
---

# Emission

## What it is

An `Emission` is one moment of computer-initiated human contact — the
typed unit of CHI (see [ChiProtocol](chi.md)). Each Emission carries a
`kind` (`Prose`, `Decision`, `Confirmation`, `Sensor`), a typed `payload`,
optional `affordances`, an optional `Confidence`, full `provenance`, an
optional content-deterministic `dedupKey`, and a `producedAt` timestamp.
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
   tool invocation, plugin, and model that produced it.

## Where it lives

- `agents/domain/emission/Emission.kt` — the data class and `computeDedupKey` extension.
- `agents/domain/emission/EmissionKind.kt` — the four core kinds as sealed `data object`s.
- `agents/domain/emission/EmissionPayload.kt` — sealed payload variants (one per kind).
- `agents/domain/emission/Affordance.kt` — response options attached to an Emission.
- `agents/domain/emission/EmissionProvenance.kt` — the provenance schema.
- `agents/domain/emission/EmissionDigest.kt` — `inputDigest(payload)` helper.
- `agents/domain/emission/EmissionIds.kt` — `EmissionId` and `AffordanceId` typealiases.
- `agents/domain/event/EmissionEvent.kt` — `Produced` and `Resolved` bus events.

## Invariants

- **Immutable once published.** Treat `dedupKey`, `provenance`, and `id` as fixed at construction time. Subscribers may copy, never mutate.
- **Dedup is content-based, never identity-based.** `dedupKey` is the dedup signal. `EmissionId` is a random UUID — using it as a dedup key is a category error.
- **Every Emission carries provenance.** `EmissionProvenance` is non-nullable on the data class. An Emission without `inputDigest` cannot exist.
- **`EmissionEvent.Resolved` references the originating Emission by id and the chosen affordance by id.** Both ids are stable for the lifetime of the Emission.
- **`@SerialName`s are wire format.** Every sealed variant carries a stable `@SerialName`. Renames are a wire-format change that must be co-ordinated with consumers.
- **No platform types in this package.** Emissions live in `commonMain`; rendering, surface arbitration, and push delivery are Socket-side concerns.

## Common operations

- **Produce an Emission** — construct an `Emission`, call `computeDedupKey()` for effect-bearing kinds, then `bus.publish(EmissionEvent.Produced(...))`.
- **Render or log on the consumer side** — subscribe to `EmissionEvent.Produced.EVENT_TYPE` on the `EventSerialBus`.
- **Resolve an affordance** — when a human selects an affordance, publish `EmissionEvent.Resolved(emissionId, affordanceId, replyContext)`. The `replyContext` carries the affordance's `signalPayload` opaquely back to the originator.
- **Compute the content digest manually** — `inputDigest(payload)` returns the 16-char hex SHA-256 used by both `EmissionProvenance.inputDigest` and `dedupKey`.

## Anti-patterns

- **Mutating an Emission after publishing.** Subscribers may have copies; later changes won't propagate, but bus replay will surface the originals.
- **Deriving `dedupKey` from `EmissionId`.** Two semantically identical Emissions get different random ids; dedup must look at content.
- **Treating Emissions as request/response RPC.** Emissions are observation events — `Produced` is one-way. The reply, if any, is a separate `Resolved` event. Anything resembling `awaitEmission(...)` belongs in a consumer.
- **Embedding rendering decisions in payload** (font, colour, layout). Surface is a Socket concept; `EmissionPayload` describes *what*, never *how*.
- **Skipping provenance because "this one is trivial".** Provenance is what makes Emissions auditable — the lazy case is the one that bites first in a trace review.
