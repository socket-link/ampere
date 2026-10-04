---
concept: VerificationManifest
status: experimental
tracked_sources:
  - .ampere/verify.yml
  - docs/verify-manifest.md
related: [Probe, CancellationAddress, LifecycleTypes]
last_verified: 2026-10-04
---

# VerificationManifest

## What it is

`.ampere/verify.yml` is a repository's definition of done, declared as data: one
file per repository, an ordered list of **gates**, each one command with an exit
code. A gate carries the metadata a runner needs to execute it and to classify
what happened — `fresh`, `timeout_minutes`, `retry_safe`, `side_effects`,
`parallel_safe`, `requires`/`needs`, `baseline` — and nothing else.

The manifest does not run. The runner that executes it (the Breaker) is separate
work; the manifest is the contract it reads. Schema v1 and the per-field reference
are in [`docs/verify-manifest.md`](../verify-manifest.md).

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
- No runner. The production interpreter is the Breaker's (Wave 2). The v1
  structural check is a throwaway Python interpreter in the AMPR-304 workspace
  under `.context/ampr-304/`, deliberately uncommitted.

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
