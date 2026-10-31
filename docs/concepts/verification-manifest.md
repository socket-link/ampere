---
concept: VerificationManifest
status: experimental
tracked_sources:
  - .ampere/verify.yml
  - docs/verify-manifest.md
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/execution/verify/VerifyManifest.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/execution/verify/GateOutcome.kt
  - ampere-core/src/jvmMain/kotlin/link/socket/ampere/agents/execution/verify/VerifyManifestParser.kt
  - ampere-core/src/jvmMain/kotlin/link/socket/ampere/agents/execution/verify/GateCommand.kt
  - ampere-core/src/jvmMain/kotlin/link/socket/ampere/agents/execution/verify/GateRunner.kt
  - ampere-core/src/jvmMain/kotlin/link/socket/ampere/agents/execution/verify/GateClassifier.kt
  - ampere-core/src/jvmMain/kotlin/link/socket/ampere/agents/execution/verify/Preconditions.kt
  - ampere-core/src/jvmMain/kotlin/link/socket/ampere/agents/execution/verify/LogExcerpts.kt
  - ampere-core/src/jvmMain/kotlin/link/socket/ampere/agents/execution/verify/ManifestSource.kt
related: [Probe, CancellationAddress, LifecycleTypes, PropelLoop, Switchboard, DispatchJournal]
last_verified: 2026-10-31
---

# VerificationManifest

## What it is

`.ampere/verify.yml` is a repository's definition of done, declared as data: one
file per repository, an ordered list of **gates**, each one command with an exit
code. A gate carries the metadata a runner needs to execute it and to classify
what happened — `fresh`, `timeout_minutes`, `retry_safe`, `side_effects`,
`parallel_safe`, `requires`/`needs`, `baseline` — and nothing else.

The manifest does not run. The runner that executes it is `GateRunner` (AMPR-309),
which the [Switchboard](switchboard.md) calls after a dispatched agent stops; the
manifest is the contract it reads. Schema v1 and the per-field reference are in
[`docs/verify-manifest.md`](../verify-manifest.md).

AMPR-309's ticket calls the whole verification gate "the Breaker" — breakers
protect. In code it is the runner here plus `Switchboard.settleByVerification`, and
nothing in it is `propel/Breaker`, which stays admission only (F14).

Every gate reports exactly one of four outcomes: `passed`, `failed`
(agent-attributable), `infrastructure` (host-attributable, escalates about the
machine, does not consume the agent's retry budget), `not_executed` (a named
precondition was absent).

## Why it exists

An agent that decides for itself when it is done is grading its own work. A CI
configuration already encodes the real answer, but it encodes it in a form that
is specific to one provider, interleaved with caching, concurrency and path
filters, and unreadable as a list. The manifest is the list, extracted, in the
repository it describes, so the agent and the person read the same thing.

