---
concept: SparkSystem
status: stable
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/cognition/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/execution/tools/ToolWriteCodeFile.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/execution/tools/ToolReadCodeFile.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/SparkEvent.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/CognitivePhaseEvent.kt
  - ampere-core/src/commonMain/composeResources/files/sparks/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/definition/AutonomousAgent.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/definition/SparkBasedAgent.kt
related: [PropelLoop, CognitiveRelay, PlugPermissions, CognitionTrace]
last_verified: 2026-10-10
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

> **2026-10-10 (AMPR-392):** The markdown-to-`Spark` path is public.
> `Spark.fromMarkdown(id, body, frontmatter, contributeRole)` returns a
> `MarkdownSpark`, and the internal `.spark.md` parser delegates its section
> split to it, so the `## When <Phase>` heading rules have one definition. Six
> headings are recognised now (`Recalling` and `Observing` joined the four the
> parser knew), a section ends at the next level-*two* heading, and any other
> `## When …` heading stays in the body. `SparkStack.buildSystemPrompt` takes an
> optional `preamble` rendered ahead of the cognitive-context header.

> **2026-10-10 (AMPR-405):** the four `SparkBasedAgent.<Role>(...)` factories take an
> `executor` (and an `outcomeRepository`), which is what finally lets `AgentFactory`
> hand its agents one. Narrowing is unchanged and still decides: a tool passed through
> `AgentFactory(additionalTools = …)` whose id no role spark's `allowedTools` admits
> stays undispatchable, executor or not. See [PropelLoop](propel-loop.md).

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
- **File access narrowing (subtractive)** — optional `FileAccessScope` the
  Spark permits: `readPatterns` / `writePatterns` intersected across the stack,
  `forbiddenPatterns` unioned. Enforced since AMPR-414:
  `AutonomousAgent.effectiveFileAccess` rides on every plan-step
  `ExecutionRequest`, and `write_code_file` / `read_code_file` refuse a path
  outside it before touching the filesystem.

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
  may contain any of the six `## When <Phase>` sections (see
  **The public markdown entry** below), which the parser extracts into
  `phaseContributions`; text outside those headers becomes the base
  `promptContribution`. Fields:
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
When an `AgentEventApi` door is provided, `PhaseSparkManager` also emits
`CognitivePhaseEvent.PhaseEntered` / `PhaseExited` at phase boundaries so
phase changes are observable even when consumers do not inspect Spark stack
events. `enabled` is the master gate — `enterPhaseInternal` and
`withPhaseInternal` both return early on `if (!enabled)`
(`PhaseSparkManager.kt:110, 161`), so with phase handling off a run emits no
phase events at all. Under it, `publishBrackets` and `injectPhaseSparks` are
independent (AMPR-387), so a run can be bracketed without its prompt changing.
The door is the agent's own, so the events name the holder; with no door wired,
phases still apply and nothing is published.

### The public markdown entry

`Spark.fromMarkdown(id, body, frontmatter = emptyMap(), contributeRole = false)`
is how a consumer with its own spark catalogue gets Ampere's rules instead of
re-deriving them. It returns a `MarkdownSpark`:

- `name` is the `id`. Pass one already shaped `Type:Subtype` — trace bucketing
  reads that prefix.
- `promptContribution` is the body with the phase sections lifted out, in source
  order.
- `phaseContributions` comes from exactly these six level-two headings, matched
  case-insensitively: `## When Perceiving`, `## When Recalling`,
  `## When Observing`, `## When Planning`, `## When Executing`,
  `## When Learning`. A section runs to the next level-two heading or the end of
  the body; a level-three heading (`### …`) is structure *inside* the open
  section and does not close it; any other level-two heading —
  `## When nothing matches` included — closes the section and stays in the body
  verbatim.
- `requestedToolIds` comes from a `tools` frontmatter key, comma- or
  whitespace-separated.
- `agentRole` comes from a `role` key only when `contributeRole = true`.
- `allowedTools` and `fileAccessScope` are always null. Prose cannot declare a
  permission; narrowing is frontmatter's job and the stack's to compose.

The frontmatter map is flat `Map<String, String>` on purpose: it is not the
typed `SparkFrontmatter` schema, and the public entry deliberately says less
than a bundled `.spark.md` can. `AmpereSpikeFlags` does not gate it.

`SparkStack.buildSystemPrompt(currentPhase, preamble)` renders a trimmed
`preamble` plus a `---` separator ahead of the cognitive-context header, so a
host that must speak first does not concatenate around the header and cannot
get the separators wrong. Null or blank leaves the prompt byte-for-byte what it
was.

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

