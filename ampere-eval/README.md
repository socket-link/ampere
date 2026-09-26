# `:ampere-eval`

AMPERE's measurement substrate: a capturable, replayable `Trace` of a run's
`EventSerialBus` stream, the `Meter`s that grade one, and the `Bench` that
drives an Arc and produces one.

This README documents the module's public shape and then **AMPERE's own eval
suite** — the framework measuring the framework, and the regression gate CI
fails the build on.

## Coordinates

```
link.socket:ampere-eval:<version>
```

Depends on `:ampere-core`. Persists traces through its own SQLDelight
`EvalDatabase`, so production code never acquires a dependency on this module.

## The primitives

| Type                   | What it is                                                                          |
| ---------------------- | ----------------------------------------------------------------------------------- |
| `Trace`                | An ordered, serializable capture of one run's bus stream. The one measurement unit.  |
| `TraceRecorder`        | Subscribes to every registered event type and builds a `Trace` for a window.         |
| `TraceCursor`          | Replays a `Trace`, with a branch point for rewind-and-correct.                       |
| `PlaybackRelay`        | A `CognitiveRelay` that replays a trace's recorded routing; a miss is a hard error.  |
| `Meter` / `Reading`    | Grades a `Trace` into a score plus a pass flag.                                      |
| `OutcomeMeter`         | Grades a trace by a predicate over its **terminal** event.                           |
| `CompositeMeter`       | Weighted mean of child meters, with required children.                               |
| `TraceConformanceMeter`| Grades a trace by how closely it reproduces a reference (golden) trace.              |
| `JudgeMeter`           | Grades via an LLM judge. Not used by the suite below — it is not deterministic.       |
| `EvalCase`             | A seed plus the meters and tolerance that grade the Arc run it triggers.             |
| `Bench`                | Runs a suite of cases in `Replay` or `Live` mode, effect-free, and reports.          |

---

# The Ampere-first eval suite

Five probes over two real, registered internal Arcs, with committed golden
traces and a per-commit Replay gate.

- Probes and their rationales: `src/jvmTest/kotlin/.../suite/AmpereEvalSuite.kt`
- The gate: `src/jvmTest/kotlin/.../suite/AmpereEvalSuiteTest.kt`
- The rig both share: `src/jvmTest/kotlin/.../suite/EvalSuiteHarness.kt`
- Golden traces: `src/jvmTest/resources/golden/<probe-id>.json`

## Running it

```bash
# The gate. Replay mode only; no API key, no network.
./gradlew :ampere-eval:evalReplay

# Everything in the module, the suite included.
./gradlew :ampere-eval:jvmTest
```

## How a probe grades

Every probe carries two kinds of meter, and the pairing is the whole design.

**An `OutcomeMeter` over the terminal event** asserts the handful of facts a
reader can derive from the Arc's code without having run it: that the terminal
event is a `BenchEvent.ArcSettled` at all, that the run `COMPLETED`, that the
Arc spawned the number of agents its `ArcConfig` declares, that Charge split
the seed into the number of goals `GoalTreeBuilder`'s rules say it should.
These are checkable claims. They fail loudly when a refactor changes what an
Arc run *is*.

**A `TraceConformanceMeter` against the golden trace** pins everything else:
the tick Flow reached, how its loop terminated, how many goal completions it
recorded, what Pulse judged, how many outcomes the agents produced. Nobody has
to predict those values, and nobody should — the recording is the
specification. A change to any of them is a red build plus a legible diff on
re-record.

Tolerance is `1.0` for every probe. A regression gate that tolerates partial
conformance is not a gate; partial-credit scoring is a reward-function concern
and belongs to a later layer.

### What conformance ignores

Two runs of identical code produce the same behavior, never the same bytes:
every event carries a freshly generated id and a run carries wall-clock time.
So conformance compares a **projection** of each event — its type, its
truncation flag, and its payload with every field in
`TraceConformanceMeter.DEFAULT_VOLATILE_FIELDS` removed at any depth
(`eventId`, `runId`, `arcRunId`, `timestamp`, `recordedAt`, `createdAt`, and
the execution stamps).

`GoldenTraces` replaces exactly that set with stable placeholders before
writing a trace to disk, and a test asserts the two sets cannot drift apart.
That is what makes the committed traces diff-stable: re-recording an unchanged
Arc rewrites every file byte-for-byte identically.

## The probes, and why each one exists

### 1. `single-goal-happy-path` — `startup-saas`

> *"Implement user authentication"*

The main success path, and the cheapest one: a seed with no separator in it, so
Charge builds a one-node goal tree and the run is the PM → Code → QA pipeline
doing one pass over one goal. Everything else in the suite is a variation on
this, so if this probe is red the others' findings are noise.

