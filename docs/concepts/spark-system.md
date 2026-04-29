---
concept: SparkSystem
status: stable
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/cognition/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/SparkAppliedEvent.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/SparkRemovedEvent.kt
related: [PropelLoop, CognitiveRelay, PluginPermissions, CognitionTrace]
last_verified: 2026-04-29
---

# Spark System

## What it is

A `Spark` is a specialization layer that narrows an agent's cognitive
focus. Rather than separate agent classes for each role / project / task,
AMPERE accumulates specialization on a single agent through a stack of
Sparks. Each Spark contributes:

- **Prompt content** — markdown appended to the system prompt.
- **Tool narrowing** — an optional `allowedTools: Set<ToolId>` the Spark permits.
- **File access narrowing** — an optional `FileAccessScope` the Spark permits.

Concrete subtypes include `RoleSpark`, `ProjectSpark`, `TaskSpark`,
`LanguageSpark`, `CoordinationSpark`, and `PhaseSpark`. The `SparkStack`
composes them in order; the system prompt is rebuilt from the live stack
before each LLM interaction. Applying or removing a Spark emits
`SparkAppliedEvent` / `SparkRemovedEvent` so the trace can show *exactly*
what specialization was active at any point in a run.

## Why it exists

Three properties motivate the cellular-differentiation model over discrete
agent types:

1. **Specialization is multi-axis.** A real task is "Kotlin code work, on
   the AMPERE project, in the planning phase, coordinating with another
   agent". One class per combination is a combinatorial explosion. Sparks
   are independent dimensions.
2. **Narrowing must compose.** A `RoleSpark` constrains tools to coding
   tools; a `TaskSpark` further narrows to test-related tools; the
   intersection is the agent's effective tool set. Inheritance hierarchies
   can't express this without diamonds.
3. **Sparks are transient.** Phase-specific guidance (`PhaseSpark`) is
   applied on phase entry and removed on phase exit. The prompt rebuild on
   each LLM call makes this cheap; baking phase guidance into agent class
   identity would not.

The architectural invariant — *Sparks can only narrow, never expand* — is
the safety property that makes this composable. A child Spark can never
exceed parent permissions, so adding a Spark is monotone safe.

## Where it lives

- `agents/domain/cognition/Spark.kt` — the interface; not sealed (subpackages need to extend).
- `agents/domain/cognition/SparkStack.kt` — composition; intersection for tools, intersection-then-union semantics for file access.
- `agents/domain/cognition/FileAccessScope.kt` — read/write/forbidden patterns.
- `agents/domain/cognition/CognitiveAffinity.kt` — Spark selection signals.
- `agents/domain/cognition/sparks/RoleSpark.kt` — role specialization (Code, PM, Reviewer, …).
- `agents/domain/cognition/sparks/ProjectSpark.kt`, `AmpereProjectSpark.kt` — project-level context.
- `agents/domain/cognition/sparks/TaskSpark.kt` — task-shaped narrowing.
- `agents/domain/cognition/sparks/LanguageSpark.kt` — language-specific guidance.
- `agents/domain/cognition/sparks/CoordinationSpark.kt` — multi-agent coordination context.
- `agents/domain/cognition/sparks/PhaseSpark.kt` + `PhaseSparkManager.kt` — `PERCEIVE | PLAN | EXECUTE | LEARN`.
- `agents/domain/event/SparkAppliedEvent.kt`, `SparkRemovedEvent.kt` — observability.

## Invariants

- **Sparks can only narrow, never expand.** A Spark may set `allowedTools` to a strict subset of the parent context; setting a wider set than the parent is a violation and breaks the recursive safety guarantee.
- **The system prompt is rebuilt from the live stack on every LLM call.** No caching of the rendered prompt is allowed unless invalidated on every push/pop. Stale prompts cause the active Spark stack to drift from observed prompt content.
- **Apply/remove are paired and observed.** Every `SparkAppliedEvent` has a matching `SparkRemovedEvent` (or end-of-run cleanup). `ArcTraceProjection` uses these events to reconstruct phase context.
- **Tool-set composition is intersection.** When two Sparks both specify `allowedTools`, the effective set is `A ∩ B`, not `A ∪ B`. A change that switches to union is a permission expansion and violates the narrowing invariant.
- **PhaseSparks add context only.** They do not narrow tools (`allowedTools = null`) or file access (`fileAccessScope = null`). Their job is prompt augmentation, not capability gating.
- **Spark `name` follows `Type:Subtype`.** `Role:Code`, `Phase:Perceive`, `Project:ampere`. The trace projection extracts subtype from this prefix; ad-hoc names break trace bucketing.

## Common operations

- **Add a new Spark type** — implement `Spark` (or extend an existing sealed family like `PhaseSpark`), define `name`, `promptContribution`, optionally `allowedTools` / `fileAccessScope`, mark it `@Serializable` with a stable `@SerialName`.
- **Apply a Spark transiently** — `SparkStack.push(spark)` and ensure a matching `pop` in `finally`. `PhaseSparkManager` handles this for phase boundaries.
- **Compose a per-agent stack** — `RoleSpark` + `ProjectSpark` at agent construction, then `PhaseSpark` pushed/popped per phase, then `TaskSpark` pushed/popped per task.
- **Inspect the active stack** — subscribe to `SparkAppliedEvent` / `SparkRemovedEvent` on the bus, or read `SparkStack.current`.
- **Enable phase sparks** — set `AgentConfiguration.cognitiveConfig.phaseSparks.enabled = true` (optionally per-phase) or `AMPERE_PHASE_SPARKS=true` globally.

## Anti-patterns

- **Subclassing `Agent` per role.** Every `class CodingAgent : Agent` you add fights the model. Use a `RoleSpark` instead — same effect, composable, observable.
- **Caching the rendered prompt across calls.** The Spark stack is the source of truth; the prompt is its projection. Caching breaks the invariant that observed prompt = stack content.
- **Mutable tool sets that expand mid-run.** A `TaskSpark` that "unlocks" extra tools after some condition violates the narrowing invariant. If you need conditional tools, push a different Spark.
- **Skipping `SparkRemovedEvent` because "the run is ending anyway".** The trace doesn't know that. Always pair apply/remove; let the projector decide what's noise.
- **`Spark.name` without `Type:Subtype`.** Trace projection strips the prefix to bucket events; an ad-hoc name like `"my-experiment"` will not be grouped with the rest of its kind.
- **Using `PhaseSpark` to narrow tools.** Phase sparks are advisory prompt content, not gates. Capability narrowing belongs in `RoleSpark` / `TaskSpark`.
