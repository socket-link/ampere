---
concept: PlugPermissions
status: stable
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/plug/permission/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/plug/PlugManifest.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/plug/PlugManifestValidator.kt
  - ampere-core/src/commonMain/sqldelight/link/socket/ampere/db/PlugGrants.sq
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/execution/ToolExecutionEngine.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/propel/Breaker.kt
related: [SparkSystem, AgentSurface, EventSerialBus, LinkLayer, PropelLoop]
last_verified: 2026-10-20
---

# Plug Permissions

## What it is

`PlugPermissionGate` is a deterministic, side-effect-free check that
runs *before* AMPERE dispatches a plug-backed tool call. It compares
the union of (a) the plug's manifest-declared permissions and (b) the
specific permissions the tool call requested, against the user's
`UserGrants`, and returns one of:

- `GateResult.Allow`
- `GateResult.DenyMissing(permission)` — the user has not granted it.
- `GateResult.DenyRevoked(permission)` — the user explicitly revoked it.

Permissions are typed and sealed: `NetworkDomain(host)`,
`MCPServer(uri)`, `KnowledgeQuery(scope)`, `NativeAction(actionId)`,
`LinkAccess(linkId)`, `DeviceCapability(capability)`. User grants persist
in `PlugGrants.sq`.

`DeviceCapability` is different from the other five: it names an OS
permission (calendar, contacts, location, ...), not an Ampere-internal
capability grant. `GateResult.Allow` only proves Ampere's own grant
bookkeeping is satisfied — it says nothing about whether the OS itself
authorized the capability. That is reported separately via
`NativeAuthorizationStatus` (`Granted` / `NotDetermined` / `Denied` /
`Restricted` / `EntitlementMissing`), which a client (e.g. Socket's
`NativeAuthorizationGate`) produces by calling the platform framework.
`Restricted` (device policy, MDM, Screen Time) and `EntitlementMissing`
(developer-account gap, e.g. FinanceKit grants or PassKit certificates)
are kept distinct from `Denied` because they have different remedies —
neither is fixed by re-prompting the user.

## Why it exists

Plugs are arbitrary code, often third-party. The threat model is
straightforward: a plug should not be able to talk to a network host,
mount an MCP server, query the knowledge store, run a native action, or
follow a link unless the user (or operator) has explicitly approved it.
Because the LLM is upstream of the gate and is not a trustworthy
authorizer, the check has to be deterministic and *not LLM-mediated*.

Two design pressures shaped the gate:

1. **Pre-LLM enforcement.** The check must happen before tool dispatch,
   not as a runtime hook the plug can intercept. A revoked permission
   means the call doesn't run, full stop — the LLM doesn't even see a
   "denied" tool result it can argue with.
2. **Closed permission set.** The sealed `PlugPermission` interface
   means new permission kinds are versioned changes — a plug can't
   sneak in a custom permission name. New permission types are added to
   the sealed hierarchy, not declared by plugs.

## Where it lives

