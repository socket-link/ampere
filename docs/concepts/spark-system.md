---
concept: SparkSystem
status: stable
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/cognition/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/SparkAppliedEvent.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/SparkRemovedEvent.kt
  - ampere-core/src/commonMain/composeResources/files/sparks/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/definition/AutonomousAgent.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/definition/SparkBasedAgent.kt
related: [PropelLoop, CognitiveRelay, PlugPermissions, CognitionTrace]
last_verified: 2026-10-09
---

> **2026-05-17 (AMPR-165):** Declarative `.spark.md` documents now use JSON
> frontmatter (`---json` / `---`) decoded via a sealed [`SparkFrontmatter`]
> family with `"type"` discriminator. The legacy `---` YAML form is rejected
> at parse time with `SparkParseError.DeprecatedYamlFrontmatter`. The schema
> supports four variants today (`"phase"`, `"role"`, `"language"`, and
> `"project"`). Bundled role fixtures now cover Code, Research, Operations,
> and Planning; language fixtures cover Kotlin, Java, TypeScript, and Python;
> `project-ampere.spark.md` supplies the canonical Ampere project context
> with env-var interpolation for `repositoryRoot`. The old `RoleSpark` Kotlin
> singleton hierarchy has been removed.

> **2026-10-09 (AMPR-387):** `PhaseSparkConfig` on `AgentConfiguration.cognitiveConfig`
> now actually reaches `PhaseSparkManager`: `SparkBasedAgent` builds its
> `AgentConfiguration` with the `cognitiveConfig` its factory was given, and
> `AgentFactory` / `SparkAgentFactory` / the `SparkBasedAgent.<Role>(...)` factories
> all pass one through. The single `enabled` flag also split: `publishBrackets`
> governs the `PhaseEntered` / `PhaseExited` pair and `injectPhaseSparks` governs
> everything that reaches the prompt, so a run can be bracketed silently. Both
> default to `true`, so `enabled = true` alone is unchanged.

# Spark System

## What it is

A `Spark` is a specialization layer that narrows an agent's cognitive
focus. Rather than separate agent classes for each role / project / task,
AMPERE accumulates specialization on a single agent through a stack of
Sparks. Each Spark contributes pure data — no Kotlin lambdas — so Sparks
remain shareable as `.spark.md` artifacts:

- **Always-on prompt content** — markdown via `promptContribution`.
- **Per-phase prompt sections** — `phaseContributions: Map<CognitivePhase, String>`
  whose entry for the current phase is appended after the base contribution.
- **Role label fragment** — optional `agentRole` (e.g. `"Cooking Domain"`)
  concatenated across the stack into the agent's effective role.
- **Tool requests (additive)** — `requestedToolIds: Set<ToolId>`, unioned
  across the stack; declares which tools this Spark needs.
- **Tool narrowing (subtractive)** — optional `allowedTools: Set<ToolId>`
  the Spark permits, intersected across the stack. Enforced since AMPR-400:
  `AutonomousAgent.effectiveTools` is `requiredTools` narrowed by the stack,
  and it is both what the planner is offered and what plan-step dispatch
  looks up.
- **File access narrowing** — optional `FileAccessScope` the Spark permits.
  Computed (`AutonomousAgent.effectiveFileAccess`) but **not yet enforced** —
  nothing reads it at a file-touching tool. See *Anti-patterns*.

Concrete subtypes include `ProjectSpark`, `TaskSpark`, `LanguageSpark`,
`CoordinationSpark`, `PhaseSpark`, `DeclarativePhaseSpark`, and
`DeclarativeRoleSpark` (loaded from bundled `.spark.md` files). The `SparkStack`
composes them in order; the system prompt is rebuilt from the live stack
before each LLM interaction, parameterized by the agent's current
`CognitivePhase` so per-phase guidance flows through. Applying or removing
a Spark emits `SparkAppliedEvent` / `SparkRemovedEvent` so the trace can
show *exactly* what specialization was active at any point in a run.

### Declarative Sparks

Sparks are parsed from `.spark.md` files under
`composeResources/files/sparks/`. Each document opens with a JSON
frontmatter block fenced by `---json` / `---`, decoded against the sealed
`SparkFrontmatter` family via a `Json { classDiscriminator = "type";
ignoreUnknownKeys = false; encodeDefaults = true }` configuration. Unknown
keys fail parsing by intent: spark frontmatter is capability-bearing, and a
misspelled `requestedToolIds` must not silently ship without its tools.

