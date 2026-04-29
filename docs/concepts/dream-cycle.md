---
concept: DreamCycle
status: experimental
tracked_sources: []  # experimental — no implementation yet
related: [MemoryProvenance, PropelLoop, CognitionTrace]
last_verified: 2026-04-29
---

# DreamCycle

## What it is

DreamCycle is the planned async memory-consolidation loop: a separate
process that runs while no Arc run is active and rewrites the
`KnowledgeRepository` to merge near-duplicate entries, decay stale
ones, and surface emergent patterns that span multiple runs. It is the
intended counterpart to PROPEL — PROPEL handles in-the-moment cognition;
DreamCycle handles offline consolidation, the way biological dreaming is
hypothesised to consolidate episodic into semantic memory overnight.

## Why it exists

Without consolidation, `KnowledgeRepository` accumulates monotonically.
Useful patterns get drowned by repetitive entries. Recall starts
returning lots of similar but unconsolidated learnings, and the prompt
window gets crowded with redundancy. The two design forces:

1. **Recall quality degrades over time without consolidation.** A
   thousand entries about "JWT auth" should compress into a handful of
   well-scored generalisations.
2. **Consolidation is wrong to do inline.** The PROPEL loop is on the
   user-visible critical path; rewriting the knowledge graph is not. It
   needs its own loop, its own scheduler, its own observable signals.

## Where it lives

- *Not yet implemented.* Searches for `DreamCycle`, `consolidation`,
  `dream*`, and async memory rewrite paths return no matches in the
  current codebase as of `last_verified`.

## Implementation gap

| Target shape | Current state |
|---|---|
| Background scheduler that triggers between Arc runs (or on demand). | Not implemented. |
| Reads from `KnowledgeRepository` and `OutcomeMemoryStore`; writes consolidated entries via `KnowledgeRepository.storeKnowledge`. | Not implemented; would need to honour the append-only invariant by writing new "consolidation" entries rather than mutating originals. |
| Emits `DreamCycleStartedEvent` / `DreamCycleCompletedEvent` and per-consolidation events for trace projection. | Event types not defined. |
| Configurable cadence and time budget; never preempts an active Arc run. | No scheduler exists. |
| Consolidation rules expressed declaratively (similar to `RoutingRule`). | Not designed. |

This file exists so an agent reading the concept index sees that the
concept is *named and reserved*, not silently absent. When an
implementation lands, this file is updated in the same diff: bump
`status: stable`, populate `tracked_sources` with real paths, replace the
"not implemented" blocks, and remove the *Implementation gap* section.

## Invariants

These are the binding rules the eventual implementation must satisfy:

- **DreamCycle never preempts an active Arc run.** The PROPEL loop owns
  the user-visible critical path; consolidation runs on idle capacity.
- **Consolidation is append-only at the storage layer.** New
  consolidated entries are written; originals are not mutated. This
  preserves the [`MemoryProvenance`](memory-provenance.md) audit
  guarantees.
- **Consolidation events are traceable.** Each consolidation cycle
  carries its own `run_id` analogous to an Arc run, so
  `ArcTraceProjection` can show what was consolidated, when, and why.
- **No PII or secrets exit the consolidation boundary.** Consolidation
  happens within the same trust domain as the original Knowledge writes;
  it does not call external APIs to summarise.

## Common operations

*Not yet applicable.* Will document trigger / pause / inspect operations
once the implementation lands.

## Anti-patterns

- **Implementing DreamCycle inline inside the PROPEL Loop phase** — the
  whole point of the separation is to keep PROPEL fast. Consolidation
  belongs in its own loop with its own scheduling and its own observable
  signals.
- **Mutating original knowledge entries during consolidation** — breaks
  the append-only memory invariant and erases the audit trail of what
  was originally learned.
- **Treating "no DreamCycle yet" as license to let `KnowledgeRepository`
  grow without bound** — interim mitigation lives in tag/recency
  weighting at recall time. Don't paper over the missing primitive by
  adding consolidation logic to recall paths.
