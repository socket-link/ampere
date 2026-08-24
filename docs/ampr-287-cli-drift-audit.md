# CLI Drift Audit: `ampere-cli` vs Current Core

**Issue:** AMPR-287 (GitHub #699) — Recon: CLI drift audit — map existing CLI module against current core
**Author:** Miley Chandonnet (recon by Claude)
**Date:** 2026-08-23
**Core baseline:** `2c8362d8` (main, 2026-08-23)

---

## Executive Summary

**The drift hypothesis is wrong at the compilation level.** The entire CLI module — all 125 jvmMain files and all 47 test files — compiles cleanly against current core, and the full test suite passes: **677 tests, 0 failures, 0 skipped** (Verified, build log in Appendix C). The reason: the CLI was not actually frozen — core-driven migrations (AMPR-163 spark agents, AMPR-182 EVALUATE→LEARN removal, AMPR-220 Links/Plugs/Arcs vocabulary, AMPR-236 UpstreamLlmClient) mechanically updated it through 2026-07-31.

The real drift is different and twofold:

1. **Dead weight, not rot.** ~51 of 125 source files (~8,700 lines, ≈36% of jvmMain) are unreachable from any command entry point. The two largest corpses are the JLine REPL (19 of 21 files; `ReplSession` is never constructed) and the coordination topology dashboard (all 8 files; `CoordinationView` is never constructed). `COMMANDS.md` still documents REPL-only verbs (`ticket create`, `message post`, `agent wake`) that are not invocable today.
2. **Unadopted core, not broken core.** The CLI touches zero of the newest core surfaces: `canon.*`, `plug.*`, `link.*`, `propel.*`, `domain.arc.bridge.*` (ArcSession/ArcRunHandle), and `eval.trace.*` (TraceRecorder/TraceBudget). It still drives arcs via `AmpereRuntime` directly and reads events via the stable `api.*` facade — both current, supported paths, so nothing contradicts core contracts; the v1.0 supervisor simply has a green field on the new primitives.

**Verdict summary:** 13 subsystems KEEP, 6 KILL, 0 REBUILD. No subsystem needs a rebuild because none is broken; the kills are pure dead-code deletion.

Claim labels used throughout: **Verified** (ran the build/tests, or confirmed by direct file read / exhaustive reference search), **Inferred** (reasoned from verified facts), **Untested** (not exercised).

---

## 1. Subsystem Inventory

125 jvmMain files, 24,311 lines, all classified (100% coverage — Verified by file enumeration). Package boundary note: `link.socket.ampere.*` in imports is **ampere-core** except `link.socket.ampere.phosphor.*` (in-repo **ampere-phosphor**); `link.socket.phosphor.*` (no `ampere` segment) is the **external** `phosphor-core:0.6.2` Maven artifact.

| # | Subsystem | Files / lines | Purpose | Entry point | Core touchpoints (current symbols) |
|---|---|---|---|---|---|
| 1 | Root commands + `AmpereContext` | 16 / 3,723 | Clikt tree, DI container, TUI root (`AmpereCommand`, 860 lines) | `MainKt` → all `ampere …` commands | `api.Ampere`/`AmpereInstance`/`fromEnvironment` + 5 `api.service.*` services; `domain.arc.{AmpereRuntime, ArcRegistry, ArcConfig, ArcOutcome}`; `agents.definition.{AgentFactory, SparkBasedAgent}` + `CodeState`/`ProductState`/`QualityState`; `agents.execution.AutonomousWorkLoop`; `llm.BundledUpstreamLlmClient`; `domain.koog.KoogAgentFactory`; `agents.domain.emission.GlobalEmissionReplyRegistry` |
| 2 | `config/` | 3 / 610 | `ampere.yaml` schema, kaml parsing, mapping onto core DSL | `Main.kt` `loadConfiguration()` | `domain.ai.*` (AIConfiguration/AIModel/AIProvider), `dsl.agent.*`, `dsl.team.*`, `agents.tools.mcp.*` |
| 3 | `cli/goal` | 3 / 474 | `--goal` text → ticket → spawned CODE agent | `AmpereCommand --goal`, TUI `:goal` | `agents.events.tickets.*` DSL, `agents.definition.*`, `agents.events.api.AgentEventApi`, `llm.BundledUpstreamLlmClient` |
| 4 | `cli/watch` + `presentation/` | 14 / 1,771 | TUI MVP core: event stream → `WatchViewState`; `:` command dispatch | `AmpereCommand` | ~28 `agents.domain.event.*` types, `agents.events.relay.{EventRelayService, EventRelayFilters}`, `agents.domain.cognition.sparks.CognitivePhase`, `integrations.issues.*` |
| 5 | `cli/layout` | 16 / 3,617 | Pane widget toolkit (`PaneRenderer` + concrete panes, layout geometry, ANSI plumbing) | `AmpereCommand` | `agents.domain.{reasoning, state, status, task}.*` |
| 6 | `cli/hybrid` | 3 / 942 | The renderer the default TUI actually uses (layered cell buffer + substrate animation) | `AmpereCommand.kt:213` | none direct (via `WatchViewState`); external phosphor artifact |
| 7 | `cli/render` | 6 / 618 | Event-bus → Phosphor waveform bridge; the module's only in-repo ampere-phosphor consumer | `AmpereCommand`, `ampere demo waveform` | `agents.events.bus.EventSerialBus`, `CognitivePhase`; **ampere-phosphor** `AmperePhosphorBridge` |
| 8 | `cli/animation` | 10 / 2,761 | Lightning/particle/logo animation engine | TUI panes via `cli/hybrid` | none (external phosphor artifact only) |
| 9 | `cli/launch` | 1 / 56 | TUI vs headless decision (`--headless`, TTY, size) | `AmpereCommand` | none |
| 10 | `cli/help` + `help/` | 4 / 921 | TUI command registry + a second, REPL-oriented help corpus | `CommandRegistry` live; `help/` dead | none |
| 11 | `renderer/` | 9 / 1,657 | Legacy mordant renderers + shared `SparkColors`/`SparkNameFormatter` vocabulary | mixed (see verdicts) | full `agents.domain.event.*` set, `agents.domain.cognition.CognitiveAffinity`, `agents.events.tickets.TicketSummary` |
| 12 | `demo/` | 5 / 1,529 | Headless E2E runners (`ampere test agent`, `ampere test ticket`) + scripted demo | `TestCommand` subtree | `agents.execution.tools.executeCreateIssues`, `agents.definition.*`, `PhaseSparkManager` |
| 13 | `repl/` | 21 / 2,989 | JLine interactive shell — **plus** the load-bearing `TerminalFactory`/`TerminalSymbols` misfiled here | `TerminalFactory` used by 19 files; `ReplSession` unreachable | `agents.events.tickets.*`, `agents.domain.status.TicketStatus` |
| 14 | `cli/coordination` | 8 / 1,638 | Agent topology dashboard (force-directed layout, interaction feed) | **none** | `coordination.{CoordinationTracker, CoordinationState, CoordinationStatistics, AgentInteraction, InteractionType}` |
| 15 | `cli/mosaic` | 3 / 482 | Alternative Compose/Mosaic declarative UI | **none** | none direct |
| 16 | `cli/surface` | 1 / 419 | Terminal renderer for `AgentSurfaceEvent` request/response | **none** | `agents.events.surface.*`, `agents.events.bus.EventSerialBus` |
| 17 | `logging/` | 1 / 52 | Info-swallowing `EventLogger` | **none** | `agents.events.utils.EventLogger` |
| 18 | `util/` | 1 / 52 | Event-name → `EventType` parsing via `EventRegistry` | none live (only dead `repl/`+`help/`) | `agents.domain.event.EventRegistry` |

(Inventory: Verified — every file read/classified and reachability established by exhaustive reference search from `Main.kt`.)

---

## 2. Drift Matrix

Per the ticket: compiles? (run, not inferred) / targeted core APIs still exist? / superseded by newer core capability?

| Subsystem | Compiles vs core `2c8362d8` | Tests | Targeted core APIs exist? | Superseded by core capability added since? |
|---|---|---|---|---|
| Root commands | **Yes — Verified** (`:ampere-cli:compileKotlinJvm` BUILD SUCCESSFUL) | pass (Verified) | Yes — all Verified (compiler-checked) | Partially: `AmpereCommand` calls `AmpereRuntime.create` directly (`AmpereCommand.kt:642,817`); the newer `ArcSession`/`ArcRunHandle` bridge (`domain/arc/bridge/ArcSession.kt:70`) with `observe()`/`cancel()`/`trace()` is the intended consumer surface — modernization opportunity, not breakage (Inferred) |
| `config/` | Yes — Verified | pass | Yes | No |
| `cli/goal` | Yes — Verified | (no direct tests) | Yes | No |
| `cli/watch` | Yes — Verified | pass | Yes — incl. post-AMPR-220 `LinkEvent`, `BenchEvent`, `AssetAccessEvent` (already adopted) | No |
| `cli/layout` | Yes — Verified | pass | Yes | No |
| `cli/hybrid` | Yes — Verified | pass | n/a (no direct core imports) | No |
| `cli/render` | Yes — Verified | pass | Yes — `EventSerialBus` (`agents/events/bus/EventSerialBus.kt:50`), `CognitivePhase` (`PhaseSpark.kt:293`), ampere-phosphor `AmperePhosphorBridge` (`AmperePhosphorBridge.kt:38`) | No — it *is* the consumer of the current phosphor bridge |
| `cli/animation` | Yes — Verified | pass | n/a | No |
| `cli/launch` | Yes — Verified | pass | n/a | No |
| `cli/help` + `help/` | Yes — Verified | n/a | n/a | No |
| `renderer/` | Yes — Verified | pass | Yes | Dead trio only (see verdicts) |
| `demo/` | Yes — Verified | pass | Yes | No |
| `repl/` | Yes — Verified | pass (terminal infra tests) | Yes | **Yes** — REPL action verbs (`ticket`, `message`, `agent wake`) are re-implemented by the TUI `:` command mode (`cli/watch/CommandExecutor`) and the stable `api.service.*` facade (Verified reachability + Inferred supersession) |
| `cli/coordination` | Yes — Verified | pass (component tests) | Yes — `coordination.*` still in core | Unreachable; nothing supersedes it, nothing uses it |
| `cli/mosaic` | Yes — Verified | n/a | n/a | Superseded by `cli/hybrid` (the shipped renderer) — Inferred from `AmpereCommand.kt:213` |
| `cli/surface` | Yes — Verified | pass (component test) | Yes — `agents.events.surface.*` + `AgentSurfaceBusExt.kt:20,51` still exist | **Yes** — human-response flow went the CHI/Emission route: `RespondCommand` + `GlobalEmissionReplyRegistry` (`EmissionReplyRegistry.kt:63`), per `docs/concepts/chi.md` (Inferred) |
| `logging/` | Yes — Verified | n/a | Yes | **Yes** — core `SilentEventLogger` + `LoggingConfiguration.createEventLogger()` (used by `Main.kt`) already cover this (Verified: `Main.kt` uses the core path) |
| `util/` | Yes — Verified | pass | Yes — `EventRegistry` (`agents/domain/event/EventRegistry.kt:17`) | No, but orphaned |

**Core surfaces with zero CLI adoption (gap list for supervisor design, all Verified by import sweep):** `canon.*` (rings, entities, adapters, `TableWriteIntent`), `plug.*` (`PlugManifest`, `PlugContext`, `PerceiveSource`/`ExecuteSink` chassis ops), `link.*`, `propel.ExecuteStep`, `domain.arc.bridge.*` (`ArcSession`, `ArcRunHandle`, `ArcCancellable`), `eval.trace.*` (`TraceRecorder`, `TraceBudget`, `TraceCursor`, `TraceService`), `trace.*` (`ArcRunTrace`, `ArcTraceProjection`), `agents.domain.routing.CognitiveRelay`, `startup.initializeAmpere`.

Note: "Oscilloscope" has no code-level type — bus recording is `link.socket.ampere.eval.trace.TraceRecorder` (`ampere-eval/.../trace/TraceRecorder.kt:36`) with the AMPR-267 budget in `TraceBudget.kt:15` (Verified). CLI's `ampere trace` is unrelated: it shows event context via `api.service.EventService.get/query` (Verified, `TraceCommand.kt:34-50`).

---

## 3. Keep / Kill / Rebuild Verdicts

One verdict per subsystem. "KEEP" means the subsystem stays as the basis for v1.0 supervisor work; individually dead files inside KEEP subsystems are enumerated in §4 and can be deleted without touching the verdict.

| # | Subsystem | Verdict | Rationale (one line) |
|---|---|---|---|
| 1 | Root commands + `AmpereContext` | **KEEP** | Compiles, 677 tests green, and is the only wiring of config→agents→arcs→TUI; modernize `AmpereRuntime` call sites to `ArcSession` during supervisor work, not before. |
| 2 | `config/` | **KEEP** | Live, current against `domain.ai.*`/`dsl.*`, and the only YAML entry path. |
| 3 | `cli/goal` | **KEEP** | Live path for `--goal` and `:goal`; targets current ticket DSL and spark-agent factory. |
| 4 | `cli/watch` + presentation | **KEEP** | The TUI's state model; already speaks the post-migration event vocabulary (LinkEvent, BenchEvent, AssetAccessEvent). |
| 5 | `cli/layout` | **KEEP** | Load-bearing widget toolkit for the live TUI (16 consumers of `PaneRenderer`). |
| 6 | `cli/hybrid` | **KEEP** | It is the shipped default-TUI renderer (`AmpereCommand.kt:213`). |
| 7 | `cli/render` | **KEEP** | Only bridge to ampere-phosphor / the waveform pane; aligned with current `AmperePhosphorBridge` contract. |
| 8 | `cli/animation` | **KEEP** | Live via `cli/hybrid`; no core coupling at all, so immune to core drift. |
| 9 | `cli/launch` | **KEEP** | Small, live, correct headless-fallback gate. |
| 10 | `cli/help` (`CommandRegistry`) | **KEEP** | Single source of truth for TUI commands/shortcuts, three live consumers. |
| 11 | `renderer/` | **KEEP** | `SparkColors`/`SparkNameFormatter` are the module-wide vocabulary (10+ consumers); `CLIRenderer`/`HelpOverlayRenderer`/`AgentFocusRenderer` are live. |
| 12 | `demo/` | **KEEP** | `AgentTestRunner`/`IssueCreationTestRunner` back the live `ampere test agent|ticket` E2E commands. |
| 13 | `util/` (`EventTypeParser`) | **KEEP** | 52 lines, current against `EventRegistry`, and the obvious hook for event-name filtering in supervisor UX; currently orphaned — rewire or fold into `TraceCommand`. |
| 14 | `repl/` (except `TerminalFactory`, `TerminalSymbols`) | **KILL** | `ReplSession` is never constructed; its verbs are superseded by the TUI `:` mode + `api.service.*`; **precondition: relocate `TerminalFactory`/`TerminalSymbols` (19 consumers) out of `repl/` first.** |
| 15 | `cli/coordination` | **KILL** | Entirely unreachable (zero external references to `CoordinationView`); if the supervisor wants topology later, rebuild from `WatchViewState`, not this. |
| 16 | `cli/mosaic` | **KILL** | Unreachable; `cli/hybrid` won; deleting it also drops the `mosaic-runtime` dependency and the Compose plugin from `ampere-cli/build.gradle.kts`. |
| 17 | `cli/surface` | **KILL** | Never instantiated; human-response flow standardized on the CHI/Emission path (`RespondCommand` + `GlobalEmissionReplyRegistry`). |
| 18 | `logging/` | **KILL** | Never instantiated; duplicated by core `SilentEventLogger`/`LoggingConfiguration`. |
| — | `help/` (`CommandHelpContent`, `HelpFormatter`) | **KILL** | Reachable only through the dead REPL; its content is REPL-specific and already drifted from real commands. |

No REBUILD verdicts: every live subsystem compiles and passes tests against current core, and every dead subsystem is either superseded or was never wired in — rebuilding any of them would precede the supervisor design this recon is meant to inform.

---

## 4. Dead-Weight List (safe to delete outright)

All entries Verified unreachable by exhaustive reference search over jvmMain + jvmTest (a file is listed only if nothing outside the listed set references it). ≈51 files, ≈8,700 lines, ≈36% of jvmMain. Corresponding orphaned tests (e.g. `AnimationDemoRunnerTest`, REPL-only tests) go with them.

| Cluster | Files | ~Lines | Note |
|---|---|---|---|
| `repl/` minus `TerminalFactory.kt`, `TerminalSymbols.kt` | 19 | ~2,600 | Includes 3 zero-reference files: `ProgressIndicator.kt` (620), `MultiStageProgress.kt` (122), `ObservationStats.kt` (65) |
| `cli/coordination/` (all) | 8 | 1,638 | |
| `cli/animation/AnimationDemo.kt` + `cli/animation/demo/` | 4 | ~886 | `AnimationDemo` has its own unregistered `main()` |
| `demo/{MultiAgentDemoRunner, DemoTiming, GoldenOutput}.kt` | 3 | 612 | |
| `renderer/{EventStreamRenderer, MemoryOpsRenderer, DashboardRenderer}.kt` | 3 | 580 | `DashboardRenderer` referenced only by other dead files |
| `help/{CommandHelpContent, HelpFormatter}.kt` | 2 | 493 | |
| `cli/mosaic/` (all) | 3 | 482 | Also remove `mosaic-runtime` dep + `kotlin("plugin.compose")` |
| `cli/surface/AgentSurfaceCliRenderer.kt` | 1 | 419 | |
| `cli/layout/PaneAdapters.kt` | 1 | 263 | Adapts only dead renderers |
| `cli/watch/{KeyboardInputHandler, AgentIndexMap, WatchMode}.kt` | 3 | 209 | `AmpereCommand` inlines its own input loop |
| `cli/help/HelpRenderer.kt` | 1 | 196 | Duplicated by `renderer/HelpOverlayRenderer` |
| `logging/QuietEventLogger.kt` | 1 | 52 | |

Borderline (NOT on the delete list): `cli/watch/presentation/SparkPresentation.kt` (225 lines) — consumed by the live `WatchPresenter`, so not dead, but its only other consumer is its own test; review during supervisor design (Verified reachability, Inferred low value). `util/EventTypeParser.kt` — orphaned but kept per verdict #13.

---

## 5. Documentation Drift (COMMANDS.md / README.md)

- Documented but **not invocable**: `ticket create|assign|status`, `message post|create-thread`, `agent wake` — exist only in the dead REPL's `ActionCommandRegistry` (Verified).
- Implemented but **undocumented**: `ampere trace`, `ampere task create`, `ampere work`, `ampere demo waveform`, and the whole `ampere knowledge search|show|stats` tree (Verified: zero occurrences in either doc).
- `--headless` documented in README only, absent from COMMANDS.md's TUI section (Verified).
- Accurate: `status`, `thread`, `outcomes`, `issues create`, `respond`, `test agent|ticket`, root flags, TUI key bindings (Verified).

---

## Technical Guidelines (for the human verdict)

- The kill list is one mechanical PR: relocate `TerminalFactory`/`TerminalSymbols` (e.g. to a `terminal/` package), delete the 12 clusters, drop `mosaic-runtime` + `jline`* + `jna` + Compose-plugin deps as their consumers go (`jline` is used **only** by dead REPL files — Verified by import search), update COMMANDS.md.
- Supervisor design should target the unadopted surfaces: `ArcSession`/`ArcRunHandle` (observe/cancel/trace), `eval.trace.TraceRecorder` for recording, `api.*` facade for reads — rather than the direct `AmpereRuntime` + hand-rolled subscriptions `AmpereCommand` uses today.
- Per ticket scope, none of the above has been executed: **no code was changed in this recon.**

## Appendix A — Build evidence (Verified)

```
$ ./gradlew :ampere-cli:compileKotlinJvm :ampere-cli:compileTestKotlinJvm --continue
> Task :ampere-core:compileKotlinJvm
> Task :ampere-phosphor:compileKotlinJvm
> Task :ampere-cli:compileKotlinJvm
> Task :ampere-cli:compileTestKotlinJvm
BUILD SUCCESSFUL in 1m 25s
19 actionable tasks: 19 executed          (0 warnings, 0 errors)

$ ./gradlew :ampere-cli:jvmTest --continue
BUILD SUCCESSFUL in 17s
JUnit XML totals: tests=677 failures=0 skipped=0
```

Full logs captured during recon: `.context/ampr287-cli-build.log`, `.context/ampr287-cli-test.log` (workspace-local).

## Appendix B — Recently deleted core surfaces a stale consumer could trip on

None of these are referenced by the CLI anymore (compiler-Verified): `link.socket.ampere.plugin.*` (AMPR-220 → `plug.*`), `CodeAgent`/`ProductAgent`/`ProjectAgent`/`QualityAgent` (AMPR-163 → `SparkBasedAgent` + `SparkAgentFactory`), `RoleSpark` (AMPR-165 → `DeclarativeRoleSpark`), `GlobalHumanResponseRegistry` (AMPR-239 → `GlobalEmissionReplyRegistry`), `CanonBinding` (AMPR-257 → binding modules), `ProviderDescriptorRegistry` (AMPR-214 → `ModelDescriptorRegistry`).

## Next Steps

1. **Human verdict on §3** (per ticket: recon halts here).
2. If approved: one deletion PR per §4 + Technical Guidelines (incl. `TerminalFactory`/`TerminalSymbols` relocation and dependency pruning).
3. Update `COMMANDS.md`/`README.md` per §5.
4. Supervisor design (separate ticket) targets `ArcSession`/`ArcRunHandle`, `eval.trace`, and the `api.*` facade.

---

*Document generated as part of recon for AMPR-287 / GitHub #699.*
