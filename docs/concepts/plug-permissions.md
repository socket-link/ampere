---
concept: PlugPermissions
status: stable
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/plug/permission/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/plug/PlugManifest.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/plug/PlugManifestValidator.kt
  - ampere-core/src/commonMain/sqldelight/link/socket/ampere/db/PlugGrants.sq
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/execution/ToolExecutionEngine.kt
related: [SparkSystem, AgentSurface, EventSerialBus, LinkLayer]
last_verified: 2026-07-30
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
- `agents/execution/ToolExecutionEngine.kt` — the call site that gates dispatch.
- `agents/domain/event/PermissionDeniedEvent.kt` — emitted on `Deny*` results.

## Invariants

- **The gate runs before tool dispatch.** No plug tool call may execute without a `GateResult.Allow`. A code path that dispatches a plug tool without consulting `PlugPermissionGate.check` is a critical security regression.
- **Revoked beats granted.** If a permission is in `userGrants.revoked`, the gate returns `DenyRevoked` regardless of whether it is also in `granted`. The order in `PlugPermissionGate.check` is intentional and load-bearing.
- **Permissions are sealed.** New permission kinds must be added as variants of the sealed `PlugPermission` interface, with stable `@SerialName`s. Plugs cannot declare ad-hoc string permissions.
- **Manifest and tool-call permissions are unioned, then deduplicated.** A permission listed in either is required. A change that ANDs them (only check the intersection) is a privilege escalation.
- **`Deny*` results emit `PermissionDeniedEvent`.** The bus carries the denial so the trace and any UI can show *what was denied and why*. Silent denials defeat observability.
- **The gate is side-effect-free.** It does not mutate `UserGrants`, the bus, or any store. Granting / revoking happens through `UserGrantStore`. A change that has `check` mutate state is wrong.

## Common operations

- **Add a permission kind** — extend `PlugPermission` with a new `@Serializable` data class, give it a stable `@SerialName`, update the renderer that maps permissions to user-friendly prompts (`AgentSurface.Confirmation` is the standard channel for grant requests). Note that no such renderer exists yet — the only exhaustive `when` over `PlugPermission` today is `PlugBundleValidator.validatePermission`, which the compiler will force you to update.
- **Report OS authorization state** — for a `DeviceCapability`, use `NativeAuthorizationStatus`, not `GateResult`. `GateResult` only says whether Ampere's own grant bookkeeping allows the call; it cannot represent `Restricted` or `EntitlementMissing`.
- **Grant / revoke** — `UserGrantStore.grant(permission)` / `revoke(permission)`. These persist to `PlugGrants` and emit grant events.
- **Gate a tool dispatch** — `ToolExecutionEngine` calls `PlugPermissionGate.check(toolCall, manifest, userGrants)`. On `Deny*`, emit `PermissionDeniedEvent` and surface a `Confirmation` to the user via `AgentSurface`.
- **Audit grants** — read `PlugGrants` directly; rows are append-only. The user's current state is the most recent grant/revoke per permission.

## Anti-patterns

- **Skipping the gate "because the user obviously approved this plug".** Manifest approval is for the plug; permissions are per-call. Skipping the gate breaks the per-call security guarantee.
- **Returning `Allow` on missing permissions to "smooth out a UX rough edge".** This is the entire failure mode the gate exists to prevent. Surface a `Confirmation` instead.
- **String-typed permissions.** "Just pass a string permission name through" — and now the surface and gate disagree about what `network` means. Use the sealed types.
- **AND-ing manifest and tool-call permissions.** Some manifests under-declare; some tool calls over-declare. Both views must be satisfied.
- **Not emitting `PermissionDeniedEvent` on denial.** The trace shows a tool that didn't run with no explanation. Always emit.
- **Caching `userGrants` for "performance" without invalidation on revoke.** Stale grants let a revoked permission act granted. Revocation must invalidate.
