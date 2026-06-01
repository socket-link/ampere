# Changelog

All notable changes to AMPERE are recorded here. Dates are in UTC.

The project is pre-1.0; breaking changes are acceptable and explicitly called out.

## [Unreleased]

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

## [0.6.0] — 2026-05

Released; see `git log v0.6.0` for the commit history.
