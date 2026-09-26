---
concept: DomainCanon
status: stable
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/canon/**
  - ampere-bindings-apple/src/commonMain/kotlin/link/socket/ampere/bindings/apple/**
  - ampere-bindings-android/src/commonMain/kotlin/link/socket/ampere/bindings/android/**
related: [Probe, LinkLayer, PlugPermissions, AgentSurface, CognitionTrace]
last_verified: 2026-09-26
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

An SDK pass against iPhoneOS 26.5 (`docs/ampr-222-domain-type-canon-v1.md`)
found Apple's catalogue narrower than expected: 15 domains, 26 entity schemas,
all documents-and-content shaped. **There is no calendar, messages, reminders,
notes, places, media, or alarm domain.** Six proposed Ring 1 types demoted as a
result. The canon earns its keep precisely where Apple's registry stops.

## Ring definitions

Ring definitions are vendor-neutral by design (AMPR-257) — which platform
ships which schema, and the per-type projection detail, lives in the
binding-table rows owned by the edge modules' registries, not in the
definition itself:

- **Ring 1 — `INTERCHANGE`**: has an OS-canonical interchange schema on ≥1 platform.
- **Ring 2 — `PLATFORM`**: reaches Ampere via a native framework.
- **Ring 3 — `SERVICE`**: arrives only over a service Link.

The vendor-specific "why" — e.g. that CALENDAR_EVENT is Ring 2 because the
shipped Apple assistant-schema catalog has no calendar domain — is documented
on `AppleCanonBindingRegistry` in `ampere-bindings-apple`, next to the binding
table it explains, not on `CanonRing` itself.

## Where it lives

`ampere-core` carries zero platform-SDK references (AMPR-257, enforced by the
merge-blocking `verifyCoreNeutrality` Gradle check): canon types and ring
membership live there; how a canon type reaches a given platform's own
vocabulary is a *binding*, declared in an edge module that depends on
`ampere-core` and never the reverse.

- `canon/CanonType.kt` — the closed enum; each member carries its `wireName` and `ring`.
- `canon/CanonRing.kt` — `INTERCHANGE` / `PLATFORM` / `SERVICE`, defined vendor-neutrally — see [Ring definitions](#ring-definitions) below.
- `ampere-bindings-apple/.../bindings/apple/AppleCanonBindingRegistry.kt` — every `CanonType`'s binding onto Apple's assistant-schema vocabulary (`AppleSchemaBinding`: entity schema or system value type), keyed by `CanonType`.
- `ampere-bindings-android/.../bindings/android/AndroidCanonBindingRegistry.kt` — the Android equivalent (`AndroidSchemaBinding`); every entry is `PendingSdkVerification` until an AppFunctions SDK pass happens.
- `canon/CanonProvenance.kt` — `CanonId`, `SourceHandle`, `NativePayload`, `CanonProvenance`.
- `canon/NativeSchema.kt` — the value class wrapping a native shape identifier (`EKEvent`, `MailMessageEntity`); referenced by both `ReadableCanonAdapter.nativeSchema` and `NativePayload.schema` so the two cannot drift into a runtime-only `SchemaMismatch`.
- `canon/CanonEntity.kt` — the sealed hierarchy; `CanonRing1Entities.kt`, `CanonRing2Entities.kt`, `CanonRing3Entities.kt`.
- `canon/CanonRecurrence.kt` — the bounded recurrence value type carried by `CanonReminder` and `CanonCalendarEvent`; deliberately not RFC 5545 (see the recurrence invariant below).
- `canon/CanonWorkGraph.kt` — the canon's first *composite* value type: a `CanonProject`, its `CanonMilestone`s, and its `CanonWorkItem`s as one value, assembled by the caller from one Link. Not a `CanonEntity` — it has no provenance or `CanonType` of its own, and no `init` guard (see the `dependsOn` invariant below). The subject of `probe/SequenceProbe.kt`, the structural Probe that checks its `dependsOn` edges for referential integrity and acyclicity.
- `canon/adapter/ReadableCanonAdapter.kt`, `WritableCanonAdapter.kt`, `CreatingCanonAdapter.kt` — the transport-agnostic adapter SPI, split by capability: read, write-back, create.
- `canon/adapter/NativeFields.kt` — a typed cursor over a native object's fields, used from `projectFields`, that replaces the repeated `?: return canonFailure(...)` shape.
- `canon/adapter/CanonChildren.kt` — `forChild`/`childNativeId`/`unstableChildNativeId`: identity and provenance rules for entities nested inside another (an event's attendees, an album's assets).
- `canon/adapter/CanonConversionFailure.kt` — the closed failure set.
- `ampere-core/src/commonTest/.../canon/` — round-trip, write-back-merge, and serialization-stability tests.
- `docs/recon/apple-assistant-schemas-ios265.tsv` — the raw SDK enumeration.

## Invariants

- **The canon set is closed for v1.** New nouns are a versioned change, not a Plug-declared extension. The escape hatch is `CanonProvenance.nativePayload`, not a new member. The set has been reopened exactly once, by the knowledge-work wave (AMPR-262), against a four-gate admission bar: the noun test (would two independent apps exchange it?), the intersection test (modelable as the intersection of ≥2 real providers, with lossy fields named on day one), the producer test (a plausible Link exists today), and the bulk test (no unbounded payloads on the entity — schema and counts in canon, content by reference).
- **The canon grows upward, never by evacuation** (AMPR-264). The canon grows by admitting nouns into the shared core; it never grows by pushing vocabulary out to consumers, and never by letting a consumer-defined type stand in for a canon member. No third-party type enters `CanonType`, `CanonEntity`, `PlugManifest.emits`/`consumes`/`optionalConsumes`, or `Link.scope` — those four are the canon's entire public vocabulary, and each is closed by construction, since a `Set<CanonType>` cannot name a noun that is not already a member. AMPR-257's "move the bindings, not the nouns" is the same rule applied to platforms: a platform's own vocabulary is reached through a binding row in an edge module, never by admitting the platform's noun into `ampere-core`. Consumer data that fails the four gates stays consumer-side, and that path is deliberately unconstrained rather than grudging — `PerceiveSource<out T>` and `ExecuteSink<in C>` are *not* bound to `CanonEntity`, and `PlugManifest.isCanonExternal` is a positive declaration that a Plug's observations are permanently outside canon rather than a gap to fill in later. There is no third route: a noun is admitted through the four gates and ships in a minor, or it stays consumer-side.
- **A *field* admission clears the same four gates as a type.** `CanonRecurrence` (AMPR-319) is the worked example, and it is worked twice — once passing, once declined.
  - **Passes, on `CanonReminder` and `CanonCalendarEvent`.** Two real providers already produce recurrence there: Apple Reminders (`EKReminder`) and Apple Calendar (`EKEvent`), both through `EKRecurrenceRule`. The intersection is narrow and honest — frequency × interval, bounded by an occurrence count or an end date — and the shape admitted is exactly that intersection, not RFC 5545's grammar. Everything the projection still drops in the canon → EventKit direction (a sub-daily `every`, `EKRecurrenceDayOfWeek`, `bySetPosition`) is named in `AppleCanonBindingRegistry` on day one.
  - **Declined, on `CanonWorkItem`.** A consumer wanted recurring tasks, and the intersection test says no: of the three providers that define `CanonWorkItem`, Linear's recurrence is a template that materializes separate issues, Jira's is a clone, and GitHub Issues has none — there is nothing in common to model. Admitting it there would have been the canon's first field justified by a consumer's need rather than by provider evidence. The conceptual split that fell out is the durable part: **a work item is the work; a reminder is its schedule.** A caller that needs a recurring task holds the task-to-reminder mapping on its own side. Re-admission trigger: two of the three work-item providers shipping recurrence as a property of the issue itself.
- **`CanonWorkItem.dependsOn` is the canon's first work-item→work-item edge, and graph invariants are convicted, never constructed away** (AMPR-322). The field cleared the intersection gate without a ruling because a blocking dependency is provider-native on all three work-item providers: Linear `blockedBy`/`blocks` relations, Jira `issuelinks` of type *Blocks*, GitHub sub-issues / "blocked by". It is a `List<CanonId>` under the same-Link rule below — it resolves only within a graph assembled from one Link — and `CanonWorkItem` itself does not check that the referents exist or that the edges are acyclic. Both are *graph* properties, so they belong to `CanonWorkGraph`, and even there they are not an `init` guard: a graph with a dangling edge or a cycle must be constructible, so it can be recorded and then convicted by `SequenceProbe` (`Violated("dangling dependsOn: a -> ghost")`, `Violated("cycle: a -> b -> c -> a")`). Rejecting it at construction would make the defect unrepresentable and, once serialized, undecodable. The same rule reached the planner side in the same change: `BatchIssueCreator` used to drop a back-edge silently and report `success = true` in an order that violated its own declared dependencies; it now refuses the batch with a `dependencyCycle` error naming the path. Timing invariants (offset non-inversion, recurrence-after-dependency) are deliberately not here — per Socket decision D17 a work item's schedule is a `CanonReminder`, and the Task↔Reminder mapping is held caller-side, so timing checks live there in v1. `CanonMilestone` ordering edges were not admitted. No Linear, Jira, or GitHub binding module exists in this repo yet; when one lands, the provider field above is the mapping it adopts and `dependsOn` must not appear on its lossy list.
- **Bulk content never rides an entity.** Counts and schema are canon; rows and bytes resolve out of band through `CanonAssetRef` and `AssetResolver`. `CanonTable` is the worked example: `columnNames` and `rowCount` on the entity, a preview bounded by `CanonTablePreview.bounded`, and full rows behind `contentRef`. The bound is a write-side factory, not a decode-time `require` — rejecting an oversized value at decode would make an already-recorded trace permanently unreplayable, which is the failure the wire-stability invariant exists to prevent.
- **Every canon entity carries provenance.** An entity with no `SourceHandle` is a guess, not a canon entity.
- **`CanonProvenance` is `Observed`, and `observedAt` binds once at perceive.** The timestamp is the framework's clock when `ReadableCanonAdapter.project` ran, and nothing downstream re-stamps it. How old is too old is not a canon question — it is a `FreshnessProbe` parameter (see [Probe](probe.md)); adding a `maxAge`/`ttl` field to provenance is a violation.
- **Write-back merges; it never replaces.** `WritableCanonAdapter.writeBack` is the only path that touches an *existing* native object, and it always routes through `mergeForWriteBack`, which overlays canon deltas onto the native payload. Adding a write path that bypasses the merge silently destroys every native field the projection dropped.
- **An adapter may only write fields it declares in `ownedFields`.** A `canonFields` result reaching outside that set fails with `UnownedFieldWrite` rather than widening the write footprint. `CreatingCanonAdapter.create` routes through the same guard.
- **`create` touches no existing object, so it cannot clobber — but it is still final and confined to `CreatingCanonAdapter`.** `ReadableCanonAdapter` and `WritableCanonAdapter` gain no create surface; a Plug that only reads or only updates cannot acquire the ability to create by inheritance.
- **Ring membership is a binding-provenance claim, not a support level.** See [Ring definitions](#ring-definitions). Promoting a type into Ring 1 requires an SDK pass, not an opinion.
- **`@SerialName` and `wireName` are wire contracts.** These types cross the wire and land in traces; renaming a discriminator breaks `PlaybackRelay` replay of every trace already recorded.
- **Version-skew contract: a consumer reads any canon entity produced at a canon version ≤ its own, and a newer one is a typed failure at the envelope.** "Canon version" is not a field — nothing carries a `canonVersion` and nothing should, in the same way the noun set is derived from `CanonType.entries` rather than counted anywhere. It is the Ampere minor whose `CanonType.entries` the consumer compiled against, which is why a published bundle pins `ampereVersion` rather than a canon revision (see the marketplace rule below). Three skews behave differently:
  - **A new field on a type the consumer already knows decodes unchanged.** The shared `DEFAULT_JSON` (`data/RepositoryFactory.kt`) sets `ignoreUnknownKeys = true`, and every field admitted after its type shipped carries a default. That is the forward tolerance the canon already has, and it is why a field admission is additive.
  - **A new entity type, or a new `CanonType` member, is the real skew.** The old consumer's `@SerialName` set has no `canon.table` and its enum has no `table`, so kotlinx throws `SerializationException` — correctly, because there is nothing to decode into. What must not happen is that throw escaping as a crash out of `CanonEntity`/`CanonType` decode. The *envelope* that carried the entity catches it and reports a typed skew failure naming both versions: `TraceEvent.payload` (`ampere-eval`'s, where the payload is a whole serialized `Event`, and `ampere-core`'s in `trace/ArcRunTrace.kt`), an event-store or trace-store row, an imported bundle. `BundleParseError.UnknownVersion(declared, supported)` is the shape to copy — an old host renders "upgrade required", not "parse failed". No `Event` carries a `CanonEntity` today (AMPR-267), so this is the contract the envelope-tolerance and store-tolerance follow-ups implement; it is not yet shipped behaviour.
  - **Never buy the tolerance inside the decode.** No `defaultDeserializer`/`polymorphicDefaultDeserializer` on `CanonEntity`, and no `coerceInputValues` for `CanonType` — see Anti-patterns. A lenient decode does not recover the newer entity; it invents an older one, and the caller cannot tell the difference.
- **Marketplace rule: a published Arc or plug bundle names only `CanonType` members, and pins the minimum `ampereVersion` whose canon it names.** Extension types in published artefacts are forbidden — there is no namespace to put one in (see Anti-patterns) and no registry a marketplace could validate one against. The first half holds by construction: `BundleManifest` embeds `PlugManifest` verbatim, whose `emits`/`consumes`/`optionalConsumes` are `Set<CanonType>`, so there is no shape an extension type could take. Both halves are enforced at import as of AMPR-365. `BundleManifest.minimumAmpereVersion` is the pin, and `PlugBundleParser` checks it — and resolves every canon name through `CanonType.fromWireName` — *before* decode, because decode order would otherwise swallow both: an unknown noun fails the enum decode and used to surface as `BundleParseError.InvalidManifest`, telling the user the manifest was malformed when the truth was that the host was too old to read it. The pre-decode pass reports `BundleParseError.UnsupportedManifest` carrying `AmpereVersionTooOld(required, current)` or `UnknownCanonType(field, wireName)` instead — the `UnknownVersion(declared, supported)` shape above, applied at import, with the version failure winning over the unknown-noun one it causes. `docs/ampere/plug-bundle-format.md` is the format's source of truth and changes with it.
- **Conversions are `Result`-typed. No adapter throws.** `NativeFields` (used from inside `projectFields`) is the one narrow exception, and only internally: its accessors throw `CanonConversionException` so a projection can read a field inline instead of `?: return canonFailure(...)`, but the only way to obtain a `NativeFields` is `NativeFields.project(...)`, which catches that exception and returns `Result.failure` — the constructor is private, so the throw cannot reach an adapter's caller. An adapter written against this cursor still satisfies the never-throws contract from the outside.
- **`SourceHandle.nativeId` is opaque.** Parsing it makes a provider's identifier format a contract Ampere has to honour.
- **A nullable `CanonId` cross-reference is a caller contract, not a resolution mechanism.** `CanonEmailMessage.mailboxId`, `CanonWorkItem.projectId`, `CanonMilestone.projectId`, and `CanonTable.documentId` all share this shape, and AMPR-266 settled it as the permanent answer rather than a placeholder for a future SPI. `CanonWorkItem.dependsOn` is a list rather than a nullable, but every id in it is under the same contract — the one difference is that once a caller has assembled a `CanonWorkGraph`, `SequenceProbe` *does* check that each referent is present, because within a graph the question is decidable:
  - **`null` is ambiguous by design.** It conflates *not attached to anything the provider models* with *the provider did not say*. A reader must not treat `null` as "definitely standalone".
  - **Scope is the same-Link rule.** A `CanonId` is Ampere-scoped, not globally unique — the same id string produced by two different Links names two different entities. A caller resolving a reference must already hold (or derive from the referencing entity's own `provenance.sourceHandle.linkId`) the Link to search, and must filter candidates by that Link *before* matching on `CanonId`; matching on `CanonId` alone across entities from multiple Links can silently return the wrong entity. Pinned in `CanonCrossReferenceContractTest`.
  - **No referential integrity.** Nothing guarantees the referenced entity was ever perceived; a caller that looks and finds nothing has learned nothing about whether the reference was ever valid.

  AMPR-266 costed and rejected the two alternatives. A resolver SPI (mirroring `AssetResolver`) looked cheap by analogy but is not: `CanonAssetRef.NativeHandle` already carries the `linkId` and `nativeId` a resolver needs, while a bare `CanonId` carries neither — nothing in the repo maps a `CanonId` back to the `SourceHandle` needed to re-fetch it (no adapter registry keyed by `CanonType`, no perceived-entity index), so "add a resolver" is actually "design and build an entity store first," a speculative investment with no confirmed consumer. A typed reference wrapper carrying `CanonType` and `LinkId` would catch a cross-Link mismatch as a type error, but it is a breaking wire change to four existing fields that still could not resolve anything without the same missing store — paying the breakage without buying the mechanism. Revisit only when a real consumer needs to resolve one of these fields and the cost of a caller re-implementing the lookup (Link-filter, then `PerceiveSource.perceive` with `PerceiveQuery.ids`) is actually felt.

## Common operations

- **Add a canon type** — first clear the four gates. A proposal filled in against [`docs/canon-admission-proposal.md`](../canon-admission-proposal.md) is the admission bar, and it is the same bar for a first-party noun and a third-party request — that template is the whole "extension API" a consumer gets, because there is no extension mechanism (see the grows-upward invariant). `docs/ampr-262-knowledge-work-canon-wave.md` is the worked example. Then: add the `CanonType` member with a stable `wireName` and `ring`; add its row to `AppleCanonBindingRegistry` (`AndroidCanonBindingRegistry` needs no edit — it derives its map from `CanonType.entries`); add the `@Serializable` entity with a stable `@SerialName`; add a sample to `CanonSerializationTest.samples()` (the coverage test fails otherwise, and it is the only tripwire — there is no exhaustive `when` over `CanonType` or `CanonEntity` anywhere in the repo, so nothing else breaks at compile time). Canon membership is closed for v1 (see Invariants) — this is a versioned decision, not a routine addition.
- **Write an adapter** — subclass the narrowest class the Plug's capability supports:
  - Read-only: `ReadableCanonAdapter<E>`, implementing `projectFields` + `fetchNative` and declaring `nativeSchema: NativeSchema` (declare the schema once as a named constant shared by the commonMain projection and the platform glue that produces the payload).
  - Updates existing objects: `WritableCanonAdapter<E>`, additionally implementing `canonFields` + `writeNative` and declaring `ownedFields`.
  - Also creates new objects: `CreatingCanonAdapter<E>`, additionally implementing `createNative`.

  Never add a public write method outside `writeBack` and `create`.
  Inside `projectFields`, read through `NativeFields.project(fields, canonType, nativeSchema) { cursor -> ... }` rather than hand-rolling `?: return canonFailure(...)` per field — it keeps the failure taxonomy (`MissingRequiredField` vs. `MalformedField`) uniform and nested/array failures report a path (`location.latitude`, `people[1].name`). When a canon type nests another entity, give the child its own id and provenance with `childNativeId(parentNativeId, role, naturalKey)` and `provenance.forChild(childNativeId)` — never derive a child id from array position, and never reuse the parent's provenance verbatim (see `CanonChildren.kt`).
- **Preview a pending write** — `adapter.mergeForWriteBack(entity)` returns the merged payload without writing.
- **Check a binding** — `AppleCanonBindingRegistry.bindingFor(CanonType.EMAIL_MESSAGE).schema` (from `ampere-bindings-apple`) gives the `mail.message` address; `.lossyFields` on the same result names the fields the projection drops.
- **Re-run the SDK pass** — the extraction commands are in the Appendix of `docs/ampr-222-domain-type-canon-v1.md`; diff against the committed TSV.

## Anti-patterns

- **"Just write the projected entity back."** This is the destructive default the SPI exists to prevent — it clobbers MIME structure, edit stacks, provider labels, everything the projection dropped.
- **Promoting `CalendarEvent` to Ring 1 because calendars feel like interchange.** `AppleCanonBindingRegistry` has no calendar assistant-schema domain entry; `Calendar.RecurrenceRule` binds a field, not the entity.
- **Putting an RFC 5545 `RRULE` string on `CanonRecurrence`, or a recurrence field on `CanonWorkItem`.** The first hands canon a calendar grammar (`BYDAY`, `BYSETPOS`, `WKST`) no provider round-trips honestly, so every binding would silently approximate it. The second fails the intersection gate — see the field-admission invariant above; use a `CanonReminder` and hold the mapping caller-side.
- **Adding a `Custom(payload)` canon member.** It would make the `when` non-exhaustive and hand the reconciliation problem back to the LLM. Use the native payload on a typed entity.
- **Re-proposing a rejected extensibility variant.** AMPR-264 assessed three and rejected all three; the reason is recorded here so none of them is re-litigated per request.
  - **A `pending`/provisional canon tier.** A ring is binding provenance, not maturity (see [Ring definitions](#ring-definitions) and the ring invariant above). A provisional marker on `CanonRing` or `CanonType` would add a second, orthogonal axis that every consumer has to branch on before trusting a noun, and the honest expression of "not ready" is "not admitted".
  - **A `defaultDeserializer` for `CanonEntity`.** It is the `Custom(payload)` member arriving through the serializers module instead of the sealed hierarchy — same untyped payload, same reconciliation handed back to the LLM, minus the compile-time visibility. An unknown discriminator is a version-skew failure for the envelope to report, not a shape to synthesize.
  - **A namespaced `CanonTypeId` registry** (`com.acme/invoice` in place of the closed enum). It needs a hosted registry to allocate and police namespaces — Ampere has none and is not in the business of running one — and without it two vendors either collide on a name or fork the same noun. schema.org ran exactly this mechanism, as hosted and external extensions, and retired it back into the core vocabulary.
- **Declaring a wide `ownedFields` "to be safe".** Over-declaring re-opens the clobber path; under-declaring merely means the field is never written.
- **Parsing `nativeId` to extract structure.** It is opaque by design.
- **Deriving a nested entity's id from its array position, or giving it the parent's provenance verbatim.** An index-derived id (`"$eventId:attendee:0"`) changes when the provider reorders the collection; a reused parent provenance hands the child the parent's `nativeId` and `nativePayload`, so e.g. a `CanonPerson` extracted from a `CanonCalendarEvent` claims to *be* an `EKEvent`. Use `childNativeId`/`forChild` from `CanonChildren.kt`.
- **Reading bindings via annotations/reflection.** `kotlin-reflect` is JVM-only and Kotlin/Native cannot read annotations reflectively; bindings are data for exactly this reason.
- **Building a resolver SPI for a `CanonId` cross-reference (`mailboxId`, `projectId`, `documentId`) before a real consumer needs one.** Assessed and rejected by AMPR-266 — see the cross-reference invariant above for why it is not the cheap mirror of `AssetResolver` it looks like.
- **Re-proposing `SPREADSHEET`, `ROADMAP`, or `INITIATIVE`.** All three were assessed by the AMPR-262 wave and the rationale is recorded in `docs/ampr-262-knowledge-work-canon-wave.md` so it is not re-litigated per request. `SPREADSHEET` is a duplicate — the file is `CanonDocument(kind = SPREADSHEET)` and the missing concept was the grid, admitted as `CanonTable`. `ROADMAP` is a view, not a noun: a rendering of projects and milestones over time, and therefore a Phosphor surface over `CanonProject`/`CanonMilestone`. `INITIATIVE` is deferred, not rejected — only Linear ships a durable object above the project; its re-admission trigger is a second provider doing the same with a non-configurable hierarchy level.
