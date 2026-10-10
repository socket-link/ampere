---
concept: PropelLoop
status: stable
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/reasoning/**
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
Every animated agent runs this loop continuously. Each phase is a discrete
cognitive step that emits structured events, writes typed memory cells, and
hands a refined context object to the next phase. The phases collectively
form one *Arc run* (`ArcRunId`), which is the unit of observability the rest
of the system reports against.

The loop is not a hot path through a single function — it is a *contract*
between independent reasoning services (`PerceptionEvaluator`,
`PlanGenerator`, `PlanExecutor`, `OutcomeEvaluator`, `KnowledgeExtractor`)
composed by `AgentReasoning`. Each service owns one phase's transformation.

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
runtime. Each phase has a focused prompt (optionally narrowed further by a
[`PhaseSpark`](spark-system.md)), a clear input/output contract, and a
single point at which we emit telemetry.

## Where it lives

- `ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/reasoning/AgentReasoning.kt` — the facade composing all phase services.
- `agents/domain/reasoning/PerceptionEvaluator.kt` — Perceive: distills `AgentState` into `Idea`s. `defaultPerceptionContext` is how the state reaches the prompt when the host supplies no `perceptionContextBuilder` (AMPR-403).
- `agents/domain/reasoning/PlanGenerator.kt` — Observe + Plan: combines ideas, recalled `Knowledge`, the ticket, and the agent's available tools into a `Plan`. The parsed `Plan.requiresHumanInput` is the planner's self-report that the plan needs a person (AMPR-398); it is a `SELF_REPORTED` yes/no, not a `Judgment`.
- `agents/domain/reasoning/AgentReasoning.ReasoningSettings.availableTools` — a `() -> Set<Tool<*>>` provider, not a set: a spark-based agent's tool set is narrowed by its live spark stack, so Perceive and Plan re-read it per call (AMPR-400). See [SparkSystem](spark-system.md).
- `agents/domain/reasoning/PlanExecutor.kt` — Execute: walks a `Plan.ForTask`'s steps in order, running each through a caller-supplied `stepExecutor` and aggregating the `StepOutcome`s. It does not reach `ToolExecutionEngine` itself; the step executor does — on the live path that is `SparkBasedAgent.executePlanStep` → `AgentReasoning.executeTool`. Given a door it publishes a `PlanEvent.PlanStepStarted` / `PlanStepCompleted` pair per executed step, under the run id (AMPR-389); without one it is silent.
- `agents/domain/reasoning/OutcomeEvaluator.kt` — first half of Learn: reads already-typed `Outcome`s and asks the model for the patterns across them, returning `Knowledge` plus a summary `Idea` for the next Perceive. Typing a raw tool return is the executor's job, not this one's.
- `agents/domain/reasoning/KnowledgeExtractor.kt` — second half of Learn: distils outcomes into `Knowledge`.
- `domain/arc/PulsePhase.kt` — the Arc-level close: one `Knowledge.FromOutcome` per successful outcome, stored through the producing agent and tagged with the run id. Needs the run's agents and a `KnowledgeRepository` threaded down from `AmpereRuntime`.
- `agents/domain/reasoning/AgentReasoning.decide` — the Decide call kind (AMPR-384): typed questions about a state, answered with a measured confidence and recorded as `JudgmentRecorded` under the asking phase. Shadow only in W1; no phase calls it yet. See [DecideSeam](decide-seam.md).
- `agents/domain/reasoning/Confidence.kt` + `ConfidenceSource.kt` — every `Confidence` a phase parses from generated JSON is `SELF_REPORTED`; a `MEASURED` confidence is a `Judgment`, not a level.
- `agents/domain/cognition/sparks/PhaseSpark.kt` + `PhaseSparkManager.kt` — phase-aware prompt augmentation (`PERCEIVE | RECALL | OBSERVE | PLAN | EXECUTE | LEARN`).
- `trace/ArcRunTrace.kt` — `PropelPhase` is the telemetry record per phase.
- `docs/AGENT_LIFECYCLE.md` — the human-readable narrative.

## Invariants

- **Recall precedes Plan.** No `Plan` may be generated without first calling `AgentMemoryService.recallRelevantKnowledge` and feeding the result into `PlanGenerator`. Skipping Recall when context "feels obvious" is the canonical failure mode. The chain that carries it is `AutonomousAgent.runtimeLoop` → `determinePlanForTask` → `NeuralAgent.runLLMToPlan(task, ideas, relevantKnowledge)` → `AgentReasoning.generatePlan` → `PlanGenerator.synthesizeKnowledge`; `runLLMToPlan` takes the knowledge as a required argument so a call site cannot drop it by omission, and the one other seam that generates a `Plan` — `SparkBasedAgent.runSubPlanForTask`, the opt-in sub-cycle a host asks for by name — recalls for itself rather than planning without (AMPR-388).
- **A phase's prompt carries its own inputs.** Perceive renders the description of the task in the state's current memory cell *and* every `Perception.ideas` entry it was handed; Plan renders the task, the ideas and the recalled `Knowledge`. A phase whose prompt does not name what it was given is asking the model a constant — which is what AMPR-403 found: `"State: $state"` over a role state only ever constructed as `.blank`, with the ideas dropped. The task reaches Perceive through `Agent.rememberNewTask`, never through the role state's own `task` field.
- **Each phase emits its own boundary events, under the run.** `CognitivePhaseEvent.PhaseEntered` / `PhaseExited` mark phase transitions when the phase manager has a bus, and carry the Arc run on their envelope — `PhaseSparkManager` defaults it to the owning agent's `Agent.currentRunId` and holds the entered run until the matching exit, so a bracket closed by `cleanup()` is stamped with the run it opened under (AMPR-386). A bracket's payload names no run, so an unstamped one is invisible to `project(runId)` even with the payload fallback; `ProviderCallStartedEvent` / `ProviderCallCompletedEvent` carry a `cognitivePhase`; memory writes carry the phase that produced them; tool calls are tagged via the active phase. `ArcTraceProjection` relies on this to bucket activity per phase. A phase that runs without emitting boundary events is invisible to the trace, which is equivalent to it not having run.
- **Execute publishes what it did, not just what it returned.** `PlanExecutor` brackets each executed step with `PlanEvent.PlanStepStarted` / `PlanStepCompleted`, and `ToolExecutionEngine` brackets each dispatch with `ToolEvent.ToolExecutionStarted` / `ToolExecutionCompleted` (AMPR-389). Both halves carry the run id and are written under `NonCancellable`, and the engine's bracket wraps the dispatch call only — since AMPR-401 that is the injected `Executor` for a `FunctionTool` and the MCP arm for an `McpTool`, both inside the bracket — while a dispatch `PlugPermissionGate` refused is reported by `PermissionDeniedEvent` instead, so a tool pair in a trace means a tool ran. A step skipped after an earlier critical failure never started, so it emits neither event and is reported only in `PlanExecutionResult.stepOutcomes`. Since AMPR-396 the live path hands Execute one step at a time, each wrapped in its own one-step plan, so a real trace shows one pair per dispatch with `stepIndex = 0`, `totalSteps = 1` and a `planId` per step — read the run's steps off the sequence of pairs, not off `totalSteps`.
- **Every model call a phase makes names its phase.** Execute's parameter-strategy call in `ToolExecutionEngine.execute` passes `RoutingContext(phase = EXECUTE, workflowId = runId, agentId = executorId)`, the same way the other phase services do. An untagged call lands in `ArcTraceProjection`'s `UNKNOWN` bucket, which is where this one used to live.
- **The loop closes through `Knowledge`.** Every successful Arc run ends with at least one `Knowledge` entry tagged with the `run_id`, *written* — building the cell and handing it back is not closing the loop (AMPR-402). Two phases write: `KnowledgeExtractor` inside an agent's own Learn phase, and the Arc's `PulsePhase`, which distils one entry per successful outcome and stores it through the producing agent's `AgentMemoryService` so `KnowledgeStored` names the holder. An Arc run that produced outcomes but no Knowledge entry has not closed the loop and will not contribute to future Recall. A run that is cancelled, or where a phase throws, does not close it and is not credited with a `Knowledge` entry: it leaves a `CompletionManifest` instead (AMPR-282), persisted as `ArcRunEvent.CompletionManifestRecorded` and read back as `ArcRunTrace.completion` (AMPR-359).
- **Phase order is fixed.** Perceive → Recall → Observe → Plan → Execute → Learn. The declaration order in `CognitivePhase` matches the PROPEL acronym; `enumValues<CognitivePhase>()` yields the cycle. New phases are added by extending the enum and updating every service that switches on it; phases are never reordered or skipped per call site.
- **Plan is offered only the tools the agent may actually use.** `PlanGenerator`'s available-tools list comes from the caller's `availableTools` provider, which for a spark-based agent is its *narrowed* set. Planning against a wider set invites steps the executor will refuse, and the refusal costs a whole Arc run rather than a retry.
- **Everything a phase prompt asks for is parsed.** A phase's response schema is a bill the run pays in tokens on every call. A key the prompt declares and the parser never looks up is a field the model spent output on and the loop discarded — `PlanGenerator` asked for `requiresHumanInput` on every plan and dropped it for four releases (AMPR-398). Adding a key to a phase schema and reading it back are one change.
- **Observe is not a side-effect of Plan.** When recalled `Knowledge` contradicts an `Idea` or current state has drifted since the last run, that contradiction must be resolved in Observe and recorded in the Plan's rationale, not silently dropped during Plan generation.

## Common operations

- **Add a phase** — extend `CognitivePhase` (in `PhaseSpark.kt`), add a corresponding `PhaseSpark` data object with prompt contribution, update `PhaseSpark.forPhase`, add a service implementing the phase transform, wire it through `AgentReasoning`, and update `ArcTraceProjection.phaseNameFor` so the trace knows about the new phase.
- **Bracket a phase under a run** — `phaseSparkManager.withPhase(phase) { … }` from inside an agent already stamps that agent's run. Pass `runId` explicitly only when the caller holds a run the agent does not (a harness driving a run-less agent).
- **Hook into a phase** — prefer `CognitivePhaseEvent.PhaseEntered` / `PhaseExited` when an event bus is wired. For older spark-stack consumers, subscribe to `SparkAppliedEvent` filtering on `phaseSparkName()`. Do not assume `PhaseSpark`s are always enabled — they're gated on `AgentConfiguration.cognitiveConfig.phaseSparks.enabled` or `AMPERE_PHASE_SPARKS=true`. Since AMPR-387 the brackets and the prompt guidance are separate switches under `enabled` (`publishBrackets` / `injectPhaseSparks`), so a consumer subscribing to the events must not infer that phase guidance was in the prompt, and one reading the prompt must not infer that brackets were published.
- **Put a host's own observations in front of Perceive** — supply `AgentReasoning.create { perceptionContextBuilder = { state -> … } }` (AMPR-403). It replaces `defaultPerceptionContext` rather than extending it, and is typed over `AgentState` because `ReasoningSettings` is not generic. To state a goal instead of a world, pass it as an `Idea` to `perceiveState`; the two compose.
- **Persist what Execute did** — wire an `OutcomeMemoryRepository` into `ToolExecutionEngine` (via `ReasoningSettings.outcomeRepository`, which the agent factories and `AmpereRuntime` thread down). The engine then records every `ExecutionOutcome` it returns — including the refusals it produces before dispatch — against the request's ticket and run id. That is the *persisting path*: an engine built without a store returns the same outcomes and records nothing.
- **Let Execute call an MCP tool** — wire a `ServerManager` into `ToolExecutionEngine` (via `ReasoningSettings.mcpServerManager`, set by `AgentReasoning.create { execution { mcpServers(…) } }`): a `PlugContext.mcpServerManager` for a plug's servers, `McpServerManager` for discovered ones. The engine is the only execute path for both tool kinds (AMPR-401, which deleted the second one, `propel/ExecuteStep`), and it gates the call before it uses the route. Without a manager an `McpTool` dispatch fails saying so, which is what every one of them did before.
- **Read what one Arc run did** — `ArcTraceProjection.project(runId)` returns an `ArcRunTrace` with one `PropelPhase` per phase. This is the "playback" of a cognitive cycle. Use this for debugging, not for orchestration.
- **Validate a change to the loop** — run `./gradlew jvmTest` (the primary gate) and verify the loop still produces a `Knowledge` entry tagged with the run id.

## Anti-patterns

- **Short-circuiting Recall when the context "feels obvious"** — the whole point of Recall is to override the agent's confidence with prior outcomes. Plans that look obvious are exactly the ones where past failures live.
- **Reading "Recall precedes Plan" as satisfied because a `KnowledgeRecalled` event exists** — it records what the *query* returned, not what the *prompt* received. From the invariant's introduction until AMPR-388 (`v0.17.0`) the two diverged: `determinePlanForTask` accepted `relevantKnowledge` and then called `runLLMToPlan(task, ideas)`, and the step re-plan (`SparkBasedAgent.runLLMToExecuteTask` before AMPR-396 moved it to `runSubPlanForTask`) planned with no knowledge at all, so every `KnowledgeRecalled` the loop recorded described knowledge the planner never saw. An optional `relevantKnowledge` parameter with an `emptyList()` default is how that drift stayed invisible; check the prompt, not the plumbing.
- **Treating Learn as bookkeeping** — the closing phase is where the system *learns*. If your change records outcomes but doesn't extract `Knowledge`, you've made the loop open-loop.
- **Returning the `Knowledge` instead of storing it** — a closing phase that builds cells and puts them on its result object looks complete at every call site and closes nothing. `PulsePhase` did exactly this for the life of the Arc runtime (AMPR-402): the cells were on `PulseResult.learnings`, no caller read them, and `Recall` found nothing on the next run. The write is the phase; a `Learning` reports whether it landed.
- **Writing a learning under a task type nothing recalls with** — Recall's first strategy matches `MemoryContext.taskType` against the stored `task_type`, so a bespoke string on the write side stores an entry no query will ever return. Take the value from `MemoryTaskTypes`.
- **Asking a decision question without a phase** — `decide(…, phase = null)` files the record under whatever phase happens to be active, the same way an untagged `callLLM` does. Pass the phase that is asking.
- **Rendering a phase's input with `toString()`** — a role state's `toString()` is an identity hash and its declared fields are `.blank`, because `CodeState.blank` and friends are the only constructions. The live channel is the memory cell (`state.getCurrentMemory()`), and a prompt that reads anything else is stable across iterations no matter what the agent is doing.
- **Threading a goal through `SparkSelectionContext.text` and expecting the model to see it** — that text selects which spark applies; it never reaches the prompt. A goal reaches the model as an `Idea` or through the phase's context builder.
- **Degrading around a transport that was never there** — `PlanGenerator` falls back to a one-step plan when a model call fails, and a plan step nominating no tool executes as a successful "reasoning step". For a `MissingUpstreamLlmClientException` that laundered a configuration error into a completed goal: an Arc with no `UpstreamLlmClient` reported `GOAL_COMPLETE` having called nothing, while the telemetry for the same step recorded a failed call. The no-transport case now plans `Plan.blank`, which executes to `Outcome.blank` and is not a success (AMPR-395). A fallback is for a model that answered badly, not for one that was never reached.
- **Inlining a "quick LLM call" outside a phase** — every model invocation should be wrapped in a `ProviderCallStartedEvent` / `ProviderCallCompletedEvent` pair tagged with `cognitivePhase`. Calls outside this contract don't appear in `ArcRunTrace` and break the glass-brain guarantee.
- **Routing a tool's outcome through `OutcomeEvaluator` on the way to the store** — it looks like the evaluator's job from its name, and from where it sits in Learn, but it takes `Outcome`s and returns learnings; it has never produced an `ExecutionOutcome`. Persisting through it would mean spending an LLM call to write a memory row.
- **Re-planning inside Execute** — a step arriving at the Execute phase is already the Plan phase's output and already names its tool, so calling `generatePlan` on it bills a PLAN call per step and dispatches a sub-plan's tools instead of the ones the Plan phase chose. `SparkBasedAgent.runLLMToExecuteTask` did exactly this until AMPR-396: a three-step plan cost four PLAN calls and ran none of its own steps' tools. Breaking a coarse step down is a sub-cycle a host asks for by name (`SparkBasedAgent.runSubPlanForTask`), never the meaning of "execute this step".
- **Reading a nullable planner field as `jsonPrimitive.content`** — `JsonNull` *is* a `JsonPrimitive` and its `content` is the string `"null"`, so a step the model wrote as `"toolToUse": null` reads back as a tool literally named `null` and fails strict tool-id dispatch. Parse nullable generated fields through a helper that rejects `JsonNull`, blanks, and the four characters `null`.
- **Treating a phase service's `eventApi` as optional plumbing** — it is what makes the phase visible. `PlanExecutor(executorId)` with no door still executes the plan perfectly and leaves no evidence it ran; a service wired without the door it was offered produces a trace that is wrong by omission rather than one that is merely thin.
- **Adding a key to a phase's response schema without a reader** — the prompt is the cheap half; the parse is the half that makes the answer exist. `grep` the key name across the repo before shipping the prompt: one hit means the answer is being bought and thrown away.
- **Folding Observe into Plan** — recalled `Knowledge` becomes a passive context dump rather than an explicit selection between alternatives. The Plan emerges with no record of *why this approach over the others*.
- **Letting the loop take its next task from its own output** — the Execute phase records each plan step as the agent's current task, so a loop that does not finish the task it ran reads the plan's last step back as its next assignment and re-plans it forever (AMPR-397). An iteration's task comes from the work the agent was given; an agent with no assignment idles without a model call rather than reasoning about a blank task.
- **Stamping a task id where a run id goes** — `runtimeLoop` passed `task.id` as the `runId` of `KnowledgeStored` until AMPR-386. Both are opaque strings, so it compiled and read plausibly, and the entry landed in the `run_id` column of a run that does not exist. The agent's run is `Agent.currentRunId`; a task id belongs in `taskType`/source fields.
- **Holding state across runs in the agent object** — agents are animated, not stateful in memory. Cross-run state belongs in `OutcomeMemoryRepository` / `KnowledgeRepository`, keyed by ids retrievable in Recall. Anything else is invisible to the trace and fragile across restarts.
