# Plugin bundle format

A **plugin bundle** is the portable distribution unit Ampere uses to ship plugins outside the in-repo tree. The marketplace (W1.10) and share-sheet import (W1.11) both consume bundles. This document is the source of truth for the on-disk layout, manifest schema, signature surface, and forward-compatibility rules.

## Design goals

- **Portable.** A bundle is a flat set of files keyed by forward-slash paths. Hosts may ship bundles as ZIP archives, on-disk directories, or in-memory blobs; the parser is agnostic.
- **Versioned.** Every bundle declares a `bundleFormatVersion`. Parsers reject unknown versions with a typed error rather than guessing.
- **Schema-driven.** The manifest is `kotlinx.serialization` JSON over the W0.1 [`PluginManifest`][PluginManifest] type. Permissions reuse the W0.1 [`PluginPermission`][PluginPermission] schema verbatim — bundles never redefine the permission shape.
- **Trust-aware but crypto-deferred.** A detached signature surface ships now so marketplace UI can wire its import pipeline. Real verification lands in a follow-up; today's default returns `Verified.Skipped` with a logged warning.

## Layout

A bundle is a set of entries at known paths. All paths are relative to the bundle root, use forward slashes, and are case-sensitive.

| Path | Required | Purpose |
| --- | --- | --- |
| `manifest.json` | Yes | Bundle metadata (`BundleManifest`) — see [Manifest schema](#manifest-schema). |
| `assets/**` | No | Arbitrary plugin-owned files (icons, sample data, vendored prompts). Parsed entries are keyed by their full path including the `assets/` prefix. |
| `signature.sig` | No | Detached signature over `manifest.json`. Verification is stubbed (`Verified.Skipped`) until production crypto lands. |

Unknown top-level entries are ignored by the parser. This keeps the format additive: future revisions may stage new optional entries without breaking older parsers, provided the `bundleFormatVersion` is bumped when their presence is *required*.

## Manifest schema

`manifest.json` decodes to `link.socket.ampere.bundle.BundleManifest`:

```json
{
  "bundleFormatVersion": 1,
  "plugin": {
    "id": "github-plugin",
    "name": "GitHub Plugin",
    "version": "1.0.0",
    "description": "Open and review GitHub PRs from inside Ampere.",
    "entrypoint": "main",
    "requiredPermissions": [
      { "type": "network_domain", "host": "api.github.com" },
      { "type": "mcp_server", "uri": "mcp://github" }
    ]
  }
}
```

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `bundleFormatVersion` | `Int` | Yes | Currently `1`. Parsers reject any other value with `UnknownVersion`. |
| `plugin` | `PluginManifest` | Yes | The W0.1 plugin manifest, embedded verbatim. |
| `plugin.id` | `String` | Yes | Stable identifier; must be non-blank. |
| `plugin.name` | `String` | Yes | Human-readable name; must be non-blank. |
| `plugin.version` | `String` | Yes | Plugin's own version; must be non-blank. Independent of `bundleFormatVersion`. |
| `plugin.description` | `String?` | No | Free-form description. |
| `plugin.entrypoint` | `String?` | No | Plugin-defined entry handle; opaque to the bundle format. |
| `plugin.requiredPermissions` | `List<PluginPermission>` | No (defaults to `[]`) | W0.1 permission objects. |

`PluginPermission` uses `type` as its discriminator and is reused unchanged from W0.1. See [`PluginPermission.kt`][PluginPermission] for the closed set of variants (`network_domain`, `mcp_server`, `knowledge_query`, `native_action`, `link_access`).

## Reading a bundle

`PluginBundleParser` runs in commonMain. It accepts a `PluginBundleSource` — an abstraction over "a set of named byte entries". Three concrete sources ship today:

| Source | Source set | Use |
| --- | --- | --- |
| `MapBundleSource` | commonMain | Tests and callers that already have entries materialised in memory. |
| `PluginBundleSource.fromDirectory(path, fileSystem)` | commonMain | Bundle laid out as a directory tree under `path`, read via okio. |
| `PluginBundleSource.fromZipFile(path, fileSystem)` | jvmMain | ZIP archive, opened read-only via `FileSystem.openZip`. JVM-only because okio's `openZip` is JVM-only in 3.11; iOS/Native ZIP support is a follow-up. |

Both okio-backed factories yield identical entry keys for the same bundle whether shipped as a directory or a ZIP, so consumers can pick a layout without changing downstream code.

```kotlin
val parser = PluginBundleParser()
when (val result = parser.parse(source)) {
    is BundleParseResult.Ok -> handle(result.bundle)
    is BundleParseResult.Failed -> when (val error = result.error) {
        BundleParseError.MissingManifest -> /* manifest.json absent */
        is BundleParseError.InvalidManifest -> /* JSON or schema failure: error.message */
        is BundleParseError.BundleTooLarge -> /* error.sizeBytes vs error.limitBytes */
        is BundleParseError.UnknownVersion -> /* error.declared vs error.supported */
    }
}
```

The parser enforces **structural** rules only:

1. Total entry size must not exceed `MAX_BUNDLE_SIZE_BYTES` (50 MiB by default; configurable for hosted environments with larger budgets).
2. `manifest.json` must exist.
3. `manifest.json` must decode against `BundleManifest`.
4. `bundleFormatVersion` must equal the build's `CURRENT_BUNDLE_FORMAT_VERSION`.

A bundle that passes parsing is *readable*; it is not yet *importable*.

## Validating a bundle

`PluginBundleValidator` runs over a parsed `PluginBundle` and enforces semantic rules. It returns `BundleValidation.Ok(permissions)` or `BundleValidation.Failed(reasons)` with **every** reason at once — callers can render a complete diagnostic without forcing a fix-and-retry loop.

Failure modes today:

| Mode | Trigger |
| --- | --- |
| Unsupported version | `bundleFormatVersion` mismatches `CURRENT_BUNDLE_FORMAT_VERSION` (defence in depth — the parser already rejects this). |
| Blank `id` / `name` / `version` | Any of the required `PluginManifest` strings is empty or whitespace. |
| Blank permission field | A `PluginPermission` variant carries an empty discriminator field (e.g. `NetworkDomain.host`). |
| Empty signature | `signature.sig` is present but zero bytes. |

`BundleValidation.Ok` surfaces the de-duplicated permission list so consent UI can render a single authoritative list.

## Signature surface

```kotlin
fun interface PluginBundleSignatureVerifier {
    suspend fun verify(bundle: PluginBundle): PluginBundleSignatureVerification
}
```

`PluginBundleSignatureVerification` is sealed:

| Variant | Meaning |
| --- | --- |
| `Verified.Trusted` | A real signature was checked against a trusted key. |
| `Verified.Skipped` | No crypto was performed. The default no-op verifier returns this. |
| `Invalid(reason)` | A signature was present but did not validate; the bundle must be rejected. |

The default `NoOpPluginBundleSignatureVerifier` always returns `Verified.Skipped` and logs a warning so a stray production deployment cannot silently bypass signature checks. Marketplace UI is expected to badge `Verified.Skipped` differently from `Verified.Trusted` even before real crypto lands.

Production crypto (key pinning, signature algorithm, distribution of trust roots) is **intentionally deferred** to a follow-up ticket. This ticket establishes only the surface so the import pipeline can be wired now.

## Versioning rules

`CURRENT_BUNDLE_FORMAT_VERSION` is `1` today.

- **Bump** the version when a structural change is made: a required entry is added or removed, on-disk encoding changes, the meaning of a path changes.
- **Do not bump** the version when adding optional fields with defaults to `PluginManifest` or `BundleManifest` — those are source-compatible and decode unchanged.
- Old hosts encountering a new version see `BundleParseError.UnknownVersion(declared, supported)` and can render a clear "upgrade required" message rather than a generic parse failure.

A v999 bundle today returns `UnknownVersion(999, 1)` rather than crashing. This is the forward-compatibility contract; tests in `commonTest` pin it.

## File reference

| Symbol | Location |
| --- | --- |
| `BundleManifest` | `commonMain/.../bundle/PluginBundle.kt` |
| `PluginBundle` | `commonMain/.../bundle/PluginBundle.kt` |
| `PluginBundleSource`, `MapBundleSource` | `commonMain/.../bundle/PluginBundleSource.kt` |
| `OkioPluginBundleSource`, `fromDirectory` | `commonMain/.../bundle/OkioPluginBundleSource.kt` |
| `fromZipFile` | `jvmMain/.../bundle/PluginBundleSource.jvm.kt` |
| `PluginBundleParser`, `BundleParseResult`, `BundleParseError` | `commonMain/.../bundle/PluginBundleParser.kt` |
| `PluginBundleValidator`, `BundleValidation` | `commonMain/.../bundle/PluginBundleValidator.kt` |
| `PluginBundleSignatureVerifier`, `PluginBundleSignatureVerification`, `NoOpPluginBundleSignatureVerifier` | `commonMain/.../bundle/PluginBundleSignatureVerifier.kt` |
| `PluginManifest` | `commonMain/.../plugin/PluginManifest.kt` |
| `PluginPermission` | `commonMain/.../plugin/permission/PluginPermission.kt` |

[PluginManifest]: ../../ampere-core/src/commonMain/kotlin/link/socket/ampere/plugin/PluginManifest.kt
[PluginPermission]: ../../ampere-core/src/commonMain/kotlin/link/socket/ampere/plugin/permission/PluginPermission.kt
