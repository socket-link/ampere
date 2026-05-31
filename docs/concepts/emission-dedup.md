---
concept: EmissionDedup
status: experimental
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/emission/EmissionDigest.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/emission/Emission.kt
related: [Emission, ChiProtocol]
last_verified: 2026-05-27
---

# EmissionDedup

## What it is

Emission deduplication is content-based: each [Emission](emission.md) may
carry an optional `dedupKey` derived from its payload via `inputDigest`
(SHA-256 over canonical JSON, truncated to 16 hex chars). Two Emissions
with the same `dedupKey` represent the same content; the *window* over
which they should collapse into a single rendered moment is a
consumer-side policy.

## Why it exists

The dedup contract sits next to `EmissionId` because the two answer
different questions. `EmissionId` is identity ("is this the same
record?"); `dedupKey` is content equivalence ("is this saying the same
thing?"). Conflating the two — for example, by reusing a stable id in
place of generating a random one — would make every replay or retry of
the same Emission collide with its predecessor and lose audit trail.

Effect-bearing kinds need dedup. A `Confirmation` raised twice in
quick succession for the same action is noise that the human shouldn't
see twice. Observation kinds (`Prose`, `Sensor`) often don't: two
identical sensor readings a minute apart may legitimately want to
render twice.

## Where it lives

- `agents/domain/emission/EmissionDigest.kt` — `inputDigest(payload: EmissionPayload): String`. SHA-256 over the canonical JSON serialization, hex, first 16 chars.
- `agents/domain/emission/Emission.kt` — the `dedupKey: String?` field on `Emission` and the `Emission.computeDedupKey()` convenience extension.

## Invariants

- **Content-based, not identity-based.** `dedupKey` is a hash of `EmissionPayload`, never of `EmissionId`.
- **Optional.** `null` means "this Emission is unique and should always render". The constructor does not auto-populate `dedupKey`; callers decide.
- **Stable across runs.** The same payload always hashes to the same digest. Canonical JSON serialization is enforced inside `EmissionDigest.kt` — callers must not pass a custom `Json` instance.
- **Effect-bearing kinds carry one.** `EmissionKind.Confirmation` (and any future `Action`) should always include a `dedupKey`. `computeDedupKey()` does this by default.
- **The dedup *window* is not part of the protocol.** AMPERE only publishes the key. How long two equal-keyed Emissions are considered duplicates is a Socket-side policy.

## Common operations

- **Compute the default key** — `emission.computeDedupKey()` returns `inputDigest(payload)` for effect-bearing kinds, `null` otherwise.
- **Compute manually** — `inputDigest(payload)` exposes the hash directly when the caller wants to combine it with other fields.
- **Compare two Emissions for content equality** — `a.dedupKey != null && a.dedupKey == b.dedupKey`. If either is null, treat as distinct.

## Anti-patterns

- **Using `EmissionId` as a dedup key.** `EmissionId` is a random UUID; equality collapses to "is this literally the same record", not "is this saying the same thing".
- **Embedding random nonces in payload.** A timestamp, a `Random().nextInt()`, or a `Clock.now()` inside the payload defeats content determinism — every Emission becomes its own dedup class.
- **Picking a custom `Json` configuration for digesting.** The hash is only stable because the configuration is shared. A caller-supplied `Json { encodeDefaults = false }` would silently re-key every previously-stored Emission.
- **Treating `dedupKey == null` as "render once" rather than "render always".** A null key is a positive statement: "do not dedup this". Consumers that collapse nulls together will swallow distinct observations.
- **Implementing the dedup *window* inside AMPERE.** Window length is a UX policy. It belongs in the consumer, alongside surface arbitration.