It is schema-first and not code-first because the feasibility question was
empirical: the [AMPR-290](https://github.com/socket-link/ampere/issues/702) recon
enumerated **and executed** every definition-of-done gate across three
repositories — 16 invocations, 12 pass, 4 real failures, 0 untested — and found
that one declarative shape covered all of them. Every construct in v1 exists
because that execution needed it; every v1 revision of the v0 draft is a repair
for something that execution broke.

## Invariants

- **One task per gate.** A gate's `run` is exactly one command. The ratifying
  evidence is repo B's `common-tests` leg: three Gradle tasks in one invocation,
  the third failed, and fail-fast meant `:common:jvmTest` never ran. One exit code
  covered three questions and answered one. A bundled gate reports the failure it
  can attribute and silently withholds the coverage it never obtained. One task
  means one task *named on the command line*, not one task in the resulting graph
  — `:ampere-core:jvmTest` legitimately drags in `verifyAmpereVersionConstant` and
  `verifySqlDelightMigration`, which cannot be reached independently anyway.
- **Attribution is structural, never parsed.** A gate id is literal, unique, and
  the key a report is written against. This is why v1 has no `matrix:` — an
  expanded `jvm-test-${leg.module}` is a computed id, and a computed id is a worse
  attribution key than an enumerated one.
- **Exit code is the only pass/fail contract.** There is no construct for
  matching output, and a gate's command may not mask its exit code — no
  `|| true`, no un-`pipefail`ed `| tee`. The same gate was observed failing with
  two different causes across retries under memory pressure while the exit code
  was correct in all 16 observed runs. Logs may be quoted into a report; they may
  not decide anything.
- **Order never short-circuits.** Gates run in listed order so the cheapest
  signal lands first, and every gate runs regardless of every other gate's
  outcome. Fail-fast between gates is the bundling bug one level up: a red ktlint
  would leave every test gate unreported. This is why v1 has no `stages:`.
- **`fresh: true` means the verdict came from work done on this host in this
  run.** It declares intent; the runner derives the mechanism per tool
  (`--no-build-cache` plus deleting the gate's own test results, for Gradle). It
  belongs on gates whose value is the act of running — tests — because a build
  cache was observed satisfying a test gate in 3 seconds having executed zero
  tests. A lint or compile gate asserts a property of the sources, which the cache
  key genuinely covers.
- **Infrastructure failure is an outcome, not a retry.** A timeout, an unset
  `${env.VAR}`, or a broken declared facility means the gate produced no verdict
  *about the code*. Charging it to the agent spends the retry budget on the host's
  problem and then blames the code. The classifying evidence is per-gate metadata
  — `baseline`, `requires`/`needs`, `timeout_minutes`, `retry_safe` — never a log
  pattern.
- **A precondition is never a silent skip.** Hard (`env.requires`) absent means
  the whole run is **refused** and every gate reports `not_executed` naming the
  blocker. Soft (`env.optional`) absent means only the gates that `need` it report
  `not_executed`. A reference to an undeclared precondition id is a manifest
  error, not a skip.
- **Local evidence convicts but does not acquit.** A gate red here is a real
  failure of this host. A gate green here has not been proven on CI's hardware.
  The standing proof is `:ampere-core:assemble`: red 3/3 on an M1 host via a
  Kotlin/JS backend exception alternating with daemon heap exhaustion, green in CI
  on the identical command. Same four-valued stance as [Probe](probe.md)'s
  `Undetermined` — absent or divergent evidence is not a pass.
- **`baseline` says where a green result is observable off this host.** `ci`,
  `release`, or `none`. A `baseline: none` gate has no acquittal available
  anywhere, so its first result establishes a baseline rather than attributing a
  fault — which is also why repo C gets no manifest until it has CI.
- **`steps` sequences; it does not bundle.** Permitted only where no later step
  has a standalone verdict (`test-without-building` cannot run without the build
  before it), so stopping at a failure withholds nothing obtainable. The gate's
  outcome names the failing step id, and `fresh` attaches to the last step —
  the one that produces the verdict.
- **A precondition must be cheap and unambiguous to check.** Four predicates:
  `toolchain`, `file`, `command`, `simulator`. Anything the build tool resolves
  and fails loudly about — the Android SDK location, a dependency, a plugin
  version — is left to the build tool and surfaces as `infrastructure`.
  Re-implementing Gradle's resolution in YAML would create a second source of
  truth that can disagree with the first.
- **Host policy stays out of `run`.** No `--max-workers`, no `--parallel`, no heap
  settings, no cache flags. Keeping them out is exactly what lets a gate's command
  be checked against CI's by eye, which is the property that makes
  one-task-per-gate auditable at all.

## Where it lives

- `.ampere/verify.yml` — this repository's 19 gates. Tracked despite `.gitignore`
  excluding `.ampere/` (a developer's local `config.yaml` can hold credentials),
  via `.ampere/*` plus a `!.ampere/verify.yml` negation — git cannot re-include a
  path under an excluded directory.
- `docs/verify-manifest.md` — schema v1: the four ratified revisions and why each
  one exists, the field reference, precondition and outcome semantics, what is
  deliberately *not* a gate, and the provenance of every committed gate.
- Repo B's own `.ampere/verify.yml` — 11 gates, exactly its five required status
  checks split one-per-task plus its two architectural-isolation tasks.
- `agents/execution/verify/` (AMPR-309) — the runner. `VerifyManifest.kt` and
  `GateOutcome.kt` (common): the parsed manifest, the four outcomes, the run's
  `VerificationVerdict`, and the `GateRecord` the dispatch journal stores.
  `VerifyManifestParser.kt` (JVM): schema v1, every violation reported with its field
  path. `GateCommand.kt`: a `run` as an argument vector, the one-task rule, `${env.VAR}`,
  the Gradle `fresh` mechanism. `Preconditions.kt`: the four predicates on this host.
  `GateRunner.kt`: execution. `GateClassifier.kt`: the infra-versus-agent step.
  `LogExcerpts.kt`: the anchored lines an escalation may quote. `ManifestSource.kt`:
  the manifest from the base revision. Verification of finished work, not admission:
  F14 (locked 2026-10-11) keeps post-conditions out of the admission Breaker. See
  "Where `Rule.Requirements` lands" below.
- Tests: `ampere-core/src/jvmTest/.../execution/verify/` — `VerifyManifestParserTest`
  (both committed manifests' shapes, every violation), `GateRunnerTest` (real
  processes, a fake `gradlew` for `fresh`), `GateClassifierTest`, `GateCommandTest`,
  `LogExcerptsTest`, `BaseRevisionManifestSourceTest`.

## Where `Rule.Requirements` lands

`Rule.Requirements` (`field/Constraint.kt`) is the data form of the three Boolean
fields of the retired `ExecutionConstraints` — `requireTests`, `requireLinting`,
`allowBreakingChanges`. Each is a **post-condition**: whether the tests pass, the
lint is clean, or an API broke is a fact about work that has finished. The
Breaker admits a step *before* it runs, so it cannot check any of them, and
F14 routes the rule here, to verification, rather than to admission (AMPR-445).
A Breaker that is handed one anyway reports it as a rule it cannot evaluate,
which fails the step closed; it never passes it.

| `Rule.Requirements` field | What verifies it | In this repository |
|---------------------------|------------------|--------------------|
| `requireTests` | the manifest's test gates — the ones whose value is the act of running, so `fresh: true` | `jvm-test-*`, `android-unit-test-*`, `ios-kotlin-test-ampere-core`, `eval-replay` |
| `requireLinting` | the manifest's lint gates | `ktlint` |
| `allowBreakingChanges = false` | a gate that diffs the public API against a baseline | none — no API-compatibility task exists to name, so this field has no gate yet |

What this does not do yet: nothing reads a `Rule.Requirements` and selects gates
from it. The mapping above is the routing F14 decided; evaluating a requirement
against a manifest — and a requirement naming a gate the manifest lacks, as
`allowBreakingChanges` would today — belongs to the runner, which exists since AMPR-309 but reads no
`Rule.Requirements` yet.
Admission and verification stay separate questions: admission asks whether a
step may start, verification whether finished work is done, and a rule answers
only one of them.

## The runner's invariants (AMPR-309)

- **The manifest comes from the base revision, never the worktree.** The agent writes
  the worktree; an agent that deleted its red gate from its own copy would pass the
  gate it deleted. `BaseRevisionManifestSource` reads `<base>:.ampere/verify.yml`.
- **No shell.** A gate's command runs as an argument vector; the parser refuses
  unquoted shell operators and every interpolation but `${env.VAR}`, which is
  substituted per argument after splitting.
- **One gate at a time per repository**, in listed order, every one of them, even when
  `parallel_safe: true` — Gradle daemon and lock contention (the AMPR-290 recon).
- **`fresh` on Gradle is `--no-build-cache`, the gate's own results deleted, and
  `--rerun` on its one task.** `--rerun` is the per-task `--rerun-tasks`: the verdict
  task executes, upstream compilation may still come from the cache.
- **A failure is examined before it is charged**, structurally: the gate's own
  preconditions are re-checked, and a `retry_safe` gate (not `side_effects: network`)
  is re-run once. Then `GateClassifier` decides, by the order its KDoc lists — exit 0;
  interrupted; never started; timed out; exit 126/127; a precondition broke; did not
  reproduce; otherwise the agent's. Unknown is the agent's: only `passed` surfaces
  anything, so no misclassification can surface a bad merge request.
- **A timeout terminates the gate's whole process group** before it reports, and each
  gate's group is journaled while it runs, so a crashed supervisor's build is
  reapable.
- **The run's verdict has a precedence**: `refused` > `interrupted` > `host_failure` >
  `agent_failure` > `incomplete` > `green`. Any infrastructure gate makes the whole run
  untrustworthy, so the agent is not charged for a failure beside it.

## Common operations

- **Read a repository's definition of done** — `cat .ampere/verify.yml`. The gate
  ids are the vocabulary a report will use.
- **Add a gate** — append a list entry with a literal id, one command, and every
  required field. Ask first whether the command belongs: see the "what is not a
  gate" table in the schema doc. A check that cannot fail is not a gate.
- **Split a bundled CI leg into gates** — one gate per task, in cheapest-first
  order, each with its own id. Keep each `run` byte-comparable to the CI command
  minus resource flags.
- **Decide `fresh`** — does the gate's value lie in the act of running (test) or
  in a property of the sources (lint, compile, assemble, scan)? Only the former.
- **Decide `baseline`** — does a hosted job run this exact task on every change
  (`ci`), only on the release path (`release`), or nowhere (`none`)?
- **Keep a manifest honest after a CI change** — the manifest is a second
  declaration of the same facts, so it drifts. Six weeks between the recon and v1
  produced five new Ampere gates and three unrepresented repo B jobs. Diff the
  manifest against `ci.yml` and the required-check list when either moves.

## Anti-patterns

- **Parsing a log to decide pass/fail, or to classify infrastructure.** The exit
  code decides the first; per-gate metadata decides the second. Failure text was
  measured non-deterministic for the same gate under memory pressure, which is
  worst exactly where classification matters most.
- **Bundling tasks "because CI does".** CI bundles to save runners. A manifest
  that copies the bundling inherits the partial-coverage failure and gains
  nothing — the runner is not paying per leg.
- **Checking a requirement before the work exists.** `Rule.Requirements` reads
  like a gate on a step, and a runner that checked it at admission would be
  grading work that has not been done — every requirement passes vacuously
  against an empty change. It is a post-condition and runs here, against
  finished work (F14, AMPR-445).
- **A static per-gate "this fails locally, ignore it" annotation.** Tempting for
  `:ampere-core:assemble`, and it would permanently misclassify a real regression
  in that gate as the host's fault. `baseline` says where to look for an
  off-host green; it does not pre-decide the verdict.
- **Treating `not_executed` as a pass.** It is absent evidence with a named
  blocker. Rendering it green is the soft pass [Probe](probe.md) forbids for
  `Undetermined`; rendering it red blames the agent for the operator's machine.
- **A conditional gate (`when:`).** No repository had one. CI path filters are
  cost optimisations, not definition-of-done semantics: the runner always runs
  every gate.
- **Adding gates to another repository's manifest because they look useful.** A
  gate is in the manifest because that repository's merge-blocking definition of
  done contains it. Repo B's three non-required CI jobs are named in a comment
  there and left out, because widening another team's DoD is that team's call.
- **Classifying by `baseline`.** A red `baseline: ci` gate is environment-suspect,
  but confirming the off-host green needs a forge, and the v1 runner is local-host
  only; it records the baseline in every result and escalation and decides nothing
  with it.
- **Letting a gate outlive its `timeout_minutes` because the runner has no way to
  stop it.** A Gradle daemon or a booted simulator survives the process that
  spawned it; a timeout that cannot actually kill the work is a timeout that
  reports `infrastructure` while still holding the machine. The address-based stop
  in [CancellationAddress](cancellation-address.md) is the mechanism this field
  assumes.
- **Writing the manifest from `ci.yml` alone.** The recon's whole value was that
  it *ran* every gate: three hazards — CI-green/locally-red, cache-satisfied test
  gates, non-deterministic failure text — were invisible in the configuration and
  all three shaped the schema.
