---
concept: LinkLayer
status: stable
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/link/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/LinkEvent.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/plug/PlugManifest.kt
  - ampere-core/src/commonMain/sqldelight/link/socket/ampere/db/Links.sq
related: [DomainCanon, PlugPermissions, EventSerialBus]
last_verified: 2026-10-29
---

# Link Layer

## What it is

A `Link` is the binding between a capability and its concrete medium — Socket's
data-link layer. It carries a `Transport` (the wire), a `LinkDirection`
(read/write/both), an `EgressClass` (where data goes), a `scope` of `CanonType`s
(what may flow), and a `CredentialRef` (a pointer, never a secret).

The grammar: *a Plug connects through a Link and powers Arcs.* A Plug declares
`LinkRequirement`s — named kinds of wire — and `LinkResolutionService` matches
them to concrete Links at **Arc execution time**, by transport and, for a Link
minted for one requirement, by name.

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

- `link/Link.kt` — `Link`, `EgressClass`, `CredentialRef`, and the alias convention `CredentialRef.aliasFor` / `forLink`.
- `link/LinkId.kt` — the shared identifier (moved here from `mcp/` in AMPR-223), and `LinkId.forRequirement`, the `<transport>:<name>` form a Link minted for one requirement carries.
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
- `plug/PlugManifest.kt` — `requiredLinks`, `emits`, `consumes`, `optionalConsumes`, `isCanonExternal`.
- What dereferences a `CredentialRef` (AMPR-464; not tracked here, but bound by the invariants below): `mcp/KeychainMcpCredentialBinding.kt` (the persistent `McpCredentialBinding`, over a `SecretStore`), `jvmMain/.../mcp/MacOsKeychain.kt` (the macOS store, through `/usr/bin/security`), `mcp/McpCredentialRefresher.kt` and `mcp/OAuthTokenEndpoint.kt` (the refresh path `McpClient` drives), and `ampere credential` in the CLI (the bootstrap).

## Invariants

- **Raw credentials never live on a `Link`.** `CredentialRef` is a keychain alias. A token on this type would put secrets into every trace that recorded a Link.
- **A Link's credential alias is derived from its id, never stored or looked up.** `CredentialRef.aliasFor(linkId)` is `ampere.link:<link id>`: a fixed prefix on a unique id, so two Links cannot collide. The persistent binding is handed only a `LinkId`, so it computes the alias the same way; the keychain item is a generic password with that alias as its *service* and the MCP server URI as its *account*. A Link Ampere mints carries `CredentialRef.forLink(id)`.
- **Credential material reaches no log, event, trace or failure message.** `McpCredential`, `McpOAuthClient` and `OAuthTokenResult.Issued` redact every secret field in `toString`; a keychain item that fails to decode is reported without quoting it (the serializer's own message would include the JSON); the macOS store hands `security` the secret on stdin, never in its argument list.
- **A rotated refresh token is persisted before the old one is let go.** `McpCredentialRefresher` binds the refreshed credential — one replace of the stored item — before it returns it, and nothing unbinds to make room, so the store always holds the old credential or the new one. A process killed between the issuer's answer and the write still has the old refresh token on disk, which the issuer's replay window (30 minutes for the work source) answers again. A failed write holds the new credential in memory, reports `PersistFailed`, and is retried before any further refresh token is spent.
- **Resolution never throws.** A missing Link is `Result.failure(LinkResolutionException(...))` carrying typed failures. `LinkResolutionGate` returns a sealed result and is side-effect-free.
- **Revoked beats granted**, matching `PlugPermissionGate`. `LinkResolutionGate` checks revocation *first*, then transport capability, then direction, then scope — a revoked Link must not produce a scope-shaped error.
- **Link revocation cascades; grant revocation does not.** Revoking a Link revokes every Plug's grant on it and reports the affected plug ids. Revoking one Plug's grant leaves the others working.
- **A Link minted for a requirement answers only that requirement's name.** `LinkId.forRequirement` writes `<transport>:<name>`, `Link.requirementName` reads it back, and the gate matches such a Link only to a requirement of that name; an opaque id matches by transport alone. The name is derived from the id, never stored, so every row already written in that form (Socket's `NativeLinkSeeder` writes nothing else) is named with no migration. Plugs share a minted Link by declaring the same requirement name. A requirement's own minted Link is tried before any opaque one (AMPR-502).
- **A Link the Plug was never granted is invisible, not rejected** — it cannot satisfy the requirement, but it is reported as `UngrantedLink` carrying its `LinkId`, so a caller can drive just-in-time consent without re-deriving the Link. A *revoked* grant stays visible so the failure can say "revoked".
- **`UngrantedLink` names only a Link a grant would actually fix** — the first candidate (own Link, then opaque ones, each in list order) that would resolve once granted. When none would, the failure is `MissingLink`: no Link answers the requirement, or every one that does would still fail a check. A grant on a Link the gate rejects can never pass, so the gate never asks for one (AMPR-502).
- **Transport capability is consulted before dispatch.** A consumer-role requirement on a transport whose platform capability is false fails with `TransportUnsupported`.
- **Every resolution outcome reaches the bus.** A Plug that quietly does nothing because its Link was revoked is the opacity the glass brain exists to prevent.
- **`Link` and `LinkResolutionFailure` are wire types** — stable `@SerialName`s; `Links.link_json` and trace payloads both depend on them.
- **Agents never touch `LinkStore` directly.** They go through `LinkResolutionService`.
- **`PlugManifest.isCanonExternal` exempts `emits` only.** A canon-external Plug's *observations* are outside canon, but it may still declare `consumes` / `optionalConsumes`, and every `CanonType` in a `LinkRequirement.minimumScope` must appear in one of them. Only a canon-external Plug that consumes no canon at all keeps the wholesale carve-out from the empty-scope and undeclared-scope rules (AMPR-320).

