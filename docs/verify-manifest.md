# `.ampere/verify.yml` — verification manifest, schema v1

A repository's definition of done, declared once, in one file, so that an agent
and a person read the same list. One manifest per repository, at
`.ampere/verify.yml`.

The manifest is **data**. It does not run anything. The runner that executes it
— the Breaker — is a separate wave; this document fixes the contract the Breaker
will read and the obligations it inherits from each field.

Schema v1 is the ratified revision of the v0 draft produced by the
[AMPR-290](https://github.com/socket-link/ampere/issues/702) feasibility recon,
which enumerated and *executed* every definition-of-done gate in three
repositories (16 gate invocations: 12 pass, 4 real failures, 0 untested). Every
construct below exists because that execution needed it, and the four changes
from v0 to v1 are the four revisions ratified on 2026-08-23.

---

## 1. The four ratified revisions

| # | Revision | Consequence in the schema |
|---|----------|---------------------------|
| 1 | **One task per gate.** No bundling. | A gate's `run` is one command. Attribution comes from structure, never from parsing a log. |
| 2 | **`fresh: true` is supported.** | A per-gate flag that obliges the runner to force re-execution rather than accept a cached verdict. |
| 3 | **Infrastructure failure is a distinct outcome.** | A closed four-value outcome set, and the per-gate metadata (`baseline`, `requires`/`needs`, `timeout_minutes`, `retry_safe`, `side_effects`) the runner needs to choose among them. |
| 4 | **Exit code is the only pass/fail contract.** | There is no construct for matching output. Logs are diagnostics. |

Each is a repair for something the recon observed, not a preference:

"One task per gate" means one task **named on the command line**, not one task in
the resulting graph. `:ampere-core:jvmTest` drags in `verifyAmpereVersionConstant`
and `verifySqlDelightMigration` through Gradle's own `dependsOn`, and that is
Gradle's business: those tasks cannot be reached independently, their failure
cannot skip a sibling that had independent value, and the gate's exit code covers
them. The rule exists to stop a *manifest author* from hiding several
independently-runnable gates behind one exit code.

**Revision 1** exists because a gate that bundled three Gradle tasks
(`:common:jvmTest :common:testDebugUnitTest :common:compileCommonMainKotlinMetadata`)
failed on the third and Gradle's fail-fast meant `:common:jvmTest` *never ran*.
One exit code covered three questions and answered one. A bundled gate reports a
failure it can attribute and silently withholds the coverage it never obtained.

**Revision 2** exists because a test gate returned `FROM-CACHE` in three seconds
having executed zero tests, and reported green. A cache that is honest about
artifacts is dishonest about executions.

**Revision 3** exists because `:ampere-core:assemble` fails on the recon host 3/3
— a Kotlin/JS backend exception in a third-party klib, alternating with Gradle
daemon heap exhaustion at the repo's 1.5 GiB cap — while the identical command is
green in CI. Charging that to an agent's retry budget spends the budget on the
host's problem and then blames the code.

**Revision 4** exists because the *same* gate failed with two different causes
across bounded retries under memory pressure, while the exit code was correct in
every one of the 16 observed runs.

### What v0 had and v1 does not

- **`stages:`** — gone. v1 is a flat ordered list. A stage's only behaviours were
  fail-fast ordering and "parallelise iff every gate in the stage is
  `parallel_safe`". Fail-fast between stages is revision 1's bug one level up: a
  red ktlint stage would leave every test gate unreported. `parallel_safe` is
  retained as per-gate metadata, so the stage had nothing left to do.
- **`matrix:`** — gone. With one task per gate, a matrix saves one line of
  repetition and costs a *computed* gate id (`jvm-test-${leg.module}`). Gate ids
  are the attribution keys a person reads in a report, and revision 1 says
  attribution comes from structure. Both repositories' inventories are
  enumerated literally instead.
- **`when:`** — never existed and still does not. No conditional gate was
  observed in any repository. CI path filters are cost optimisations; the Breaker
  always runs every gate.

---

## 2. File shape

```yaml
version: 1

env:
  requires: [ … ]    # hard preconditions
  optional: [ … ]    # soft preconditions

gates:
  - id: …            # one gate per list entry, executed in listed order
```

Top-level keys are exactly `version`, `env`, `gates`. An unknown key is a
manifest error.

### `version`

`1`. The runner refuses a manifest whose `version` it does not implement rather
than reading it optimistically.

### `env.requires` — hard preconditions

Checked **before any gate runs**. If one is absent the whole run is **refused**:
no gate executes, and every gate reports `not_executed` naming the missing
precondition. A refused run is reported, never treated as a pass and never
treated as an agent failure.

### `env.optional` — soft preconditions

Checked before any gate runs. If one is absent, the gates that name it in
`needs:` report `not_executed` naming it, and every other gate runs normally.

**A precondition is never a silent skip.** `not_executed` is a reported outcome
with a named blocker. A runner that renders it as a pass has accepted absent
evidence; a runner that renders it as a failure blames the agent for the host.

### Precondition predicates

Each precondition is an `id` plus exactly one predicate plus an optional
`reason`. The `id` is what a gate's `requires`/`needs` refers to; a reference to
an undeclared id is a manifest error, not a skip.

| Predicate | Satisfied when | Example |
|-----------|----------------|---------|
| `toolchain: "21"` | a JVM toolchain of that version is resolvable | `{ id: jdk-21, toolchain: "21" }` |
| `file: <path>` | the path exists, relative to the repository root | `{ id: local-properties, file: local.properties }` |
| `command: <argv>` | the command exits 0 | `{ id: xcode, command: xcodebuild -version }` |
| `simulator: <glob>` | at least one available simulator's name matches | `{ id: simulator, simulator: "iPhone *" }` |

Four predicates, deliberately. **A precondition must be cheap and unambiguous to
check.** Anything the build tool itself resolves and fails loudly about — the
Android SDK location, a missing dependency, a Gradle plugin version — is left to
the build tool and surfaces as an `infrastructure` outcome on the first gate that
needs it. Re-implementing Gradle's own resolution in YAML would produce a second
source of truth that can disagree with the first.

---

## 3. Gates

```yaml
  - id: jvm-test-ampere-core
    run: ./gradlew :ampere-core:jvmTest
    fresh: true
    timeout_minutes: 20
    retry_safe: true
    side_effects: build-dir
    parallel_safe: false
    requires: [ jdk-21, local-properties ]
    baseline: ci
```

| Field | Required | Type | Meaning |
|-------|----------|------|---------|
| `id` | yes | string | Stable, literal, unique within the manifest. The attribution key. |
| `run` | one of `run`/`steps` | string | Exactly one command. |
| `steps` | one of `run`/`steps` | list | An ordered, irreducible sequence. See §5. |
| `fresh` | no (default `false`) | bool | The verdict may not come from a cache. See §4. |
| `timeout_minutes` | yes | int | Wall clock. Exceeding it is `infrastructure`, never `failed`. |
| `retry_safe` | yes | bool | Re-running the gate from the current tree is sound. |
| `side_effects` | yes | enum | `none` \| `build-dir` \| `device` \| `network`. What the runner must expect the gate to touch. |
| `parallel_safe` | yes | bool | May run concurrently with another gate in the same repository. |
| `requires` | no | list of ids | Hard preconditions this gate depends on, drawn from `env.requires`. |
| `needs` | no | list of ids | Soft preconditions, drawn from `env.optional`. |
| `baseline` | yes | enum | `ci` \| `release` \| `none`. Where a green result for this exact task is observable off this host. See §6. |

`${env.VAR}` in a `run` or a step's `run` is substituted from the runner's
environment at execution time. It is the only interpolation v1 has. A referenced
variable that is unset is an `infrastructure` outcome for that gate — a gate must
never run with an empty destination.

Gates execute in listed order. **Order is not a gate.** Every gate runs
regardless of every other gate's outcome: ordering is a cheapest-signal-first
courtesy to whoever reads the report, and short-circuiting it would reintroduce
revision 1's partial coverage between gates instead of inside one.

---

## 4. `fresh` semantics

`fresh: true` declares **the verdict must come from work done on this host in
this run.** It is intent; the mechanism is the runner's, per tool.

For a Gradle gate the recon measured the working mechanism:

- pass `--no-build-cache`, and
- delete the gate's own test-result and report outputs before invoking.

`fresh` is not `--rerun-tasks`. The gate's *verdict* may not be restored from a
cache; its upstream compilation may.

For a tool with no result cache — `xcodebuild test-without-building` — the
obligation is already satisfied and the runner adds nothing.

**Which gates get it.** `fresh: true` belongs on a gate whose value is the act of
running: every test gate, and the eval replay. A lint, compile, assemble or
source-scan gate asserts a property *of the sources*, and Gradle's cache key
covers exactly those sources, so a cached verdict there attests to the same
thing a fresh one would. A cached test report attests to an execution that did
not happen here.

This is a per-gate authoring decision, not a schema rule. The schema's only
claim is that the flag exists and binds the runner.

---

## 5. `steps` — the ordered-gate construct

```yaml
  - id: ios-swift-bridge
    steps:
      - id: link-framework
        run: ./gradlew :ampere-core:linkDebugFrameworkIosSimulatorArm64
      - id: build-for-testing
        run: xcodebuild build-for-testing …
      - id: test-without-building
        run: xcodebuild test-without-building …
```

`steps` does **not** bundle; it sequences, and it reports per step. The
distinction is exactly revision 1's: bundling is wrong because the tasks it hides
had independent value that a fail-fast then destroyed. A step sequence is
permitted only where no later step has a standalone verdict at all —
`test-without-building` cannot run without the build that precedes it, so
stopping there withholds nothing that was obtainable.

- Steps run in order and stop at the first non-zero exit.
- The gate's outcome is that step's outcome, and the report names the step `id`.
  Attribution stays structural.
- Each step is one command, by the same rule as a gate.
- `fresh`, `timeout_minutes`, `retry_safe`, `side_effects`, `parallel_safe`,
  `requires`, `needs` and `baseline` are properties of the gate, not of a step.
  The timeout covers the whole sequence.
- `fresh` attaches to the step that produces the verdict — the last one. Earlier
  steps are setup and may legitimately be served from a cache; forcing a relink
  to prove a test executed confuses the two.

**The test for whether `steps` is legitimate:** could each step be its own gate
and still mean something if the one before it failed? If yes, they must be
separate gates.

---

## 6. Outcomes, and the infrastructure branch

Every gate reports exactly one of four outcomes. The set is closed.

| Outcome | Reached when | Who owns the next move |
|---------|--------------|------------------------|
| `passed` | exit code 0 | nobody |
| `failed` | non-zero exit, with every declared precondition satisfied and no infrastructure signal | the agent — the code under test is wrong |
| `infrastructure` | the gate could not produce a verdict about the code: `timeout_minutes` exceeded, an `${env.VAR}` unset, a declared precondition that passed the pre-check broken by the time the gate ran, or the runner's own classification | the host — escalate about the machine |
| `not_executed` | a precondition was absent: a hard one (whole run refused) or a soft one this gate `needs` | the operator — named blocker, reported, never silent |

**`infrastructure` does not consume the agent's retry budget.** That accounting
lives in the Breaker. What the manifest owes it is enough per-gate metadata to
classify without reading a log:

- **`baseline`** — where a green result for this exact task is observable off
  this host. `ci` (a hosted job runs it on every change), `release` (only the
  release path runs it), `none` (nothing but this manifest runs it). A
  `baseline: ci` gate that is red locally is *environment-suspect*: there is an
  off-host green to check before charging the agent. A `baseline: none` gate has
  no acquittal available anywhere, and its first result establishes a baseline
  rather than attributing a fault — the lesson from the recon's third repository,
  which had no CI at all.
- **`requires` / `needs`** — the host facilities the gate depends on. A failure
  with a named facility unhealthy is the host's, structurally, with no guessing.
- **`timeout_minutes`** — a gate that ran out of time produced no verdict about
  the code. That is not a failing test.
- **`retry_safe` + `side_effects`** — whether and how the runner may re-run to
  separate the two. A transient host failure usually does not reproduce; wrong
  code always does. This is the strongest discriminator available and it needs no
  log at all.

Local evidence **convicts but does not acquit.** A gate that fails here is a real
failure of this host; a gate that passes here has not been proven on CI's
hardware. The recon's one CI-green/locally-red gate is the standing proof that
the gap is not theoretical.

### Not in the schema: output matching

There is no `success_pattern`, no `expect_output`, no `allow_failure`. Revision 4
is a prohibition as much as a rule, and it extends to the command: a gate's `run`
must not contain anything that masks an exit code — no `|| true`, no `| tee`
without `pipefail`, no trailing `; echo done`. A gate whose exit code is a
constant is not a gate.

Gradle and xcodebuild failure text *is* machine-anchorable (`Task :x:y FAILED`,
`** TEST FAILED **`) and a runner is welcome to quote it into a report. It may
not decide anything with it.

---

## 7. What is not a gate

Not everything in a CI configuration is part of the definition of done, and the
manifest says so by omission. Recorded here so the omissions are decisions:

| Excluded | Why |
|----------|-----|
| `concept-staleness.yml` | `scripts/validate-concepts.sh` always exits 0 and the action self-describes as "informs only; never fails the workflow". A check that cannot fail is not a gate. |
| `publish.yml` | Release plumbing, runs only on a `v*` tag. The one task it uniquely covers, `:ampere-core:compileCommonMainKotlinMetadata`, is a gate in its own right (`baseline: release`). |
| CI path filters, `concurrency`, caching | Cost and scheduling. The Breaker always runs every gate. |
| `--max-workers`, `--parallel`, heap settings | Host policy. The runner's business, and keeping them out of `run` is what lets a gate's command be checked against CI's by eye. |
| Deploy / TestFlight workflows (repo B) | Release and distribution, not verification. |

---

## 8. Provenance of the committed manifests

The recon transcript is six weeks old at the time these manifests land, and both
repositories moved. The rule applied: **a gate belongs in the manifest iff it is
part of the repository's current definition of done.** Every gate below traces to
either a command in the AMPR-290 execution transcript or a named post-recon
change, and nothing traces to neither.

### This repository — 19 gates

Every gate's command appears in `.github/workflows/ci.yml`, in
`scripts/release-preflight.sh`, or in a build file, as noted.

| Gates | Provenance |
|-------|------------|
| `ktlint`, `assemble-ampere-{core,cli,compose,eval}` | AMPR-290 transcript, `lint+assemble` gate — split one-per-task per revision 1. |
| `jvm-test-ampere-{core,cli,compose,eval}`, `android-unit-test-ampere-{core,compose}` | AMPR-290 transcript, the four `jvm-test` matrix legs — split one-per-task per revision 1. |
| `ios-kotlin-test-ampere-core` | AMPR-290 transcript, verbatim. |
| `ios-swift-bridge` | AMPR-290 transcript, verbatim, as the three ordered steps it was executed as. |
| `metadata-compile-ampere-core` | AMPR-290 transcript, verbatim. Convention gate: no PR CI job runs it; `scripts/release-preflight.sh` and `publish.yml` do. `baseline: release`. |
| `verify-publish-workflow-coverage` | **Post-recon:** added to the `jvm-lint-build` job after 2026-08-23. Merge-blocking. |
| `assemble-ampere-work-linear`, `jvm-test-ampere-work-linear` | **Post-recon:** the `:ampere-work-linear` module joined the assemble list and the test matrix after 2026-08-23. Merge-blocking. |
| `eval-replay` | **Post-recon:** the `eval-replay-gate` job (AMPR-187) was added after 2026-08-23 and is aggregated into the required `JVM + Android Tests` check. Merge-blocking. |
| `core-neutrality` | **Not in CI at all.** `verifyCoreNeutrality` is registered in `ampere-core/build.gradle.kts` and wired into `check` (AMPR-257), but no workflow job runs `check` — the required status check named `check` is the concept-staleness job. The manifest is currently the only thing that runs this guard outside a local `./gradlew build`. `baseline: none`. |

Omitting the five post-recon gates would have shipped a manifest that understates
this repository's own definition of done by four merge-blocking checks on the day
it landed. Including them is the one place these manifests go beyond a literal
reading of the transcript, and each is named above.

### Repo B — 11 gates

Exactly the five CI matrix legs that GitHub branch protection requires by name
(`ktlint`, `common-tests`, `backend`, `desktop`, `android`), split per revision 1,
plus the two architectural-isolation tasks.

| Gates | Provenance |
|-------|------------|
| `ktlint` | Transcript, verbatim, with its five documented source-set exclusions. |
| `common-jvm-test`, `common-android-unit-test`, `common-metadata-compile` | Transcript, the `common-tests` leg — split into its three tasks. This is the gate whose bundling proved revision 1. |
| `backend-build` | Transcript, verbatim. |
| `desktop-build`, `common-jvm-test-source-set-ktlint` | Transcript, the `desktop` leg — split one-per-task. |
| `android-lint`, `android-unit-test` | Transcript, the `android` leg — split one-per-task. |
| `ampere-isolation`, `client-provider-isolation` | Transcript, the `isolation checks` gate — split one-per-task. Both tasks exist in the root build script and ride on every `check`/`ktlintCheck`, so `baseline: ci`. |

Three jobs that repo B's CI gained after the recon — a shared-wire-models leg, an
iOS app compile job, and an instrumented-test job — are **deliberately absent**
from v1. None is a required status check there, so none is part of that
repository's merge-blocking definition of done, and widening another team's DoD
is not this change's call. A comment in that manifest names them so the gap is
visible rather than forgotten.

### Repo C

No manifest. Ratified revision 4 of the verdict: that repository has no CI and no
green baseline, so a manifest would attribute a pre-existing red tree to whoever
ran it first. It lands after that repository's own cleanup establishes a baseline.

---

## 9. Validation performed for schema v1

The v1 restructuring was checked by extending the recon's throwaway dry-run
interpreter to parse v1 and echo both manifests' command lines, gate metadata,
`fresh`-derived flags, and per-step ids. It is evidence, not product — it lives
in the AMPR-304 workspace at `.context/ampr-304/` and is not committed. The
production interpreter is the Breaker's.

What that check establishes: both manifests parse, every gate id is unique, every
`requires`/`needs` reference resolves to a declared precondition, every gate has
exactly one of `run`/`steps`, and the echoed command lines are the intended
inventory. What it does not establish: that any gate passes. These manifests
describe gates; running them is the Breaker's job.