- `agents/domain/cognition/Spark.kt` — the interface; not sealed (subpackages need to extend). Its companion carries `fromMarkdown`, the public markdown entry.
- `agents/domain/cognition/MarkdownSpark.kt` — the `Spark` a markdown body becomes, plus `splitMarkdownSparkBody`: the one definition of the `## When <Phase>` heading rules.
- `agents/domain/cognition/SparkStack.kt` — composition; `buildSystemPrompt(phase, preamble)` concatenates every spark's contribution plus its per-phase section; `effectiveAgentRole()` concatenates role fragments; `effectiveRequestedTools()` unions; `effectiveAllowedTools()` intersects; intersection-then-union semantics for file access.
- `agents/domain/cognition/FileAccessScope.kt` — read/write/forbidden patterns, the pure-`commonMain` glob matcher (`matches`), the gate (`allowsRead` / `allowsWrite` / `forbiddingPattern`), and subsumption-aware `intersect`.
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
- `agents/domain/cognition/sparks/SparkParser.kt` — JSON-fenced (`---json` / `---`) parser; extracts `## When <Phase>` sections for phase and language variants by delegating to `Spark.fromMarkdown`.
- `agents/domain/cognition/sparks/SparkRegistry.kt` — public role/language/project lookup consumed by factories and default spark helpers.
- `agents/domain/cognition/sparks/DefaultSparkCatalog.kt`, `DeclarativeSparkIds.kt` — synchronous bundled registry access for legacy non-suspend factory paths plus canonical ids.
- `agents/domain/cognition/sparks/PhaseSparkLibrary.kt`, `DefaultPhaseSparkLibrary.kt` — read-only catalog with deterministic `selectFor` ordering; extends `SparkRegistry`.
- `agents/domain/cognition/sparks/AmpereSpikeFlags.kt` — `declarativeSparksEnabled: Boolean = false`; gates declarative spark application.
- `composeResources/files/sparks/*.spark.md` — bundled declarative spark fixtures.
- `agents/definition/AutonomousAgent.kt` — `availableTools` (the permitted *ids*, null when unconstrained) and `effectiveTools` (`requiredTools` ∩ permitted ids: the tools the agent may actually act with).
- `agents/definition/SparkBasedAgent.kt` — the tool-enforcement sites (the reasoning unit is built with `availableTools = { effectiveTools }`, and `executePlanStep` resolves a step's `toolId` against `effectiveTools`) plus `buildPlanStepRequest`, which stamps `effectiveFileAccess` onto the request (alongside the workspace pin and, since AMPR-408, the earlier steps' results — none of which the spark stack narrows). A step that nominates *no* tool skips that lookup entirely and goes to `executeReasoningStep` — one `EXECUTE`-tagged model call by this agent, gated on `cognitiveConfig.reasoningSteps.execute` (AMPR-407) and handed the same earlier results (AMPR-412). See [PropelLoop](propel-loop.md).
- `agents/execution/request/ExecutionRequest.kt` — `fileAccessScope`, the carrier that gets the stack's file narrowing to a tool; `withFileAccessScope` re-applies it at the dispatch funnel in `ToolExecutionEngine` after a `ParameterStrategy` has rebuilt the request.
- `agents/execution/tools/ToolWriteCodeFile.kt`, `ToolReadCodeFile.kt` — the file-enforcement sites: each refuses the whole call, as an `ExecutionOutcome.*.Failure`, when any path it was handed is outside the request's scope.
- `agents/domain/event/SparkEvent.kt` — `SparkAppliedEvent` (`:44`) and `SparkRemovedEvent` (`:83`), the observability pair. There are no per-class files of those names, despite the class names.
- `agents/domain/event/CognitivePhaseEvent.kt` — first-class phase boundary events.

## Invariants