- `plug/permission/PlugPermission.kt` — the sealed permission types.
- `plug/permission/NativeAuthorizationStatus.kt` — the sealed OS-authorization outcome, distinct from `GateResult`.
- `plug/permission/PlugPermissionGate.kt` — `check(toolCall, manifest, userGrants): GateResult`.
- `plug/permission/UserGrantStore.kt` — persistence facade.
- `plug/PlugManifest.kt` — declares `requiredPermissions`, plus the `requiredLinks` / `emits` / `consumes` declarations owned by [LinkLayer](link-layer.md).
- `plug/PlugManifestValidator.kt` — structural manifest checks, including duplicate `DeviceCapability` declarations. Expected to run at plug install time on every host that accepts manifests; today only `PlugContext.create` calls it.
- `commonMain/sqldelight/link/socket/ampere/db/PlugGrants.sq` — schema for user grants.
- `agents/execution/ToolExecutionEngine.kt` — the call site that gates dispatch, and since AMPR-401 the *only* one: it is also where an `McpTool` is dispatched, through the `ServerManager` it is constructed with (a `PlugContext.mcpServerManager` for plug tools, `McpServerManager` for discovered ones). The dead second path, `propel/ExecuteStep`, is gone.
- `propel/Breaker.kt` — the plan-step call site (AMPR-440). `SparkBasedAgent.executePlanStep` admits every step before its tool runs, and the Breaker's access rule is a Hard `Rule.AccessPolicy` derived from the tool's manifest, evaluated by calling `PlugPermissionGate.check` unchanged. It is the tool's rule (`Breaker.rulesFor`); a hosted run's quotas ride beside it as the run's rules (AMPR-445, see [HostedRun](hosted-run.md)) and are evaluated after it, so an access trip still wins and still publishes its denial. `Allow` is `Admission.Pass`; `DenyMissing` / `DenyRevoked` are `Admission.Trip`, with the plug, tool and permission in the reason. Since AMPR-472 the trip is also on the record as a `PlanEvent.StepAdmitted` whose `Trip.rule` is `AccessPolicy:<plug id>`, published after the denial, and the failed step carries the same rule as `StepOutcome.Failure.tripped` — so a reader can tell a refused step from a failed one without parsing the `Permission denied for plug …` text.
- `agents/domain/event/PermissionDeniedEvent.kt` — emitted on `Deny*` results, built by the one `internal` helper `publishPermissionDenied` (in `ToolExecutionEngine.kt`) that both the engine and the Breaker call.

## Invariants

- **The gate runs before tool dispatch.** No plug tool call may execute without a `GateResult.Allow`. A code path that dispatches a plug tool without consulting `PlugPermissionGate.check` is a critical security regression. For a `Tool` the engine dispatches, AMPR-401 made this structural as well: a plug-sourced `McpTool` carries no executor of its own and `McpClient.activeConnection` is `internal`, so the engine's `ServerManager` is the only route to a plug's MCP server and the gate sits in front of it. One non-dispatch path remains and is not gated: `PlugContext.mcpClientFor` hands an adapter that *implements* a plug (`LinearWorkSource.open`) the client for its own pinned tools, called by name over a Link `PlugContext.create` already resolved. No agent and no `Tool` is involved there, but it does mean "every MCP call is gated" is not true today — only every MCP call an agent makes.
- **On the plan-step path the Breaker evaluates the gate first, and the engine still evaluates it after.** Since AMPR-440 a plan step whose tool has a `plugManifest` is admitted by the Breaker at `executePlanStep`, before its parameter-strategy model call and before the engine is entered; a trip fails the step there. The engine's own `checkPlugPermissions` is unchanged and still runs for every dispatch, so it covers every path that reaches the engine without `executePlanStep` and, on the plan-step path, sees `Allow` unless consent was withdrawn between the two reads — in which case it still refuses. The Breaker reads grants from the same provider the engine was built with (`SparkBasedAgent._userGrantProvider`, or the same deny-all `UserGrants()` when the agent has none), so the two cannot disagree about a step.
- **Revoked beats granted.** If a permission is in `userGrants.revoked`, the gate returns `DenyRevoked` regardless of whether it is also in `granted`. The order in `PlugPermissionGate.check` is intentional and load-bearing.
- **Permissions are sealed.** New permission kinds must be added as variants of the sealed `PlugPermission` interface, with stable `@SerialName`s. Plugs cannot declare ad-hoc string permissions.
- **Manifest and tool-call permissions are unioned, then deduplicated.** A permission listed in either is required. A change that ANDs them (only check the intersection) is a privilege escalation.
- **`Deny*` results emit `PermissionDeniedEvent`, once per denied step.** The bus carries the denial so the trace and any UI can show *what was denied and why*. Silent denials defeat observability. A step the Breaker trips publishes it from the Breaker and never reaches the engine; a dispatch the engine refuses publishes it from the engine. Both go through `publishPermissionDenied`, so the event has the same fields whichever site refused. The `StepAdmitted` a Breaker trip also leaves is the admission's record, not a second denial: `PermissionDeniedEvent` is still published exactly once.
- **The gate is side-effect-free.** It does not mutate `UserGrants`, the bus, or any store. Granting / revoking happens through `UserGrantStore`. A change that has `check` mutate state is wrong.

## Common operations

