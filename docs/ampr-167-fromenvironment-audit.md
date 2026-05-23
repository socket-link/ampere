# AMPR-167 Task 3 — `Ampere.fromEnvironment` KMP audit

**Issue:** [AMPR-167](https://linear.app/miley/issue/AMPR-167)
**Date:** 2026-05-22

Cross-file audit of every JVM-specific dependency along the
`Ampere.fromEnvironment` call graph, with a proposed migration path for each.

---

## TL;DR

**The body of `Ampere.fromEnvironment` (`Ampere.jvm.kt:35-109`) is already
KMP-portable — it sits in `jvmMain` for historical reasons, not because it
references anything JVM-only.** The migration is essentially:

1. Move `Ampere.fromEnvironment` to `commonMain` (file move, no code edits).
2. Move 7 `Default*Service` files from `jvmMain` → `commonMain` (file move,
   no code edits — they import only commonMain types).
3. Leave `internal actual fun createInstance(config)` in `Ampere.jvm.kt` as
   the JVM `actual` for the heavy `Ampere.create()` path, which legitimately
   depends on JDBC + `java.io.File`.
4. No new `expect`/`actual` declarations are required for `fromEnvironment`,
   because callers already own the database / driver / scope. The two
   `expect fun` declarations the ticket sketched (`defaultDatabaseDriver`,
   `defaultAmpereDataDirectory`) would only be needed if we migrated
   `Ampere.create()` to commonMain — which is **out of scope** for this
   ticket per the ticket's stated focus on the light construction seam.

Android and iOS `actual fun createInstance` continue to throw
`UnsupportedOperationException` (matching JS / wasmJs). After this task,
those targets will support `Ampere.fromEnvironment` (the light path Socket
consumes) but not `Ampere.create()` (the heavy path Socket does not).

---

## 1. `Ampere.fromEnvironment` body — file-by-file dependency check

Per `Ampere.jvm.kt:35-109`, the function builds eight services from inputs
the caller supplies. Each is checked for JVM-only types below.

| Dep | Source file | JVM-only? | Notes |
|---|---|---|---|
| `EnvironmentService` (input) | `commonMain/.../environment/EnvironmentService.kt:46` | No | Pure commonMain |
| `KnowledgeRepository` (input) | `commonMain/.../knowledge/KnowledgeRepository.kt:20` | No | Pure commonMain interface |
| `AgentActionService` | `commonMain/.../agents/service/AgentActionService.kt` | No | Verified in commonMain |
| `TicketActionService` | `commonMain/.../agents/service/TicketActionService.kt` | No | Verified in commonMain |
| `MessageActionService` | `commonMain/.../agents/service/MessageActionService.kt` | No | Verified in commonMain |
| `DefaultThreadViewService` | `commonMain/.../events/messages/DefaultThreadViewService.kt` | No | Already commonMain |
| `DefaultTicketViewService` | `commonMain/.../events/tickets/DefaultTicketViewService.kt` | No | Already commonMain |
| `DefaultAgentService` | `jvmMain/.../api/internal/DefaultAgentService.kt:1-86` | **No (misplaced)** | Imports: `kotlinx.datetime.Clock`, `AgentEventApi`, `AgentTeam[Builder]`. All commonMain. **Move to commonMain.** |
| `DefaultTicketService` | `jvmMain/.../api/internal/DefaultTicketService.kt:1-72` | **No (misplaced)** | Imports: `link.socket.ampere.*` only. **Move to commonMain.** |
| `DefaultThreadService` | `jvmMain/.../api/internal/DefaultThreadService.kt:1-63` | **No (misplaced)** | Imports: `kotlinx.coroutines.flow.*`, `MessageEvent`, etc. All commonMain. **Move to commonMain.** |
| `DefaultEventService` | `jvmMain/.../api/internal/DefaultEventService.kt:1-45` | **No (misplaced)** | Imports: `kotlinx.coroutines.flow.channelFlow`, `EventRepository`, `EventRelay*`. All commonMain. **Move to commonMain.** |
| `DefaultOutcomeService` | `jvmMain/.../api/internal/DefaultOutcomeService.kt:1-52` | **No (misplaced)** | Imports: `OutcomeMemoryRepository`, `OutcomeStats`. All commonMain. **Move to commonMain.** |
| `DefaultPricingService` | `commonMain/.../api/internal/DefaultPricingService.kt` | No | Already commonMain |
| `DefaultKnowledgeService` | `jvmMain/.../api/internal/DefaultKnowledgeService.kt:1-77` | **No (misplaced)** | Imports: `KnowledgeRepository`, `Knowledge`, `KnowledgeService`. All commonMain. **Move to commonMain.** |
| `DefaultStatusService` | `jvmMain/.../api/internal/DefaultStatusService.kt:1-83` | **No (misplaced)** | Imports: `kotlinx.coroutines.flow.flow`, `ThreadViewService`, `TicketViewService`. All commonMain. **Move to commonMain.** |
| anonymous `AmpereInstance` | inline in `Ampere.jvm.kt:96-108` | No | Just a property bag; `close()` is a no-op |

**Conclusion:** Zero genuine JVM-isms in the `fromEnvironment` graph. Seven
of the `Default*Service` files are in `jvmMain` only because the function
that constructs them was; moving them to `commonMain` is the load-bearing
edit.

---

## 2. `EnvironmentService.create()` — `EnvironmentService.kt:194-214`

Already in commonMain. Inputs: `Database`, `CoroutineScope`, `Json`,
`EventLogger`. No JVM-isms.

The caller-supplies-a-`Database` constraint is what keeps this portable:
the SQLDelight `Database` interface is generated to commonMain by the
SQLDelight Gradle plugin. The platform-specific work (constructing the
driver from a `Database.Schema`) happens upstream in the caller.

---

## 3. `DefaultAmpereInstance.kt:1-161` — genuinely JVM-only

This is the `Ampere.create()` heavy-construction path, not the
`fromEnvironment` light path. **Out of scope for AMPR-167** but worth
documenting for the eventual full-KMP `Ampere.create` follow-up.

| Line | JVM-ism | Migration path (future ticket) |
|---|---|---|
| `3` | `import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver` | `expect fun defaultSqlDriver(name: String): SqlDriver` — actuals call `createJvmDriver` / `createAndroidDriver(context, ...)` / `createIosDriver` (already exist) |
| `4` | `import java.io.File` | `expect fun defaultAmpereDataDirectory(): String` returning per-platform durable path |
| `49-53` | `JdbcSqliteDriver("jdbc:sqlite:$databasePath")` + `dbFile.parentFile?.mkdirs()` | Subsumed by the `expect fun defaultSqlDriver` above; the Android actual would need a `Context`, which is the awkward part of this future work |
| `155-158` | `System.getProperty("user.home")` and `System.getProperty("user.dir")` | Subsumed by `expect fun defaultAmpereDataDirectory()` |

For AMPR-167 we leave `DefaultAmpereInstance` and `Ampere.jvm.kt`'s
`actual fun createInstance` untouched. Android / iOS / JS / wasmJs continue
to throw `UnsupportedOperationException` for `Ampere.create`. They DO gain
`Ampere.fromEnvironment` support via the migration.

---

## 4. Migration plan for Task 4

**Step 1 — Move portable files from `jvmMain` to `commonMain`:**

```
jvmMain/.../api/internal/DefaultAgentService.kt     → commonMain/...
jvmMain/.../api/internal/DefaultTicketService.kt    → commonMain/...
jvmMain/.../api/internal/DefaultThreadService.kt    → commonMain/...
jvmMain/.../api/internal/DefaultEventService.kt     → commonMain/...
jvmMain/.../api/internal/DefaultOutcomeService.kt   → commonMain/...
jvmMain/.../api/internal/DefaultKnowledgeService.kt → commonMain/...
jvmMain/.../api/internal/DefaultStatusService.kt    → commonMain/...
```

Pure file moves. No content edits. `git mv` is appropriate.

**Step 2 — Move `Ampere.fromEnvironment` to `commonMain`:**

Two options:

- **Option A**: extend `commonMain/.../api/Ampere.kt` with the
  `fun Ampere.fromEnvironment(...)` extension. Keeps the public API
  cohesively in one file.
- **Option B**: create a sibling `commonMain/.../api/AmpereFromEnvironment.kt`
  to keep `Ampere.kt` terse and let `fromEnvironment` grow independently
  (it's likely to gain `MemoryStore` / `UpstreamLlmClient` params over time).

**Recommendation: Option B.** `Ampere.kt` today is a clean entry point;
adding a 75-line extension function plus its imports muddies it. A sibling
file keeps PRs that touch `fromEnvironment` from churning `Ampere.kt`.

**Step 3 — Trim `Ampere.jvm.kt`:**

Reduce to just:

```kotlin
package link.socket.ampere.api

import link.socket.ampere.api.internal.DefaultAmpereInstance

internal actual fun createInstance(config: AmpereConfig): AmpereInstance =
    DefaultAmpereInstance(config)
```

(File renamed `Ampere.actual.jvm.kt` is optional; current name still works
since file naming conventions in KMP don't require `.actual.` infix.)

**Step 4 — Add `actual fun createInstance` parity:**

The Android / iOS / JS / wasmJs `actual fun createInstance` already exist
and throw `UnsupportedOperationException`. The error message currently says
"JVM-only for now" — that's still accurate for the `create` path, so no
change needed. (The ticket's "throw NotImplementedError" instruction for
JS / wasmJs is satisfied; the existing UnsupportedOperationException is
functionally equivalent and matches the pre-existing pattern.)

**Step 5 — Verify all KMP source sets still compile:**

```
./gradlew :ampere-core:compileCommonMainKotlinMetadata
./gradlew :ampere-core:compileKotlinJvm
./gradlew :ampere-core:compileKotlinAndroid (or testDebug equivalent)
./gradlew :ampere-core:compileKotlinIosSimulatorArm64
./gradlew :ampere-core:compileKotlinJs (or wasmJs equivalent)
```

**Step 6 — Smoke test:**

Add a commonTest that constructs `Ampere.fromEnvironment` with an
in-memory SQLDelight driver (provided by `app.cash.sqldelight.driver.test`)
and a `KnowledgeRepositoryImpl`. Verify `AmpereInstance.agents.pursue(...)`
succeeds. This is Task 8's smoke test.

---

## 5. Risks / open questions

1. **`internal` visibility leakage.** `Default*Service` classes are
   `internal class`. Moving them to commonMain means they remain `internal`
   to `ampere-core` — that's still correct visibility for the consumer
   contract (the public surface is `AgentService` / `TicketService` /
   etc., which are already in commonMain).

2. **No `MemoryStore` / `UpstreamLlmClient` plumbing yet.** The current
   `fromEnvironment` signature still takes `KnowledgeRepository` directly.
   A follow-up could swap that for `memoryStore: MemoryStore`, but that's
   a *breaking* signature change for the CLI and any other existing
   consumer. **Recommendation: keep the existing signature, add an
   overload accepting `MemoryStore` in a follow-up ticket once the CLI is
   ready to migrate.** Likewise `UpstreamLlmClient` flows through
   `AgentLLMService` construction, not `fromEnvironment`, so no plumbing
   needed at this layer.

3. **CLI compatibility.** The CLI calls `Ampere.fromEnvironment` (`Ampere.jvm.kt:35`)
   directly via Kotlin source compilation, not via reflection. Moving the
   declaration to commonMain is source- and binary-compatible: same
   package, same function signature, callers see no change.

---

## 6. JVM-ism count summary

- In `Ampere.fromEnvironment` body: **0**
- In `EnvironmentService.create`: **0**
- In `Default*Service` files used by `fromEnvironment`: **0**
- In `DefaultAmpereInstance` (used by `Ampere.create`, not `fromEnvironment`): **4** (JdbcSqliteDriver, java.io.File, System.getProperty x2)

Net: `fromEnvironment` is already KMP-clean. The audit's load-bearing
finding is that the `Default*Service` files are misplaced into `jvmMain`
purely by convention, and moving them is a no-edit `git mv`.
