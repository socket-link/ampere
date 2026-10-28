---
concept: HarnessExecutor
status: experimental
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/execution/executor/**
  - ampere-core/src/jvmMain/kotlin/link/socket/ampere/agents/execution/executor/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/execution/tools/ToolHarnessCodeChange.kt
related: [PropelLoop, HostedRun, SparkSystem, CancellationAddress, CognitionTrace, MemoryProvenance]
last_verified: 2026-10-27
---

# HarnessExecutor

## What it is

A third-party coding harness as an `Executor`: a plan step whose work is a code change is
handed to an external agent process over the Agent Client Protocol (ACP), admitted by the
Breaker like any other step, bounded by the spark's `FileAccessScope`, and recorded with the
harness as the performer. `AcpCodeExecutor` is the first `CodeExecutor` implementation, and
`AcpCodeExecutor.openCode(...)` binds it to OpenCode (`opencode acp`). A planner reaches it
through one built-in tool, `harness_code_change`, and a `RoutedExecutor` in front of the
engine's single executor slot sends that tool to the harness and everything else to the
in-process `FunctionExecutor`.

## Why it exists

The Field epic's thesis (AMPR-328) is that third-party harnesses own execution and Ampere's
differentiator is in-loop metacognition. A harness becomes an executor the Breaker admits
steps to, not a second loop. Until AMPR-444 the `CodeExecutor` interface had no
implementation, and the only way to change code was `write_code_file`, whose parameter
strategy spends a model call generating file contents before the executor is reached — a
harness generates the code itself.

Three pressures fix the shape:

- **Attribution.** AMPR-443 made the tool pair, the dispatched context and the outcome row
  name the `Executor.id` that performed a call. The engine holds one executor per seat, so a
  harness cannot be *the* executor without taking every other tool with it, and a router in
  that slot would be recorded as the performer. `Executor.performerFor(tool)` is the one
  default member that lets a composite executor answer with the executor it routes to.
- **The fence.** OpenCode writes edits to disk from its own process even when the client
  advertises `fs.writeTextFile` (anomalyco/opencode#38196). A gate on the client-side `fs/*`
  methods is therefore a courtesy a cooperative agent honours, not the boundary. The boundary
  is what actually changed on disk, read after the turn and checked against the scope.
- **Hostability.** `CodeExecutor` is sealed, so its implementation has to be `commonMain`,
  and `propel/` must stay hostable everywhere. The protocol, the executor and the fence are
  common and written against an `AcpTransport` interface; the process and git are JVM.

## Where it lives

`$exec` = `ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/execution/executor`.

- `$exec/AcpCodeExecutor.kt` — the executor: lazy spawn, one session per `(workspace, run)`,
  the turn, the audit, the terminal outcome, the `fs/*` and permission handler.
- `$exec/RoutedExecutor.kt` — the router; `Executor.performerFor` is on `Executor.kt`.
- `$exec/acp/AcpMessages.kt` — the JSON-RPC envelope and the typed ACP messages;
  `AcpJson` is the wire's one `Json`.
- `$exec/acp/AcpTransport.kt`, `$exec/acp/AcpConnection.kt` — one line in, one line out;
  request correlation, notification delivery, the agent's requests answered on their own
  coroutine, the cancel handshake.
- `$exec/acp/HarnessFence.kt`, `HarnessPolicy.kt` — the deterministic answer to every
  permission and file request; shell access, grace and timeouts.
- `$exec/acp/HarnessTurn.kt`, `HarnessPrompt.kt`, `WorkspaceObserver.kt` — updates to
  statuses, the prompt, the before/after snapshot contract.
- `ampere-core/src/jvmMain/.../executor/acp/StdioAcpTransport.kt` — the subprocess, through
  `ProcessGroups.start`; `GitWorkspaceObserver.kt` — `git status --porcelain -z` plus a
  content digest; `../executor/OpenCodeExecutor.kt` — `openCode(...)`, id `opencode`;
  `../executor/HarnessConfig.kt` — one `harnesses:` YAML entry, `build` into `AcpCodeExecutor`s
  and `routedOver` a default (AMPR-497).
- `ampere-core/src/jvmMain/.../api/AmpereConfigYaml.kt` — `harnessesFromYaml(path)`, the SDK's
  YAML route. The CLI reads its own `ampere-cli/.../config/AmpereConfig.kt` (`harnesses:`);
  `AmpereContext` builds `executor` and `harnesses` from it, `StatusCommand` asks each its health.
- `agents/execution/tools/ToolHarnessCodeChange.kt` — `harness_code_change`, the tool a
  planner nominates; its own function fails `TOOL_UNAVAILABLE` so an unbound declaration
  says so.
- `ToolExecutionEngine.performerOf` — reads `executor.performerFor(tool)` for a
  `FunctionTool`; AMPR-444's one engine change. `ToolExecutionEngine.publishToolProgress`
  (AMPR-495) turns each streamed status into a `ToolEvent.ToolExecutionProgress`.
- Tests: `commonTest/.../executor/acp/` (the wire, the connection, the fence, an in-memory
  `ScriptedAcpAgent`), `commonTest/.../executor/AcpCodeExecutorTest`, `RoutedExecutorTest`;
  `jvmTest/.../executor/` (a real `sh` fake agent, the git observer, the hosted-run proof).

## Invariants

- **The harness is the recorded performer.** Every `ExecutionStatus` and `ExecutionOutcome`
  an `AcpCodeExecutor` produces carries its own `id`; the engine stamps
  `executor.performerFor(tool)` on the pair, the context and the row. A `RoutedExecutor` never
  appears as a performer.
- **Allow once, never always.** `HarnessFence` answers a permission request with the option
  of kind `allow_once` or a `reject_*`; it never selects `allow_always`, and cancels the turn
  when no `allow_once` is offered. Every request is decided on its own.
- **The audit decides, the agent informs.** The outcome's `changedFiles` is what the
  `WorkspaceObserver` saw change between the two snapshots. What the agent reported is the
  fallback only when the workspace could not be observed, and the outcome then says so.
- **An out-of-scope change fails the step and reverts nothing.** Any changed path that fails
  `allowsWrite` is a `CodeChanged.Failure(WORKSPACE_ERROR)` naming it, with every changed
  path in `partiallyChangedFiles`. Undoing writes in a person's checkout is not the
  executor's call.
- **Zero changes is `NoChanges.Success`.** A turn that ended `end_turn` and touched nothing
  returns the agent's words, not a `CodeChanged.Success` with an empty list.
- **Every spawn goes through `ProcessGroups`.** `StdioAcpTransport` holds the
  `CancellationAddress` from spawn, closes stdin first, then `terminate()`s the group. No
  `ProcessBuilder.start()`, no `destroy()`.
- **Cancellation is the ACP handshake.** A cancelled turn sends `session/cancel` under
  `NonCancellable`, waits `HarnessPolicy.cancelGrace` for the `cancelled` stop reason, and
  closes the connection if it does not come. The next call spawns a fresh agent.
- **The harness's statuses are on the record, and the engine puts them there.** Each
  `Planning`, `Analyzing` and `Writing` an `AcpCodeExecutor` streams is published by
  `ToolExecutionEngine` as one `ToolEvent.ToolExecutionProgress` between the tool pair,
  through the deciding agent's door, under the pair's `invocationId`, run and performer — a
  `Writing` with its `filePath` and `operation` (AMPR-495). `ArcTraceProjection` folds them
  into `ToolCallTrace.progress`. The executor publishes nothing itself: it has no door, and
  the outcome is still read from the terminal status alone.
- **No ACP type reaches an `ExecutionOutcome`.** Outcome variants stay tool-agnostic
  (MemoryProvenance); the harness's account rides in
  `validation.codeChanges.diffSummary`, which `describeResult()` renders for the next step.
- **No section, no router.** A config with no `harnesses:` entries routes over nothing, and
  `routedOver` returns the default executor itself — the CLI's `FunctionExecutor`, not an empty
  `RoutedExecutor`. A section that cannot be bound (a problem in an entry, two entries on one
  tool id, an unset `${NAME}`) binds no harness at all, and says why.
- **Secrets come from the environment.** An `environment:` value written `${NAME}` is read from
  the process environment when the executor is built; an unset name is an error naming the
  variable, never a value. No harness's environment is logged.
- **Nothing in `propel/` changes.** The router plugs into the existing `executor` parameter
  of `rosterRunHost` and `Ampere.fromEnvironment`; a hosted seat carries no workspace, so the
  harness is bound to its checkout at construction and a request's workspace wins when
  present.

## Common operations

- **Bind OpenCode behind the tool, for a hosted run** —
  ```kotlin
  val harness = AcpCodeExecutor.openCode(workspace = ExecutionWorkspace("/abs/checkout"))
  val executor = RoutedExecutor(
      default = FunctionExecutor.create(),
      bindings = mapOf(HARNESS_CODE_CHANGE_TOOL_ID to harness),
  )
  val host = rosterRunHost(createEventApi = env::createEventApi, agentScope = scope, executor = executor)
  // the doer seat's RoleConfig.tools includes HARNESS_CODE_CHANGE_TOOL_ID, and the run's
  // `tools` includes ToolHarnessCodeChange(); the planner nominates `<doer>/harness_code_change`.
  ```
- **Bind OpenCode from YAML, for the CLI** — the same router, built by `AmpereContext` over
  `FunctionExecutor.create()` and bound to the CLI's workspace; `ampere status` lists each
  harness with its health:
  ```yaml
  harnesses:
    opencode:                       # `opencode acp`, recorded as `opencode`
      binary: /opt/homebrew/bin/opencode
      environment:
        OPENCODE_API_KEY: ${OPENCODE_API_KEY}
    my-agent:                       # any ACP agent, recorded under its key
      kind: acp
      binary: my-agent
      tools: [scout/harness_code_change]
  ```
  An SDK consumer reads the same section with `harnessesFromYaml(path)` and hands
  `.routedOver(FunctionExecutor.create(), workspace)` to `Ampere.fromEnvironment(executor = …)`.
- **Narrow what the harness may write** — stack a `Spark` with a `fileAccessScope` on the
  seat; the request's scope reaches the fence unchanged (AMPR-414).
- **Let the harness run shell commands** — `HarnessPolicy(shell = ShellAccess.Allow)`.
  Denied by default.
- **Bound a turn** — `HarnessPolicy(promptTimeout = 10.minutes)`; the step fails `TIMEOUT`,
  retryable, with whatever changed in `partiallyChangedFiles`.
- **Add another harness** — a factory like `openCode` with its own `id` and command line;
  nothing else changes. `junie` and `claude-code` are the ids `Executor.kt` already names.
- **Test against a fake agent** — `commonTest`'s `InMemoryAcpTransport` + `ScriptedAcpAgent`
  drive the whole executor without a process; `jvmTest`'s `FakeAcpAgentScript` is a real `sh`
  process for the transport and the git audit.

## Anti-patterns

- *Binding the harness as the seat's only executor.* The engine sends every `FunctionTool`
  through that one executor; `ask_human` would reach a harness that cannot run it. Route.
- *Reusing `write_code_file` for the harness.* Its `CodeParams.CodeWriting` strategy spends a
  model call generating file contents before the executor sees the request.
- *Trusting the `fs/*` gate as the fence.* OpenCode writes to disk directly; only the
  post-turn audit sees that.
- *Selecting `allow_always` because the agent offered it.* The next edit in the session
  would skip the gate; the audit could only report it, not prevent it.
- *Reverting an out-of-scope write.* Destructive in a person's checkout; the failure names the
  path and the Breaker's or the person's decision follows.
- *Giving the harness a door to publish its own progress.* The engine already brackets the
  dispatch and holds the door, the `invocationId` and the performer; a second publisher
  beside it would be two producers of one call's trace (AMPR-495). Emit a status and let the
  engine publish it.
- *Publishing the agent's message chunks as progress.* They produce no status on purpose;
  the harness's own account rides in the outcome's `diffSummary`.
- *Adding a CLI config key to `api/AmpereConfigYaml.kt`.* That file is the SDK's `fromYaml`;
  the CLI parses `ampere-cli/.../config/AmpereConfig.kt` through `ConfigParser`, so a section
  only core knows about never reaches `AmpereContext`.
- *Resolving a harness's secrets at parse time.* The parsed entry keeps `${NAME}` as written;
  only `toExecutor` reads the environment, so a parsed config can be printed or re-encoded
  without carrying a key.
- *A `commonMain` subprocess.* `ProcessGroups` is JVM; the common half must stay behind
  `AcpTransport`, or the module stops compiling for Android, iOS, JS and wasmJs.