Four variants exist today, addressed by the `"type"` discriminator:

- **`"phase"`** (`PhaseSparkFrontmatter`) — prompt-only guidance. The body
  may contain `## When Perceiving / Planning / Executing / Learning`
  sections, which the parser extracts into `phaseContributions`; text
  outside those headers becomes the base `promptContribution`. Fields:
  `id`, `name`, `whenToUse`, `phases`, `tags`, `agentRole`,
  `requestedToolIds`, `modelPreference`.
- **`"role"`** (`RoleSparkFrontmatter`) — capability-bearing. The body is
  preserved verbatim as `promptContribution` (no per-phase section
  extraction; role guidance applies uniformly across phases). Fields:
  `id`, `name`, `agentRole`, `requestedToolIds`, `allowedTools`,
  `fileAccessScope`.
- **`"language"`** (`LanguageSparkFrontmatter`) — capability-bearing file
  narrowing plus language guidance. The parser extracts `## When <Phase>`
  sections into `phaseContributions`, like phase sparks, so language guidance
  can contribute planning/execution notes. Fields: `id`, `name`,
  `requestedToolIds`, `allowedTools`, `fileAccessScope`.
- **`"project"`** (`ProjectSparkFrontmatter`) — project context adapted into
  a runtime `ProjectSpark`. The body must contain `## Project Description`
  and `## Project Conventions` sections. `repositoryRoot` supports
  `${env:VAR:-fallback}` interpolation, including nested fallbacks such as
  `${env:AMPERE_ROOT:-${env:PWD:-.}}`.

`FileAccessScopeFrontmatter` supports `forbiddenRefs`; the only reference
today is `"sensitive-files"`, which expands to
`FileAccessScope.SensitiveFileForbiddenPatterns`. Role fixtures use this so
the central sensitive-file list is not duplicated across fixtures.

Documents that still open with the bare `---` YAML fence are rejected with
`SparkParseError.DeprecatedYamlFrontmatter`. The legacy YAML parser was
removed in AMPR-165 Wave 2.

`DefaultPhaseSparkLibrary` loads the bundled fixtures at construction
time, dispatching each parse result by variant: `Phase` becomes a
`DeclarativePhaseSpark`, `Role` becomes a `DeclarativeRoleSpark`,
`Language` becomes a `LanguageSpark`, and `Project` becomes a
`ProjectSpark`.
`PhaseSparkLibrary.selectFor(SparkSelectionContext)` filters phase sparks
by eligibility, tag intersection, and keyword match against `whenToUse`;
`SparkRegistry.roleSparkById(id)`, `languageSparkById(id)`, and
`projectSparkById(id)` resolve capability-bearing sparks by canonical id.
`PhaseSparkManager` consults the phase surface when
`AmpereSpikeFlags.declarativeSparksEnabled` is on; the `SparkBasedAgent`
role factories consult the role surface at construction time and fail fast
if the bundled role fixture is missing.
When an `EventSerialBus` is provided, `PhaseSparkManager` also emits
`CognitivePhaseEvent.PhaseEntered` / `PhaseExited` at phase boundaries so
phase changes are observable even when consumers do not inspect Spark stack
events.

## Why it exists

Three properties motivate the cellular-differentiation model over discrete
agent types:

1. **Specialization is multi-axis.** A real task is "Kotlin code work, on
   the AMPERE project, in the planning phase, coordinating with another
   agent". One class per combination is a combinatorial explosion. Sparks
   are independent dimensions.
