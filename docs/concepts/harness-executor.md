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
  when no `allow_once` is offered. Every request is decided on its own. The fence is only
  consulted when the harness is configured to ask: OpenCode's default asks for nothing, so
  `openCode(...)` spawns it with `OPENCODE_CONFIG_CONTENT` carrying
  `{"permission":{"edit":"ask","bash":"ask"}}`, merged under any config the caller passes
  (AMPR-504). `bash` stays `ask` under `ShellAccess.Allow` too, so every command is on the
  record as an `allow_once`.
- **The audit decides, the agent informs.** The outcome's `changedFiles` is what the
  `WorkspaceObserver` saw change between the two snapshots. What the agent reported is the
  fallback only when the workspace could not be observed, and the outcome then says so.
- **An out-of-scope change fails the step and reverts nothing.** Any changed path that fails
  `allowsWrite` is a `CodeChanged.Failure(WORKSPACE_ERROR)` naming it, with every changed
  path in `partiallyChangedFiles`. Undoing writes in a person's checkout is not the
  executor's call.
- **A refusal is reported as a refusal, whatever stop reason the agent answers with.** An
  agent may end the turn instead of carrying on after the fence rejects a permission
  request: OpenCode 2.2.0 marks the tool call `failed` and answers `cancelled` (AMPR-505).
  A `cancelled` turn with a refusal on record is a failure that names the refusal and is
  not retryable: `WORKSPACE_ERROR` when a path was refused, `UNEXPECTED` for a denied shell
  command or an unacceptable set of options. `HarnessTurn.refusals` keeps each refusal's
  kind for this. Only a `cancelled` with nothing refused reads as the agent cancelling on
  its own, retryable. A turn that goes on to `end_turn` is still judged by the audit, with
  the refusals in its account.
- **Zero changes fails a step that asked for a change.** A turn that ended `end_turn` and
  touched nothing, on a step whose task is a `Task.CodeChange` or whose tool is
  `harness_code_change` (bare or seat-namespaced), is a `CodeChanged.Failure(UNEXPECTED)`, retryable, with an empty
  `partiallyChangedFiles` and the agent's words in `details` (AMPR-507). A harness that
  declined in prose and one that found the work already done end the turn the same way, and
  the step has not done what the plan asked either way. Any other step routed to the harness
  that changes nothing is `NoChanges.Success` with the agent's words. Never a
  `CodeChanged.Success` with an empty list.
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
- **The request's workspace first; the constructor's is the fallback.** The router plugs into
  the existing `executor` parameter of `rosterRunHost` and `Ampere.fromEnvironment`, and the
  checkout arrives on the request: since AMPR-496 a hosted run is opened with
  `RunHost.open(…, workspace)` and every seat stamps it on every step request, so one
  executor serves runs on different checkouts, a session per `(workspace, run)`. The
  `workspace` an `AcpCodeExecutor` is built with is used only for a request that names none,
  and with neither the step fails `WORKSPACE_ERROR` without spawning anything — there is no
  default workspace (AMPR-300).

## Common operations

- **Bind OpenCode behind the tool, for a hosted run** —
  ```kotlin
  val harness = AcpCodeExecutor.openCode()   // no checkout of its own: each run names one
  val executor = RoutedExecutor(
      default = FunctionExecutor.create(),
      bindings = mapOf(HARNESS_CODE_CHANGE_TOOL_ID to harness),
  )
  val host = rosterRunHost(createEventApi = env::createEventApi, agentScope = scope, executor = executor)
  val run = host.open(roster, seats, goal, tools, workspace = ExecutionWorkspace("/abs/checkout"))
  // the doer seat's RoleConfig.tools includes HARNESS_CODE_CHANGE_TOOL_ID, and the run's
  // `tools` includes ToolHarnessCodeChange(); the planner nominates `<doer>/harness_code_change`.
  ```
  An `openCode` with no `workspace` starts the agent process in the host process's working
  directory. That is where the binary runs, not where it works: every session is opened with
  `cwd` set to the request's checkout, and the audit observes that checkout.
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
- **Pick OpenCode's model** — `openCode(environment = mapOf("OPENCODE_CONFIG_CONTENT" to
  """{"model":"anthropic/claude-haiku-4-5"}"""))`; `opencode acp` has no `--model` flag. The
  caller's JSON is merged over the permission asks, key by key, and wins on a collision.
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
- *Spawning OpenCode with its default permissions.* It then asks nothing: edits are written
  and commands run from its own process, so `HarnessFence`, the `fs/…` gate and
  `ShellAccess.Deny` are never reached and only the audit sees what happened (AMPR-498).
  Spawn through `openCode(...)`, or set `permission` to `ask` yourself; never write an
  `opencode.json` into the person's checkout to do it.
- *Trusting the `fs/*` gate as the fence.* OpenCode writes to disk directly; only the
  post-turn audit sees that.
- *Selecting `allow_always` because the agent offered it.* The next edit in the session
  would skip the gate; the audit could only report it, not prevent it.
- *Reading the agent's `cancelled` as its own cancel.* After a fence rejection OpenCode
  ends the turn `cancelled`; reporting that as a retryable agent-side cancel sends the
  Breaker back into the same refusal (AMPR-505). Check `HarnessTurn.refusals` first.
- *Reverting an out-of-scope write.* Destructive in a person's checkout; the failure names the
  path and the Breaker's or the person's decision follows.
- *Giving the harness a door to publish its own progress.* The engine already brackets the
  dispatch and holds the door, the `invocationId` and the performer; a second publisher
  beside it would be two producers of one call's trace (AMPR-495). Emit a status and let the
  engine publish it.
- *Telling a decline from "already done" by reading the agent's message.* The wording is the
  model's and changes with it; the audit's count of changed files is the deterministic signal,
  and the message is carried for the planner to read, not parsed (AMPR-507).
- *Deciding "asked for a change" by `Task.CodeChange` alone.* A hosted run's goal is a
  `Task.Step`, so every step it plans is one too; the nominated `harness_code_change` tool is
  what says the step wanted code changed (AMPR-507).
- *Publishing the agent's message chunks as progress.* They produce no status on purpose;
  the harness's own account rides in the outcome's `diffSummary`.
- *Adding a CLI config key to `api/AmpereConfigYaml.kt`.* That file is the SDK's `fromYaml`;
  the CLI parses `ampere-cli/.../config/AmpereConfig.kt` through `ConfigParser`, so a section
  only core knows about never reaches `AmpereContext`.
- *Resolving a harness's secrets at parse time.* The parsed entry keeps `${NAME}` as written;
  only `toExecutor` reads the environment, so a parsed config can be printed or re-encoded
  without carrying a key.
- *Binding the harness to a checkout to make a hosted run work.* Before AMPR-496 a hosted seat
  carried no workspace and this was the only way in; now it pins every run through that
  executor to one tree. Open the run with its `workspace` and leave the executor unbound, or
  bound only as the fallback.
- *A `commonMain` subprocess.* `ProcessGroups` is JVM; the common half must stay behind
  `AcpTransport`, or the module stops compiling for Android, iOS, JS and wasmJs.
