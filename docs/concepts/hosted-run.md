---
concept: HostedRun
status: experimental
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/propel/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/api/AmpereFromEnvironment.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/api/AmpereInstance.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/api/service/AgentService.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/api/internal/DefaultAgentService.kt
related: [PropelLoop, TeamLayer, SparkSystem, MemoryProvenance, CognitionTrace, Probe, SubjectRegistry]
last_verified: 2026-10-11
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
11. **A judgment asked by the run is recorded and never read** (AMPR-421). A cycle
    that executed may be asked whether the goal is met, and the verifier seat may be
    asked its own declared questions; every answer leaves a `JudgmentRecorded` and
    nothing consults it. The loop, `currentStatus()`, LEARN and every `Verdict` are the
    same with a judgment as without one, and `RunResult` is identical whatever came
    back. Acting on one is AMPR-381 rows J6, J8 and J9, none of which has a verdict —
    the records exist so that AMPR-422 can measure whether a model answers well enough
    for a verdict to be taken at all.
12. **RECALL reads twice and reports once.** The host seat's own memory and every
    `RecallSource` the run was given, merged into one `KnowledgeRecalled` through the
    host's door (AMPR-394). The memory half reaches PLAN as `relevantKnowledge`; both
    halves reach *every* seat's active prompt as the "# Recalled context" section, so
    the seat that runs a step three phases later still knows what the run recalled.
    No model call: RECALL is two retrievals and a clip.

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
            recallScopes = setOf("uploader", "runbooks"),
        ),
        RoleId("scout") to HostedAgent(/* … */),
    ),
    goal = Task.Step(id = "goal-1", status = TaskStatus.Pending, description = "Find the retry policy"),
    tools = setOf(webSearchTool),
    policy = RunPolicy(
        cycles = 2,
        planGate = myReviewSheet,
        recall = RecallPolicy(sources = listOf(myDocumentCorpus)),
    ),
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

## What RECALL reads, and where it goes

Two reads and two destinations (row H4, AMPR-388 for a, AMPR-394 for b):

| Half | Query | Reaches |
|------|-------|---------|
| The seat's memory | `MemoryContext(taskType = GENERIC, tags = recallScopes, description = goal)`, through the host seat's `AgentMemoryService` | `PlanGenerator`'s `relevantKnowledge`, **and** the prompt section |
| The consumer's corpora | `RecallSource.recall(intent = goal, scopes = recallScopes, limit = perSourceLimit)`, per source | the prompt section only |

The task type is `MemoryTaskTypes.GENERIC` — the bucket a hosted run's own LEARN files
under — and the scopes are the context's *tags*, not its task type. AMPR-394 proposed
`taskType = agent.role`; that would have made a hosted run unable to recall the
Knowledge its own LEARN wrote, because Recall matches `task_type` exactly and LEARN
stores `GENERIC` with the role as a tag. The symmetric reading of the same intent is
the one implemented: the role and the scopes are tags on both sides.

The prompt section is rendered once, at the end of RECALL, and set on every seat. It
sits ahead of everything the spark stack contributes — which includes the seat's
charter, since a hosted run stacks the charter as a spark rather than passing it as
`buildSystemPrompt`'s `preamble`:

```markdown
# Recalled context

Drawn from past work and saved material; use it only where it bears on the task at
hand, and never repeat it back.

- Retried the upload with a capped backoff — Three attempts was enough…
- Uploader runbook — The uploader retries on 503 and gives up on 400
```

One line per item, best first, each half clipped (`titleClip` 200, `textClip` 400 by
default) with whitespace collapsed first, because the section is one line per item and
a learning written as three paragraphs is not. A run that recalled nothing renders no
heading at all — a heading over no items reads as "the agent remembered nothing
relevant", which is a claim a seat with no memory and no sources has no standing to
make.

Nothing recalled from a `RecallSource` is stored. Corpus items are typed `FROM_TASK`
in the event's `retrievedKnowledge` so a trace reader can tell the halves apart; the
type is a label on a summary, not a row in `KnowledgeStore`.

## The public surface

| Type | Role |
|------|------|
| `RunHost` | `open`, `openSeats(roster, seats: List<HostedAgent>, …)` (the Swift-friendly overload), `openRunSeats(): List<OpenSeat>` |
| `HostedAgent` | Who fills a seat: id, role, sparks, charter, `AIConfiguration`, `CognitiveRelay?`, **required** `UpstreamLlmClient`, `MemoryStore?`, `recallScopes`, affinity, `probes`, `questions`, `UpstreamDecisionClient?` |
| `HostedRun` | `runId`, `plan`, `execute()`, `close()` |
| `RunPolicy` | `cycles`, `learn`, `planGate`, `recall`, `goalCheck`, `judgmentResultClip` |
| `GoalCheck` | `Off` / `Record`: whether the run asks itself, after each executed cycle, whether the goal is met. `Record` is one decision call per executed cycle on the host seat's transport |
| `RunQuestions` | The run's own question (`goalMet`, `GOAL_MET_ID`) and `RunObservation.asDecisionState(clip)`, the fixed-key-order JSON a decision call is given |
| `RecallPolicy` | `sources`, `perSourceLimit`, `memoryLimit`, `titleClip`, `textClip` |
| `RecallSource` / `Recalled` | The consumer-implemented corpus SPI (`memory/`, beside `MemoryStore`) and one item of it |
| `PlanGate` / `PlanDecision` | `review(plan)` → `Approved(plan)` (possibly trimmed) or `Stopped(reason)` |
| `RunResult` / `RunStatus` | `cyclesUsed`, `stepOutcomes`, `outcome`; `Succeeded` / `Failed` / `Stopped` |
| `RunObservation` | What OBSERVE hands the verifier's Probes: run id, goal, cycle, prior results |
| `rosterRunHost(...)` | The shipped implementation, for composing without an `AmpereInstance` |