- **Sparks can only narrow, never expand.** A Spark may set `allowedTools` to a strict subset of the parent context; setting a wider set than the parent is a violation and breaks the recursive safety guarantee.
- **The system prompt is rebuilt from the live stack on every LLM call.** No caching of the rendered prompt is allowed unless invalidated on every push/pop. Stale prompts cause the active Spark stack to drift from observed prompt content.
- **Apply/remove are paired and observed.** Every `SparkAppliedEvent` has a matching `SparkRemovedEvent` (or end-of-run cleanup). `ArcTraceProjection` uses these events to reconstruct phase context. `ObservableAgent` publishes both — and `CognitiveStateSnapshot` — with the agent's `Agent.currentRunId` on the envelope, so they land on the run's trace rather than on no run at all (AMPR-386).
- **Phase boundaries are explicit when a bus is wired.** `PhaseSparkManager` publishes `PhaseEntered` after `currentCognitivePhase` is assigned and before phase sparks are applied, and publishes `PhaseExited` after phase sparks are removed and the previous phase is restored. Both carry the Arc run on their envelope, defaulted from the owning agent's `Agent.currentRunId` (AMPR-386).
- **Bracketing and prompt injection are separate switches.** `PhaseSparkConfig.publishBrackets` and `injectPhaseSparks` sit under `enabled` and are independent. `injectPhaseSparks = false` must leave the system prompt byte-for-byte what it would be with phases off — which is why `agent.currentCognitivePhase` is written only when that switch is on: it is read by exactly one thing, `buildSystemPrompt`, so leaving it set would still pull every role/language spark's `## When <Phase>` section into a supposedly silent run's prompt.
- **A phase is active when `currentPhase != null`, not when sparks are on the stack.** The two were equivalent before AMPR-387 and are not now: a bracketed-but-not-injected phase pushes nothing. Any guard written as `appliedSparks.isNotEmpty()` silently disables `injectPhaseSparks = false`.
- **`AMPERE_PHASE_SPARKS` is an override, not a default.** Set, it forces `enabled`, `publishBrackets`, and `injectPhaseSparks` on regardless of config; unset, it contributes nothing and the config decides alone. It is a developer switch — never the mechanism a consumer relies on.
- **A tool-less step is narrowed by nothing, because it dispatches nothing.** `executePlanStep` resolves `toolId` against `effectiveTools`; a step whose `toolId` is null never reaches that lookup, so no spark can withdraw it and no spark has to permit it. What a spark still shapes is the *prompt* the step is carried out with, through the live stack like any other call.
- **Narrowing is read, not just computed.** Every site that offers or dispatches tools reads `effectiveTools`, never `requiredTools`. There are two — the planner's available-tools list and `executePlanStep`'s lookup — and they share one set, so a tool the stack withdrew fails identically to one that never existed. A narrowing that nothing reads is decoration; that was the AMPR-400 bug. (One tool-advertising path is still unnarrowed: `AutonomousAgent.buildToolAwarenessIdea` lists every `ToolRegistry` tool to Perceive. Those tools are outside `requiredTools`, so they are already undispatchable by `executePlanStep` — the leak is that the model is told about them at all.)
- **File narrowing is read too, at the tool.** `effectiveFileAccess` is stamped onto the plan-step `ExecutionRequest` and gated by the file-touching tools before any filesystem call, because the request is the only value that reaches a tool's execution function and a tool must not depend on `AutonomousAgent` (AMPR-414). A tool that takes a path and does not consult `ExecutionRequest.fileAccessScope` is a hole in the gate. Two are known and left alone deliberately: `git_stage`, which only records paths already on disk that whatever wrote them was gated for; and `read_codebase`, which ships no `ParameterStrategy` and so cannot be promoted out of the generic `ExecutionContext.NoChanges` a plan step dispatches with — it is undispatchable before it is ungated. Give either one a path source and it needs the gate.
- **Composition keeps the narrower pattern, never the matching string.** `FileAccessScope.intersect` pairs the two sides and keeps the narrower of each *subsuming* pair, so `{"**/*"} ∩ {"**/*.kt"}` is `{"**/*.kt"}`. `FileAccessScope.subsumes` is sound and deliberately incomplete: it may miss a containment, which drops a pattern and narrows, but it must never report one that does not hold, which would widen. Every pattern in an intersection is one of its two inputs — the operation never synthesizes a pattern.
- **An empty pattern set denies; `null` is how a Spark says nothing.** `emptySet()` is the strongest constraint under intersection, not the absence of one, which is what makes `FileAccessScope.NoAccess` and `ReadOnly` mean what they say. A Spark contributing no constraint on an axis widens it to `**/*` (as `ProjectSpark` does for writes); a Spark contributing no file constraint at all sets `fileAccessScope = null`.
- **`forbiddenPatterns` spans reads and writes.** There is one deny-list, it is unioned across the stack, and it outranks every allow pattern on both axes. "This role does not *edit* code" is expressed by the write allow-list not naming source files, never by forbidding them — a forbid also blinds the role's `read_code_file` (AMPR-414, `role-operations`).
- **The narrowed set is read live, never captured.** The stack is mutable for the agent's lifetime, so `ReasoningSettings.availableTools` is a `() -> Set<Tool<*>>` provider rather than a set. A snapshot taken when the reasoning unit was constructed would keep offering tools a later spark has since withdrawn.
- **A capability-bearing spark's `allowedTools` is a real permission, so it must name real tool ids.** Because composition is intersection, an id that no tool in the repo carries contributes nothing, and a *missing* id silently withdraws a tool the agent was deliberately built with. A role spark must list every tool its factory hands the agent, `plan_steps` included.
- **Tool-set composition is intersection.** When two Sparks both specify `allowedTools`, the effective set is `A ∩ B`, not `A ∪ B`. A change that switches to union is a permission expansion and violates the narrowing invariant.
- **PhaseSparks add context only.** They do not narrow tools (`allowedTools = null`) or file access (`fileAccessScope = null`). Their job is prompt augmentation, not capability gating.
- **Spark `name` follows `Type:Subtype`.** `Role:Code`, `Phase:Perceive`, `Project:ampere`, `PhaseSpark:cooking-domain`. The trace projection extracts subtype from this prefix; ad-hoc names break trace bucketing. Declarative sparks use `PhaseSpark:<id>` so trace bucketing that keys on `Phase:` still treats built-in phases distinctly.
- **The `## When <Phase>` heading rules have one definition.** `Spark.fromMarkdown` owns them and `SparkParser.extractPhaseSections` delegates to it, so a consumer's `.spark.md` and a bundled fixture split identically. A second splitter — in the parser, in a consumer, in a test helper — is the drift this was built to close (AMPR-392).
- **A markdown spark narrows nothing.** `MarkdownSpark.allowedTools` and `fileAccessScope` are `null`, always. `requestedToolIds` from a `tools` key is a request, not a grant. A public factory that let a caller's prose set an allow-list would hand a consumer a widening lever, since nothing above it in the stack vetoes.
- **Sparks are pure data.** No Kotlin lambdas on the `Spark` interface — behavioral guidance is expressed as markdown in `promptContribution` / `phaseContributions`, interpreted by the LLM. This is what makes a `.spark.md` file a complete, shareable unit of customization.
- **Composition is additive, not exclusive.** When `N` sparks are on the stack, `buildSystemPrompt` includes contributions from all `N` — not "the topmost wins". `effectiveAgentRole` concatenates fragments with `" + "` (e.g. `Code Writer + Cooking Domain`). `effectiveRequestedTools` unions.

