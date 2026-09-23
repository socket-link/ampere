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
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/Principal.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/EmissionEvent.kt
related: [ChiProtocol, EventSerialBus, AgentSurface, PropelLoop, EmissionDedup]
last_verified: 2026-09-21
---

# Emission

## What it is

An `Emission` is one moment of computer-initiated human contact — the
typed unit of CHI (see [ChiProtocol](chi.md)). Each Emission carries a
`kind` (`Prose`, `Decision`, `Confirmation`, `Sensor`), a typed `payload`,
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

- `agents/domain/emission/Emission.kt` — the data class and `computeDedupKey` extension.
- `agents/domain/emission/EmissionKind.kt` — the four core kinds as sealed `data object`s.
- `agents/domain/emission/EmissionPayload.kt` — sealed payload variants (one per kind).
- `agents/domain/emission/Affordance.kt` — response options attached to an Emission.
- `agents/domain/emission/EmissionProvenance.kt` — the provenance schema, plus the surrogate serializer that reads pre-edge payloads.
- `agents/domain/emission/EmissionScope.kt` — the authoring DSL (`emission { }`, `ask`, `confirm`, `emit`, `sense`, `askHuman`, `inServiceOf`).
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
- **`@SerialName`s are wire format.** Every sealed variant carries a stable `@SerialName`. Renames are a wire-format change that must be co-ordinated with consumers.
- **No platform types in this package.** Emissions live in `commonMain`; rendering, surface arbitration, and push delivery are Socket-side concerns.

## Common operations

- **Produce an Emission** — construct an `Emission`, call `computeDedupKey()` for effect-bearing kinds, then `bus.publish(EmissionEvent.Produced(...))`. Or author through the DSL: `emission(source, eventApi, principal = Principal.Ambient, parentEmissionId = null) { emit(...) }`.
- **Produce an Emission caused by another** — inside an `emission { }` block, `inServiceOf(parent.id) { confirm(...) }`. For work handed a parent id from outside the block (a reply handler, say), open the block with `parentEmissionId = thatId` rather than as a root.
- **Walk what an Emission caused** — collect the Emissions whose `provenance.parentEmissionId` is its id, then recurse. The edge is only in the payload JSON for now; there is no `EventStore` column or index for it yet. A traversal policy that uses the edge is D4's to define.
- **Render or log on the consumer side** — subscribe to `EmissionEvent.Produced.EVENT_TYPE` on the `EventSerialBus`.
- **Resolve an affordance** — when a human selects an affordance, publish `EmissionEvent.Resolved(emissionId, affordanceId, replyContext)`. The `replyContext` carries the affordance's `signalPayload` opaquely back to the originator.
- **Compute the content digest manually** — `inputDigest(payload)` returns the 16-char hex SHA-256 used by both `EmissionProvenance.inputDigest` and `dedupKey`.

## Anti-patterns

- **Mutating an Emission after publishing.** Subscribers may have copies; later changes won't propagate, but bus replay will surface the originals.
- **Deriving `dedupKey` from `EmissionId`.** Two semantically identical Emissions get different random ids; dedup must look at content.
- **Treating Emissions as request/response RPC.** Emissions are observation events — `Produced` is one-way. The reply, if any, is a separate `Resolved` event. Anything resembling `awaitEmission(...)` belongs in a consumer.
- **Embedding rendering decisions in payload** (font, colour, layout). Surface is a Socket concept; `EmissionPayload` describes *what*, never *how*.
- **Skipping provenance because "this one is trivial".** Provenance is what makes Emissions auditable — the lazy case is the one that bites first in a trace review.
- **Giving `parentEmissionId` or `principal` a default to save call sites.** A default turns "root" back into "nobody passed it", which is the failure the required parameters exist to prevent. The legacy-read default lives only on the private wire surrogate.
- **Opening a fresh root `emission { }` while handling another Emission's reply.** The follow-up was produced in service of that Emission. Pass its id as `parentEmissionId`, or use `inServiceOf`, or the causal chain breaks silently at exactly the hop D4 needs.
- **Reading `Principal.Ambient` as "the device owner" or "a human approved this".** It means that no identity was resolved. Pinning it to a meaning ahead of D5 would decide D5 by accident.