## Common operations

- **Declare a requirement** — add a `LinkRequirement(name, transport, direction, minimumScope)` to `PlugManifest.requiredLinks`. Names must be unique within a manifest, and scope must be non-empty and drawn from the Plug's declared canon types; `PlugManifestValidator` enforces all three.
- **Mint a Link for one requirement** — give it `LinkId.forRequirement(requirement)`. It then answers only requirements of that name, in any Plug. A Link meant to serve differently named requirements (one Google OAuth Link for `calendar` and `mail`) takes an opaque id instead.
- **Resolve at execution time** — `linkResolutionService.resolve(plugId, manifest)` → `ResolvedLinks`, indexed by requirement name.
- **Check an operation against a resolved Link** — `LinkResolutionGate.permits(link, LinkOperation.PERCEIVE)`.
- **Grant / revoke** — `service.grant(plugId, linkId)`, `service.revokeGrant(plugId, linkId)`, `service.revokeLink(linkId)` (cascading).
- **Give a Link a credential** — mint it with `credentialRef = CredentialRef.forLink(id)`, bind through `KeychainMcpCredentialBinding` (the in-memory twin in tests), and pass a `McpCredentialRefresher` to `PlugContext.create` when the credential is a short-lived OAuth token: the client then refreshes on connect when the token is within five minutes of expiry, and once after a 401, then reports `McpCredentialException.Rejected`.
- **Seed the work-source credential** — `ampere credential seed`, reading the OAuth code exchange's response on stdin; `ampere-cli/README.md` has the command sequence. `ampere credential status` shows what is stored without showing it.
- **Add a transport** — add the enum member, add its per-platform row to `Transport.CAPABILITIES`, and pin the row in `TransportCapabilityTest`. Unlisted combinations default to `TransportCapability.NONE`.

## Anti-patterns

- **Putting transport on the Plug.** "The Uber Plug is an MCP plug" — then the same MCP credentials get re-authenticated per Plug and Link sharing is impossible.
- **Separate enum members per direction** (`AppFunctionConsumer` / `AppFunctionProvider`). Direction is per-platform; enum members are not.
- **Binding Links in the Arc manifest.** It couples the Arc to an account and defeats late resolution.
- **Gating resolution on `Transport.hasImplementation`.** Only MCP has a transport implementation today; gating on it would make every other Link unresolvable before its transport ticket lands. The flag is metadata for tooling.
- **Treating `UngrantedLink` as a configuration error.** It is the consent question — a Plug's first use of a seeded Link always lands there. Asking, recording the grant, and re-resolving is the remedy.
- **Treating `MissingLink` as an error to throw.** Every resolution failure is a consent-shaped fact the user may be able to fix; it belongs in a `Result` and on the bus.
- **Checking scope before revocation.** The reader is sent looking in the wrong place entirely.
- **Matching a requirement by transport alone.** Every `NATIVE_FRAMEWORK` requirement then matches every `NATIVE_FRAMEWORK` Link: a Plug whose own Link is missing is asked to consent to the first stranger in the store (Socket showed "Calendar — used by Workspace"), and canon-external Plugs, whose empty `minimumScope` passes every scope check, resolve to each other's Links (AMPR-502).
- **Reporting `UngrantedLink` for a Link that fails `check`.** The allow sheet's "yes" records a grant that is rejected on the very next resolution and misstates what the Plug reaches. Report `MissingLink`.
- **Unbinding a credential to make room for its refreshed replacement.** A crash between the two leaves the store empty while the issuer has already spent the old refresh token: the operator is locked out. `bind` replaces in one step; use it alone.
- **Giving an MCP Link a hand-picked `keychainAlias`** (the old `"work-source-reconcile"`). The MCP credential binding never reads the field: it derives the alias from the `LinkId`, so a credential seeded under any other name is never found.
- **Keying a long-lived credential to an opaque, per-command Link id.** The alias follows the id, so a command that mints its own id (as `ampere reconcile` did, with `work-source-reconcile`) cannot find what another command seeded. Every work-source command uses the id minted for the requirement, `mcp:work-source`.
- **Minting a `<transport>:<name>` id for a Link meant to be shared across names.** The gate reads the suffix as a requirement name, so `oauth_rest:google` would answer only a requirement named `google`, never `calendar` or `mail`.
