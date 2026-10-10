---
concept: PropelLoop
status: stable
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/reasoning/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/definition/AutonomousAgent.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/domain/arc/FlowPhase.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/cognition/sparks/PhaseSpark.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/cognition/sparks/PhaseSparkManager.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/trace/ArcRunTrace.kt
  - docs/AGENT_LIFECYCLE.md
related: [CognitiveRelay, MemoryProvenance, SparkSystem, CognitionTrace, EventSerialBus, DecideSeam]
last_verified: 2026-10-10
---

# PROPEL Loop

## What it is

PROPEL is AMPERE's autonomous cognitive cycle: **Perceive → Recall → Observe → Plan → Execute → Learn**.
Each phase is a discrete cognitive step that emits structured events, writes
typed memory cells, and hands a refined context object to the next phase. Every
phase, event and memory write is attributed to an *Arc run* (`ArcRunId`), which
is the unit of observability the rest of the system reports against — one run
holds many passes through the cycle, not one (see *Vocabulary* below).

The loop is not a hot path through a single function — it is a *contract*
between independent reasoning services (`PerceptionEvaluator`,
`PlanGenerator`, `PlanExecutor`, `OutcomeEvaluator`, `KnowledgeExtractor`)
composed by `AgentReasoning`. Each service owns one phase's transformation —
except RECALL, which is a store query with no service, and OBSERVE, which has
no service at all.

### What drives it today

Two drivers, and neither runs all six phases:

- **`AutonomousAgent.runtimeLoop`** (`agents/definition/AutonomousAgent.kt:389-463`)
  — a `while (agentIsRunning)` loop bracketing PERCEIVE → PLAN → EXECUTE → LEARN
  with `phaseSparkManager.withPhase`, then closing out the assignment with
  `finishCurrentTask()` (AMPR-397). With no assignment — a `Task.Blank` — it
  idles on a `delay` rather than reasoning about nothing (`:393-396`), so an
  unassigned agent costs no model calls. Recall runs between PERCEIVE and PLAN
  (`:420`) but is not bracketed, and OBSERVE has no step at all. The loop is
  started only by `initialize` (`:529-535`), whose only callers are the three CLI
  agents in `ampere-cli/src/jvmMain/kotlin/link/socket/ampere/Main.kt:116-124`.
  "Runs continuously" therefore describes the CLI's long-lived agents, not agents
  in general.
- **`FlowPhase.executeAgentTickTyped`** (`domain/arc/FlowPhase.kt:185-253`) —
  the Arc path, one tick per agent per turn. Since AMPR-395 the tick derives its
  task from the Arc's *current goal* (`taskForCurrentGoal`, `:267-278`) and
  assigns it with `rememberNewTask`, rather than reading the agent's own memory
  cell — which only ever held `Task.Blank`, because nothing on the Arc path
  writes it. A blank task ends the tick before the Perceive call rather than
  spending one. Then perceive, recall, plan, execute, and record the outcome
  into the in-memory `SharedContext` (`:245`). No phase brackets and no OBSERVE.
  A tick has no Learn step either; the Arc's closing phase is `PulsePhase`, once
  per run rather than once per tick (AMPR-402).

`PhaseSpark` carries a prompt contribution for all six phases, `Recall` and
`Observe` included (`agents/domain/cognition/sparks/PhaseSpark.kt:98-160`), but
nothing enters either phase, so those two contributions are unreachable.

### Vocabulary

Three words mean something narrower in code than in prose, and the mismatch has
cost readers a wrong assumption more than once:

- **Run.** An *Arc run* is Charge + up to `maxFlowTicks` (default 100) Flow
  ticks + Pulse — not one pass through six phases. One run contains many
  partial cycles, one per agent per tick.
- **Task.** A `Task` in code is one *plan step*: `PlanGenerator` turns each
  entry of the model's `"steps"` array into a `Task.CodeChange` named
  `step-N-<taskId>` (`PlanGenerator.kt:224-256, 350-365`).
  `CORE_CONCEPTS.md:96` calls Tasks "the atomic units of execution", which is
  that plan step, not a unit of the ticket's work.
- **Remember / Optimize.** Pre-PROPEL phase names that survive in comments
  (`domain/arc/FlowPhase.kt:221, 224`, `domain/arc/AmpereRuntime.kt:42`). They
  map onto Recall and Plan; there is no seventh and eighth phase.

## Why it exists

The loop is the load-bearing answer to *"how does an animated agent decide
what to do next?"*. Three forces shaped it:

1. **Recall before action.** LLMs are notoriously bad at remembering what
   *this very system* has already tried. PROPEL forces a Recall step
   before any planning, so prior `ExecutionOutcome`s and `Knowledge`
   entries are surfaced into the prompt, not silently re-discovered.
