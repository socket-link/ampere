# Changelog

All notable changes to AMPERE are recorded here. Dates are in UTC.

The project is pre-1.0; breaking changes are acceptable and explicitly called out.

## [Unreleased]

### Added

- **Knowledge-work canon wave: `WORK_ITEM`, `PROJECT`, `MILESTONE`, `TABLE`**
  ([AMPR-262](https://linear.app/miley/issue/AMPR-262)).

  The canon's first deliberate reopening since v1, admitting the vocabulary Arcs
  need to participate in projects, tasks, and tabular data. All four are Ring 3
  — they arrive only over `Mcp`, `OAuthRest`, or `FolderMount` Links — bringing
  the canon to 33 types. Each admission is recorded against a four-gate bar
  (noun / intersection / producer / bulk) in
  `docs/ampr-262-knowledge-work-canon-wave.md`, with per-provider lossy
  fields named in KDoc on day one.

  Also added: `CanonWorkStatus` (a coarse `BACKLOG`/`TODO`/`IN_PROGRESS`/`DONE`/
  `CANCELLED` lifecycle plus verbatim `providerStatus`, mirroring
  `CanonServiceStatus`), and `CanonTablePreview`, whose `bounded()` factory caps
  a table preview at 5 rows × 12 columns × 120 characters so bulk rows resolve
  out of band rather than riding the entity.

  `SPREADSHEET` and `ROADMAP` were assessed and rejected, `INITIATIVE` deferred
  with a recorded re-admission trigger; the rationale is in `domain-canon.md`.

  **Additive.** No existing type, field, or wire name changes, and no exhaustive
  `when` over `CanonType` or `CanonEntity` exists in the codebase — external
  consumers who wrote one will see a new-member exhaustiveness error, which is
  the intended tripwire.

- **`CanonAssetRef` widened to out-of-band content**
  ([AMPR-262](https://linear.app/miley/issue/AMPR-262),
  [AMPR-258](https://linear.app/miley/issue/AMPR-258)).

  Documentation only — no shape or wire-name change. `CanonAssetRef` now names
  where any out-of-band content lives, not only visual media, and backs
  `CanonTable.contentRef`. This resolves the non-visual case AMPR-258 deferred
  in favour of reuse over a sibling primitive, so consent enforcement stays in
  one place (`ConsentEnforcingAssetResolver`) rather than forking with a second
  resolver hierarchy.

- **Canon cross-reference contract settled** ([AMPR-266](https://linear.app/miley/issue/AMPR-266)).

  Documentation and tests only — no shape or wire-name change. The nullable
  `CanonId` cross-reference shape shared by `CanonEmailMessage.mailboxId`,
  `CanonWorkItem.projectId`, `CanonMilestone.projectId`, and
  `CanonTable.documentId` is now a formalized invariant in
  `domain-canon.md`: `null` is ambiguous between "not attached" and "provider
  didn't say," a `CanonId` only resolves against entities from the *same*
  Link, and there is no referential integrity. `mailboxId` — the field the
  other three copied without KDoc — is documented for the first time. A
  resolver SPI and a breaking typed-reference wrapper were both costed and
  rejected: neither `CanonId` nor anything else in the repo maps back to the
  `SourceHandle` a resolver would need, so either option is new-store
  machinery speculatively built for no confirmed consumer, not a cheap mirror
  of `AssetResolver`. Pinned in `CanonCrossReferenceContractTest`.

### Breaking

- **PROPEL `CognitivePhase` enum is now canonically six members**
  ([AMPR-172](https://linear.app/miley/issue/AMPR-172)).

  `CognitivePhase` in
  `link.socket.ampere.agents.domain.cognition.sparks.PhaseSpark.kt`
  becomes the full PROPEL cycle in canonical order:

  ```kotlin
  enum class CognitivePhase {
      PERCEIVE,
      RECALL,
      OBSERVE,
      PLAN,
      EXECUTE,
      LEARN,
  }
  ```

  Previously the enum carried only `PERCEIVE / PLAN / EXECUTE / LEARN`,
  silently dropping `RECALL` and `OBSERVE`. The acronym is now load-bearing:
  `enumValues<CognitivePhase>().toList()` yields the cycle in order.

  **Migration for external consumers:**
  - Any `when (phase: CognitivePhase)` site without an `else` branch will
    fail to compile with an exhaustiveness error. Add explicit branches
    for `RECALL` and `OBSERVE`.
  - Code that iterated `CognitivePhase.entries` will now see six phases
    instead of four. Test matrices that assumed four-phase coverage will
    automatically extend; tests that hardcoded a four-element list need
    updating.
  - Declarative spark `.spark.md` frontmatter that previously enumerated
    `"phases": ["PERCEIVE", "PLAN", "EXECUTE", "LEARN"]` continues to
    parse, but the spark will not apply during `RECALL` or `OBSERVE`. If
    full coverage is intended, update the list to all six phases.
  - Serialized values are unchanged: existing `"PERCEIVE"` / `"PLAN"` /
    `"EXECUTE"` / `"LEARN"` strings still deserialize. AMPERE does not
    persist `CognitivePhase` across runs today, so no data migration is
    required.

- **CLI `AmperePhosphorBridge` removes the `LEARN → EVALUATE` paveover
  ([AMPR-182](https://linear.app/miley/issue/AMPR-182)).**
  Phosphor 0.6.2 ships with [PHO-28](https://linear.app/miley/issue/PHO-28),
  adding `OBSERVE` and renaming `EVALUATE → LEARN` to align with the
  canonical PROPEL phases. The bridge now maps directly:
  `PERCEIVE → PERCEIVE`, `RECALL → RECALL`, `OBSERVE → OBSERVE`,
  `PLAN → PLAN`, `EXECUTE → EXECUTE`, `LEARN → LEARN`.
  The `CognitiveChoreographer`, `CognitivePalette`, and related rendering
  surfaces updated to reference the canonical phases.

### Added

- `PhaseSpark.Recall` and `PhaseSpark.Observe` built-in sparks with
  default `promptContribution` strings tuned for memory-recall and
  state-monitoring behavior, respectively. `PhaseSpark.forPhase` covers
  all six members.

### Notes

- Phosphor 0.6.2 ([PHO-28](https://linear.app/miley/issue/PHO-28)) now
  aligns with canonical PROPEL phases: `PERCEIVE / RECALL / OBSERVE / PLAN / EXECUTE / LEARN / LOOP / NONE`.

## [0.12.0] — 2026-07-31

### Added

- `CanonAssetRef` and `AssetResolver` for canon asset references
  ([AMPR-258](https://linear.app/miley/issue/AMPR-258)).
- `NativeFields` cursor, `NativeSchema` value class, and child provenance
  rules ([AMPR-248](https://linear.app/miley/issue/AMPR-248)).
- `PlugPermission.DeviceCapability` and `NativeAuthorizationStatus`
  ([AMPR-249](https://linear.app/miley/issue/AMPR-249)).
- `PerceiveSource`/`ExecuteSink` chassis operation layer
  ([AMPR-246](https://linear.app/miley/issue/AMPR-246)).
- `ampere-core-test-fixtures` published as its own Maven artifact
  ([AMPR-250](https://linear.app/miley/issue/AMPR-250)).

### Changed

- Binding declarations split out of `ampere-core` into edge modules
  ([AMPR-257](https://linear.app/miley/issue/AMPR-257)).
- `CanonAdapter` split into `Readable`/`Writable`/`CreatingCanonAdapter`
  ([AMPR-247](https://linear.app/miley/issue/AMPR-247)).
- Thin Canon types widened for the P0 Plug wave
  ([AMPR-252](https://linear.app/miley/issue/AMPR-252)).
- Plug manifest dead ends closed
  ([AMPR-251](https://linear.app/miley/issue/AMPR-251)).
- iOS framework link moved out of Xcode's nested Gradle invocation
  ([AMPR-253](https://linear.app/miley/issue/AMPR-253)).
- iOS CI job time reduced via simulator boot caching/overlap
  ([AMPR-244](https://linear.app/miley/issue/AMPR-244),
  [AMPR-255](https://linear.app/miley/issue/AMPR-255)).

### Fixed

- Dokka link-resolution and Kotlin/Native cast warnings.

## [0.6.0] — 2026-05

Released; see `git log v0.6.0` for the commit history.
