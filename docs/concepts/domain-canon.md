---
concept: DomainCanon
status: stable
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/canon/**
related: [LinkLayer, PlugPermissions, AgentSurface, CognitionTrace]
last_verified: 2026-07-30
---

# Domain Canon

## What it is

`CanonType` is Ampere's closed catalogue of domain nouns — Person, EmailMessage,
CalendarEvent, Note, Ride — and `CanonEntity` is a provenance-carrying instance
of one. Every canon entity knows where it came from: which Link it travelled
over, which native object it was projected from, and when it was observed.
Types are grouped into three **rings** by where that provenance originates.

The canon is an **intermediate representation**, not a DTO set. Arc logic
compiles against the IR, which is why one Arc can run on iOS and Android
without branching.

## Why it exists

Apps are frontends and backends. Without an IR, every Plug hands agents a
different shape for the same idea and the LLM is left to reconcile them at
runtime — guessing that "a Things task" and "a Reminders reminder" are the same
kind of thing. With the IR, Ampere *knows* it, with provenance.

The platform asymmetry is the load-bearing rationale. Apple ships a canonical
schema registry, so there the IR partly duplicates it and integration is a
mapping table. Android's AppFunctions has **no** noun catalogue — per-app data
classes reconciled by an LLM at runtime. That void is the shape of this IR.

An SDK pass against iPhoneOS 26.5 (`.context/issue-586-domain-type-canon-v1.md`)
found Apple's catalogue narrower than expected: 15 domains, 26 entity schemas,
all documents-and-content shaped. **There is no calendar, messages, reminders,
notes, places, media, or alarm domain.** Six proposed Ring 1 types demoted as a
result. The canon earns its keep precisely where Apple's registry stops.

## Where it lives

- `canon/CanonType.kt` — the closed enum; each member carries its ring and binding.
- `canon/CanonRing.kt` — `INTERCHANGE` / `PLATFORM` / `SERVICE`.
- `canon/CanonBinding.kt` — `AppleSchemaBinding` (entity schema or system value type), `AndroidSchemaBinding`.
- `canon/CanonProvenance.kt` — `CanonId`, `SourceHandle`, `NativePayload`, `CanonProvenance`.
- `canon/NativeSchema.kt` — the value class wrapping a native shape identifier (`EKEvent`, `MailMessageEntity`); referenced by both `ReadableCanonAdapter.nativeSchema` and `NativePayload.schema` so the two cannot drift into a runtime-only `SchemaMismatch`.
- `canon/CanonEntity.kt` — the sealed hierarchy; `CanonRing1Entities.kt`, `CanonRing2Entities.kt`, `CanonRing3Entities.kt`.
- `canon/adapter/ReadableCanonAdapter.kt`, `WritableCanonAdapter.kt`, `CreatingCanonAdapter.kt` — the transport-agnostic adapter SPI, split by capability: read, write-back, create.
- `canon/adapter/NativeFields.kt` — a typed cursor over a native object's fields, used from `projectFields`, that replaces the repeated `?: return canonFailure(...)` shape.
- `canon/adapter/CanonChildren.kt` — `forChild`/`childNativeId`/`unstableChildNativeId`: identity and provenance rules for entities nested inside another (an event's attendees, an album's assets).
- `canon/adapter/CanonConversionFailure.kt` — the closed failure set.
- `ampere-core/src/commonTest/.../canon/` — round-trip, write-back-merge, and serialization-stability tests.
- `.context/recon/apple-assistant-schemas-ios265.tsv` — the raw SDK enumeration.

## Invariants

- **The canon set is closed for v1.** New nouns are a versioned change, not a Plug-declared extension. The escape hatch is `CanonProvenance.nativePayload`, not a new member.
- **Every canon entity carries provenance.** An entity with no `SourceHandle` is a guess, not a canon entity.
- **Write-back merges; it never replaces.** `WritableCanonAdapter.writeBack` is the only path that touches an *existing* native object, and it always routes through `mergeForWriteBack`, which overlays canon deltas onto the native payload. Adding a write path that bypasses the merge silently destroys every native field the projection dropped.
- **An adapter may only write fields it declares in `ownedFields`.** A `canonFields` result reaching outside that set fails with `UnownedFieldWrite` rather than widening the write footprint. `CreatingCanonAdapter.create` routes through the same guard.
- **`create` touches no existing object, so it cannot clobber — but it is still final and confined to `CreatingCanonAdapter`.** `ReadableCanonAdapter` and `WritableCanonAdapter` gain no create surface; a Plug that only reads or only updates cannot acquire the ability to create by inheritance.
- **Ring membership is a binding-provenance claim, not a support level.** A Ring 1 type maps to an Apple assistant-schema entity or system value type *without contortion*. Promoting a type into Ring 1 requires an SDK pass, not an opinion.
- **`@SerialName` and `wireName` are wire contracts.** These types cross the wire and land in traces; renaming a discriminator breaks `PlaybackRelay` replay of every trace already recorded.
- **Conversions are `Result`-typed. No adapter throws.** `NativeFields` (used from inside `projectFields`) is the one narrow exception, and only internally: its accessors throw `CanonConversionException` so a projection can read a field inline instead of `?: return canonFailure(...)`, but the only way to obtain a `NativeFields` is `NativeFields.project(...)`, which catches that exception and returns `Result.failure` — the constructor is private, so the throw cannot reach an adapter's caller. An adapter written against this cursor still satisfies the never-throws contract from the outside.
- **`SourceHandle.nativeId` is opaque.** Parsing it makes a provider's identifier format a contract Ampere has to honour.

## Common operations

- **Add a canon type** — add the `CanonType` member with a stable `wireName`, ring, and `CanonBinding`; add the `@Serializable` entity with a stable `@SerialName`; add a sample to `CanonSerializationTest.samples()` (the coverage test fails otherwise).
- **Write an adapter** — subclass the narrowest class the Plug's capability supports:
  - Read-only: `ReadableCanonAdapter<E>`, implementing `projectFields` + `fetchNative` and declaring `nativeSchema: NativeSchema` (declare the schema once as a named constant shared by the commonMain projection and the platform glue that produces the payload).
  - Updates existing objects: `WritableCanonAdapter<E>`, additionally implementing `canonFields` + `writeNative` and declaring `ownedFields`.
  - Also creates new objects: `CreatingCanonAdapter<E>`, additionally implementing `createNative`.

  Never add a public write method outside `writeBack` and `create`.
  Inside `projectFields`, read through `NativeFields.project(fields, canonType, nativeSchema) { cursor -> ... }` rather than hand-rolling `?: return canonFailure(...)` per field — it keeps the failure taxonomy (`MissingRequiredField` vs. `MalformedField`) uniform and nested/array failures report a path (`location.latitude`, `people[1].name`). When a canon type nests another entity, give the child its own id and provenance with `childNativeId(parentNativeId, role, naturalKey)` and `provenance.forChild(childNativeId)` — never derive a child id from array position, and never reuse the parent's provenance verbatim (see `CanonChildren.kt`).
- **Preview a pending write** — `adapter.mergeForWriteBack(entity)` returns the merged payload without writing.
- **Check a binding** — `CanonType.EMAIL_MESSAGE.binding.apple` gives the `mail.message` address and the fields the projection drops.
- **Re-run the SDK pass** — the extraction commands are in the Appendix of `.context/issue-586-domain-type-canon-v1.md`; diff against the committed TSV.

## Anti-patterns

- **"Just write the projected entity back."** This is the destructive default the SPI exists to prevent — it clobbers MIME structure, edit stacks, provider labels, everything the projection dropped.
- **Promoting `CalendarEvent` to Ring 1 because calendars feel like interchange.** No calendar assistant-schema domain exists. `Calendar.RecurrenceRule` binds a field, not the entity.
- **Adding a `Custom(payload)` canon member.** It would make the `when` non-exhaustive and hand the reconciliation problem back to the LLM. Use the native payload on a typed entity.
- **Declaring a wide `ownedFields` "to be safe".** Over-declaring re-opens the clobber path; under-declaring merely means the field is never written.
- **Parsing `nativeId` to extract structure.** It is opaque by design.
- **Deriving a nested entity's id from its array position, or giving it the parent's provenance verbatim.** An index-derived id (`"$eventId:attendee:0"`) changes when the provider reorders the collection; a reused parent provenance hands the child the parent's `nativeId` and `nativePayload`, so e.g. a `CanonPerson` extracted from a `CanonCalendarEvent` claims to *be* an `EKEvent`. Use `childNativeId`/`forChild` from `CanonChildren.kt`.
- **Reading bindings via annotations/reflection.** `kotlin-reflect` is JVM-only and Kotlin/Native cannot read annotations reflectively; bindings are data for exactly this reason.