2. **Observation is a distinct step.** The leap from "I have ideas and
   memories" to "I have a chosen approach" requires reading the current
   state of the environment, including what changed since the last Arc
   run. Folding Observe into Plan made the planning prompt too crowded
   and recalled knowledge went unused against stale state.
3. **Loop closes via Knowledge.** Without the explicit closing phase that
   stores knowledge, the loop is open-loop and the agent never improves.
   The **Learn** phase is where the autocatalytic property lives.

The phase boundaries exist because of cognitive load on the LLM, not on the
runtime. Each phase is meant to have a focused prompt (optionally narrowed
further by a [`PhaseSpark`](spark-system.md)), a clear input/output contract,
and a single point at which we emit telemetry. Four phases have a prompt-building
service today — `PerceptionEvaluator` (PERCEIVE), `PlanGenerator` (PLAN),
`ToolExecutionEngine`'s parameter strategies (EXECUTE) and `OutcomeEvaluator`
(LEARN). RECALL is a store query with no prompt, and OBSERVE has no service at
all.

## Where it lives

- `ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/reasoning/AgentReasoning.kt` — the facade composing all phase services.
- `agents/domain/reasoning/PerceptionEvaluator.kt` — Perceive: distills `AgentState` into `Idea`s. `defaultPerceptionContext` is how the state reaches the prompt when the host supplies no `perceptionContextBuilder` (AMPR-403).
- `agents/domain/reasoning/PlanGenerator.kt` — Plan: combines ideas, recalled `Knowledge`, the ticket, and the agent's available tools into a `Plan` (`:62-106`). It takes no reading of current state — nothing enters OBSERVE — so this is Plan alone, not the "Observe + Plan" it was once described as. The parsed `Plan.requiresHumanInput` is the planner's self-report that the plan needs a person (AMPR-398); it is a `SELF_REPORTED` yes/no, not a `Judgment`.
- `agents/domain/reasoning/AgentReasoning.ReasoningSettings.executor` — the `Executor` the Execute phase's `ToolExecutionEngine` is built on. Null builds no engine, so `AgentReasoning.canExecuteTools` is false and every `executeTool` call returns `NO_EXECUTOR_MESSAGE` instead of dispatching. The agent factories (`AgentFactory`, `SparkAgentFactory`), `Ampere.fromEnvironment`, `AmpereRuntime.create` and `ArcSession` all supply one (AMPR-405).
- `agents/domain/reasoning/AgentReasoning.ReasoningSettings.availableTools` — a `() -> Set<Tool<*>>` provider, not a set: a spark-based agent's tool set is narrowed by its live spark stack, so Perceive and Plan re-read it per call (AMPR-400). See [SparkSystem](spark-system.md).
- `agents/definition/SparkBasedAgent.executePlanStep` — the live step executor, and the one place the two kinds of step part company: a step naming a tool dispatches it, a step naming none is carried out by `executeReasoningStep`'s single `EXECUTE` call (AMPR-407), prompted with the same prior-results chain a tool step's strategy renders (AMPR-412). `agents/config/CognitiveConfig.ReasoningStepConfig` is the host's switch over that.
- `agents/domain/reasoning/PlanExecutor.kt` — Execute: walks a `Plan.ForTask`'s steps in order, running each through a caller-supplied `stepExecutor` and aggregating the `StepOutcome`s. It does not reach `ToolExecutionEngine` itself; the step executor does — on the live path that is `SparkBasedAgent.executePlanStep` → `AgentReasoning.executeTool`. Given a door it publishes a `PlanEvent.PlanStepStarted` / `PlanStepCompleted` pair per executed step, under the run id (AMPR-389); without one it is silent. Its summary — which becomes the folded outcome's `message`, and is all a caller holding that outcome has — lists each failure's own error, not just how many there were (AMPR-405). `execute(plan, priorResults, stepExecutor)` also owns the running results chain: `priorResults` seeds `StepContext.priorResults` and one `StepOutcome` is appended per executed step, so a step executor reads what came before from one place however the plan reached it (AMPR-408).
- `agents/definition/AutonomousAgent.executePlan` — the *live* multi-step walk, and so the other half of that chain: it dispatches one step at a time through `NeuralAgent.runLLMToExecuteTask(task, priorResults)` and accumulates a `StepOutcome` per step from the outcome's own payload (`ExecutionOutcome.describeResult`). `PlanExecutor` sees a one-step plan per step on this path (AMPR-396), which is why the seed exists.
- `agents/execution/PriorStepResults.kt` — the one renderer: `describeResult()` turns an `ExecutionOutcome` into what it actually said, `priorResultsSection()` turns the chain into the prompt block every default `ParameterStrategy` renders (AMPR-408) — the hand-written code, git and project strategies, `SchemaParameterStrategy` for a schema-described tool, and the reasoning-step call for a step with no tool (AMPR-412). The results travel on `ExecutionRequest.priorResults` and are re-applied at the dispatch funnel, like the run id and the file access scope.
- `agents/domain/reasoning/OutcomeEvaluator.kt` — Learn, the model-backed route: reads already-typed `Outcome`s and asks the model for the patterns across them, returning `Knowledge` plus a summary `Idea` for the next Perceive. Typing a raw tool return is the executor's job, not this one's.
- `agents/domain/reasoning/KnowledgeExtractor.kt` — Learn, the deterministic route: a model-free builder that formats one `Outcome` + `Task` + `Plan` into a `Knowledge.FromOutcome`. It is an *alternative* to `OutcomeEvaluator`, not a second half of it — either one produces the entry, and `AgentReasoning.extractKnowledge` hands it back for the caller to store.
- `domain/arc/PulsePhase.kt` — the Arc-level close: one `Knowledge.FromOutcome` per successful outcome, stored through the producing agent and tagged with the run id. Needs the run's agents and a `KnowledgeRepository` threaded down from `AmpereRuntime`.
- `agents/domain/reasoning/AgentReasoning.decide` — the Decide call kind (AMPR-384): typed questions about a state, answered with a measured confidence and recorded as `JudgmentRecorded` under the asking phase. Shadow only in W1; no phase calls it yet. See [DecideSeam](decide-seam.md).
- `agents/domain/reasoning/Confidence.kt` + `ConfidenceSource.kt` — every `Confidence` a phase parses from generated JSON is `SELF_REPORTED`; a `MEASURED` confidence is a `Judgment`, not a level.
- `agents/domain/cognition/sparks/PhaseSpark.kt` + `PhaseSparkManager.kt` — phase-aware prompt augmentation (`PERCEIVE | RECALL | OBSERVE | PLAN | EXECUTE | LEARN`).
- `trace/ArcRunTrace.kt` — `PropelPhase` is the telemetry record per phase.
- `docs/AGENT_LIFECYCLE.md` — the human-readable narrative.