2. **Narrowing must compose.** A role spark constrains tools to coding
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
- `agents/domain/cognition/SparkStack.kt` — composition; `buildSystemPrompt(phase)` concatenates every spark's contribution plus its per-phase section; `effectiveAgentRole()` concatenates role fragments; `effectiveRequestedTools()` unions; `effectiveAllowedTools()` intersects; intersection-then-union semantics for file access.
- `agents/domain/cognition/FileAccessScope.kt` — read/write/forbidden patterns.
- `agents/domain/cognition/CognitiveAffinity.kt` — Spark selection signals.
- `agents/domain/cognition/sparks/ProjectSpark.kt`, `AmpereProjectSpark.kt` — project-level context; `ProjectSpark.kt` also adapts `"project"` fixtures and resolves env-var interpolation.
- `agents/domain/cognition/sparks/TaskSpark.kt` — task-shaped narrowing.
- `agents/domain/cognition/sparks/LanguageSpark.kt` — language-specific guidance adapted from `"language"` fixtures.
- `agents/domain/cognition/sparks/CoordinationSpark.kt` — multi-agent coordination context.
- `agents/domain/cognition/sparks/PhaseSpark.kt` + `PhaseSparkManager.kt` — `PERCEIVE | RECALL | OBSERVE | PLAN | EXECUTE | LEARN`; manager applies built-in + selected declarative sparks as a list, pops in reverse on phase exit.
- `agents/domain/cognition/sparks/DeclarativePhaseSpark.kt` — markdown-authored `PhaseSpark` with `eligiblePhases` + per-phase `phaseContributions`.
- `agents/domain/cognition/sparks/DeclarativeRoleSpark.kt` — markdown-authored role spark; capability-bearing (`allowedTools`, `fileAccessScope`).
- `agents/domain/cognition/sparks/DeclarativeSparkSource.kt` — sealed parser output: `Phase` / `Role` / `Language` / `Project`.
- `agents/domain/cognition/sparks/SparkFrontmatter.kt` — sealed `@Serializable` frontmatter schema with `"phase"`, `"role"`, `"language"`, and `"project"` variants.
- `agents/domain/cognition/sparks/SparkParser.kt` — JSON-fenced (`---json` / `---`) parser; extracts `## When <Phase>` sections for phase and language variants.
- `agents/domain/cognition/sparks/SparkRegistry.kt` — public role/language/project lookup consumed by factories and default spark helpers.
- `agents/domain/cognition/sparks/DefaultSparkCatalog.kt`, `DeclarativeSparkIds.kt` — synchronous bundled registry access for legacy non-suspend factory paths plus canonical ids.
- `agents/domain/cognition/sparks/PhaseSparkLibrary.kt`, `DefaultPhaseSparkLibrary.kt` — read-only catalog with deterministic `selectFor` ordering; extends `SparkRegistry`.
- `agents/domain/cognition/sparks/AmpereSpikeFlags.kt` — `declarativeSparksEnabled: Boolean = false`; gates declarative spark application.
- `composeResources/files/sparks/*.spark.md` — bundled declarative spark fixtures.
- `agents/definition/AutonomousAgent.kt` — `availableTools` (the permitted *ids*, null when unconstrained) and `effectiveTools` (`requiredTools` ∩ permitted ids: the tools the agent may actually act with).
- `agents/definition/SparkBasedAgent.kt` — the two enforcement sites: the reasoning unit is built with `availableTools = { effectiveTools }`, and `executePlanStep` resolves a step's `toolId` against `effectiveTools`.
- `agents/domain/event/SparkAppliedEvent.kt`, `SparkRemovedEvent.kt` — observability.
- `agents/domain/event/CognitivePhaseEvent.kt` — first-class phase boundary events.

## Invariants