## Common operations

- **Add a new Spark type** — implement `Spark` (or extend an existing sealed family like `PhaseSpark`), define `name`, `promptContribution`, optionally `allowedTools` / `fileAccessScope` / `phaseContributions` / `agentRole` / `requestedToolIds`, mark it `@Serializable` with a stable `@SerialName`.
- **Author a declarative phase spark** — write a `.spark.md` file under `composeResources/files/sparks/` with a `---json` / `---` frontmatter block of type `"phase"` (id, name, whenToUse required) and a markdown body, optionally with `## When <Phase>` sections for phase-specific guidance. Add the path to `DefaultPhaseSparkLibrary.DEFAULT_SPARKS`.
- **Author a declarative role spark** — same path, `---json` / `---` frontmatter block of type `"role"` (id, name, agentRole required; `allowedTools` / `fileAccessScope` optional for narrowing). If you supply `allowedTools`, check it against the ids the agent's factory actually passes (`grep -r '_TOOL_ID' ampere-core/src` plus the private ids in `execution/tools/git/GitTools.kt`) and include `plan_steps`, which every `SparkBasedAgent` ships with — omitting an id withdraws that tool at dispatch. Body is the role's `promptContribution` verbatim — do not use `## When <Phase>` headers, they will not be extracted. Add the path to `DefaultPhaseSparkLibrary.DEFAULT_SPARKS`. Factory call sites resolve it via `SparkRegistry.roleSparkById(id)`.
- **Author a declarative language spark** — use type `"language"` with optional `fileAccessScope`; body text outside `## When <Phase>` headers is always-on guidance, and matching phase sections become `phaseContributions`. Resolve through `SparkRegistry.languageSparkById(id)`.
- **Author a declarative project spark** — use type `"project"`, put `## Project Description` and `## Project Conventions` in the body, and use `${env:VAR:-fallback}` for dynamic fields such as `repositoryRoot`. Resolve through `SparkRegistry.projectSparkById(id)`.
- **Narrow what an agent may touch** — give the Spark a `fileAccessScope` whose `write`/`read` lists are a *subset* of what the sparks beneath it allow, remembering that composition keeps the narrower of each subsuming pair and drops pairs that merely overlap (`src/**` against `**/*.kt` composes to nothing, not to `src/**/*.kt`). Check the composed result with `SparkStack.effectiveFileAccess().allowsWrite(path)` rather than reading the fixture.
- **Check whether a path is in scope** — `FileAccessScope.allowsRead(path)` / `allowsWrite(path)`; `forbiddingPattern(path)` names the deny-list entry that refused it, which is what a tool's refusal message should quote. Paths are normalized first, so a leading `./`, a leading `/` and a doubled separator are all insignificant; `..` is deliberately *not* resolved, because workspace containment is `ExecutionWorkspace`'s job (AMPR-300) and resolving it here would bless a match on a path that escapes.
- **Build a Spark from markdown outside the module** — `Spark.fromMarkdown(id = "Consumer:charter", body = markdown)`, optionally with `frontmatter = mapOf("tools" to "read_code_file, write_code_file")` and `contributeRole = true` for a `role` key. No flag gates it, and no bundled fixture is involved.
- **Put a consumer's charter ahead of the stack** — `stack.buildSystemPrompt(phase, preamble = charter)` rather than `charter + stack.buildSystemPrompt(phase)`.
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
- **Reading `fileAccessScope` off a spark instead of the composition.** A single fixture's write list is not what the agent may write; the stack's intersection is. A production CODE agent stacks `role-code`, `project-ampere` and `language-kotlin`, and composes down to `{"**/*.kt", "**/*.kts"}` — `role-code` alone also names `**/*.md`, which that agent cannot write.
- **Saying "no constraint" with `emptySet()`.** Under intersection the empty set denies everything downstream of it, which is how a `ProjectSpark` whose comment read "role sparks enable writing" took write access away from every agent built with it. Widen to `**/*`, or set `fileAccessScope = null`.
- **Forbidding a file extension to stop a role writing it.** `forbiddenPatterns` is one list for reads and writes both, so forbidding `**/*.kt` also stops the role reading Kotlin — which is what `role-operations` did to an agent holding `read_code_file`. Leave the extension out of the write allow-list instead.
- **Using `java.nio.file.PathMatcher` for the glob matcher.** It is JVM-only, and `ampere-core` also targets Android, iOS, JS and wasmJs. `FileAccessScope.matches` is pure `commonMain` for that reason, and the pattern syntax is deliberately small enough not to need an `expect`/`actual` per platform.
- **Re-implementing the `## When <Phase>` split in a consumer.** That is what AMPR-392 closed: the rules are small enough to re-derive and subtle enough to re-derive *wrongly* (four headings instead of six, a section that swallows the appendix behind it, a `## When nothing matches` silently eaten). Call `Spark.fromMarkdown`.
- **Concatenating around `buildSystemPrompt` to get a charter in first.** The prompt's separator structure is the stack's; a caller gluing text to the front of the returned string owns a `---` it cannot see. Pass `preamble`.
- **Using `PhaseSpark` to narrow tools.** Phase sparks are advisory prompt content, not gates. Capability narrowing belongs in role, language, project, or task sparks.
- **Building an `AgentConfiguration` without the `cognitiveConfig` you were handed.** This was the AMPR-387 bug exactly: `SparkBasedAgent.agentConfiguration` constructed `AgentConfiguration(...)` without one, so the default (`phaseSparks.enabled = false`) won and the documented config path did nothing for two releases while `AgentFactory(cognitiveConfig = …)` fed a private getter nothing read. A config parameter that is accepted and dropped reads as supported.
- **Treating `currentCognitivePhase` as observability.** It is prompt state — `buildSystemPrompt` is its only reader. Subscribe to `CognitivePhaseEvent` to know what phase an agent is in; `getCurrentPhase()` on the manager is the in-process equivalent.
- **Declaring a spark event under a non-sealed interface.** `SparkEvent` sat open under the sealed `Event` because "the implementations are concrete data classes in this same file". The serialization plugin walks sealed subtypes to register a polymorphic subclass, so it stopped there: all three spark events failed to encode, every `publish` of one logged an `EventSerializationException` and persisted nothing, and the `Phase:`-prefix bucketing in `ArcTraceProjection` had no rows to bucket. Sealed since AMPR-386; `EventRegistryCompletenessTest` fails if another open branch appears.
