---
concept: SparkSystem
status: stable
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/cognition/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/SparkAppliedEvent.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/SparkRemovedEvent.kt
  - ampere-core/src/commonMain/composeResources/files/sparks/**
related: [PropelLoop, CognitiveRelay, PluginPermissions, CognitionTrace]
last_verified: 2026-05-17
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
  the Spark permits, intersected across the stack.
- **File access narrowing** — optional `FileAccessScope` the Spark permits.

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
- `agents/domain/event/SparkAppliedEvent.kt`, `SparkRemovedEvent.kt` — observability.

## Invariants

- **Sparks can only narrow, never expand.** A Spark may set `allowedTools` to a strict subset of the parent context; setting a wider set than the parent is a violation and breaks the recursive safety guarantee.
- **The system prompt is rebuilt from the live stack on every LLM call.** No caching of the rendered prompt is allowed unless invalidated on every push/pop. Stale prompts cause the active Spark stack to drift from observed prompt content.
- **Apply/remove are paired and observed.** Every `SparkAppliedEvent` has a matching `SparkRemovedEvent` (or end-of-run cleanup). `ArcTraceProjection` uses these events to reconstruct phase context.
- **Tool-set composition is intersection.** When two Sparks both specify `allowedTools`, the effective set is `A ∩ B`, not `A ∪ B`. A change that switches to union is a permission expansion and violates the narrowing invariant.
- **PhaseSparks add context only.** They do not narrow tools (`allowedTools = null`) or file access (`fileAccessScope = null`). Their job is prompt augmentation, not capability gating.
- **Spark `name` follows `Type:Subtype`.** `Role:Code`, `Phase:Perceive`, `Project:ampere`, `PhaseSpark:cooking-domain`. The trace projection extracts subtype from this prefix; ad-hoc names break trace bucketing. Declarative sparks use `PhaseSpark:<id>` so trace bucketing that keys on `Phase:` still treats built-in phases distinctly.
- **Sparks are pure data.** No Kotlin lambdas on the `Spark` interface — behavioral guidance is expressed as markdown in `promptContribution` / `phaseContributions`, interpreted by the LLM. This is what makes a `.spark.md` file a complete, shareable unit of customization.
- **Composition is additive, not exclusive.** When `N` sparks are on the stack, `buildSystemPrompt` includes contributions from all `N` — not "the topmost wins". `effectiveAgentRole` concatenates fragments with `" + "` (e.g. `Code Writer + Cooking Domain`). `effectiveRequestedTools` unions.

## Common operations

- **Add a new Spark type** — implement `Spark` (or extend an existing sealed family like `PhaseSpark`), define `name`, `promptContribution`, optionally `allowedTools` / `fileAccessScope` / `phaseContributions` / `agentRole` / `requestedToolIds`, mark it `@Serializable` with a stable `@SerialName`.
- **Author a declarative phase spark** — write a `.spark.md` file under `composeResources/files/sparks/` with a `---json` / `---` frontmatter block of type `"phase"` (id, name, whenToUse required) and a markdown body, optionally with `## When <Phase>` sections for phase-specific guidance. Add the path to `DefaultPhaseSparkLibrary.DEFAULT_SPARKS`.
- **Author a declarative role spark** — same path, `---json` / `---` frontmatter block of type `"role"` (id, name, agentRole required; `allowedTools` / `fileAccessScope` optional for narrowing). Body is the role's `promptContribution` verbatim — do not use `## When <Phase>` headers, they will not be extracted. Add the path to `DefaultPhaseSparkLibrary.DEFAULT_SPARKS`. Factory call sites resolve it via `SparkRegistry.roleSparkById(id)`.
- **Author a declarative language spark** — use type `"language"` with optional `fileAccessScope`; body text outside `## When <Phase>` headers is always-on guidance, and matching phase sections become `phaseContributions`. Resolve through `SparkRegistry.languageSparkById(id)`.
- **Author a declarative project spark** — use type `"project"`, put `## Project Description` and `## Project Conventions` in the body, and use `${env:VAR:-fallback}` for dynamic fields such as `repositoryRoot`. Resolve through `SparkRegistry.projectSparkById(id)`.
- **Apply a Spark transiently** — `SparkStack.push(spark)` and ensure a matching `pop` in `finally`. `PhaseSparkManager` handles this for phase boundaries.
- **Compose a per-agent stack** — declarative role spark + `ProjectSpark` at agent construction, then `PhaseSpark` pushed/popped per phase (potentially multiple when declarative library is active), then `TaskSpark` pushed/popped per task.
- **Inspect the active stack** — subscribe to `SparkAppliedEvent` / `SparkRemovedEvent` on the bus, or read `SparkStack.current`.
- **Enable phase sparks** — set `AgentConfiguration.cognitiveConfig.phaseSparks.enabled = true` (optionally per-phase) or `AMPERE_PHASE_SPARKS=true` globally.
- **Enable declarative phase sparks** — set `AmpereSpikeFlags.declarativeSparksEnabled = true` and inject a `PhaseSparkLibrary` into the agent (via `SparkBasedAgent.setPhaseSparkLibrary` or `PhaseSparkManager.createWithLibrary`). Default is off; flip in `try { ... } finally { ... = false }` blocks in tests.

## Anti-patterns

- **Subclassing `Agent` per role.** Every `class CodingAgent : Agent` you add fights the model. Use a declarative role spark instead — same effect, composable, observable.
- **Caching the rendered prompt across calls.** The Spark stack is the source of truth; the prompt is its projection. Caching breaks the invariant that observed prompt = stack content.
- **Mutable tool sets that expand mid-run.** A `TaskSpark` that "unlocks" extra tools after some condition violates the narrowing invariant. If you need conditional tools, push a different Spark.
- **Skipping `SparkRemovedEvent` because "the run is ending anyway".** The trace doesn't know that. Always pair apply/remove; let the projector decide what's noise.
- **`Spark.name` without `Type:Subtype`.** Trace projection strips the prefix to bucket events; an ad-hoc name like `"my-experiment"` will not be grouped with the rest of its kind.
- **Using `PhaseSpark` to narrow tools.** Phase sparks are advisory prompt content, not gates. Capability narrowing belongs in role, language, project, or task sparks.
