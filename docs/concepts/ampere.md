---
concept: Ampere
status: stable
tracked_sources:
  - SOUL.md
  - AGENTS.md
  - docs/CORE_CONCEPTS.md
  - docs/AGENT_LIFECYCLE.md
  - docs/ARCS.md
related: [PropelLoop, EventSerialBus, CognitiveRelay, MemoryProvenance, SparkSystem, AgentSurface, PluginPermissions, CognitionTrace]
last_verified: 2026-04-29
---

# Ampere — The Meta-Concept

## What it is

AMPERE is the *AMP Example Runtime Environment*: a Kotlin Multiplatform
implementation of the AniMA Model Protocol. Calling a framework "an AMPERE
framework" means it satisfies a specific cluster of architectural choices,
not just "a multi-agent system in Kotlin". This concept is the index of
those choices — the load-bearing self-description that distinguishes
AMPERE from a generic agent framework that happens to live in this repo.

The AMPERE choice cluster:

1. **Glass brain.** Cognition is visible by design. Every decision emits
   structured signal. See [`SOUL.md`](../../SOUL.md).
2. **Animated agents (AniMA).** Agents carry persistent identity,
   environmental awareness, social dynamics, and experiential learning —
   not stateless functions.
3. **Electrical metaphor, taken literally.** Events are neurotransmitters,
   the bus is a nervous system, knowledge is semantic memory, outcomes
   are episodic memory. The metaphor is not decoration; it is the
   architecture.
4. **PROPEL loop.** Perceive → Recall → Optimize → Plan → Execute → Loop.
   Recall before Plan. Loop closes through Knowledge.
5. **Coordination through convergence, not RPC.** Agents publish typed
   events and react; they do not call each other.
6. **Provider-agnostic cognition.** Cognitive code never imports a
   provider SDK. The relay handles routing.
7. **Read-model observability.** State is reconstructed from append-only
   stores via projections (`ArcTraceProjection`), not maintained as
   live mutable objects.
8. **Permissions enforced before LLM dispatch, not after.** The plugin
   permission gate is deterministic and runs ahead of the model.

A framework that satisfies these is an AMPERE framework. A framework that
ships a coordination layer based on direct method calls or bakes a
provider into cognitive code is *not* — it may share the codebase, but
the load-bearing architecture is different.

## Why it exists

Without an explicit meta-concept, the cluster of choices erodes one
"reasonable refactor" at a time:

- Someone inlines a "quick" provider call → cognition becomes
  provider-coupled.
- Someone adds an internal method-call path between agents → coordination
  becomes opaque.
- Someone pulls a long-lived session object onto an agent → state
  escapes the read model.

Each move feels local. Each erodes a property the rest of the system
depends on. Naming the cluster makes the erosion visible: the question is
not "is this change small?" but "does this change preserve the AMPERE
property cluster?".

## Where it lives

- `SOUL.md` — values and philosophy ("glass brain", "transparency in
  Ampere is not bolted on — it *is* the architecture").
- `AGENTS.md` — agent contract and operational rules.
- `docs/CORE_CONCEPTS.md` — AniMA / AMP / AMPERE / AAIF stack; the six
  primitives (Tickets, Tasks, Plans, Meetings, Outcomes, Knowledge).
- `docs/AGENT_LIFECYCLE.md` — the human-readable PROPEL narrative.
- `docs/ARCS.md` — orchestration patterns.
- This concept directory — invariants per primitive.

## Invariants

- **Cognition emits signal.** No interesting state change is silent. If a
  reviewer can't tell from the event/memory streams that something
  happened, the change has gone opaque and is a regression.
- **Agents coordinate via the bus.** No direct agent-to-agent method
  calls for coordination. (Internal helpers within one agent are fine;
  cross-agent calls are not.)
- **Cognition is provider-agnostic.** No provider SDK imports under
  `agents/domain/reasoning/` or `agents/domain/cognition/`.
- **State lives in append-only stores.** Read models project. In-memory
  state in long-lived objects is a smell, not a feature.
- **Recall before Plan.** The PROPEL ordering is fixed. See
  [`PropelLoop`](propel-loop.md).
- **Permissions are deterministic and pre-LLM.** See
  [`PluginPermissions`](plugin-permissions.md).
- **The AMPERE/AMP/AniMA layering is not optional.** Code that mixes
  layers (e.g., a "quick" provider import in a Spark) collapses the
  stack into provider-coupled goo.

## Common operations

- **Decide whether a change is "AMPERE-compliant"** — walk the invariant
  list above. If a change requires breaking one, it needs a discussion in
  the relevant concept doc *before* the diff lands.
- **Onboarding read** — `SOUL.md` → `AGENTS.md` → `docs/CORE_CONCEPTS.md`
  → this concept directory. The fastest path from zero to actually
  changing code without violating the cluster.
- **Adding a new primitive** — write its concept file first; let the
  invariants drive the implementation, not the reverse.

## Anti-patterns

- **"It's just a metaphor"** — said about the electrical / nervous system
  language, then a method call replaces an event "for performance",
  then the trace goes opaque, then a future agent has anterograde
  amnesia again. The metaphor *is* the architecture; treating it as
  decoration permits the erosion.
- **One-off provider import "for a quick test"** — there is no quick
  test. Every cognitive call belongs in the relay path.
- **Adding a sixth-level layer to the AAIF stack diagram in
  `docs/CORE_CONCEPTS.md`** without writing the concept file. New layers
  need invariants before pictures.
- **Treating `AGENTS.md` as soft documentation** — it is the operational
  contract. CI / agents read it. Updates land alongside the behaviour
  they describe.
- **Writing a new concept file without updating `_index.md`** — the index
  is what agents skim first. An unlinked concept might as well not exist.