- **Sparks can only narrow, never expand.** A Spark may set `allowedTools` to a strict subset of the parent context; setting a wider set than the parent is a violation and breaks the recursive safety guarantee.
- **The system prompt is rebuilt from the live stack on every LLM call.** No caching of the rendered prompt is allowed unless invalidated on every push/pop. Stale prompts cause the active Spark stack to drift from observed prompt content.
- **Apply/remove are paired and observed.** Every `SparkAppliedEvent` has a matching `SparkRemovedEvent` (or end-of-run cleanup). `ArcTraceProjection` uses these events to reconstruct phase context.
- **Phase boundaries are explicit when a bus is wired.** `PhaseSparkManager` publishes `PhaseEntered` after `currentCognitivePhase` is assigned and before phase sparks are applied, and publishes `PhaseExited` after phase sparks are removed and the previous phase is restored.
- **Bracketing and prompt injection are separate switches.** `PhaseSparkConfig.publishBrackets` and `injectPhaseSparks` sit under `enabled` and are independent. `injectPhaseSparks = false` must leave the system prompt byte-for-byte what it would be with phases off — which is why `agent.currentCognitivePhase` is written only when that switch is on: it is read by exactly one thing, `buildSystemPrompt`, so leaving it set would still pull every role/language spark's `## When <Phase>` section into a supposedly silent run's prompt.
- **A phase is active when `currentPhase != null`, not when sparks are on the stack.** The two were equivalent before AMPR-387 and are not now: a bracketed-but-not-injected phase pushes nothing. Any guard written as `appliedSparks.isNotEmpty()` silently disables `injectPhaseSparks = false`.
- **`AMPERE_PHASE_SPARKS` is an override, not a default.** Set, it forces `enabled`, `publishBrackets`, and `injectPhaseSparks` on regardless of config; unset, it contributes nothing and the config decides alone. It is a developer switch — never the mechanism a consumer relies on.
- **Narrowing is read, not just computed.** Every site that offers or dispatches tools reads `effectiveTools`, never `requiredTools`. There are two — the planner's available-tools list and `executePlanStep`'s lookup — and they share one set, so a tool the stack withdrew fails identically to one that never existed. A narrowing that nothing reads is decoration; that was the AMPR-400 bug. (One tool-advertising path is still unnarrowed: `AutonomousAgent.buildToolAwarenessIdea` lists every `ToolRegistry` tool to Perceive. Those tools are outside `requiredTools`, so they are already undispatchable by `executePlanStep` — the leak is that the model is told about them at all.)
- **The narrowed set is read live, never captured.** The stack is mutable for the agent's lifetime, so `ReasoningSettings.availableTools` is a `() -> Set<Tool<*>>` provider rather than a set. A snapshot taken when the reasoning unit was constructed would keep offering tools a later spark has since withdrawn.
- **A capability-bearing spark's `allowedTools` is a real permission, so it must name real tool ids.** Because composition is intersection, an id that no tool in the repo carries contributes nothing, and a *missing* id silently withdraws a tool the agent was deliberately built with. A role spark must list every tool its factory hands the agent, `plan_steps` included.
- **Tool-set composition is intersection.** When two Sparks both specify `allowedTools`, the effective set is `A ∩ B`, not `A ∪ B`. A change that switches to union is a permission expansion and violates the narrowing invariant.
- **PhaseSparks add context only.** They do not narrow tools (`allowedTools = null`) or file access (`fileAccessScope = null`). Their job is prompt augmentation, not capability gating.
- **Spark `name` follows `Type:Subtype`.** `Role:Code`, `Phase:Perceive`, `Project:ampere`, `PhaseSpark:cooking-domain`. The trace projection extracts subtype from this prefix; ad-hoc names break trace bucketing. Declarative sparks use `PhaseSpark:<id>` so trace bucketing that keys on `Phase:` still treats built-in phases distinctly.
- **Sparks are pure data.** No Kotlin lambdas on the `Spark` interface — behavioral guidance is expressed as markdown in `promptContribution` / `phaseContributions`, interpreted by the LLM. This is what makes a `.spark.md` file a complete, shareable unit of customization.
- **Composition is additive, not exclusive.** When `N` sparks are on the stack, `buildSystemPrompt` includes contributions from all `N` — not "the topmost wins". `effectiveAgentRole` concatenates fragments with `" + "` (e.g. `Code Writer + Cooking Domain`). `effectiveRequestedTools` unions.

## Common operations

