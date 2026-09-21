---
concept: ChassisSpi
status: experimental
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/plug/spi/**
  - ampere-core-test-fixtures/src/commonMain/kotlin/link/socket/ampere/plug/spi/**
related: [LinkLayer, DomainCanon, PlugPermissions]
last_verified: 2026-09-20
---

# Chassis SPI

## What it is

The operation boundary a Plug implements against: `PerceiveSource<T>` (native
objects in, pages of `T` out — `LinkOperation.PERCEIVE`), `ExecuteSink<C>` (a
command in, an `ExecuteReceipt` out — `LinkOperation.EXECUTE`), and the
optional `AssetResolver` (canon asset refs to bytes). The canon adapters
(`ReadableCanonAdapter`, `WritableCanonAdapter`) are the *projection*
underneath; the chassis SPI is the *operation* surface on top.

On the Execute side, `execute(command)` is an unconditional write.
`executeIf(command, precondition)` is a conditional one — compare-and-swap
against a provider version token — available only where a sink declares the
matching `WritePreconditionKind` in `supportedPreconditions`. Receipts carry
the post-write provider version on `handle.etag` and, when the provider
returns it, the post-write native state on `postWriteState`.

## Why it exists

Without a shared operation boundary, every Plug grows its own read/write path
and the framework can't enforce consent, provenance, or write safety in one
place. `T` and `C` are unconstrained (not bound to `CanonEntity`) because
canon-external Plugs — Notify, Clipboard — still need a base to implement.

Conditional writes (AMPR-312, from the AMPR-289 recon's G1/G5) exist because
an unconditional-only SPI erased the capability of providers that *do* offer
if-match semantics, and a receipt with no post-write state forced an extra
Perceive round-trip just to learn whether a write survived. The rejected
alternative was a `precondition` parameter on `execute` itself: an override
can ignore a parameter and still compile, which is exactly a silent downgrade
to an unconditional write. A separate member whose default refuses makes
loud refusal structural.

## Where it lives

- `plug/spi/PerceiveSource.kt` — `PerceiveSource`, `PerceiveQuery`, `PerceivePage`.
- `plug/spi/ExecuteSink.kt` — `ExecuteSink` (`execute`, `executeIf`, `supportedPreconditions`) and `ExecuteReceipt`.
- `plug/spi/WritePrecondition.kt` — `WritePrecondition` (`MatchVersion`) and `WritePreconditionKind`.
- `plug/spi/ExecuteFailure.kt` — `PreconditionUnsupported` / `PreconditionFailed`, `ExecuteException`, `executeFailure()`.
- `plug/spi/AssetResolver.kt`, `plug/spi/ConsentEnforcingAssetResolver.kt` — asset resolution and its consent wrapper.
- `canon/table/TableWriteSink.kt` — the AMPR-263 capability-gated `ExecuteSink` for `TABLE`.
- `ampere-core-test-fixtures/.../plug/spi/VersionedInMemoryExecuteSink.kt` — reference sink with real versioned writes.
- `ampere-core-test-fixtures/.../plug/spi/ExecuteSinkPreconditionContract.kt` — inheritable suite: undeclared kinds are refused.
- `commonTest/.../plug/spi/` — `ExecuteSinkTest`, `ConditionalExecuteTest`.

## Invariants

- **A precondition is never silently downgraded.** `executeIf` on a kind the sink does not declare returns `ExecuteFailure.PreconditionUnsupported` before touching the transport — never an unconditional write.
- **Declaring a `WritePreconditionKind` means the provider enforces it atomically.** The provider itself must reject the write on mismatch. A sink that emulates it client-side (read, compare, write) has a race between compare and write and must not declare the kind.
- **Unsupported and failed are different outcomes.** `PreconditionUnsupported` is a capability gap (arbitrate at the protocol level instead); `PreconditionFailed` is a lost race (nothing was written; re-plan). Never collapse one into the other.
- **There is no `WritePrecondition.None`.** "No precondition" is `execute`. Adding a `None` case reintroduces a path where `executeIf` behaves unconditionally.
- **The version token is `SourceHandle.etag`.** Receipts carry the post-write version on `handle.etag`; `PreconditionFailed.current` carries the current one. Do not add a parallel version field.
- **`postWriteState` is native, not canon.** It is a `NativePayload`; projecting it is the adapter's job.
- **Every `PerceiveQuery`/`ExecuteReceipt` names its `LinkId`.** Consent and provenance are keyed on it.

## Common operations

- **Write conditionally where the provider allows it** —
  ```kotlin
  if (WritePreconditionKind.MATCH_VERSION in sink.supportedPreconditions) {
      sink.executeIf(command, WritePrecondition.MatchVersion(entity.provenance.sourceHandle.etag!!))
  } else {
      // protocol-level arbitration, e.g. claim-by-write-then-verify
  }
  ```
- **Chain conditional writes** — feed `receipt.handle?.etag` into the next `MatchVersion`; no re-read needed.
- **Recover from a lost race** — on `PreconditionFailed`, read `current?.etag` / `currentState` from the failure; re-Perceive only when the provider returned neither.
- **Implement conditional writes in a sink** — declare the kind in `supportedPreconditions`, override `executeIf`, refuse undeclared kinds first, and extend `ExecuteSinkPreconditionContract` in the sink's tests.
- **Sink with no conditional writes** — do nothing. The `executeIf` default already refuses.

## Anti-patterns

- *Preconditions replace protocol arbitration* — they don't. For providers without CAS (e.g. the Linear-over-MCP work source, AMPR-305), comment-arbitrated claim-by-write-then-verify remains the documented pattern; `executeIf` only removes the need for it where the provider enforces the condition.
- *Overriding `executeIf` to call `execute`* — that is the silent downgrade the default exists to prevent.
- *A decorator that forwards `execute` but not `executeIf`/`supportedPreconditions`* — it silently strips the capability (the default refuses, so this is loud, but it still loses a capability the inner sink had).
- *Declaring `MATCH_VERSION` because the sink re-reads before writing* — client-side compare is not atomic.
