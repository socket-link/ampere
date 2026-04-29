---
concept: CoordinatorDigestStep
status: experimental
tracked_sources: []  # experimental — no implementation yet
related: [PropelLoop, EventSerialBus, MemoryProvenance]
last_verified: 2026-04-29
---

# CoordinatorDigestStep

## What it is

CoordinatorDigestStep is the planned anti-lazy-delegation primitive: a
mandatory step that a coordinator agent must complete *before* delegating
work to a sub-agent. The coordinator digests the available context —
ticket, recent outcomes, recalled knowledge, current sub-agent state —
into a structured summary, and that digest becomes the brief handed to
the sub-agent. Without the digest, the coordinator cannot delegate; it
must either do the work itself or surface that it cannot proceed.

## Why it exists

A common failure mode in multi-agent systems is the *lazy coordinator*:
an orchestrator that re-delegates a task with the same prompt it
received, having added no value, multiplying token cost and creating a
chain of agents who all assume someone else has thought about the
problem. The pattern is invisible in raw event logs (each delegation
looks reasonable), but the trace shows N agents and zero distillation.

The digest step is a structural fix:

1. **Forces the coordinator to add value.** The digest is concrete output
   the coordinator owns. If it can't produce one, it can't delegate.
2. **Gives the sub-agent traceable provenance.** The digest is what the
   sub-agent saw — not the original prompt. Replay shows exactly what
   the coordinator passed down.
3. **Makes coordination cost legible.** Coordinator runs that produce
   thin digests get visibly thin in the trace; reviewers can spot the
   lazy pattern instead of just seeing "two model calls".

## Where it lives

- *Not yet implemented.* Searches for `CoordinatorDigest*`, `digest`,
  `delegation*`, and similar return no matching primitives in the
  current codebase as of `last_verified`.

## Implementation gap

| Target shape | Current state |
|---|---|
| A typed `CoordinatorDigest` record (problem statement, acceptance criteria, recalled context summary, decisions taken, hand-off contract). | Not defined. |
| A coordinator-facing API that requires producing a digest before invoking sub-agent dispatch. | Not enforced; coordinators in the current codebase delegate via direct task hand-off without an explicit digest contract. |
| `CoordinatorDigestEvent` so the trace can show the digest each delegation produced. | Not defined. |
| Structural test: a coordinator that delegates without first producing a digest fails CI. | Not enforced. |
| Integration with `KnowledgeExtractor` so digests become `Knowledge.FromPlan` entries when the run closes. | Not designed. |

This file exists so the concept index acknowledges the planned primitive
and reserves its name. When implementation lands, update this file in
the same diff: bump `status`, populate `tracked_sources`, replace the
gap section, and verify the *Invariants* below survived the
implementation choices.

## Invariants

These are the binding rules the eventual implementation must satisfy:

- **No delegation without a digest.** A coordinator that hands work to a
  sub-agent without producing a `CoordinatorDigest` first is in
  violation. Enforced by the dispatch path, not by convention.
- **Digests are persisted and recallable.** Each digest is written
  through `KnowledgeRepository` (likely as `Knowledge.FromPlan`) so
  future Recall can surface "how did the coordinator brief the sub-agent
  last time on a similar task?".
- **Digests are emitted as events.** `CoordinatorDigestEvent` carries the
  full digest so `ArcTraceProjection` can surface it as part of the
  coordinator's phase output.
- **Digests are structured, not free-form prose.** Free text is exactly
  the lazy pattern this primitive prevents. The digest schema is
  enforced.

## Common operations

*Not yet applicable.* Will document compose / inspect / extend operations
once the implementation lands.

## Anti-patterns

- **Skipping the digest "because the sub-agent will figure it out"** —
  this is the lazy delegation pattern itself, exactly what the
  primitive exists to prevent.
- **Free-form prose digests** — defeats the structural enforcement. The
  schema is the point.
- **Re-using the original ticket description as the digest** — adds zero
  coordinator value; should fail the digest contract.
- **Implementing this as a soft convention rather than a dispatch-time
  check** — conventions decay; structural gates do not.
