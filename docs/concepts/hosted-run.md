---
concept: HostedRun
status: experimental
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/propel/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/api/AmpereFromEnvironment.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/api/AmpereInstance.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/api/service/AgentService.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/api/internal/DefaultAgentService.kt
related: [PropelLoop, TeamLayer, SparkSystem, MemoryProvenance, CognitionTrace, Probe]
last_verified: 2026-10-10
---

# Hosted Run

## What it is

A **hosted run** is one PROPEL run a consumer opens for one goal, over a roster of
seats, from the public API. `RunHost.open(roster, seats, goal, tools, policy)` charges
PERCEIVE → RECALL → OBSERVE → PLAN on the roster's **host seat** and returns a
`HostedRun` whose plan has already passed the consumer's `PlanGate`;
`HostedRun.execute()` is EXECUTE plus a re-entry into OBSERVE → PLAN while cycles
remain; `HostedRun.close()` is LEARN.

The vocabulary:

| Term | Meaning |
|------|---------|
| **roster** | A set of roles with one host (`roster/`: `Roster`, `RoleConfig`, `RoleId`). A value, serialisable, authored by the consumer. |
| **seat** | A role filled by one agent for one run. A `HostedAgent` says who fills it. |
| **host seat** | The role that plans. `Roster.host`. |
| **verifier seat** | The optional role whose Probes run in OBSERVE. `Roster.verifier`. |

The consumer supplies the roster, the seats, the goal, the tools and a policy. It
never constructs an agent: the run builds one `SparkBasedAgent` per seat, with that
seat's sparks stacked, its own event door, and the run id on every event it publishes.

## Why it exists

`0.17.0` offered no entry point for a single run. The phase services were public and
worked (`PerceptionEvaluator`, `PlanGenerator`, `ToolExecutionEngine`,
`OutcomeEvaluator`, `KnowledgeExtractor`) and `SparkBasedAgent` composed them, but
`AutonomousAgent.runtimeLoop()` was `protected` and infinite, `AmpereRuntime` needed a
project directory and built `CodeState` agents, and every `SparkBasedAgent.runLLMTo*`
call blocked under `runBlockingCompat` with a 60 s timeout — which throws outright on
JS and wasm. An embedding consumer therefore assembled the loop itself, twice. This is
that loop, moved into Ampere, so the consumer can delete its copy.

## Invariants

1. **The run plans.** A consumer supplies a goal, never a step list. The host seat's
   PLAN produces `Task.Step`s over the roster's seats; `HostedRun.plan` is an
   **output**.
2. **Every event carries the run id on its envelope**, through the seat's own door.
   `ArcTraceProjection.project(runId)` is therefore the whole run, not a guess
   assembled from payloads (F4, AMPR-385 row H2).
3. **A seat's provenance is its door's identity.** Work done by seat A publishes
   through `createEventApi(A.id)`. Two seats of one run must not share an agent id, and
   `RunHost.open` refuses when they do.
4. **All six phases are bracketed**, on the host seat, by `PhaseEntered`/`PhaseExited`.
   Brackets are on by default *inside* a hosted run; the default elsewhere stays off
   (row H3). A phase that found nothing is still a bracketed phase — "entered and empty"
   and "skipped" are different facts.
5. **Suspend-only.** No `runBlockingCompat`, no `expect`/`actual`, no timeout inside
   the run; the caller owns the scope. `PropelPackagePurityTest` fails the build if a
   source under `propel/` breaks any of the three.
6. **`close()` runs under `NonCancellable`**, and a cancelled step publishes its
   completed pair before the run rethrows. A run that happened always records that it
   happened.
7. **LEARN runs once per run, not once per cycle**, and the default bills nothing:
   `KnowledgeExtractor.extractDefault` per seat that executed a step, plus one
   `OutcomeMemoryRepository.recordOutcome` for the run keyed by the run id.
   `LearnPolicy.ModelBacked` adds `OutcomeEvaluator` for the host seat and is opt-in
   because it bills.
8. **A seat runs only the tools its role declares**, intersected with the run's
   `tools`. Each reaches the seat under the seat-namespaced id `<seat>/<tool>`
   (`SeatTool`), which is the id the planner is offered and the id dispatch resolves.
9. **One loop.** EXECUTE dispatches every step through
   `SparkBasedAgent.executePlanStep` — the same entry the Arc path uses — so tool
   narrowing, reasoning steps, prior results and inline arguments have one definition.
   There is no second planner.
10. **A non-code agent is first class.** No `CodeState`, no project directory, no
    workspace required.

## How it composes

