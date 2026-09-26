# Plug bundle format

A **plug bundle** is the portable distribution unit Ampere uses to ship plugs outside the in-repo tree. The marketplace (W1.10) and share-sheet import (W1.11) both consume bundles. This document is the source of truth for the on-disk layout, manifest schema, signature surface, and forward-compatibility rules.

## Design goals

- **Portable.** A bundle is a flat set of files keyed by forward-slash paths. Hosts may ship bundles as ZIP archives, on-disk directories, or in-memory blobs; the parser is agnostic.
- **Versioned twice over.** Every bundle declares a `bundleFormatVersion` for the *layout* and, optionally, a `minimumAmpereVersion` for the *canon* it names. Parsers reject either being too new with a typed error rather than guessing.
- **Schema-driven.** The manifest is `kotlinx.serialization` JSON over the W0.1 [`PlugManifest`][PlugManifest] type. Permissions reuse the W0.1 [`PlugPermission`][PlugPermission] schema verbatim — bundles never redefine the permission shape.
- **Trust-aware but crypto-deferred.** A detached signature surface ships now so marketplace UI can wire its import pipeline. Real verification lands in a follow-up; today's default returns `Verified.Skipped` with a logged warning.

## Layout

A bundle is a set of entries at known paths. All paths are relative to the bundle root, use forward slashes, and are case-sensitive.

