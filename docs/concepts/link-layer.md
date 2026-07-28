---
concept: LinkLayer
status: stable
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/link/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/LinkEvent.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/plug/PlugManifest.kt
  - ampere-core/src/commonMain/sqldelight/link/socket/ampere/db/Links.sq
related: [DomainCanon, PlugPermissions, EventSerialBus]
last_verified: 2026-07-28
---

# Link Layer

## What it is

A `Link` is the binding between a capability and its concrete medium — Socket's
data-link layer. It carries a `Transport` (the wire), a `LinkDirection`
(read/write/both), an `EgressClass` (where data goes), a `scope` of `CanonType`s
(what may flow), and a `CredentialRef` (a pointer, never a secret).

The grammar: *a Plug connects through a Link and powers Arcs.* A Plug declares
`LinkRequirement`s — kinds of wire — and `LinkResolutionService` matches them to
concrete Links at **Arc execution time**.

## Why it exists

Three inversions, each load-bearing:

1. **Transport belongs to the Link, not the Plug.** The Uber Plug doesn't "have
   MCP transport"; it requires a Link of kind `Mcp`. Memory requires
   `FolderMount`; Notify requires `Apns`. This is what lets one authenticated
   Google Link serve both the Calendar and Gmail Plugs.
2. **Links are directional endpoints, not sources.** APNS is a write-only sink;
   a folder mount is read/write. Modelling direction on the Link turns "this
   Plug tried to Perceive through a push channel" into a resolution-time
   failure instead of a runtime surprise.
3. **Resolution is late.** An Arc references Plugs, never Link bindings. That is
   what keeps Arc manifests lean and lets the same Arc run against a different
   account or a different wire with no edit.

**Transport capability is per-platform, not global.** The tempting model is one
enum member per direction (`AppFunctionProvider`, `AppFunctionConsumer`). It is
wrong: `AppFunction` is bidirectional on Android and *absent* on iOS, where
cross-app orchestration belongs to Siri and app-to-app is limited to
`UriScheme`/Shortcuts. Capability is therefore a function of (transport,
platform), exposed as `canProvide`/`canConsume` flags.

## Where it lives

- `link/Link.kt` — `Link`, `EgressClass`, `CredentialRef`.
- `link/LinkId.kt` — the shared identifier (moved here from `mcp/` in AMPR-223).
- `link/Transport.kt` — `Transport`, `PlatformTarget`, `TransportCapability`, `TransportRole`, and the capability table.
- `link/LinkDirection.kt` — `LinkDirection`, `LinkOperation` (Perceive/Execute).
- `link/LinkRequirement.kt` — `LinkRequirement`, `LinkResolution`, `ResolvedLinks`.
- `link/LinkResolutionFailure.kt` — the closed failure set + `RevocationScope`.
- `link/LinkResolutionGate.kt` — the pure, deterministic matching policy.
- `link/LinkGrants.kt` — per-(Plug, Link) grants.
- `link/LinkStore.kt` — persistence boundary; `SqlDelightLinkStore` and `InMemoryLinkStore`.
- `link/LinkResolutionService.kt` — orchestration + bus emission.
- `agents/domain/event/LinkEvent.kt` — `LinkGranted`, `LinkRevoked`, `LinkResolved`, `LinkResolutionFailed`.
- `commonMain/sqldelight/link/socket/ampere/db/Links.sq` — schema (migration `2.sqm`).
- `plug/PlugManifest.kt` — `requiredLinks`, `emits`, `consumes`.

## Invariants

- **Raw credentials never live on a `Link`.** `CredentialRef` is a keychain alias. A token on this type would put secrets into every trace that recorded a Link.
- **Resolution never throws.** A missing Link is `Result.failure(LinkResolutionException(...))` carrying typed failures. `LinkResolutionGate` returns a sealed result and is side-effect-free.
- **Revoked beats granted**, matching `PlugPermissionGate`. `LinkResolutionGate` checks revocation *first*, then transport capability, then direction, then scope — a revoked Link must not produce a scope-shaped error.
- **Link revocation cascades; grant revocation does not.** Revoking a Link revokes every Plug's grant on it and reports the affected plug ids. Revoking one Plug's grant leaves the others working.
- **A Link the Plug was never granted is invisible, not rejected** — it yields `MissingLink`. A *revoked* grant stays visible so the failure can say "revoked".
- **Transport capability is consulted before dispatch.** A consumer-role requirement on a transport whose platform capability is false fails with `TransportUnsupported`.
- **Every resolution outcome reaches the bus.** A Plug that quietly does nothing because its Link was revoked is the opacity the glass brain exists to prevent.
- **`Link` and `LinkResolutionFailure` are wire types** — stable `@SerialName`s; `Links.link_json` and trace payloads both depend on them.
- **Agents never touch `LinkStore` directly.** They go through `LinkResolutionService`.

## Common operations

- **Declare a requirement** — add a `LinkRequirement(name, transport, direction, minimumScope)` to `PlugManifest.requiredLinks`. Names must be unique within a manifest and scope must be non-empty; `PlugManifestValidator` enforces both.
- **Resolve at execution time** — `linkResolutionService.resolve(plugId, manifest)` → `ResolvedLinks`, indexed by requirement name.
- **Check an operation against a resolved Link** — `LinkResolutionGate.permits(link, LinkOperation.PERCEIVE)`.
- **Grant / revoke** — `service.grant(plugId, linkId)`, `service.revokeGrant(plugId, linkId)`, `service.revokeLink(linkId)` (cascading).
- **Add a transport** — add the enum member, add its per-platform row to `Transport.CAPABILITIES`, and pin the row in `TransportCapabilityTest`. Unlisted combinations default to `TransportCapability.NONE`.

## Anti-patterns

- **Putting transport on the Plug.** "The Uber Plug is an MCP plug" — then the same MCP credentials get re-authenticated per Plug and Link sharing is impossible.
- **Separate enum members per direction** (`AppFunctionConsumer` / `AppFunctionProvider`). Direction is per-platform; enum members are not.
- **Binding Links in the Arc manifest.** It couples the Arc to an account and defeats late resolution.
- **Gating resolution on `Transport.hasImplementation`.** Only MCP has a transport implementation today; gating on it would make every other Link unresolvable before its transport ticket lands. The flag is metadata for tooling.
- **Treating `MissingLink` as an error to throw.** Every resolution failure is a consent-shaped fact the user may be able to fix; it belongs in a `Result` and on the bus.
- **Checking scope before revocation.** The reader is sent looking in the wrong place entirely.