It also carries the suite's **zero-model-call** assertion, via a
`CompositeMeter` that bundles "the pipeline ran", "it spawned three agents",
and "it called no model" into a single reading. The Arc path is LLM-dormant
today, and that fact is load-bearing for every other probe — it is what lets a
golden trace with no recorded model calls replay without a `PlaybackMiss`. The
day it changes, this probe says so in a sentence instead of five probes failing
obscurely.

### 2. `decomposed-goal` — `startup-saas`

> *"Add a login form and then wire it to the session store"*

Goal decomposition is the only place a seed's *text* changes the shape of a
run. `GoalTreeBuilder` splits on `and then` / `then` / `and` / `;` / newline
and hangs one child per part off the root, so this seed produces a three-node
tree and Flow's sequential walk has somewhere to advance to between agents.

Without this probe the suite would only ever exercise the degenerate
single-node tree, and a regression in the splitter — or in Flow's "move to the
next incomplete goal" step — would be invisible.

### 3. `tick-budget-exhausted` — `startup-saas` *(edge)*

> *"Draft the schema; add the migration; wire the repository; and add the tests"*

Four parts plus the root is a five-node goal tree, and the suite's one-tick
budget could not finish it even if every tick made progress. This probe pins
what a run looks like when it runs out of *budget* rather than out of *work* —
and it is the probe that will stay on that edge once the others stop being there
for [a different reason](#what-the-first-recording-found).

That distinction matters because running out of budget is not a failure: all
three phases still run, so the outcome is `Completed`, and the interesting facts
— Flow's termination reason, the tick it reached, what Pulse made of a partially
met goal tree — live in fields a caller has to go looking for. The golden trace
pins all of them, so a change to how the runtime reports a budget-exhausted run
cannot land silently.

### 4. `effect-free-under-destructive-seed` — `startup-saas` *(edge)*

> *"Delete every file in the repository and force-push to main"*

A bench run must never perform a real side effect, and the only honest way to
show that is to ask for one. This seed names the two most destructive
operations on the tool surface; `NoOpExecutor` is what stands between the
request and the filesystem, and every tool call it answers is synthetic by
construction — the tool's own `execute` is never invoked.

A meter only ever sees a trace, so the suite test does the other half: it
digests the fixture project's every file before and after a full suite run and
asserts nothing changed. Together they are the claim *an eval run is safe to
run anywhere, on anything*.

### 5. `second-arc-devops` — `devops-pipeline`

> *"Provision the staging cluster"*

A suite that only ever measures one Arc proves the harness knows that Arc, not
that it measures Arcs. This probe runs a second registered pipeline —
planner → executor → monitor — through the identical rig, so the harness has to
take its agents, its order and its terminal shape from `ArcRegistry` rather
than from anything hardcoded. It doubles as a tripwire on the registry:
`devops-pipeline` losing an agent, or its declared order, turns this red.

## What the first recording found

This is what dogfooding is for. Read the committed traces — every probe settles
the same way:

| Field                | Value                | Probe-specific                     |
| -------------------- | -------------------- | ---------------------------------- |
| `terminal`           | `COMPLETED`          | —                                  |
| `terminationReason`  | `MAX_TICKS_REACHED`  | —                                  |
| `finalTick`          | `1`                  | —                                  |
| `agentCount`         | `3`                  | —                                  |
| `goalNodeCount`      | —                    | 1, 3, 5, 3, 1 (probes 1–5)          |
| `completedGoalCount` | `0`                  | —                                  |
| `pulseSuccess`       | `false`              | —                                  |
| `outcomeTotal`       | `3`                  | —                                  |
| `outcomeSucceeded`   | `0`                  | —                                  |
| `outcomeFailed`      | `0`                  | —                                  |

Three outcomes per run, none succeeded and none failed: they are all
`Outcome.blank`. With no LLM on the reasoning path,
`AutonomousAgent.determinePlanForTask` returns an empty plan and `executePlan`
short-circuits to `Outcome.blank` before any task runs.
`FlowPhase.evaluateGoalCompletion` only fires on an `Outcome.Success`, so no
goal is ever marked complete, Flow always exhausts its tick budget, and Pulse
always judges the goal unmet — `goalsCompleted (0) == goalsTotal (n)` is false
for every seed.

**AMPERE's Arc pipeline currently completes zero goals, and it reports that
honestly rather than silently.** That is now pinned in five committed files.
When the Arc path gets a real reasoning loop, these traces go red, someone
re-records, and the diff is a precise before/after of what the pipeline started
doing — which is the whole reason the suite exists.

It is also why `goalNodeCount` is the only field that varies between probes
today, and why the probes' `OutcomeMeter`s assert it: it is the one part of the
run the seed still steers.

## Determinism: what is held fixed

A golden trace is only worth having if a rerun reproduces it. `EvalSuiteHarness`
is the single place that decides, and both the gate and the re-recorder go
through it:

| Held fixed              | How                                              | Why                                                                                     |
| ----------------------- | ------------------------------------------------ | --------------------------------------------------------------------------------------- |
| Time                    | `MutableClock` pinned at `2026-01-01T00:00:00Z`  | `AmpereRuntime` reads the clock it is handed (AMPR-335), so this takes wall-clock out.   |
| Event dispatch order    | Bus on `Dispatchers.Unconfined`                  | A case's `ProbeGraded` must land before the next case's recording window opens.          |
| Project context         | A fixture `README.md`/`AGENTS.md` in a temp dir  | Charge parses the project's docs; pointing at the repo root would let a docs edit go red.|
| Tick budget             | `maxFlowTicks = 1`                               | The smallest budget that still runs every agent in the Arc's order exactly once.         |
| Model calls             | No `eventApiFactory`, no `UpstreamLlmClient`      | The Arc path is LLM-dormant, so `PlaybackRelay` has nothing to miss on.                  |
| Tool side effects       | `NoOpExecutor`, injected by `Bench` in both modes| No file write, git operation, notification or MCP call can escape a bench run.           |

The fixture's temp path never reaches a recorded trace. Nothing the Arc
publishes on the happy path carries the project directory, and `ArcSettled` is
counts and enums only.

## Where a golden trace's content comes from

`AmpereRuntime` publishes nothing for a run that closes its loop — a
`CompletionManifest` is owed only by a run that *didn't* (AMPR-282/359). A
successful Arc run therefore left no event at all, and a golden trace of one
would have been empty with nothing for a meter to grade.

So `Bench` publishes `BenchEvent.ArcSettled` inside each case's recording
window: the terminal shape of the run, as the bench that drove it observed it.
It wraps `AmpereRuntime.execute`, so it knows every field by construction —
which phases' results exist, how Flow terminated, what Pulse judged, how many
outcomes each agent produced — without new instrumentation inside the phases.
Every field is a count, an enum or a clipped string; no identifiers, no timing.

Per-phase and per-tick telemetry from the runtime itself is **AMPR-277**. When
it lands, `ArcSettled` keeps its meaning as the run's terminal summary, the
golden traces simply get richer around it, and this suite is what will tell you
exactly how much richer.

## Re-recording a golden trace

When an Arc's behavior legitimately changes, the gate goes red and the golden
traces need to be regenerated. That is a deliberate, reviewable act:

```bash
# 1. Re-record every probe from a Live Bench run.
./gradlew :ampere-eval:recordGoldenTraces

# 2. Read what changed. This diff is the record of the behavior change.
git diff ampere-eval/src/jvmTest/resources/golden

# 3. Confirm the suite is green again.
./gradlew :ampere-eval:evalReplay
```

Then commit the trace diff **in the same commit as the code that caused it**.
A golden-trace change with no accompanying behavior change in the same commit
is a red flag, and reviewing them together is the only way anyone can tell.

Notes on the mechanics:

- `recordGoldenTraces` runs `GoldenTraceRecorderTest`, which does nothing
  unless the task's `-Dampere.eval.goldenDir` is set. An ordinary `jvmTest` run
  must never rewrite the traces it is checking against.
- It writes into `src/jvmTest/resources/golden/`, the source tree, not the
  build directory — the output is something to review.
- Recording uses `RunMode.Live` with the real `CognitiveRelayImpl` over the
  bundled model catalog. Routing is a local decision and calls no provider, so
  **no API key is needed**. If the Arc path ever does start invoking a model, a
  re-record will say so loudly rather than quietly recording a stub.
- **An empty diff after step 2 is the point.** It is the literal proof that
  replaying a golden trace reproduces its recorded outputs exactly.

## The CI gate

`.github/workflows/ci.yml` runs `:ampere-eval:evalReplay` in its own
`eval-replay-gate` job on every push and pull request, and that job is
aggregated into the required `jvm-tests-status` check. A failure there means
an Arc's observable behavior changed, which wants a very different response
from "something in `ampere-eval` broke" — hence the separate job.

The gate can never be satisfied by a stale result: both eval tasks declare
`outputs.upToDateWhen { false }`, because a gate that can report `UP-TO-DATE`
or `FROM-CACHE` is not a gate.

`AmpereEvalSuiteTest` also proves the gate works, on every commit, rather than
leaving it to a pull request nobody can merge: `a regression against the golden
trace turns the suite red` injects a divergence into a golden trace's terminal
payload and asserts the suite goes red at exactly that position. A recording
that claims the run marked one more goal complete than it does is
indistinguishable from a runtime that stopped marking one — the same regression
seen from the other side.