| Path | Required | Purpose |
| --- | --- | --- |
| `manifest.json` | Yes | Bundle metadata (`BundleManifest`) — see [Manifest schema](#manifest-schema). |
| `assets/**` | No | Arbitrary plug-owned files (icons, sample data, vendored prompts). Parsed entries are keyed by their full path including the `assets/` prefix. |
| `signature.sig` | No | Detached signature over `manifest.json`. Verification is stubbed (`Verified.Skipped`) until production crypto lands. |

Unknown top-level entries are ignored by the parser. This keeps the format additive: future revisions may stage new optional entries without breaking older parsers, provided the `bundleFormatVersion` is bumped when their presence is *required*.

## Manifest schema

`manifest.json` decodes to `link.socket.ampere.bundle.BundleManifest`:

```json
{
  "bundleFormatVersion": 1,
  "minimumAmpereVersion": "0.12.0",
  "plug": {
    "id": "github-plug",
    "name": "GitHub Plug",
    "version": "1.0.0",
    "description": "Open and review GitHub PRs from inside Ampere.",
    "requiredPermissions": [
      { "type": "network_domain", "host": "api.github.com" },
      { "type": "mcp_server", "uri": "mcp://github" }
    ],
    "emits": ["work_item"],
    "consumes": ["person"],
    "requiredLinks": [
      {
        "name": "github",
        "transport": "mcp",
        "direction": "read",
        "minimumScope": ["work_item", "person"]
      }
    ]
  }
}
```

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `bundleFormatVersion` | `Int` | Yes | Currently `1`. Parsers reject any other value with `UnknownVersion`. |
| `minimumAmpereVersion` | `String?` | No (defaults to `null`) | Oldest Ampere release whose canon covers every name in `plug` — see [Canon declarations and the version pin](#canon-declarations-and-the-version-pin). |
| `plug` | `PlugManifest` | Yes | The W0.1 plug manifest, embedded verbatim. |
| `plug.id` | `PlugId` | Yes | Stable identifier; must match `[a-z0-9_-]+` (validated at construction). |
| `plug.name` | `String` | Yes | Human-readable name; must be non-blank. |
| `plug.version` | `String` | Yes | Plug's own version; must be non-blank. Independent of `bundleFormatVersion`. |
| `plug.description` | `String?` | No | Free-form description. |
| `plug.requiredPermissions` | `List<PlugPermission>` | No (defaults to `[]`) | W0.1 permission objects. |
| `plug.mcpServers` | `List<McpServerDependency>` | No (defaults to `[]`) | MCP dependencies. Each one needs a matching `mcp_server` permission, and any permission a dependency declares must be lifted to `plug.requiredPermissions`. |
| `plug.requiredLinks` | `List<LinkRequirement>` | No (defaults to `[]`) | Kinds of wire the plug needs, resolved to concrete Links at Arc execution time. Names must be unique. |
| `plug.requiredLinks[].minimumScope` | `Set<CanonType>` | No (defaults to `[]`) | Canon types the wire must be permitted to carry. Every name here must also appear in the plug's own canon contract, and an empty scope is rejected — see below. |
| `plug.emits` | `Set<CanonType>` | No (defaults to `[]`) | Canon this plug produces for later Arc steps. |
| `plug.consumes` | `Set<CanonType>` | No (defaults to `[]`) | Canon this plug requires to be handed to it. |
| `plug.optionalConsumes` | `Set<CanonType>` | No (defaults to `[]`) | Canon this plug uses if offered but does not require. A type may not appear in both `consumes` and `optionalConsumes`. |
| `plug.resolvesAssets` | `Boolean` | No (defaults to `false`) | The plug implements `AssetResolver`. |
| `plug.isCanonExternal` | `Boolean` | No (defaults to `false`) | Positive declaration that the plug's *observations* are outside canon, so `emits` is empty by design. Exempts `emits` and nothing else: `consumes`/`optionalConsumes` stay subject to scope validation, and setting the flag while `emits` is non-empty is rejected. |
| `plug.tableWriteCapabilities` | `Set<TableWriteCapability>` | No (defaults to `[]`) | Which `table` writes the plug can honour losslessly. Requires `table` in `emits` or `consumes`, and is rejected alongside `isCanonExternal`. |

`PlugPermission` uses `type` as its discriminator and is reused unchanged from W0.1. See [`PlugPermission.kt`][PlugPermission] for the closed set of six variants: `network_domain`, `mcp_server`, `knowledge_query`, `native_action`, `link_access`, `device_capability`.

Every field but `bundleFormatVersion` and `plug`'s three required strings has a default, so a manifest written before a field existed decodes unchanged. That is the same additive rule the [versioning rules](#versioning-rules) state: adding one does not bump `bundleFormatVersion`.

## Canon declarations and the version pin

`plug.emits`, `plug.consumes`, `plug.optionalConsumes` and `plug.requiredLinks[].minimumScope` are `Set<CanonType>` — the closed canon, by wire name (`person`, `calendar_event`, `table`, …). That closure *is* the marketplace rule from [Domain Canon](../concepts/domain-canon.md): **a published bundle names only `CanonType` members.** There is no namespace for an extension type and no registry a marketplace could validate one against, so `"com.acme.invoice"` in `emits` is not importable anywhere.

`minimumAmpereVersion` is the other half: **the oldest Ampere release whose canon covers every name the bundle uses.** A plug naming `table` — admitted in 0.12.0 — pins `"0.12.0"`. It is a property of the *distribution*, which is why it sits on the wrapper rather than inside `plug`: an in-repo plug compiles against the canon it ships with and has nothing to pin.

Both are checked by `PlugBundleParser` **before** the manifest is decoded, because decode order would otherwise swallow them: an unknown name in a `Set<CanonType>` fails enum decode, which used to surface as `InvalidManifest` with a kotlinx message. A pre-decode pass reads those four fields off the raw JSON and resolves each name through `CanonType.fromWireName`, so the failure names the field and the value instead:

| Declared | Host | Reported |
| --- | --- | --- |
| `minimumAmpereVersion: "0.12.0"` | 0.11.0 | `UnsupportedManifest([AmpereVersionTooOld("0.12.0", "0.11.0")])` — "upgrade Ampere", not "malformed manifest". This is the SCKT-444 failure shape, fixed. |
| `minimumAmpereVersion: "0.12.0"` | 0.15.0 | Nothing. The pin is carried onto `PlugBundle.minimumAmpereVersion`. |
| `minimumAmpereVersion: "latest"` | any | `UnsupportedManifest([MalformedAmpereVersion("latest")])`. A pin that cannot be compared is a gate that cannot be enforced, so it is reported rather than ignored. |
| `emits: ["com.acme.invoice"]` | any | `UnsupportedManifest([UnknownCanonType("emits", "com.acme.invoice")])` |
| `minimumAmpereVersion` too new *and* an unknown name | older host | Only `AmpereVersionTooOld`. The unknown nouns are its symptom, not a second fault. |

Version comparison is deliberately narrower than semver: numeric release components are compared left to right, a missing component reads as `0` (`"0.15"` == `"0.15.0"`), and pre-release or build metadata after the first `-` or `+` is ignored — `"0.16.0-rc.1"` and `"0.16.0"` have the same `CanonType.entries`, so a host running its own release candidate must not reject a bundle pinned to the version it is about to be.

A bundle with no pin is not rejected: its canon names are still resolved against this build's vocabulary. The host simply cannot tell "you are too old" from "that is a typo" when one of them is unknown, which is the whole reason to declare the pin.

## Reading a bundle

`PlugBundleParser` runs in commonMain. It accepts a `PlugBundleSource` — an abstraction over "a set of named byte entries". Three concrete sources ship today:

| Source | Source set | Use |
| --- | --- | --- |
| `MapBundleSource` | commonMain | Tests and callers that already have entries materialised in memory. |
| `PlugBundleSource.fromDirectory(path, fileSystem)` | commonMain | Bundle laid out as a directory tree under `path`, read via okio. |
| `PlugBundleSource.fromZipFile(path, fileSystem)` | jvmMain | ZIP archive, opened read-only via `FileSystem.openZip`. JVM-only because okio's `openZip` is JVM-only in 3.11; iOS/Native ZIP support is a follow-up. |

Both okio-backed factories yield identical entry keys for the same bundle whether shipped as a directory or a ZIP, so consumers can pick a layout without changing downstream code.

```kotlin
val parser = PlugBundleParser()
when (val result = parser.parse(source)) {
    is BundleParseResult.Ok -> handle(result.bundle)
    is BundleParseResult.Failed -> when (val error = result.error) {
        BundleParseError.MissingManifest -> /* manifest.json absent */
        is BundleParseError.InvalidManifest -> /* JSON or schema failure: error.message */
        is BundleParseError.BundleTooLarge -> /* error.sizeBytes vs error.limitBytes */
        is BundleParseError.UnknownVersion -> /* error.declared vs error.supported */
        is BundleParseError.UnsupportedManifest -> /* error.reasons, each one describe()-able */
    }
}
```

The parser enforces **structural** rules only:

1. Total entry size must not exceed `MAX_BUNDLE_SIZE_BYTES` (50 MiB by default; configurable for hosted environments with larger budgets).
2. `manifest.json` must exist.
3. `minimumAmpereVersion`, if declared, must not exceed the host's `AMPERE_RUNTIME_VERSION`, and every canon name must resolve — see [Canon declarations and the version pin](#canon-declarations-and-the-version-pin). Checked before decode, so it precedes rules 4 and 5.
4. `manifest.json` must decode against `BundleManifest`.
5. `bundleFormatVersion` must equal the build's `CURRENT_BUNDLE_FORMAT_VERSION`.

`PlugBundleParser(hostAmpereVersion = ...)` overrides the version rule 3 compares against — a marketplace backend reviewing a bundle on behalf of older clients names their version instead of its own.

A bundle that passes parsing is *readable*; it is not yet *importable*.

## Validating a bundle

`PlugBundleValidator` runs over a parsed `PlugBundle` and enforces semantic rules. It returns `BundleValidation.Ok(permissions)` or `BundleValidation.Failed(reasons, manifestReasons)` with **every** reason at once — callers can render a complete diagnostic without forcing a fix-and-retry loop.

`reasons` is the rendered list, for display. `manifestReasons` is the typed subset — `ManifestValidationReason`s a host may want to *act* on rather than print, e.g. offering an upgrade for `AmpereVersionTooOld` — and every one of them also appears in `reasons`, rendered by `ManifestValidationReason.describe()`.

Failure modes today:

| Mode | Trigger |
| --- | --- |
| Unsupported version | `bundleFormatVersion` mismatches `CURRENT_BUNDLE_FORMAT_VERSION` (defence in depth — the parser already rejects this). |
| Blank `id` / `name` / `version` | Any of the required `PlugManifest` strings is empty or whitespace. |
| Blank permission field | A `PlugPermission` variant carries an empty discriminator field (e.g. `NetworkDomain.host`). |
| Empty signature | `signature.sig` is present but zero bytes. |
| Ampere too old | `minimumAmpereVersion` exceeds `hostAmpereVersion` (defence in depth again — reachable for a `PlugBundle` assembled in memory rather than parsed). |
| Any `PlugManifest` rule | The validator runs `PlugManifestValidator` and surfaces its reasons: ungranted MCP dependency, unlifted dependency permission, duplicate Link requirement name, empty or undeclared `minimumScope`, duplicate device capability, a type in both `consumes` and `optionalConsumes`, `isCanonExternal` contradictions, undeclared `table` write capability. |

That last row is new in AMPR-365. Before it, `PlugManifestValidator`'s only production call site was `PlugContext.create`, so a marketplace import was held to fewer rules than an in-repo install: an undeclared Link scope was accepted at import and refused later, at the first attempt to run the plug.

`BundleValidation.Ok` surfaces the de-duplicated permission list so consent UI can render a single authoritative list.

## Signature surface

```kotlin
fun interface PlugBundleSignatureVerifier {
    suspend fun verify(bundle: PlugBundle): PlugBundleSignatureVerification
}
```

`PlugBundleSignatureVerification` is sealed:

| Variant | Meaning |
| --- | --- |
| `Verified.Trusted` | A real signature was checked against a trusted key. |
| `Verified.Skipped` | No crypto was performed. The default no-op verifier returns this. |
| `Invalid(reason)` | A signature was present but did not validate; the bundle must be rejected. |

The default `NoOpPlugBundleSignatureVerifier` always returns `Verified.Skipped` and logs a warning so a stray production deployment cannot silently bypass signature checks. Marketplace UI is expected to badge `Verified.Skipped` differently from `Verified.Trusted` even before real crypto lands.

Production crypto (key pinning, signature algorithm, distribution of trust roots) is **intentionally deferred** to a follow-up ticket. This ticket establishes only the surface so the import pipeline can be wired now.

## Versioning rules

`CURRENT_BUNDLE_FORMAT_VERSION` is `1` today.

- **Bump** the version when a structural change is made: a required entry is added or removed, on-disk encoding changes, the meaning of a path changes.
- **Do not bump** the version when adding optional fields with defaults to `PlugManifest` or `BundleManifest` — those are source-compatible and decode unchanged. `minimumAmpereVersion` was added this way.
- **Canon growth is not a format change.** A new `CanonType` member is an Ampere release, and a bundle that names it says so with `minimumAmpereVersion`, not with `bundleFormatVersion`.
- Old hosts encountering a new version see `BundleParseError.UnknownVersion(declared, supported)` and can render a clear "upgrade required" message rather than a generic parse failure.

A v999 bundle today returns `UnknownVersion(999, 1)` rather than crashing. This is the forward-compatibility contract; tests in `commonTest` pin it.

## File reference

| Symbol | Location |
| --- | --- |
| `BundleManifest` | `commonMain/.../bundle/PlugBundle.kt` |
| `PlugBundle` | `commonMain/.../bundle/PlugBundle.kt` |
| `PlugBundleSource`, `MapBundleSource` | `commonMain/.../bundle/PlugBundleSource.kt` |
| `OkioPlugBundleSource`, `fromDirectory` | `commonMain/.../bundle/OkioPlugBundleSource.kt` |
| `fromZipFile` | `jvmMain/.../bundle/PlugBundleSource.jvm.kt` |
| `PlugBundleParser`, `BundleParseResult`, `BundleParseError` | `commonMain/.../bundle/PlugBundleParser.kt` |
| `PlugBundleValidator`, `BundleValidation` | `commonMain/.../bundle/PlugBundleValidator.kt` |
| `PlugBundleSignatureVerifier`, `PlugBundleSignatureVerification`, `NoOpPlugBundleSignatureVerifier` | `commonMain/.../bundle/PlugBundleSignatureVerifier.kt` |
| `PlugManifest` | `commonMain/.../plug/PlugManifest.kt` |
| `PlugManifestValidator`, `ManifestValidationReason`, `describe()` | `commonMain/.../plug/PlugManifestValidator.kt` |
| `PlugPermission` | `commonMain/.../plug/permission/PlugPermission.kt` |
| `AMPERE_RUNTIME_VERSION`, version comparison | `commonMain/.../AmpereVersion.kt` |
| `CanonType`, `fromWireName` | `commonMain/.../canon/CanonType.kt` |

[PlugManifest]: ../../ampere-core/src/commonMain/kotlin/link/socket/ampere/plug/PlugManifest.kt
[PlugPermission]: ../../ampere-core/src/commonMain/kotlin/link/socket/ampere/plug/permission/PlugPermission.kt