## What a consumer can and cannot drive today

The gap between this cell's invariants and the code is the subject of
[AMPR-385](https://linear.app/miley/issue/AMPR-385) — *Hosted runs: a
consumer-driveable PROPEL run from the public API*. Read that epic before
building against the loop; the H rows named below are its children.

**Can drive:**

- **A whole Arc run.** `ArcSession.create(...)` then `start(goal)` / `tryStart(goal)`
  returns an `ArcRunHandle`: Charge spawns one agent per configured role, Flow
  ticks them in order up to `maxFlowTicks`, Pulse evaluates
  (`domain/arc/bridge/ArcSession.kt:244-291`, `domain/arc/AmpereRuntime.kt`).
  Cancellation, the on-device state and `ArcRunHandle.trace()` all work.
- **One model call under a phase.** `AgentReasoning.callLLM` /
  `callLLMForJson` with an explicit `phase` (AMPR-273), and `decide` for the
  Decide call kind (AMPR-384). Both emit their telemetry pair under the run id
  when the agent has an event door.
- **Reading a run back.** `ArcTraceProjection.project(runId)` — phases, model
  invocations, Watt cost, the completion manifest of a cut-short run. See
  [CognitionTrace](cognition-trace.md) for what is populated and what is empty.

**Cannot drive:**

- **A goal *verifiably* complete.** The goal does reach an agent now
  (AMPR-395), but what marks it done is still "any successful outcome":
  `evaluateGoalCompletion` completes the current goal on the first
  `Outcome.Success` of any kind and moves to the next, with no check that the
  goal was actually met (`FlowPhase.kt:298-310`, comment: "Simple heuristic").
  Tool steps do run now (AMPR-405), so a run can change something — but whether
  what it changed is what the goal asked for is not checked.
- **A team.** `AgentService.team {}`, `pursue` and `wake` are `@Deprecated` as
  of AMPR-399 rather than repaired: `pursue` publishes one
  `TaskCreated(assignedTo = null)` that nothing consumes as work, and
  `AgentTeam.pursue` only emits UI markers (`api/service/AgentService.kt:60,
  88, 110`). The deprecation messages point at the CLI or `AmpereRuntime`, and
  say the methods are re-pointed at `RunHost` when AMPR-393 ships. Treat the
  surface as documentation of an intent, not an entry point.
- **A bracketed Arc run.** Phase brackets are configurable since AMPR-387, and a
  consumer that builds its own `AgentFactory` / `SparkAgentFactory` can turn them
  on — but `ChargePhase` constructs the Arc's factory itself and passes no
  `cognitiveConfig` (`domain/arc/ChargePhase.kt:130-143`), so an `ArcSession`
  run has no seam for it. `AMPERE_PHASE_SPARKS=true` still works process-wide.
  Moot until something in the Arc path calls `withPhase` at all.

## Invariants

- **Recall precedes Plan.** No `Plan` may be generated without first calling `AgentMemoryService.recallRelevantKnowledge` and feeding the result into `PlanGenerator`. Skipping Recall when context "feels obvious" is the canonical failure mode. The chain that carries it is `AutonomousAgent.runtimeLoop` → `determinePlanForTask` → `NeuralAgent.runLLMToPlan(task, ideas, relevantKnowledge)` → `AgentReasoning.generatePlan` → `PlanGenerator.synthesizeKnowledge`; `runLLMToPlan` takes the knowledge as a required argument so a call site cannot drop it by omission, and the one other seam that generates a `Plan` — `SparkBasedAgent.runSubPlanForTask`, the opt-in sub-cycle a host asks for by name — recalls for itself rather than planning without (AMPR-388).
- **A phase's prompt carries its own inputs.** Perceive renders the description of the task in the state's current memory cell *and* every `Perception.ideas` entry it was handed; Plan renders the task, the ideas and the recalled `Knowledge`. A phase whose prompt does not name what it was given is asking the model a constant — which is what AMPR-403 found: `"State: $state"` over a role state only ever constructed as `.blank`, with the ideas dropped. The task reaches Perceive through `Agent.rememberNewTask`, never through the role state's own `task` field.
- **Each phase emits its own boundary events, under the run.** `CognitivePhaseEvent.PhaseEntered` / `PhaseExited` mark phase transitions when the phase manager has a bus, and carry the Arc run on their envelope — `PhaseSparkManager` defaults it to the owning agent's `Agent.currentRunId` and holds the entered run until the matching exit, so a bracket closed by `cleanup()` is stamped with the run it opened under (AMPR-386). A bracket's payload names no run, so an unstamped one is invisible to `project(runId)` even with the payload fallback; `ProviderCallStartedEvent` / `ProviderCallCompletedEvent` carry a `cognitivePhase`; memory writes carry the phase that produced them; tool calls are tagged via the active phase. `ArcTraceProjection` relies on this to bucket activity per phase. A phase that runs without emitting boundary events is invisible to the trace, which is equivalent to it not having run. **Two facts about when this fires:** phase handling is opt-in and off by default (`PhaseSparkConfig.enabled = false`, `CognitiveConfig.kt:42`; the manager returns early at `PhaseSparkManager.kt:110, 161`), so an un-opted run brackets nothing; and only `AutonomousAgent.runtimeLoop` calls `withPhase` at all — `FlowPhase` brackets no phases whatever the config, so an Arc run emits no `CognitivePhaseEvent` and the projection falls back to each event's own phase tag.
- **Execute publishes what it did, not just what it returned.** `PlanExecutor` brackets each executed step with `PlanEvent.PlanStepStarted` / `PlanStepCompleted`, and `ToolExecutionEngine` brackets each dispatch with `ToolEvent.ToolExecutionStarted` / `ToolExecutionCompleted` (AMPR-389). Both halves carry the run id and are written under `NonCancellable`, and the engine's bracket wraps the dispatch call only — since AMPR-401 that is the injected `Executor` for a `FunctionTool` and the MCP arm for an `McpTool`, both inside the bracket — while a dispatch `PlugPermissionGate` refused is reported by `PermissionDeniedEvent` instead, so a tool pair in a trace means a tool ran. A step skipped after an earlier critical failure never started, so it emits neither event and is reported only in `PlanExecutionResult.stepOutcomes`. Since AMPR-396 the live path hands Execute one step at a time, each wrapped in its own one-step plan, so a real trace shows one pair per dispatch with `stepIndex = 0`, `totalSteps = 1` and a `planId` per step — read the run's steps off the sequence of pairs, not off `totalSteps`.
- **Execute passes results forward.** A plan step's parameters are *generated* by a model call the nominated tool's `ParameterStrategy` prompts for, so a step can only be parameterised from what reaches that prompt. The chain is `AutonomousAgent.executePlan` → `runLLMToExecuteTask(task, priorResults)` → `SparkBasedAgent.buildPlanStepRequest` → `ExecutionRequest.priorResults` → `priorResultsSection` inside the strategy's prompt, with `ToolExecutionEngine` re-applying the list after a strategy rebuilds the request. Every prompt Execute builds renders that one block: the hand-written strategies, `SchemaParameterStrategy`, and the reasoning-step call, so a tool step reads a reasoning step's conclusion and a reasoning step reads a tool's result through the same carrier (AMPR-412, H17). `priorResults` is a required argument at every hop for the same reason `relevantKnowledge` is on `runLLMToPlan`: a default would let a call site drop it by omission. Until AMPR-408 nothing carried it, so a plan of the form "search for X, then summarise what you found" generated step two's parameters from step two's description alone and could not run. What travels is the outcome's payload, not its type name — `"tool=search outcome=Success"` is true and useless.
- **Execute performs every step it reports.** A plan step whose `toolToUse` is null is a step whose work is thinking, and the planning prompt invites exactly those (`"tool ID or null if no specific tool"`). The executing seat carries it out with one model call tagged `EXECUTE`, prompted with the step and the chain of what the earlier steps produced, and the text that comes back is the step's result — on the `StepResult` as its details (so `PlanStepCompleted` carries it), and from there onto the chain the later steps are handed (AMPR-412). Failing to reach a model is a step *failure*: until AMPR-407 `SparkBasedAgent.executePlanStep` answered `success(details = "reasoning step (no toolToUse)")` with no call at all, so a plan of "1. decide the approach, 2. write the file" had its first step done by nobody and reported done. A host that wants tool-less steps to be free declares it — `cognitiveConfig.reasoningSteps.execute = false` — and then the old behaviour is a stated policy rather than a silent one.
- **Every model call a phase makes names its phase.** Execute's parameter-strategy call in `ToolExecutionEngine.execute` passes `RoutingContext(phase = EXECUTE, workflowId = runId, agentId = executorId)`, the same way the other phase services do. An untagged call lands in `ArcTraceProjection`'s `UNKNOWN` bucket, which is where this one used to live.
- **The loop closes through `Knowledge`.** Every successful Arc run ends with at least one `Knowledge` entry tagged with the `run_id`, *written* — building the cell and handing it back is not closing the loop (AMPR-402). Two phases write: `KnowledgeExtractor` inside an agent's own Learn phase, and the Arc's `PulsePhase`, which distils one entry per successful outcome and stores it through the producing agent's `AgentMemoryService` so `KnowledgeStored` names the holder. An Arc run that produced outcomes but no Knowledge entry has not closed the loop and will not contribute to future Recall. A run that is cancelled, or where a phase throws, does not close it and is not credited with a `Knowledge` entry: it leaves a `CompletionManifest` instead (AMPR-282), persisted as `ArcRunEvent.CompletionManifestRecorded` and read back as `ArcRunTrace.completion` (AMPR-359).
- **Phase order is fixed.** Perceive → Recall → Observe → Plan → Execute → Learn. The declaration order in `CognitivePhase` matches the PROPEL acronym; `enumValues<CognitivePhase>()` yields the cycle. New phases are added by extending the enum and updating every service that switches on it; phases are never reordered or skipped per call site. **Order holds; completeness does not:** `CognitivePhase.RECALL` and `OBSERVE` are never entered by any code path, so the two phases that run no code are skipped by both drivers rather than by any call site — `runtimeLoop` brackets four of six, `FlowPhase` runs four and brackets none. Treat the enum as the order to add work in, not as a description of what executes.
- **Execute's dispatcher is a dependency, not an option, and the loop can ask for it before it plans.** The Execute phase runs tools through a `ToolExecutionEngine`, which exists only if `ReasoningSettings.executor` was supplied; without one every plan step naming a tool is refused. That is a wiring mistake rather than a runtime condition, so it is asked about once, up front — `AgentReasoning.canExecuteTools` / `SparkBasedAgent.canExecuteTools` — rather than discovered one step at a time. Until AMPR-405 no shipped construction path supplied an `Executor` at all, so no tool had ever run through the engine in production: the CLI goal path, `Ampere.fromEnvironment`'s bound factory, `AmpereRuntime.create` and `ArcSession` all built agents that could plan and not act. An agent with tools and no executor is misconfigured; a host that drives the cycle refuses it rather than spending a Perceive and a Plan call on a phase that cannot act.
- **A failure reports its own reason, not its type.** The reason is on the outcome — `NoChanges.Failure.message`, an `ExecutionError` on the rest — and `ExecutionOutcome.describeResult()` is the one thing that reads it. A fold that reaches for `outcome::class.simpleName` instead loses the only evidence of the cause: `SparkBasedAgent.executePlanStep` reported `tool=write_code_file failed: Failure` for a missing executor, a denied permission and a tool's own error alike (AMPR-412), and `PlanExecutor`'s summary counted failures without listing them, so a caller holding the plan's folded outcome had nothing to print either (AMPR-405). Both halves have to carry it: the step's error *and* the summary that aggregates the steps.
- **Plan is offered only the tools the agent may actually use.** `PlanGenerator`'s available-tools list comes from the caller's `availableTools` provider, which for a spark-based agent is its *narrowed* set. Planning against a wider set invites steps the executor will refuse, and the refusal costs a whole Arc run rather than a retry.
- **Everything a phase prompt asks for is parsed.** A phase's response schema is a bill the run pays in tokens on every call. A key the prompt declares and the parser never looks up is a field the model spent output on and the loop discarded — `PlanGenerator` asked for `requiresHumanInput` on every plan and dropped it for four releases (AMPR-398). Adding a key to a phase schema and reading it back are one change.
- **Observe is not a side-effect of Plan.** When recalled `Knowledge` contradicts an `Idea` or current state has drifted since the last run, that contradiction must be resolved in Observe and recorded in the Plan's rationale, not silently dropped during Plan generation. **Nothing enters OBSERVE today:** `PlanGenerator.generate` takes task, ideas, knowledge and tools and nothing else (`PlanGenerator.kt:62-106`), and `CognitivePhase.OBSERVE` appears only in labels, palettes and the unreachable `PhaseSpark.Observe`. This invariant is a constraint on how Observe gets built, not a description of a step that runs.

## Common operations

- **Add a phase** — extend `CognitivePhase` (in `PhaseSpark.kt`), add a corresponding `PhaseSpark` data object with prompt contribution, update `PhaseSpark.forPhase`, add a service implementing the phase transform, wire it through `AgentReasoning`, and update `ArcTraceProjection.phaseNameFor` so the trace knows about the new phase.
- **Bracket a phase under a run** — `phaseSparkManager.withPhase(phase) { … }` from inside an agent already stamps that agent's run. Pass `runId` explicitly only when the caller holds a run the agent does not (a harness driving a run-less agent).
- **Hook into a phase** — prefer `CognitivePhaseEvent.PhaseEntered` / `PhaseExited` when an event bus is wired. For older spark-stack consumers, subscribe to `SparkAppliedEvent` filtering on `phaseSparkName()`. Do not assume `PhaseSpark`s are always enabled — they're gated on `AgentConfiguration.cognitiveConfig.phaseSparks.enabled` or `AMPERE_PHASE_SPARKS=true`. Since AMPR-387 the brackets and the prompt guidance are separate switches under `enabled` (`publishBrackets` / `injectPhaseSparks`), so a consumer subscribing to the events must not infer that phase guidance was in the prompt, and one reading the prompt must not infer that brackets were published.
- **Put a host's own observations in front of Perceive** — supply `AgentReasoning.create { perceptionContextBuilder = { state -> … } }` (AMPR-403). It replaces `defaultPerceptionContext` rather than extending it, and is typed over `AgentState` because `ReasoningSettings` is not generic. To state a goal instead of a world, pass it as an `Idea` to `perceiveState`; the two compose.
- **Give Execute something to dispatch with** — pass an `Executor` to the factory (`AgentFactory` / `SparkAgentFactory` / the `SparkBasedAgent.<Role>(...)` factories all take one, and the first two default to `FunctionExecutor.create()`). `FunctionExecutor` runs in-process `FunctionTool`s; `NoOpExecutor` is the effect-free one the eval Bench uses. `executor = null` is a *declaration* that these agents must not act, readable off `canExecuteTools` — not the same thing as forgetting to wire one, which is what it used to be everywhere.
- **Refuse a cycle that cannot act** — check `SparkBasedAgent.canExecuteTools` before Perceive and report `AgentReasoning.NO_EXECUTOR_MESSAGE`, the way `GoalHandler` does. Reporting it here costs nothing; letting the cycle run costs two model calls and then reports a `NoChanges` nobody can distinguish from a plan that genuinely changed nothing.
- **Persist what Execute did** — wire an `OutcomeMemoryRepository` into `ToolExecutionEngine` (via `ReasoningSettings.outcomeRepository`, which the agent factories and `AmpereRuntime` thread down). The engine then records every `ExecutionOutcome` it returns — including the refusals it produces before dispatch — against the request's ticket and run id. That is the *persisting path*: an engine built without a store returns the same outcomes and records nothing.
- **Let Execute call an MCP tool** — wire a `ServerManager` into `ToolExecutionEngine` (via `ReasoningSettings.mcpServerManager`, set by `AgentReasoning.create { execution { mcpServers(…) } }`): a `PlugContext.mcpServerManager` for a plug's servers, `McpServerManager` for discovered ones. The engine is the only execute path for both tool kinds (AMPR-401, which deleted the second one, `propel/ExecuteStep`), and it gates the call before it uses the route. Without a manager an `McpTool` dispatch fails saying so, which is what every one of them did before.
- **Declare reasoning steps free** — `CognitiveConfig(reasoningSteps = ReasoningStepConfig(execute = false))`, handed to whichever factory builds the agent. Tool-less steps then succeed without a model call, as they did before AMPR-407. What such a step is told about the steps before it is not a setting: it reads the same prior-results chain a tool step does.
- **Read what one Arc run did** — `ArcTraceProjection.project(runId)` returns an `ArcRunTrace` with one `PropelPhase` per phase. This is the "playback" of a cognitive cycle. Use this for debugging, not for orchestration.
- **Validate a change to the loop** — run `./gradlew jvmTest` (the primary gate) and verify the loop still produces a `Knowledge` entry tagged with the run id. Both paths close now: `runtimeLoop` through `extractAndStoreKnowledge` and the Arc through `PulsePhase` (AMPR-402). On the Arc path assert on `Learning.stored`, not on the entry existing — an unstored cell is still returned.

## Anti-patterns

- **Short-circuiting Recall when the context "feels obvious"** — the whole point of Recall is to override the agent's confidence with prior outcomes. Plans that look obvious are exactly the ones where past failures live.
- **Reading "Recall precedes Plan" as satisfied because a `KnowledgeRecalled` event exists** — it records what the *query* returned, not what the *prompt* received. From the invariant's introduction until AMPR-388 (`v0.17.0`) the two diverged: `determinePlanForTask` accepted `relevantKnowledge` and then called `runLLMToPlan(task, ideas)`, and the step re-plan (`SparkBasedAgent.runLLMToExecuteTask` before AMPR-396 moved it to `runSubPlanForTask`) planned with no knowledge at all, so every `KnowledgeRecalled` the loop recorded described knowledge the planner never saw. An optional `relevantKnowledge` parameter with an `emptyList()` default is how that drift stayed invisible; check the prompt, not the plumbing.
- **Reading `StepContext` as already solving result-passing** — it carries only what a step chose to name in `StepResult.contextUpdates`, keyed by strings the next step has to know in advance. A step executor about to dispatch a *tool* cannot invent those keys, which is why `StepContext.priorResults` is typed and filled by the executor itself rather than by the steps (AMPR-408).
- **Assuming `PlanExecutor`'s walk is the live multi-step path** — it is not, and a fix applied there alone is dormant. Since AMPR-396 `AutonomousAgent.executePlan` dispatches each step on its own and `SparkBasedAgent.dispatchStepAsPlan` wraps it in a *one-step* plan, so anything `PlanExecutor` accumulates across steps is accumulated across a plan of length one. The multi-step state lives in the agent's walk; `PlanExecutor` takes it as a seed. `runSubPlanForTask` is the only caller whose plan has more than one step in it.
- **Treating Learn as bookkeeping** — the closing phase is where the system *learns*. If your change records outcomes but doesn't extract `Knowledge`, you've made the loop open-loop.
- **Returning the `Knowledge` instead of storing it** — a closing phase that builds cells and puts them on its result object looks complete at every call site and closes nothing. `PulsePhase` did exactly this for the life of the Arc runtime (AMPR-402): the cells were on `PulseResult.learnings`, no caller read them, and `Recall` found nothing on the next run. The write is the phase; a `Learning` reports whether it landed.
- **Writing a learning under a task type nothing recalls with** — Recall's first strategy matches `MemoryContext.taskType` against the stored `task_type`, so a bespoke string on the write side stores an entry no query will ever return. Take the value from `MemoryTaskTypes`.
- **Asking a decision question without a phase** — `decide(…, phase = null)` files the record under whatever phase happens to be active, the same way an untagged `callLLM` does. Pass the phase that is asking.
- **Rendering a phase's input with `toString()`** — a role state's `toString()` is an identity hash and its declared fields are `.blank`, because `CodeState.blank` and friends are the only constructions. The live channel is the memory cell (`state.getCurrentMemory()`), and a prompt that reads anything else is stable across iterations no matter what the agent is doing.
- **Threading a goal through `SparkSelectionContext.text` and expecting the model to see it** — that text selects which spark applies; it never reaches the prompt. A goal reaches the model as an `Idea` or through the phase's context builder.
- **Degrading around a transport that was never there** — `PlanGenerator` falls back to a one-step plan when a model call fails, and a plan step nominating no tool executes as a successful "reasoning step". For a `MissingUpstreamLlmClientException` that laundered a configuration error into a completed goal: an Arc with no `UpstreamLlmClient` reported `GOAL_COMPLETE` having called nothing, while the telemetry for the same step recorded a failed call. The no-transport case now plans `Plan.blank`, which executes to `Outcome.blank` and is not a success (AMPR-395). A fallback is for a model that answered badly, not for one that was never reached.
- **Inlining a "quick LLM call" outside a phase** — every model invocation should be wrapped in a `ProviderCallStartedEvent` / `ProviderCallCompletedEvent` pair tagged with `cognitivePhase`. Calls outside this contract don't appear in `ArcRunTrace` and break the glass-brain guarantee.
- **Routing a tool's outcome through `OutcomeEvaluator` on the way to the store** — it looks like the evaluator's job from its name, and from where it sits in Learn, but it takes `Outcome`s and returns learnings; it has never produced an `ExecutionOutcome`. Persisting through it would mean spending an LLM call to write a memory row.
- **Reporting a step as done because it nominated no tool** — "no tool to run" and "nothing to do" are different claims, and only the first one is in the plan. A tool-less step returned as a bare success is a plan that half-ran and fully reported, and the half that was skipped is the reasoning the later steps were written to depend on. The tell is a `StepOutcome.Success` whose `details` name the executor's own control flow (`"reasoning step (no toolToUse)"`) rather than anything about the step's subject.
- **Giving one kind of step result its own carrier** — a reasoning step's conclusion and a tool's result are the same thing to the step after them: something that happened earlier in this plan. AMPR-407's draft kept a plan-scoped buffer of conclusions beside the chain and folded it into a tool step's `instructions`, so once AMPR-408's chain existed the same conclusion would have reached one prompt twice under two headings, with a cap of its own and no cap for the tool results next to it. One carrier (`ExecutionRequest.priorResults`), one renderer (`priorResultsSection`), read by every prompt Execute builds (AMPR-412).
- **Re-planning inside Execute** — a step arriving at the Execute phase is already the Plan phase's output and already names its tool, so calling `generatePlan` on it bills a PLAN call per step and dispatches a sub-plan's tools instead of the ones the Plan phase chose. `SparkBasedAgent.runLLMToExecuteTask` did exactly this until AMPR-396: a three-step plan cost four PLAN calls and ran none of its own steps' tools. Breaking a coarse step down is a sub-cycle a host asks for by name (`SparkBasedAgent.runSubPlanForTask`), never the meaning of "execute this step".
- **Reading a nullable planner field as `jsonPrimitive.content`** — `JsonNull` *is* a `JsonPrimitive` and its `content` is the string `"null"`, so a step the model wrote as `"toolToUse": null` reads back as a tool literally named `null` and fails strict tool-id dispatch. Parse nullable generated fields through a helper that rejects `JsonNull`, blanks, and the four characters `null`.
- **Reading a `NoChanges` outcome as "the plan succeeded"** — `PlanExecutor` folds every step into `ExecutionOutcome.NoChanges.Success` or `.Failure` whatever the steps actually did, so a `CodeChanged` outcome never reaches a caller holding a plan's result, and a plan whose every step was refused arrives as a `NoChanges.Failure`. A `when` that matches only `CodeChanged.Failure` and lets the rest fall through to its `else` reports the second case as done: the CLI `--goal` path printed COMPLETED for every cycle in which nothing ran, for as long as nothing supplied an executor (AMPR-405). Match `Outcome.Failure`.
- **Treating a phase service's `eventApi` as optional plumbing** — it is what makes the phase visible. `PlanExecutor(executorId)` with no door still executes the plan perfectly and leaves no evidence it ran; a service wired without the door it was offered produces a trace that is wrong by omission rather than one that is merely thin.
- **Adding a key to a phase's response schema without a reader** — the prompt is the cheap half; the parse is the half that makes the answer exist. `grep` the key name across the repo before shipping the prompt: one hit means the answer is being bought and thrown away.
- **Folding Observe into Plan** — recalled `Knowledge` becomes a passive context dump rather than an explicit selection between alternatives. The Plan emerges with no record of *why this approach over the others*.
- **Letting the loop take its next task from its own output** — the Execute phase records each plan step as the agent's current task, so a loop that does not finish the task it ran reads the plan's last step back as its next assignment and re-plans it forever (AMPR-397). An iteration's task comes from the work the agent was given; an agent with no assignment idles without a model call rather than reasoning about a blank task.
- **Stamping a task id where a run id goes** — `runtimeLoop` passed `task.id` as the `runId` of `KnowledgeStored` until AMPR-386. Both are opaque strings, so it compiled and read plausibly, and the entry landed in the `run_id` column of a run that does not exist. The agent's run is `Agent.currentRunId`; a task id belongs in `taskType`/source fields.
- **Holding state across runs in the agent object** — agents are animated, not stateful in memory. Cross-run state belongs in `OutcomeMemoryRepository` / `KnowledgeRepository`, keyed by ids retrievable in Recall. Anything else is invisible to the trace and fragile across restarts.