`Ampere.fromEnvironment` exposes one as `AmpereInstance.runs`, and `agentScope` is
documented as the caller's to own. `AgentService.team {}` declares the roster and
`AgentService.pursue(goal)` opens a run over it; `AgentService.listAll()` and
`inspect()` report the seats of open runs. With no run open they read the Subject
registry (AMPR-416, `SubjectRegistry`): the first `pursue`, `inspect` or `listAll` after
`team {}` registers the team as a `Subject.Group` under `AgentTeam.id` with one `MemberOf`
edge per role, and `listAll` then lists every registered agent — not the team's role
strings.

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
- **Reading the section in the PLAN prompt as the only place recall lands.** The PLAN
  prompt carries recalled memory *twice* and on purpose: once as the planner's own
  "Relevant past experiences" block with relevance percentages (AMPR-388), and once as
  the active prompt's "# Recalled context". They are different views for different
  readers, and an assertion over the whole prompt proves nothing about either — slice
  the section.
- **Giving a seat `RecallSource`s and no `recallScopes`.** The sources are never asked:
  a corpus query with no scope is a request for the whole corpus. The scopes live on
  the seat and the sources on the policy because the consumer wires its corpus once per
  run and the seat declares which slice of it this run is about.
- **Catching a `RecallSource` that throws.** It propagates, like a `Probe` that throws
  in OBSERVE: `RunHost.open` fails and the run never opens. A source that treats an
  unreachable backend as an empty recall should return `emptyList()` and say so in its
  own code, where the decision is legible — swallowing it here would make "the agent
  remembered nothing" and "the corpus was down" the same observation.
- **Expecting a wired decision transport to be asked anything.**
  `HostedAgent.upstreamDecisionClient` is inert on its own: the host seat is asked only
  under `RunPolicy.goalCheck == Record`, and a seat's `questions` only when it is the
  roster's verifier — a non-verifier's are ignored exactly as its `probes` are. The
  converse is refused rather than ignored: `open` throws when a seat *would* be asked
  and carries no transport, because `assess` drops what a failed decision call throws,
  so the alternative is a run that silently records nothing all the way through.
- **Folding the goal question into the next cycle's OBSERVE.** It is asked after
  EXECUTE, before the loop decides whether to continue, because that is where a
  judgment that *acts* would have to sit. A cycle later the run has already decided to
  continue, and the final cycle never reaches another OBSERVE at all.
- **Putting a step's id or timestamps in a decision state.** The record carries a
  `stateDigest` and never the state, so the digest is the only join key back — and a
  state that is unique per render makes the join useless.
  `RunObservation.asDecisionState` renders the goal, the cycle, and per step a
  description, a status word and a clipped result, in a fixed key order, for that reason.
- **Assuming per-seat memory is separated.** Ampere's `KnowledgeStore` has no holder
  column, so two seats sharing a `MemoryStore` recall each other's knowledge.
  Separation is whatever the consumer's store scopes (row H22, no verdict).

## Not implemented here

AMPR-385 rows H19–H25 were proposed on 2026-10-09. H21 was recorded ✅ on 2026-10-11
and is built — `HostedAgent.upstreamDecisionClient`, with `HostedAgent.questions`,
`RunPolicy.goalCheck` and `RunQuestions` beside it (AMPR-421). The rest carry **no
verdict**, so none of them is built: the shared tick functions and judgment seams
(H19), four-valued step and run results with dependent-skipping and `maxStepsPerCycle`
(H20), a `holder` column for per-seat memory (H22), the F19–F21 reconciliation (H23),
`External` provenance on a step result (H24), and re-gating a step whose arguments
changed after approval (H25).

What H21 deliberately does *not* come with: a way to name the seat's own generative
model as its decision transport (`ModelBackedDecisionClient` needs an `AgentLLMService`
the run builds internally), threshold bands (row J10), replay (row J14), and anything
that reads an answer.

Within RECALL, two things are deliberately absent. There is no embedding or search
inside Ampere — a `RecallSource` does its own retrieval — and there is no ranking
across sources beyond score order: a corpus's scores and `AgentMemoryService`'s
relevance are not calibrated against each other, and the merge does not pretend
otherwise. RECALL also runs once per run, in `charge`, not once per cycle, so a
second cycle plans with what the first one recalled.

## See also

- [PropelLoop](propel-loop.md) — the six phases and the services behind them
- [TeamLayer](team-layer.md) — the roster types a run is opened over
- [CognitionTrace](cognition-trace.md) — what `project(runId)` reads back
- [MemoryProvenance](memory-provenance.md) — what LEARN writes
