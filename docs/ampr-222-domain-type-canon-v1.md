# Domain-type canon v1 — three rings, Apple App Schema bindings

**Issue:** [#586](https://github.com/socket-link/ampere/issues/586) - Domain-type canon v1 — three rings, Apple App Schema bindings (recon: Xcode 27 pass)
**Linear:** [AMPR-222](https://linear.app/miley/issue/AMPR-222) (child of AMPR-221)
**Author:** @miley
**Date:** 2026-07-28

> **Recovered 2026-08-01 (AMPR-262).** This document and
> `docs/recon/apple-assistant-schemas-ios265.tsv` were written into an ignored
> `.context/` directory and never committed, so the five places that cite them —
> `CanonType`, `CanonRing`, `AndroidSchemaBinding`, `AppleCanonBindingRegistry`,
> and `domain-canon.md` — pointed at nothing for anyone but the original author.
> Content is verbatim apart from those paths being rewritten to their new homes.

> **Android column filled 2026-10-30 (AMPR-510).** Recon task 3 has now run against
> `androidx.appfunctions` 1.0.0-beta01. **Beta01 ships no predefined AppFunction
> schema**, so every row of the Android column reads `none` and every
> `AndroidCanonBindingRegistry` entry is `AndroidSchemaBinding.None`. The extraction is
> in [Appendix C](#c-enumerating-the-androidxappfunctions-predefined-schemas). There is
> no `docs/recon/` TSV for the Android side, because a TSV of zero rows records
> nothing that sentence does not.

---

## Executive Summary

Phase 1 recon is complete against the **Apple SDK that is actually installed on this
machine: Xcode 26.6 / iPhoneOS 26.5**. Xcode 27 is *not* installed, so the ticket's
literal "Xcode 27 pass" could not be run — see [Recon caveats](#recon-caveats). The
26.5 pass is authoritative for everything shipped through iOS 26 and is enough to
settle ring membership, because the finding that matters is a *structural absence*
that a point release will not reverse.

Three findings change the plan:

1. **The shipped Apple assistant-schema catalog is much narrower than the ticket
   assumed.** There are **15 domain accessors** and **26 entity schemas** in the SDK
   (full dump: `docs/recon/apple-assistant-schemas-ios265.tsv`). There is **no
   calendar domain, no messages domain, no reminders domain, no notes domain, no
   places domain, no music/video media domain, and no alarm domain.** The ticket's
   guessed list ("mail, photos, messages, calendar, books, journal, presentations,
   spreadsheets, system") is wrong on `messages` and `calendar`, and misses
   `browser`, `camera`, `files`, `reader`, `whiteboard`, `wordProcessor`,
   `visualIntelligence`.

2. **Half the proposed Ring 1 fails the no-contortion rule.** `CalendarEvent`,
   `Reminder`, `Alarm`, `MediaItem` demote to Ring 2 (native framework, no assistant
   vocabulary). `Message` and `Note` demote to Ring 3. This is not a defeat — it is
   the *platform-philosophy asymmetry* the epic predicted, arriving earlier and
   harder than expected: Apple's canonical registry covers **documents and content**,
   not **the user's life data**. The IR earns its keep precisely where Apple's
   registry stops.

3. **Ring 3's definition is too narrow and must widen.** It reads "arrives only via
   Mcp/OAuthRest Links". A `Note` from a `FolderMount` Link and a `Transcript` from a
   `Cli` Link are neither. Recommendation: Ring 3 = *"arrives via a non-OS Link
   (Mcp, OAuthRest, FolderMount, Cli)"*.

Phase 2/3 deliverables (canon types, binding annotations, preserve-and-merge adapter
SPI, round-trip + write-back + serialization-stability tests) are implemented in
`ampere-core/src/commonMain/kotlin/link/socket/ampere/canon/`.

---

## Recon caveats

Read this before treating any table below as gospel.

| Ticket task | Status | Note |
|---|---|---|
| 1. Xcode 27 pass — enumerate shipped App Schema domains/entities/actions | **Done against Xcode 26.6 / iPhoneOS 26.5 SDK** | Xcode 27 is not installed (`xcodebuild -version` → 26.6; `xcrun --show-sdk-version` → 26.5). Re-run the extraction command in the Appendix when 27 lands and diff the TSV. |
| 2. Enumerate Apple system value types | **Done** | Complete `_IntentValue` conformer list, extracted from the SDK `.swiftinterface`. |
| 3. Check `AppFunctionSchemaDefinition` for predefined Android schemas | **Done against `androidx.appfunctions` 1.0.0-beta01 (AMPR-510, 2026-10-30)** | Originally not verified: `androidx.appfunctions` was not a dependency of this repo and no local artifact contained it, so the column read `?`. AMPR-510 read the published beta01 artifacts in a scratch directory (they are still not a dependency). They define the `@AppFunctionSchemaDefinition` annotation but ship **no interface carrying it**, so the Android column is `none` throughout. See [Appendix C](#c-enumerating-the-androidxappfunctions-predefined-schemas). |
| 4. Draft binding table, flag lossy + failing candidates | **Done** | §2, §3. |
| 5. STOP — human review | **PENDING** | §3 (ring membership) and §5 (open questions) are the review surface. |

Extraction was done from the SDK's own Swift module interface, not from blog posts:

```
/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs/
  iPhoneOS26.5.sdk/System/Library/Frameworks/AppIntents.framework/Modules/
  AppIntents.swiftmodule/arm64e-apple-ios.swiftinterface
```

---

## 1. The shipped Apple assistant-schema catalog (iOS 26.5)

### 1.1 Domains

15 domain accessors exist on `AssistantSchemas.Entity` / `.Intent` / `.Enum`:

`assistant`, `books`, `browser`, `camera`, `files`, `journal`, `mail`, `photos`,
`presentation`, `reader`, `spreadsheet`, `system`, `visualIntelligence`,
`whiteboard`, `wordProcessor`

Of those, only 11 declare **entity** schemas. `camera`, `system`,
`visualIntelligence`, and `assistant` are verb-only (intents ± enums).

### 1.2 Entity schemas — the complete noun catalog

| Domain | Accessor | Schema identifier |
|---|---|---|
| books | `book` | `BookEntity` |
| books | `audiobook` | `AudiobookEntity` |
| books | `settings` | `BookSettingsEntity` |
| browser | `bookmark` | `BookmarkEntity` |
| browser | `tab` | `TabEntity` |
| browser | `window` | `WindowEntity` |
| files | `file` | `FileEntity` |
| journal | `entry` | `JournalEntity` |
| mail | `message` | `MailMessageEntity` |
| mail | `draft` | `MailDraftEntity` |
| mail | `mailbox` | `MailboxEntity` |
| mail | `account` | `MailAccountEntity` |
| photos | `asset` | `PhotoEntity` |
| photos | `album` | `PhotoAlbumEntity` |
| photos | `recognizedPerson` | `PhotoPersonEntity` |
| presentation | `document` | `PresentationEntity` |
| presentation | `slide` | `PresentationSlideEntity` |
| presentation | `template` | `PresentationTemplateEntity` |
| reader | `document` | `ReaderDocumentEntity` |
| reader | `page` | `ReaderPageEntity` |
| spreadsheet | `document` | `SpreadsheetEntity` |
| spreadsheet | `sheet` | `SheetEntity` |
| spreadsheet | `template` | `SpreadsheetTemplateEntity` |
| whiteboard | `board` | `CanvasEntity` |
| whiteboard | `item` | `CanvasItemEntity` |
| wordProcessor | `document` | `WordProcessorDocumentEntity` |
| wordProcessor | `page` | `WordProcessorPageEntity` |
| wordProcessor | `template` | `WordProcessorDocumentTemplateEntity` |

**The catalog is documents-and-content shaped.** Every noun above is either a file, a
document, a page of a document, a piece of media, a mail object, or a browser object.
There is no person, place, event, task, health, transaction, or communication noun.

Full action-schema dump (152 intent schemas) lives in
`docs/recon/apple-assistant-schemas-ios265.tsv` alongside the entities and enums.

### 1.3 System value types (`_IntentValue` conformers)

These are the cross-app interchange currency — usable via
`IntentValueRepresentation` / `Transferable` without an entity schema.

| Type | Kind | Canon relevance |
|---|---|---|
| `AppIntents.IntentPerson` | struct | **The only Apple-canonical Person.** |
| `AppIntents.IntentFile` | struct | Document/File payload transfer. |
| `AppIntents.IntentCurrencyAmount` | struct | Money — feeds Ring 2 `Transaction`. |
| `AppIntents.IntentPaymentMethod` | struct | Feeds Ring 2 `Transaction`. |
| `AppIntents.EntityIdentifier` | struct | The stable cross-process handle. Maps to our opaque source handle. |
| `CoreLocation.CLPlacemark` | class | **The only Apple-canonical Place.** Postal-address shaped. |
| `Foundation.Calendar.RecurrenceRule` | struct | Recurrence only — *not* an event. |
| `Foundation.Date`, `.DateComponents` | struct | |
| `Foundation.Measurement` | struct | Feeds Ring 2 `HealthSample`, `WeatherForecast`. |
| `Foundation.AttributedString` | struct | Rich text bodies. |
| `Foundation.URL`, `.NSNull` | | |
| `Swift.String/Int/Double/Bool/Array/Set/Optional/Never` | | primitives |

---

## 2. Binding table: canon type ↔ Apple ↔ Android

`none` in the Android column means **verified absent**: `androidx.appfunctions`
1.0.0-beta01 ships no predefined AppFunction schema for any noun (see
[Appendix C](#c-enumerating-the-androidxappfunctions-predefined-schemas)). It is the
`AndroidSchemaBinding.None` value in `AndroidCanonBindingRegistry`. With no Android
projection there is no Android lossy axis to record; on Android, the whole entity is
the "dropped" part, carried only as each app's own `@AppFunctionSerializable` data
class.

Lossy axes are the fields the projection *drops* — these are exactly the fields the
preserve-and-merge write-back contract has to protect.

### Ring 1 — Interchange

| Canon type | Apple binding | Android | Lossy axis (dropped on projection) |
|---|---|---|---|
| `Person` | system value type `IntentPerson`; also `photos.recognizedPerson` when photo-scoped | none | Contact relationships, multiple handles, org/title, per-source ranking. `IntentPerson` is handle+display-name shaped. |
| `EmailMessage` | `mail.message` (`MailMessageEntity`) | none | MIME structure, headers, threading identifiers, per-provider labels, attachment bytes. |
| `EmailDraft` | `mail.draft` (`MailDraftEntity`) | none | Same as above + send-state. |
| `Mailbox` | `mail.mailbox` (`MailboxEntity`) | none | Provider folder semantics (Gmail label vs IMAP folder). |
| `Photo` | `photos.asset` (`PhotoEntity`) | none | Edit stack, adjustment data, live-photo pair, burst identity, original vs rendered. |
| `PhotoAlbum` | `photos.album` (`PhotoAlbumEntity`) | none | Smart-album predicates, shared-album participants. |
| `Document` | `files.file` (`FileEntity`) + system value type `IntentFile`; **fans out** to `wordProcessor.document`, `reader.document`, `spreadsheet.document`, `presentation.document`, `whiteboard.board` | none | **The 1→N fan-out is itself the lossy axis.** Canon `Document` carries a `documentKind` discriminator; a projection that loses `documentKind` cannot round-trip to the right Apple schema. |
| `Place` | system value type `CLPlacemark` | none | Venue identity, hours, category, rating, provider place-id. `CLPlacemark` is a postal address + coordinate. |
| `JournalEntry` | `journal.entry` (`JournalEntity`) | none | Attached media, mood/health suggestions metadata. |
| `Book` | `books.book` (`BookEntity`) | none | Reading position, annotations, DRM/asset identity. |
| `WebBookmark` | `browser.bookmark` (`BookmarkEntity`) | none | Folder hierarchy, sync state, favicon. |
| `BrowserTab` | `browser.tab` (`TabEntity`) | none | Back-forward history, window/group membership, scroll state. |

`Book`, `WebBookmark`, and `BrowserTab` are **additions** surfaced by the SDK pass —
they were not in the ticket's candidate list but they satisfy the Ring 1 rule cleanly.

### Ring 2 — Platform (native framework, outside assistant vocabulary)

| Canon type | Apple native framework | Android | Why not Ring 1 |
|---|---|---|---|
| `CalendarEvent` | EventKit `EKEvent` | none | **No calendar assistant-schema domain exists.** `Calendar.RecurrenceRule`/`Date` are field-level system value types, not an entity binding. |
| `Reminder` | EventKit `EKReminder` | none | No reminders domain. |
| `Alarm` | AlarmKit | none | No clock/alarm domain. |
| `MediaItem` | MediaPlayer `MPMediaItem` | none | Only `books.audiobook` exists; no music/video noun. |
| `HealthSample` | HealthKit | none | Never was Ring 1. `Measurement` binds the value, not the sample. |
| `HomeAccessory` | HomeKit | none | |
| `Transaction` | FinanceKit; `IntentCurrencyAmount`/`IntentPaymentMethod` bind the money fields only | none | |
| `Pass` | PassKit | none | |
| `WeatherForecast` | WeatherKit | none | |
| `BluetoothPeripheral` | CoreBluetooth | none | |
| `MotionSample` | CoreMotion | none | |

### Ring 3 — Service (arrives via a non-OS Link)

| Canon type | Arrives via | Note |
|---|---|---|
| `Message` | `Mcp` / `OAuthRest` Link | **Demoted from Ring 1.** No messages assistant-schema domain and no public iOS read API for Messages. The realistic sources are Slack/Discord/Twilio/Matrix — all service Links. |
| `Note` | `FolderMount` / `Mcp` / `OAuthRest` Link | **Demoted from Ring 1.** No notes domain; `wordProcessor.document` would be a contortion (a note is not a paginated word-processor document). Real sources: Obsidian vault (FolderMount), Notion/Things (service). |
| `Ride` | `Mcp` / `OAuthRest` | as specified |
| `Order` | `Mcp` / `OAuthRest` | as specified |
| `Delivery` | `Mcp` / `OAuthRest` | as specified |
| `ThirdPartyPlaylist` | `Mcp` / `OAuthRest` | as specified |

---

## 3. Ring membership verdict

### Option A: Keep the ticket's Ring 1 and force the bindings

Bind `CalendarEvent` → `wordProcessor.document`-style nearest neighbour, or invent a
"pending Apple support" marker inside Ring 1.

Rejected. It violates the ticket's own no-contortion rule and makes the Ring 1
guarantee ("this type crosses app boundaries through Apple's registry") a lie. A
downstream Arc author reading `Ring 1` would assume interchange works.

### Option B: Demote the failures, keep three rings as defined

`Message` and `Note` → Ring 3; `CalendarEvent`, `Reminder`, `Alarm`, `MediaItem` →
Ring 2. Ring 3 stays "Mcp/OAuthRest only", so `Note`-from-a-folder is unhoused.

### Option C (recommended): Demote the failures **and** widen Ring 3

Same demotions as B, plus Ring 3 is redefined from *"arrives only via Mcp/OAuthRest
Links"* to *"arrives via a non-OS Link (`Mcp`, `OAuthRest`, `FolderMount`, `Cli`)"*.

### Recommendation

**Option C.** The rings are a statement about *where provenance comes from*, and
`FolderMount` is a first-class Link kind in AMPR-223 — a ring taxonomy that can't
house a folder-sourced entity is incomplete on its own terms. The widening costs
nothing (it is a doc change plus one more `LinkKind` accepted by `Ring.SERVICE`) and
it removes the only unhoused canon type.

The demotions are not a downgrade in capability. Ring is a **binding-provenance**
label, not a support level: a Ring 2 `CalendarEvent` sourced from EventKit is fully
usable by Arcs. What the demotion says precisely is *"this type does not travel
through Apple's cross-app registry"* — which is true, and which is the fact an Arc
author needs.

---

## 4. Preserve-and-merge write-back

The ticket's non-negotiable: a canon projection is lossy by design, so naive
write-back clobbers dropped native fields.

### Option A: Document the rule, let each adapter implement it

Rejected by the ticket ("implemented in the adapter SPI contract itself (not left to
individual adapters to remember)").

### Option B: Runtime assertion — compare pre/post native payloads and fail on loss

Catches violations but only at runtime, only when the dropped field happened to be
populated, and only after the adapter already had a chance to write.

### Option C (recommended): Make the merge structurally unavoidable

The SPI exposes `project(native): CanonEntity` and
`applyCanonDelta(native, canon): Native` — an adapter **never sees a bare write
path**. `CanonAdapter.writeBack(...)` is a non-open member that:

1. resolves the current native payload (carried on the entity's source handle, or
   re-fetched),
2. calls the adapter's `applyCanonDelta`,
3. hands the *merged* native object to `writeNative`.

An adapter author cannot write an unmerged entity without deleting a `final` member
of the SPI. That is the "not left to individual adapters to remember" property.

### Recommendation

**Option C**, with Option B's assertion kept as a *test-only* helper
(`assertPreservesNativeFields`) that every Ring 1 adapter's test suite calls.

---

## 5. Open questions for the STOP-gate review

1. **Ring 3 widening** (§3, Option C) — approve or keep the narrow definition and
   leave `Note` unhoused?
2. **`Document` fan-out** — one canon `Document` with a `documentKind` discriminator
   (recommended), versus five canon types mirroring Apple's five document domains?
   The discriminator keeps Arc logic writing against one IR type; the fan-out is more
   faithful to the registry.
3. **`Person` vs `PhotoPersonEntity`** — should a photos-scoped recognized person
   project to canon `Person`, or to a distinct `RecognizedPerson`? Recommendation:
   canon `Person` with `recognizedIn = PHOTOS` provenance, because Arc logic asking
   "who is in this photo" and "who did I email" wants the same type.
4. **Android column is unverified.** Approve shipping `appleSchema` bindings now and
   `androidSchema` as a follow-up once `androidx.appfunctions` is on the classpath?
   *Resolved by AMPR-510:* beta01 ships no predefined schema, so every
   `androidSchema` is `None`. This argues for no ring move: Ring 1 needs an
   interchange schema on at least one platform, and every Ring 1 type keeps its Apple
   one. It does mean that **Ring 1 is Apple-only in fact**: on Android a Ring 1 type
   crosses app boundaries no better than a Ring 2 type does. An Arc author should not
   read Ring 1 as "interchange works on Android".
5. **Re-run on Xcode 27.** Who owns the diff, and is a canon change gated on it?

---

## Technical Guidelines

- Canon types live in `ampere-core/src/commonMain/kotlin/link/socket/ampere/canon/`.
  KMP `commonMain` only — no platform types in the contract, mirroring the
  `AgentSurface` concept's rule.
- Bindings are **declarative data, not annotations**. Kotlin annotations can't be
  read reflectively on Kotlin/Native, and `kotlin("reflect")` is JVM-only. Bindings
  are therefore `CanonBinding` values hung off `CanonType`, which is also what makes
  them serializable into traces.
- Every canon entity is `@Serializable` with a stable `@SerialName`, because these
  types cross the wire and land in traces — schema drift breaks `PlaybackRelay`
  replay (ticket task 9).
- Conversions are `Result`-typed. No throwing adapters.
- The opaque source handle carries `linkId` so canon provenance and AMPR-223 Link
  provenance are the same fact, not two facts that can disagree.

---

## Appendix

### A. Re-running the SDK extraction

```bash
SDK=$(xcrun --sdk iphoneos --show-sdk-path)
I="$SDK/System/Library/Frameworks/AppIntents.framework/Modules/AppIntents.swiftmodule/arm64e-apple-ios.swiftinterface"

# domains
awk '/^extension AppIntents\.AssistantSchemas\.(Entity|Intent|Enum) where Self ==/ {c=1;next}
     /^extension / && !/where Self ==/ {c=0}
     c && /public static var / {n=$0; sub(/.*public static var /,"",n); sub(/ *:.*/,"",n); print n}' "$I" | sort -u

# entity / intent / enum schemas, grouped by domain
awk '/^extension AppIntents\.AssistantSchemas\.[A-Za-z]+(Entity|Intent|Enum) \{$/ {d=$2; sub(/AppIntents\.AssistantSchemas\./,"",d); cur=d; next}
     /^extension / {cur=""}
     cur!="" && /public var / {n=$0; sub(/.*public var /,"",n); sub(/ *:.*/,"",n); pend=n; next}
     cur!="" && /Schema\("/ {s=$0; sub(/.*Schema\("/,"",s); sub(/".*/,"",s); if(pend!=""){print cur"\t"pend"\t"s; pend=""}}' "$I" | sort -u

# system value types
grep -oE "extension [A-Za-z.]+ : AppIntents\._IntentValue" "$I" | sort -u
```

Raw output committed at `docs/recon/apple-assistant-schemas-ios265.tsv`.

### B. Existing code patterns this builds on

| Pattern | Reference |
|---|---|
| Sealed `@Serializable` hierarchy with stable `@SerialName` | `plug/permission/PlugPermission.kt:9-31` |
| Sealed validation result + reason list, no throwing | `plug/PlugManifestValidator.kt:53-78` |
| `Result`-typed transport contract | `agents/tools/mcp/connection/McpServerConnection.kt:27-103` |
| Value-class identifier | `mcp/McpCredentialBinding.kt:15-17` (`LinkId`) |
| Serialization-stability test | `agents/events/EventSerializationTest.kt` |

### C. Enumerating the `androidx.appfunctions` predefined schemas

Run 2026-10-30 for AMPR-510 against the artifacts on Google's Maven repository. They
are read in a scratch directory and are not a dependency of any module here.

```bash
V=1.0.0-beta01
B=https://dl.google.com/android/maven2/androidx/appfunctions
W=$(mktemp -d)   # untrusted downloads: their own empty directory
cd "$W"

# 1. every artifact in the group: no appfunctions-schema* is published
curl -sS "$B/group-index.xml"

# 2. fetch the binaries and sources the schemas could live in, and unpack them
for a in appfunctions appfunctions-testing; do
  curl -sSO "$B/$a/$V/$a-$V.aar" && mkdir "$a" && unzip -qo "$a-$V.aar" -d "$a" \
    && mkdir "$a-classes" && unzip -qo "$a/classes.jar" -d "$a-classes"
done
a=appfunctions-compiler
curl -sSO "$B/$a/$V/$a-$V.jar" && mkdir "$a-classes" && unzip -qo "$a-$V.jar" -d "$a-classes"
for a in appfunctions appfunctions-compiler appfunctions-testing; do
  curl -sSO "$B/$a/$V/$a-$V-sources.jar" && mkdir "$a-src" && unzip -qo "$a-$V-sources.jar" -d "$a-src"
done

# 3. classes annotated with @AppFunctionSchemaDefinition. Retention is BINARY, so a
#    shipped schema carries it in RuntimeInvisibleAnnotations. Use javap, not grep:
#    under a UTF-8 locale grep silently skips binary .class files.
find ./*-classes -name '*.class' | xargs javap -v 2>/dev/null > javap.txt
grep -cE '^\s+androidx\.appfunctions\.AppFunctionSchemaDefinition\(' javap.txt  # schemas
grep -cE '^\s+androidx\.annotation\.RestrictTo\(' javap.txt                     # control

# 4. source declarations annotated with it (KDoc examples start with " *")
grep -rn '@AppFunctionSchemaDefinition(' ./*-src | grep -vE ':[0-9]+: *\*'
```

Output, and what it settles:

| Step | Result |
|---|---|
| 1. Group index | Four artifacts: `appfunctions`, `appfunctions-compiler`, `appfunctions-testing` (all at 1.0.0-beta01) and `appfunctions-service` (abandoned at 1.0.0-alpha09). No schema artifact. |
| 3. Annotated classes | **0** across 518 class files. The control finds 112 `@RestrictTo` uses with the same pattern, so the method sees class annotations. |
| 4. Annotated source declarations | **None.** The only occurrences are the annotation's own declaration, its KDoc example (`findNotes`, category `Notes`), and the compiler's processor code. |

**Finding: beta01 ships no predefined AppFunction schema.** `AppFunctionSchemaDefinition`
is a mechanism, not a catalogue: its KDoc says agent developers "can define and share these
annotated App Function schemas as an SDK with app developers". The platform
`AppFunctionManager` KDoc's "the SDK provides predefined function schemas for common use
cases" describes that mechanism; no such schema is published.

Two cross-checks, so the absence is not an artifact of one release:

- The `appfunctions` sources jars for 1.0.0-alpha01, -alpha04, -alpha07, -alpha10 and
  -alpha13 carry the same four schema-related files (the annotation,
  `AppFunctionSchemaCapability`, `SchemaAppFunctionInventory`, `AppFunctionSchemaMetadata`)
  and no annotated interface, and `appfunctions-service` 1.0.0-alpha09 has none either.
- On `androidx-main` (2026-10-31), the only interfaces annotated with it are test
  fixtures that are not published: `appfunctions-testing/src/test/.../NoteSchema.kt`,
  `integration-tests/shared-schema/.../NoteSchema.kt` (`createNote`, category
  `myNotes`), and the compiler's `src/test/test-data/input/schema/`. The API files
  there already name 1.0.0-rc01, which is the next version to re-check.

---

## Next Steps

1. Human review of §3 (ring membership) and §5 (open questions) — **STOP gate**.
2. Implement canon v1: `CanonType` + `Ring` + `CanonBinding`, Ring 1 entities in
   full, Rings 2–3 as typed declarations. *(done — see
   `ampere-core/src/commonMain/kotlin/link/socket/ampere/canon/`)*
3. Implement `CanonAdapter` with structurally-enforced preserve-and-merge. *(done)*
4. Round-trip, write-back-merge, and serialization-stability tests. *(done)*
5. Re-run the extraction on Xcode 27 and diff `apple-assistant-schemas-ios265.tsv`;
   file a follow-up if any of the six demoted types gains a domain.
6. Fill the Android column once `androidx.appfunctions` is on the classpath
   (AMPR-226 / socket-link/ampere#590). *(done in AMPR-510 against 1.0.0-beta01, from
   a scratch read rather than the classpath: `none` throughout)*
7. Re-run Appendix C when `AndroidCanonBindingRegistry.VERIFIED_AGAINST` would move
   past 1.0.0-beta01 (the androidx-main API files already name 1.0.0-rc01), and flip
   an entry to `Predefined` only for a schema that ships in a published artifact.

---

*Document generated as part of spike for issue #586*
