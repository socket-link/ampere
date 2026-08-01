# Knowledge-work canon wave — recon, verdicts, and admission

**Issue:** [#663](https://github.com/socket-link/ampere/issues/663) — Knowledge-work canon wave — fitness spike + admission: WORK_ITEM, PROJECT, MILESTONE, TABLE (Ring 3)
**Linear:** [AMPR-262](https://linear.app/miley/issue/AMPR-262)
**Author:** @miley
**Date:** 2026-08-01

---

## Executive Summary

Four Ring 3 types admitted — `WORK_ITEM`, `PROJECT`, `MILESTONE`, `TABLE` — plus a
shared `CanonWorkStatus` lifecycle. Canon count 29 → 33 (Ring 1: 12, Ring 2: 11,
Ring 3: 6 → 10).

Recon changed four things relative to the locked sketch. Each is recorded with
evidence below:

| # | Sketch said | Recon found | Verdict |
| -- | -- | -- | -- |
| 1 | `PROJECT` intersects "Linear project / Jira project / GitHub project" | A **Jira project is a container, not an effort** — no status, no target date. The real Jira counterpart is an Epic (or an Advanced Roadmaps plan) | Intersection restated as Linear Project ∩ GitHub Projects v2, with Jira Epic as the third leg. Type still admits |
| 2 | `CanonWorkStatus {BACKLOG, TODO, IN_PROGRESS, DONE, CANCELLED}` | Linear ships **six** `statusType` values in this very workspace, two of which (`triage`, `duplicate`) have no coarse home | Membership held at five. `triage` → `TODO`, `duplicate` → `CANCELLED`, both preserved verbatim in `providerStatus` |
| 3 | `TABLE` preview "mechanism decided at gate" | — | `CanonTablePreview` with a `bounded()` factory: 5 rows × 12 columns × 120 chars. `CanonAssetRef` **reused** (KDoc widened) rather than forked |
| 4 | "trace budget (Oscilloscope recording size)" | **The trace budget does not exist.** `TraceRecorder` uses `Channel.UNLIMITED`; `Trace.sq` stores an unbounded `TEXT` blob; no truncation, cap, or hygiene test anywhere in the repo | Budget *established* here: 32 KiB per serialized canon projection, asserted in `CanonWorkEntitiesTest` |

Two constraints in the ticket turned out not to bind:

- **"Four new members mean every exhaustive `when` downstream breaks deliberately."**
  There is **no** `when` over `CanonEntity` or `CanonType` anywhere in the repo.
  The only tripwire is `CanonSerializationTest.samples()` (`:152`), which asserts
  set-equality against `CanonType.entries`. Nothing else breaks at compile time.
- **"Binding-table doc updated … ring counts corrected everywhere they appear."**
  There is no markdown binding table (AMPR-257 moved it into
  `AppleCanonBindingRegistry.kt`) and **no numeric canon count is stated anywhere**
  in committed source or docs. Counts are always derived from `CanonType.entries`.
  Nothing to correct; four `UNBOUND` rows added to the Apple registry, and
  `AndroidCanonBindingRegistry` needs no edit (it derives from `CanonType.entries`).

Sequencing precondition satisfied: 0.12.0 published (`6a6f12c4`) and AMPR-257
merged (`15544055`), so this lands on the settled post-split layout.

---

## 1. Provider-intersection tables

Evidence marked **(live)** was read from the Linear MCP Link during this spike —
which doubles as the producer-test evidence for gate 3.

### 1.1 `WORK_ITEM`

| Canon field | Linear `Issue` | Jira `issue.fields` | GitHub `Issue` | Notes |
| -- | -- | -- | -- | -- |
| `title` | `title` **(live)** | `summary` | `title` | 3/3 |
| `status` | `state.type` **(live: `statusType: "started"`)** | `status.statusCategory.key` | `state` + `state_reason` | 3/3, coarsened — see §2 |
| `providerStatus` | `state.name` **(live: `"In Progress"`)** | `status.name` | `state_reason` | 3/3 |
| `assignee` | `assignee` **(live)** | `assignee` | `assignee` (first of `assignees`) | 3/3 |
| `projectId` | `project.id` **(live)** | `parent` (Epic link) | Projects v2 item, via a second API | 3/3, unequal fidelity |
| `dueAt` | `dueDate` **(live: `null`)** | `duedate` | **absent** | 2/3 — see loss note |
| `labels` | `labels[].name` **(live: `["api","spike","architecture"]`)** | `labels[]` (already strings) | `labels[].name` | 3/3 |

**Dropped per provider (day-one `lossyFields`):**

- **Linear** — `priority` (0–4 ordinal), `estimate`, `cycle`, `team`, `parent`/`children`,
  `subscribers`, `snoozedUntilAt`, `triagedAt`, `slaBreachesAt`, `branchName`,
  `sortOrder`, `stateHistory`, `description` (see below).
- **Jira** — `issuetype`, `resolution`, `components`, `fixVersions`, `worklog`,
  `timetracking`, `votes`/`watchers`, every `customfield_*` (story points live here),
  `description` in ADF form.
- **GitHub** — `milestone` (GitHub attaches milestones to issues, not to projects),
  `reactions`, `locked`, `pull_request`, `body`, label colour/description.
- **All three** — `labels` collapse from objects to names, so colour and description
  are lost on every provider.

**`dueAt` is the sharpest loss and it is a type mismatch, not an omission.** Linear
`dueDate` and Jira `duedate` are **calendar dates with no time and no zone**;
`Instant` cannot represent that. Adapters normalise to `00:00Z` on the named date,
which means a due date rendered in `UTC-5` shows as the previous evening. Named as
`dueDateTimeZone` in the KDoc; the verbatim provider string survives in
`nativePayload`. Rejected the alternative (`LocalDate`) because it introduces a
second temporal type into a canon that is uniformly `Instant`, for one field.

**`description`/`body` deliberately omitted.** All three providers have it and it is
unbounded provider prose — GitHub issue bodies routinely exceed the whole per-entity
budget established in §4. Adding a field later is additive (`= null` default) and
therefore non-breaking; shipping an unbounded field now would violate gate 4 on the
type the ticket calls the strongest candidate. Deferred with that trigger: admit
`description` when the bulk rule has a bounded-prose mechanism, the same way `TABLE`
now has one.

### 1.2 `PROJECT`

**Recon correction.** The ticket's claim of a "clean cross-provider intersection
(Linear project / Jira project / GitHub project)" does not survive contact. A Jira
**project** is an issue container with a key and a lead — closer to a Linear *team*
than a Linear *project*. It has no status and no target date, so it cannot supply
two of the six sketched fields. The Jira object that carries an effort's lifecycle
and dates is an **Epic** (or an Advanced Roadmaps plan on Premium).

The type still clears gate 2 on Linear ∩ GitHub Projects v2 alone, with Jira Epic
as a third, weaker leg. Recording the correction so the mapping is not re-derived
wrongly per-adapter.

| Canon field | Linear `Project` | GitHub `ProjectV2` | Jira Epic | Notes |
| -- | -- | -- | -- | -- |
| `name` | `name` **(live)** | `title` | `fields.summary` | 3/3 |
| `status` | `status.type` **(live: `"backlog"`, `"started"`)** | `closed: Boolean` | `status.statusCategory` | GitHub collapses to two values |
| `providerStatus` | `status.name` **(live: `"Backlog"`, `"In Progress"`)** | — (no name) | `status.name` | 2/3 |
| `targetDate` | `targetDate` **(live)** | **absent** (custom field only) | `duedate` | 2/3 |
| `lead` | `lead` **(live: a `User`)** | **absent** (`owner` is an org/user account, *not* a lead) | `assignee` | 2/3 |
| `summary` | `summary` **(live)** — distinct from `description` | `shortDescription` | `fields.description` | 3/3 |

**Dropped per provider:**

- **Linear** — `description` (long form; `summary` is the short one), `startDate`,
  **`targetDateResolution`/`startDateResolution`** (see below), `progress`, `scope`,
  `health`, `priority`, `teams`, `members`, `initiatives`, `icon`/`color`, `url`.
- **GitHub** — `readme`, `public`, `number`, `owner`, `fields` (the entire custom
  field schema), `items`.
- **Jira** — `issuetype`, `resolution`, child issues, `customfield_*`.

**`targetDateResolution` is a real, non-obvious loss.** Linear target dates carry a
resolution — a project can be due "Q3" or "2026 H2", not a specific day
(`targetDateResolution` is a first-class field on the MCP projection **(live)**).
Projecting to `Instant` silently promotes "sometime in Q3" to a precise timestamp.
Named in the KDoc; adapters should normalise a coarse resolution to the **last** day
of the period, so a deadline is never reported earlier than the provider means it.

**GitHub `closed: Boolean` → `CanonWorkStatus`.** `closed = true` → `DONE`;
`closed = false` → `IN_PROGRESS`. This is the coarsest mapping in the wave and it
lies about planned-but-not-started projects. `providerStatus` is `null` for GitHub
because there is no name to preserve — the honest signal that the value was derived,
not observed.

### 1.3 `MILESTONE`

| Canon field | Linear `ProjectMilestone` | GitHub `Milestone` | Jira `Version` | Notes |
| -- | -- | -- | -- | -- |
| `name` | `name` **(live: `milestones` field exists on `Project`)** | `title` | `name` | 3/3 |
| `targetDate` | `targetDate` | `due_on` | `releaseDate` | 3/3 |
| `projectId` | `project.id` | **absent** — GitHub milestones hang off a *repository* | `projectId` | 2/3 |
| `progressFraction` | `progress` (0..1) | derived: `closed_issues / (open + closed)` | derived from issue counts | 1/3 observed, 3/3 derivable |

**Dropped per provider:** Linear `sortOrder`, `description`, `documentContent`;
GitHub `state`, `open_issues`/`closed_issues`, `creator`, `number`, `html_url`;
Jira `released`, `archived`, `startDate`, `overdue`, `userReleaseDate`.

**`progressFraction` is observed on one provider and computed on two.** That is a
meaningful distinction — a Linear `progress` reflects issue *estimates*, while a
derived GitHub fraction counts issues equally. Named in the KDoc as
`progressWeighting`.

**No `status` on `MILESTONE` — verdict recorded.** Two of three providers have
something status-shaped (GitHub `state`, Jira `released`/`archived`), which
technically clears the ≥2 bar. Rejected anyway because **the two disagree on what
the state means**: GitHub `closed` is a manual housekeeping act, Jira `released` is
a shipping event, and Linear has neither. A canon field whose two producers mean
different things by it is exactly the harm AMPR-252 exists to prevent.
`targetDate` + `progressFraction` answer every question a status would.

### 1.4 `TABLE`

| Canon field | Google Sheets (a *sheet*, i.e. tab) | Notion database | Folder-mounted CSV |
| -- | -- | -- | -- |
| `title` | `properties.title` | `title[].plain_text` | file name |
| `columnNames` | **first row, by convention** | `properties` map keys | header row, by convention |
| `rowCount` | `properties.gridProperties.rowCount` (allocated, not used) | requires paginating `/query` | line count minus header |
| `preview` | first N rows of `values` | first N `results[].properties` | first N parsed lines |
| `documentId` | `spreadsheetId` | `parent.page_id` / `database_id` | the mounted file |
| `contentRef` | `values.get` range URL | `/v1/databases/{id}/query` | file handle |

**Dropped per provider:**

- **Sheets** — formulas, number/date formatting, merged cells, conditional
  formatting, frozen rows, data validation, charts, protected ranges, and the fact
  that a spreadsheet holds *many* sheets (one `CanonDocument` → many `CanonTable`).
  `gridProperties.rowCount` is the **allocated** grid, not populated rows;
  an adapter that reports it as `rowCount` will claim 1000 rows for a 3-row sheet.
- **Notion** — the entire column *type* system (`select`, `multi_select`, `relation`,
  `rollup`, `formula`, `people`, `files`), per-property configuration, views,
  filters, sorts, icon/cover, and rich-text annotations inside cells.
- **CSV** — dialect (delimiter, quote char, escape, line terminator), character
  encoding, and whether a header row exists at all.

**`columnNames` is a name list, not a schema.** This is the largest single loss in
the wave: a Notion database's value is mostly in its column *types*, and canon keeps
only the names. Named as `columnTypes` in the KDoc. Deferring a typed column model
is correct for a first admission — a cross-provider column-type lattice is its own
spike, and the names alone are enough for an Arc to ask for the right column by
reference.

**`documentId` added to the sketch.** The `SPREADSHEET` rejection rationale is *"the
file is already `CanonDocument(kind = SPREADSHEET)`"* — which is only a coherent
answer if a `TABLE` can point at that document. Without the back-reference the
rejection leaves the grid orphaned from its file and an Arc cannot get from one to
the other. Same `CanonId`-reference mechanism as `projectId`, so no new machinery.

---

## 2. `CanonWorkStatus` membership

### Evidence

Linear's `statusType` values, read live from this workspace's Ampere team:

| `statusType` | Statuses in this workspace |
| -- | -- |
| `backlog` | Backlog |
| `unstarted` | Todo |
| `started` | **In Progress, In Review** |
| `completed` | Done |
| `canceled` | Canceled |
| `duplicate` | Duplicate |

Plus `triage`, which Linear ships but this workspace does not use.

Jira status categories: exactly three — `new` ("To Do"), `indeterminate` ("In
Progress"), `done` ("Done"). Jira has no cancelled category; a cancelled issue is
category `done` with a resolution of "Won't Do".

GitHub: `state` ∈ {`open`, `closed`} plus `state_reason` ∈ {`completed`,
`not_planned`, `reopened`, `duplicate`}.

Notion `status` property groups: three — "To-do", "In progress", "Complete".

### Verdict: five members, unchanged

```
BACKLOG, TODO, IN_PROGRESS, DONE, CANCELLED
```

The live evidence makes the coarsening argument itself: **Linear already performs
this collapse.** "In Progress" and "In Review" are distinct statuses sharing one
`statusType`. `CanonWorkStatus` is the same operation, one notch coarser.

**Why `BACKLOG` survives despite only Linear elevating it to a category.** The
intersection test asks whether the *concept* is producible across providers, not
whether each provider promotes it to an enum. Every provider here can produce a
backlog: Jira teams keep a "Backlog" status inside category `new`, GitHub Projects
templates ship a "Backlog" column, Notion boards have a "Backlog" option in the
"To-do" group. The mapping is therefore **per-status-name, not per-category** —
an adapter reads the provider's own status name and only falls back to the category.

The asymmetry that decides it: under-modelling is lossy and unrecoverable
(collapsing `backlog` and `unstarted` destroys a distinction Linear treats as
primary), while over-modelling is merely unused (a provider that never produces
`BACKLOG` costs nothing). Contrast `INITIATIVE` in §5, where the *noun itself* has
one producer — that is a different failure and gets a different answer.

**Adapter rule, recorded so it is not re-derived:** where a provider offers no
backlog signal, map to `TODO`, never `BACKLOG`. `TODO` is the conservative default
because it keeps work visible; guessing `BACKLOG` hides it.

### Lifecycle states that do not map coarsely

Gate item 2's deliverable. Two Linear `statusType` values have no natural home:

| Provider state | Assignment | Why |
| -- | -- | -- |
| Linear `triage` | `TODO` | Triage is unsorted-but-live work. `BACKLOG` would hide it, which inverts triage's purpose |
| Linear `duplicate`, GitHub `state_reason: duplicate` | `CANCELLED` | Work that will not be done independently. `DONE` would let an Arc count it as completed |
| GitHub `state_reason: not_planned` | `CANCELLED` | Direct match |
| Jira resolution "Won't Do" (category `done`) | `CANCELLED` | The category lies here; the resolution is the truth. Adapters must read `resolution`, not `statusCategory`, to avoid reporting abandoned work as done |

All four preserve the provider's own wording in `providerStatus`.

---

## 3. The `mailboxId` cross-reference precedent

Gate item 3. Audit result: **`mailboxId` documents nothing.** It is a bare field at
`CanonRing1Entities.kt:76`:

```kotlin
val mailboxId: CanonId? = null,
```

No KDoc on the field, no resolution semantics, no statement of what `null` means, no
mechanism for turning it into a `CanonMailbox`. There is no registry, no resolver
SPI, and no test covering it. The precedent is a *shape*, not a contract.

**What `projectId`/`documentId` should copy:** the shape (a nullable `CanonId`, no
new machinery — this is what the ticket asks for and it is right).

**What they should not copy:** the silence. Three questions `mailboxId` leaves open
are answered explicitly in KDoc on the new fields:

1. **What `null` means.** Two different facts collapse into `null` — "not in a
   project" and "the provider did not tell us". Documented as: `null` means
   *unknown or unattached, indistinguishable*; an Arc must not read `null` as
   "definitely standalone".
2. **Scope of the id.** A `CanonId` is Ampere-scoped and only resolvable against
   entities from the **same Link**. A `projectId` from a Linear Link will never
   match a `CanonProject` from a Jira Link even if both name the same effort.
3. **No referential integrity.** Nothing guarantees the referenced entity was ever
   perceived. Resolution is a caller concern and may find nothing.

Deliberately *not* addressed here: a resolver SPI for canon cross-references. That
is a real gap — `mailboxId` has had it for the whole life of the canon — but it is
new machinery, which this ticket excludes. Filed as a follow-up below.

---

## 4. `TABLE` bulk mechanics

### 4.1 The trace budget does not exist

Gate item 4 asks for "a preview bound compatible with the trace budget (Oscilloscope
recording size)". Recon result: **there is no such budget, anywhere.**

- "Oscilloscope" appears nowhere in the codebase. The subsystem is
  `ampere-eval/.../trace/`.
- `TraceRecorder.kt:46` — `val buffer = Channel<Event>(Channel.UNLIMITED)`.
- `TraceService.kt:40` serialises the entire event list into one column;
  `Trace.sq:9-15` declares it `events_json TEXT NOT NULL`, unconstrained.
- No truncation, cap, drop policy, or redaction exists in the trace path. A
  repo-wide search for `budget|truncat|maxLength|redact` across `ampere-eval/` and
  `canon/` returns one hit: the `Channel.UNLIMITED` line above.
- No trace-hygiene test exists — no test anywhere asserts that something is *absent*
  from a trace, or that a trace stays under a size.

The cost is not only storage. `RecordedModelCall.kt:65` deserialises **every** event
in a trace just to find provider calls, so unbounded canon content is paid on every
`PlaybackRelay` construction, not just at write time.

**So the budget is established here rather than looked up:** **32 KiB per serialised
canon projection.** Asserted in `CanonWorkEntitiesTest` against a worst-case
`CanonTable`, the largest entity the canon can now produce. A hundred-entity trace
stays under 3.2 MiB worst case and near 1 MiB in practice — a comfortable SQLite
`TEXT` value and a cheap full decode on replay.

**The budget is scoped to the entity's own fields, and it has to be.**
`CanonProvenance.nativePayload` is an unbounded `JsonObject` by design; its own KDoc
already tells adapters to drop it for large payloads. No per-entity byte budget can
cover it, so the bulk rule governs the canon *projection* and the native payload
stays the separate, already-documented escape hatch.

Three honest caveats:

- **This is a canon-side budget, not a trace-side one.** No `CanonEntity` currently
  rides any `Event` — no event type has a `CanonEntity` field — so the ticket's
  task 16 ("record an emission carrying a max-preview table") cannot be written as
  stated without new machinery, which is out of scope. The equivalent structural
  assertion (serialise the max-preview table, assert size and absence of bulk
  content) is implemented instead. When an emission does carry canon entities, the
  recorder-side cap becomes real work; filed as a follow-up.
- **`CanonDocument.plainText` already violates this budget** (`CanonRing1Entities.kt:161`
  — an unbounded `String?` with no cap, no truncation, no KDoc about size). The new
  bound does not retroactively fix it. Flagged, not fixed: changing it is not
  additive.
- **`columnNames` and `labels` are unbounded** and could breach the budget on a
  pathological input (a Notion database with 500 properties). Both are schema, which
  gate 4 explicitly permits in canon. Left unbounded because bounding schema defeats
  the type's purpose; the budget test is the tripwire if it ever bites.

### 4.2 Content-ref primitive

#### Option A: reuse `CanonAssetRef`, widen its KDoc

Its two shapes are already exactly right and carry nothing visual in their
structure: `Url(template, width, height)` and `NativeHandle(linkId, nativeId)` —
where `linkId` doubles as the consent key. `width`/`height` are nullable and simply
unused for a table.

#### Option B: a sibling `CanonContentRef`

A parallel sealed interface with the same two cases, plus a parallel
`ContentResolver` SPI beside `AssetResolver`.

#### Option C: rename `CanonAssetRef` → `CanonContentRef`

#### Recommendation: **A**

C is disqualified outright: the `@SerialName`s `canon_asset_ref.url` and
`canon_asset_ref.native_handle` are wire contracts, and renaming breaks
`PlaybackRelay` replay of every trace carrying a `CanonPhoto` or `CanonDocument`
thumbnail.

B duplicates a two-case type and — worse — forks the resolver SPI, so consent
enforcement lives in two places. `ConsentEnforcingAssetResolver` exists precisely so
the check happens once in the SPI contract rather than per implementor; a second
resolver hierarchy re-opens that. AMPR-262's own coordination note asks that "the
ref story stays singular", and B is the option that makes it plural.

A's only cost is a slightly vestigial type name. Wire names are opaque, so nothing
downstream reads "asset" as a claim about content type. **Verdict: reuse
`CanonAssetRef`; widen its KDoc from "visual media" to "out-of-band content", with
the artwork case named as the motivating example rather than the definition.**
Record on AMPR-258 as the resolution of its deferred non-visual case.

### 4.3 Preview bound

| Constant | Value | Why |
| -- | -- | -- |
| `MAX_ROWS` | 5 | Enough to show shape and infer types; not enough to be a data transfer |
| `MAX_COLUMNS` | 12 | Above the median real table; wide grids truncate rather than blow the budget |
| `MAX_CELL_CHARS` | 120 | A Notion rich-text cell can hold a paragraph |

Worst case: 5 × 12 × 120 = 7,200 UTF-16 units of content. **In bytes that peaks at
×3, not ×4** — a four-byte codepoint costs two UTF-16 units, so the worst
byte-per-unit ratio belongs to three-byte BMP characters like CJK. That gives
21,600 bytes plus JSON quoting, inside the 32 KiB budget with real margin; an ASCII
table lands near 7.5 KiB.

**Truncation must not split a surrogate pair.** A naive `take(120)` can leave a
trailing lone high surrogate, which is not valid UTF-8 — it encodes as a replacement
character, so the cell fails to round-trip through the very serialisation the bound
exists to protect. `bounded()` backs off one unit when the cut lands on a high
surrogate; `bounding never splits a surrogate pair` pins it.

**Enforcement is a factory plus a tripwire, not a compiler guarantee — deliberately.**
`CanonTablePreview.bounded()` truncates and sets `truncated = true`; adapters are
documented to construct only through it. The obvious alternative — `require()` in an
`init` block — was rejected because it makes an oversized *already-recorded* trace
permanently undecodable, which is precisely the failure mode the `@SerialName`
stability invariant exists to prevent. A canon type must always decode; bounding is
a write-side concern. `CanonTablePreview.isWithinBounds` exposes the predicate so
the rule is testable rather than merely stated.

Residual risk from unbounded `columnNames` is named in §4.1.

---

## 5. `INITIATIVE` deferral

**Confirmed deferred.** Linear ships `Initiative` as a first-class object
(`initiatives` is a field on `Project` **(live)**). No second provider has a
peer-level noun above the project: Jira's Advanced Roadmaps "initiative" is a
*configurable issue type* in a hierarchy an admin can rename or delete, not a fixed
noun; GitHub has nothing above `ProjectV2`.

**Re-admission trigger:** a second provider exposes a durable object above the
project — one that is part of the provider's data model rather than a
customer-configured hierarchy level — with at least a name and a set of member
projects. The Jira leg specifically requires that the hierarchy level be
non-configurable, since a noun an admin can rename is not a noun two apps can
exchange (gate 1).

`SPREADSHEET` and `ROADMAP` rejections stand as written in the ticket. Recorded in
`domain-canon.md` so they are not re-litigated per request.

---

## Technical Guidelines

**File layout (gate item 5, confirmed).** Ring 3 entities did **not** move in
AMPR-257 — that ticket moved *bindings* out to edge modules, not entities.
`CanonRing3Entities.kt` is still the right home. `ampere-core` remains
platform-neutral, enforced by the merge-blocking `verifyCoreNeutrality` check; none
of the four new types names a vendor.

**What breaks (measured, not assumed).**

| Site | Effect |
| -- | -- |
| `CanonSerializationTest.kt:152` | **Fails** until four samples are added. The only real tripwire |
| `AppleCanonBindingRegistry.kt:24` | Silent — `bindingFor` falls back to `UNBOUND` (`:192`). Rows added anyway, so the table stays a complete statement |
| `AndroidCanonBindingRegistry.kt:17` | No change — derives from `CanonType.entries` |
| `AppleCanonBindingRegistryTest.kt:25` | Passes; new Ring 3 types correctly claim no Apple binding |
| Any exhaustive `when` | **None exist.** No `when` over `CanonEntity` or `CanonType` anywhere in the repo |

**Version plan.** Additive only: four enum members, four entity types, two new
supporting types, one widened KDoc. No existing type, field, or wire name changes.
Byte-identity of the 29 pre-existing samples was **verified empirically, not
assumed**: the sample set was encoded on this branch and again with the branch
stashed, and the two dumps are identical. Ships in the next
minor after 0.12.0 (0.13.0) under `### Added`. Not a breaking change — but the
canon's "closed for v1" invariant means it is still a *versioned decision*, recorded
in the CHANGELOG with the admission rationale, not a routine addition.

**Follow-ups filed by this spike (not implemented here — all are new machinery):**

| Ticket | Follow-up |
| -- | -- |
| [AMPR-266](https://linear.app/miley/issue/AMPR-266) | **Canon cross-reference resolution.** `mailboxId`, `projectId`, `documentId` all name an entity with no way to resolve it. Needs an SPI or a documented caller contract. Predates this wave (§3) |
| [AMPR-267](https://linear.app/miley/issue/AMPR-267) | **Trace-side size enforcement.** The budget established in §4.1 is asserted on canon entities only. When an `Event` carries a `CanonEntity`, `TraceRecorder` needs a real cap at `RecordingHandle.stop()` — the single chokepoint every event passes |
| [AMPR-268](https://linear.app/miley/issue/AMPR-268) | **`CanonDocument.plainText` is unbounded** and predates the bulk rule (`CanonRing1Entities.kt:161`). Bounding it is a breaking change |
| [AMPR-269](https://linear.app/miley/issue/AMPR-269) | **`WORK_ITEM.description`** — admit once a bounded-prose mechanism exists (§1.1). Blocked by AMPR-268 |
| [SCKT-462](https://linear.app/miley/issue/SCKT-462) | **Socket `EntityKind` mirror** — four members, Tier C fallback, per the ticket's coordination note. No `EntityKind` exists in this repo. Blocked by AMPR-262 |

---

## Appendix

### Final field sets (gate item 7)

```kotlin
// canon.work_item
title: String, status: CanonWorkStatus, providerStatus: String? = null,
assignee: CanonPerson? = null, projectId: CanonId? = null,
dueAt: Instant? = null, labels: List<String> = emptyList()

// canon.project
name: String, status: CanonWorkStatus, providerStatus: String? = null,
targetDate: Instant? = null, lead: CanonPerson? = null, summary: String? = null

// canon.milestone
name: String, targetDate: Instant? = null,
projectId: CanonId? = null, progressFraction: Double? = null

// canon.table                                    // documentId + contentRef added by recon
title: String, columnNames: List<String> = emptyList(),
rowCount: Int? = null, preview: CanonTablePreview? = null,
documentId: CanonId? = null, contentRef: CanonAssetRef? = null
```

Only `TABLE` changed from the sketch; the other three ship exactly as locked.

### Wire names

| Type | `@SerialName` | `wireName` | Ring |
| -- | -- | -- | -- |
| `CanonWorkItem` | `canon.work_item` | `work_item` | SERVICE |
| `CanonProject` | `canon.project` | `project` | SERVICE |
| `CanonMilestone` | `canon.milestone` | `milestone` | SERVICE |
| `CanonTable` | `canon.table` | `table` | SERVICE |

`CanonWorkStatus`: `backlog`, `todo`, `in_progress`, `done`, `cancelled` — note
`cancelled` (double-l), matching the existing `CanonServiceStatus.CANCELLED`.

### Status mapping quick reference

| `CanonWorkStatus` | Linear `statusType` | Jira | GitHub issue | GitHub project |
| -- | -- | -- | -- | -- |
| `BACKLOG` | `backlog` | `new` + status named "Backlog" | — | column named "Backlog" |
| `TODO` | `unstarted`, `triage` | `new` (otherwise) | `open` | — |
| `IN_PROGRESS` | `started` | `indeterminate` | `open` | `closed = false` |
| `DONE` | `completed` | `done` (resolution ≠ won't-do) | `closed` + `completed` | `closed = true` |
| `CANCELLED` | `canceled`, `duplicate` | `done` + won't-do resolution | `closed` + `not_planned`/`duplicate` | — |

### Existing patterns followed

| Pattern | Source |
| -- | -- |
| Coarse lifecycle + verbatim `providerStatus` | `CanonServiceStatus`, `CanonRing3Entities.kt:98-111` |
| Nullable `CanonId` cross-reference | `CanonEmailMessage.mailboxId`, `CanonRing1Entities.kt:76` |
| Refs in canon, bytes at the edge | `CanonAssetRef` + `AssetResolver` |
| Counts, not content | `CanonPhotoAlbum.assetCount`, `CanonDocument.sizeBytes` |
| Documented bound constant | `EmissionDigest.DIGEST_HEX_CHARS` |

---

## Next Steps

1. Add four `CanonType` members and `CanonWorkStatus` to `ampere-core`. ✔
2. Add the four entities plus `CanonTablePreview` to `CanonRing3Entities.kt`, each
   with KDoc naming its provider intersection and its lossy fields. ✔
3. Widen `CanonAssetRef`'s KDoc to out-of-band content; record on AMPR-258. ✔
4. Add four `UNBOUND` rows to `AppleCanonBindingRegistry`. ✔
5. Add four samples to `CanonSerializationTest.samples()`; confirm pre-existing
   samples byte-identical. ✔
6. Add `CanonWorkEntitiesTest`: per-type round-trip over `Mcp` and `FolderMount`
   provenance, preview-bound enforcement, and the 16 KiB budget assertion. ✔
7. Update `docs/concepts/domain-canon.md` and `CHANGELOG.md`. ✔
8. File the five follow-ups listed under Technical Guidelines. ✔ AMPR-266/267/268/269, SCKT-462
9. Post the §4.2 content-ref verdict on AMPR-258. ✔

---

*Document generated as part of spike for issue #663*