- **Add a permission kind** — extend `PlugPermission` with a new `@Serializable` data class, give it a stable `@SerialName`, update the renderer that maps permissions to user-friendly prompts (`AgentSurface.Confirmation` is the standard channel for grant requests). Note that no such renderer exists yet — the only exhaustive `when` over `PlugPermission` today is `PlugBundleValidator.validatePermission`, which the compiler will force you to update.
- **Report OS authorization state** — for a `DeviceCapability`, use `NativeAuthorizationStatus`, not `GateResult`. `GateResult` only says whether Ampere's own grant bookkeeping allows the call; it cannot represent `Restricted` or `EntitlementMissing`.
- **Grant / revoke** — `UserGrantStore.grant(permission)` / `revoke(permission)`. These persist to `PlugGrants` and emit grant events.
- **Give a hosted seat its grants** — set `HostedAgent.userGrants` (or the `userGrants` parameter of `HostedAgent.of`) to a `suspend (PlugManifest) -> UserGrants`, typically one that reads the consumer's `UserGrantStore`. `RosterRunHost` passes it to the seat's `SparkBasedAgent` as `_userGrantProvider`, which installs it on that seat's `ToolExecutionEngine` and hands it to the seat's Breaker; it is called on every dispatch of a tool with a `plugManifest` — on a plan step once by the Breaker and once more by the engine, and a third time by the engine when a hosted step went back to its plan gate because its arguments changed (AMPR-454), since that review may have waited on a person — so a revocation takes effect on the next call. Null leaves the engine's deny-all default, and one seat's provider never covers another seat (AMPR-423).
- **Gate a tool dispatch** — `ToolExecutionEngine` calls `PlugPermissionGate.check(toolCall, manifest, userGrants)`. On `Deny*`, emit `PermissionDeniedEvent` (through `publishPermissionDenied`) and surface a `Confirmation` to the user via `AgentSurface`. A plan step is gated earlier as well: `Breaker.admit(tool, Breaker.rulesFor(tool) + runRules)` at `executePlanStep`, where `runRules` is empty except on a hosted run with quotas.
- **Audit grants** — read `PlugGrants` directly; rows are append-only. The user's current state is the most recent grant/revoke per permission.

## Anti-patterns

- **Skipping the gate "because the user obviously approved this plug".** Manifest approval is for the plug; permissions are per-call. Skipping the gate breaks the per-call security guarantee.
- **Returning `Allow` on missing permissions to "smooth out a UX rough edge".** This is the entire failure mode the gate exists to prevent. Surface a `Confirmation` instead.
- **String-typed permissions.** "Just pass a string permission name through" — and now the surface and gate disagree about what `network` means. Use the sealed types.
- **AND-ing manifest and tool-call permissions.** Some manifests under-declare; some tool calls over-declare. Both views must be satisfied.
- **Not emitting `PermissionDeniedEvent` on denial.** The trace shows a tool that didn't run with no explanation. Always emit.
- **Constructing `PermissionDeniedEvent` at a second site by hand.** AMPR-440 put a second refusal site in front of the engine; copying the event's construction there would let the two drift, and a denial would read differently depending on which site refused. Call `publishPermissionDenied`.
- **Removing the engine's check because the Breaker now runs first.** The Breaker sits only on `executePlanStep`. `runLLMToExecuteTool`, `ToolExecutionEngine.execute` called directly, and any path added later reach the engine without it, and on the plan-step path the engine's re-read is what catches consent withdrawn after admission. Wrap, don't rewrite (F15).
- **Building the Breaker over a different grant source than the engine.** A Breaker that grants what the engine denies is a pass the engine has to catch; one that denies what the engine grants refuses steps that would have run. Hand both the same provider.
- **Caching `userGrants` for "performance" without invalidation on revoke.** Stale grants let a revoked permission act granted. Revocation must invalidate.
- **Giving a plug-sourced `McpTool` an executor so `tool.execute()` works.** Tempting while unifying the two plug execute paths (AMPR-401), because it is what `McpServerManager` does for the tools it discovers — but those carry no `plugManifest`, and `Tool.execute` does not consult the gate, so stamping one onto a plug's tool creates a dispatch route around it. Hand the route to the engine instead.