```kotlin
val roster = RosterConfig(
    host = RoleId("planner"),
    roles = listOf(
        RoleConfig(id = RoleId("planner"), title = "Planner", instructions = plannerPrompt),
        RoleConfig(
            id = RoleId("scout"),
            title = "Scout",
            instructions = scoutPrompt,
            tools = setOf("web_search"),
        ),
    ),
)

val run = ampere.runs!!.open(
    roster = roster,
    seats = mapOf(
        RoleId("planner") to HostedAgent(
            id = "seat-planner",
            role = RoleId("planner"),
            sparks = listOf(Spark.fromMarkdown("planner", plannerSparkBody)),
            aiConfiguration = sonnet,
            upstreamLlmClient = myProxy,
            memory = myStore,
        ),
        RoleId("scout") to HostedAgent(/* … */),
    ),
    goal = Task.Step(id = "goal-1", status = TaskStatus.Pending, description = "Find the retry policy"),
    tools = setOf(webSearchTool),
    policy = RunPolicy(cycles = 2, planGate = myReviewSheet),
)

try {
    val result = run.execute()      // EXECUTE, then OBSERVE → PLAN → EXECUTE again
} finally {
    run.close()                     // LEARN, also on cancel
}
```

`HostedRun` is a `CoroutineContext.Element`, so a consumer can carry the run in its own
context (`withContext(run) { … }`) and read it back as `coroutineContext[HostedRun]`.
The run does not put itself there — where it is visible is the caller's decision.

## The public surface

| Type | Role |
|------|------|
| `RunHost` | `open`, `openSeats(roster, seats: List<HostedAgent>, …)` (the Swift-friendly overload), `openRunSeats(): List<OpenSeat>` |
| `HostedAgent` | Who fills a seat: id, role, sparks, charter, `AIConfiguration`, `CognitiveRelay?`, **required** `UpstreamLlmClient`, `MemoryStore?`, affinity, `probes` |
| `HostedRun` | `runId`, `plan`, `execute()`, `close()` |
| `RunPolicy` | `cycles`, `learn`, `planGate` |
| `PlanGate` / `PlanDecision` | `review(plan)` → `Approved(plan)` (possibly trimmed) or `Stopped(reason)` |
| `RunResult` / `RunStatus` | `cyclesUsed`, `stepOutcomes`, `outcome`; `Succeeded` / `Failed` / `Stopped` |
| `RunObservation` | What OBSERVE hands the verifier's Probes: run id, goal, cycle, prior results |
| `rosterRunHost(...)` | The shipped implementation, for composing without an `AmpereInstance` |

`Ampere.fromEnvironment` exposes one as `AmpereInstance.runs`, and `agentScope` is
documented as the caller's to own. `AgentService.team {}` declares the roster and
`AgentService.pursue(goal)` opens a run over it; `AgentService.listAll()` and
`inspect()` report the seats of open runs.

## Anti-patterns

- **Reaching the phase services through the agent's `runLLMTo*` lambdas.** They are
  the same services wrapped in `runBlockingCompat` and a 60 s timeout. A hosted run
  uses `SparkBasedAgent.reasoningUnit`, which is `internal` for exactly this reason:
  the un-bracketed, un-recorded call must not be the easy one.
- **Re-implementing step dispatch in the run.** `executePlanStep` already enforces
  strict tool-id routing against the seat's narrowed tool set, carries out a tool-less
  step as that seat's model call, and puts the prior results and inline arguments on
  the request. A second copy is three behaviours to keep in step.
- **Deriving a seat's planner tool ids from `RoleConfig.tools` instead of from the
  seat's actual tools.** `PlanGenerator` looks a tool's description up in
  `availableTools` by id and lists anything unmatched under "Unassigned tools"; the two
  must come off the same objects, which is why the run namespaces the tool objects
  rather than only the declared ids.
- **Expecting a plan with no steps from the planner.** `PlanGenerator` turns an empty
  `steps` array into a one-step *fallback* plan. With a transport wired — which
  `HostedAgent` requires — a plan with no steps is what a gate left, or `Plan.Blank`.
- **Publishing a run's own `TaskCreated` before PLAN.** The plan is the first thing
  about a run there is anything to say about, and the lifecycle opens once, on the
  first cycle, *before* the gate — so a plan a person stopped is still on the record.
- **Assuming per-seat memory is separated.** Ampere's `KnowledgeStore` has no holder
  column, so two seats sharing a `MemoryStore` recall each other's knowledge.
  Separation is whatever the consumer's store scopes (row H22, no verdict).

## Not implemented here

AMPR-385 rows H19–H25 were proposed on 2026-10-09 and carry **no verdict**, so none of
them is built: the shared tick functions and judgment seams (H19), four-valued step and
run results with dependent-skipping and `maxStepsPerCycle` (H20),
`HostedAgent.upstreamDecisionClient` (H21), a `holder` column for per-seat memory
(H22), the F19–F21 reconciliation (H23), `External` provenance on a step result (H24),
and re-gating a step whose arguments changed after approval (H25). The "Recalled
context" prompt section and the `RecallSource` SPI are AMPR-394's.

## See also

- [PropelLoop](propel-loop.md) — the six phases and the services behind them
- [TeamLayer](team-layer.md) — the roster types a run is opened over
- [CognitionTrace](cognition-trace.md) — what `project(runId)` reads back
- [MemoryProvenance](memory-provenance.md) — what LEARN writes