- **Add a new Spark type** — implement `Spark` (or extend an existing sealed family like `PhaseSpark`), define `name`, `promptContribution`, optionally `allowedTools` / `fileAccessScope` / `phaseContributions` / `agentRole` / `requestedToolIds`, mark it `@Serializable` with a stable `@SerialName`.
- **Author a declarative phase spark** — write a `.spark.md` file under `composeResources/files/sparks/` with a `---json` / `---` frontmatter block of type `"phase"` (id, name, whenToUse required) and a markdown body, optionally with `## When <Phase>` sections for phase-specific guidance. Add the path to `DefaultPhaseSparkLibrary.DEFAULT_SPARKS`.
- **Author a declarative role spark** — same path, `---json` / `---` frontmatter block of type `"role"` (id, name, agentRole required; `allowedTools` / `fileAccessScope` optional for narrowing). If you supply `allowedTools`, check it against the ids the agent's factory actually passes (`grep -r '_TOOL_ID' ampere-core/src` plus the private ids in `execution/tools/git/GitTools.kt`) and include `plan_steps`, which every `SparkBasedAgent` ships with — omitting an id withdraws that tool at dispatch. Body is the role's `promptContribution` verbatim — do not use `## When <Phase>` headers, they will not be extracted. Add the path to `DefaultPhaseSparkLibrary.DEFAULT_SPARKS`. Factory call sites resolve it via `SparkRegistry.roleSparkById(id)`.
- **Author a declarative language spark** — use type `"language"` with optional `fileAccessScope`; body text outside `## When <Phase>` headers is always-on guidance, and matching phase sections become `phaseContributions`. Resolve through `SparkRegistry.languageSparkById(id)`.
- **Author a declarative project spark** — use type `"project"`, put `## Project Description` and `## Project Conventions` in the body, and use `${env:VAR:-fallback}` for dynamic fields such as `repositoryRoot`. Resolve through `SparkRegistry.projectSparkById(id)`.
- **Apply a Spark transiently** — `SparkStack.push(spark)` and ensure a matching `pop` in `finally`. `PhaseSparkManager` handles this for phase boundaries.
- **Compose a per-agent stack** — declarative role spark + `ProjectSpark` at agent construction, then `PhaseSpark` pushed/popped per phase (potentially multiple when declarative library is active), then `TaskSpark` pushed/popped per task.
- **Inspect the active stack** — subscribe to `SparkAppliedEvent` / `SparkRemovedEvent` on the bus, or read `SparkStack.current`.
- **Enable phase sparks** — set `AgentConfiguration.cognitiveConfig.phaseSparks.enabled = true` (optionally per-phase via `phases`), and hand that `CognitiveConfig` to whichever factory builds the agent (`AgentFactory`, `SparkAgentFactory`, or a `SparkBasedAgent.<Role>(...)` factory — all take `cognitiveConfig`). `AMPERE_PHASE_SPARKS=true` forces it on process-wide as a developer switch.
- **Bracket a run's phases without changing its prompt** — `PhaseSparkConfig(enabled = true, injectPhaseSparks = false)`. The agent publishes `PhaseEntered` / `PhaseExited` for every `withPhase` and its system prompt is unchanged. The mirror, `publishBrackets = false`, injects the guidance and publishes nothing.
- **Enable declarative phase sparks** — set `AmpereSpikeFlags.declarativeSparksEnabled = true` and inject a `PhaseSparkLibrary` into the agent (via `SparkBasedAgent.setPhaseSparkLibrary` or `PhaseSparkManager.createWithLibrary`). Default is off; flip in `try { ... } finally { ... = false }` blocks in tests.

## Anti-patterns

- **Subclassing `Agent` per role.** Every `class CodingAgent : Agent` you add fights the model. Use a declarative role spark instead — same effect, composable, observable.
- **Caching the rendered prompt across calls.** The Spark stack is the source of truth; the prompt is its projection. Caching breaks the invariant that observed prompt = stack content.
- **Mutable tool sets that expand mid-run.** A `TaskSpark` that "unlocks" extra tools after some condition violates the narrowing invariant. If you need conditional tools, push a different Spark.
- **Skipping `SparkRemovedEvent` because "the run is ending anyway".** The trace doesn't know that. Always pair apply/remove; let the projector decide what's noise.
- **`Spark.name` without `Type:Subtype`.** Trace projection strips the prefix to bucket events; an ad-hoc name like `"my-experiment"` will not be grouped with the rest of its kind.
- **Reading `requiredTools` at a planning or dispatch site.** It is the set the agent was *built* with, not the set it may *use*. `effectiveTools` is the only correct answer to "which tools does this agent have"; `requiredTools` is the input to it.
- **Assuming `fileAccessScope` gates anything.** It does not. `effectiveFileAccess` is computed and no file-touching tool consults it, and as composed today it would deny nearly everything if one did: `FileAccessScope.intersect` is literal set intersection over glob *strings*, so `{"**/*"} ∩ {"**/*.kt"}` is empty rather than `{"**/*.kt"}`. Enforcing it needs a glob matcher and subsumption-aware composition first.
- **Using `PhaseSpark` to narrow tools.** Phase sparks are advisory prompt content, not gates. Capability narrowing belongs in role, language, project, or task sparks.
- **Building an `AgentConfiguration` without the `cognitiveConfig` you were handed.** This was the AMPR-387 bug exactly: `SparkBasedAgent.agentConfiguration` constructed `AgentConfiguration(...)` without one, so the default (`phaseSparks.enabled = false`) won and the documented config path did nothing for two releases while `AgentFactory(cognitiveConfig = …)` fed a private getter nothing read. A config parameter that is accepted and dropped reads as supported.
- **Treating `currentCognitivePhase` as observability.** It is prompt state — `buildSystemPrompt` is its only reader. Subscribe to `CognitivePhaseEvent` to know what phase an agent is in; `getCurrentPhase()` on the manager is the in-process equivalent.
