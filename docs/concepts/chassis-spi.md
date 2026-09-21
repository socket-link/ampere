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

On the Perceive side, a `PerceiveQuery` carries `predicates`: a conjunction
drawn from a closed, three-form `PerceivePredicate` vocabulary — `Equals`,
`Not(Equals)`, `HasNoRelation`. The returned `PerceivePage` says, per
predicate, whether the source `evaluated` it or left it `residual` for the
caller. `isExact` is derived: a page is over-approximate exactly when it has
residual predicates, and `applyResidual(evaluator)` is the standard way to
make it exact.

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

Predicates and exactness (AMPR-313, from the AMPR-289 recon's G3/G4) exist
because a `Map<String, String>` filter could not express the work-source
ready-queue rule ("carries `wave:w0`, not gated, no open blocker"), so the true
query was unrecordable and could not be pushed down. And nothing on the page
could say "this is an over-approximation" — the recon's ready-queue returned a
blocked and a gated ticket that a trusting caller would have dispatched.
Recording `evaluated` *and* `residual` (not just residual) is what makes a
silently dropped predicate detectable: with only a residual list, "dropped"
and "evaluated" look identical. Rejected: a general query language (no live
consumer), a boolean `isApproximate` flag (a source can forget to set it;
deriving it from `residual` means it can't), and `Not` over any predicate
(`Not(HasNoRelation)` and double negation grow the algebra with no consumer).

Conditional writes (AMPR-312, from the AMPR-289 recon's G1/G5) exist because
an unconditional-only SPI erased the capability of providers that *do* offer
if-match semantics, and a receipt with no post-write state forced an extra
Perceive round-trip just to learn whether a write survived. The rejected
alternative was a `precondition` parameter on `execute` itself: an override
can ignore a parameter and still compile, which is exactly a silent downgrade
to an unconditional write. A separate member whose default refuses makes
loud refusal structural.

## Where it lives

- `plug/spi/PerceiveSource.kt` — `PerceiveSource`, `PerceiveQuery`, `PerceivePage` (+ `PerceivePage.unfiltered`).
- `plug/spi/PerceivePredicate.kt` — `PerceivePredicate` (`Equals` / `Not` / `HasNoRelation`), `PredicateEvaluator`, `applyResidual`.
- `plug/spi/ExecuteSink.kt` — `ExecuteSink` (`execute`, `executeIf`, `supportedPreconditions`) and `ExecuteReceipt`.
- `plug/spi/WritePrecondition.kt` — `WritePrecondition` (`MatchVersion`) and `WritePreconditionKind`.
- `plug/spi/ExecuteFailure.kt` — `PreconditionUnsupported` / `PreconditionFailed`, `ExecuteException`, `executeFailure()`.
- `plug/spi/AssetResolver.kt`, `plug/spi/ConsentEnforcingAssetResolver.kt` — asset resolution and its consent wrapper.
- `canon/table/TableWriteSink.kt` — the AMPR-263 capability-gated `ExecuteSink` for `TABLE`.
- `ampere-core-test-fixtures/.../plug/spi/PerceiveSourceContract.kt` — inheritable suite + `assertPerceiveContract`: every predicate is evaluated or residual, never dropped.
- `ampere-core-test-fixtures/.../plug/spi/VersionedInMemoryExecuteSink.kt` — reference sink with real versioned writes.
- `ampere-core-test-fixtures/.../plug/spi/ExecuteSinkPreconditionContract.kt` — inheritable suite: undeclared kinds are refused.
- `commonTest/.../plug/spi/` — `PerceiveSourceTest`, `PerceivePredicateTest`, `ReadyQueueFixture` (the AMPR-289 ready-queue shape), `ExecuteSinkTest`, `ConditionalExecuteTest`.

## Invariants

- **Every query predicate is accounted for exactly once on every page.** `evaluated + residual == query.predicates` as a multiset, and no predicate is in both. `assertPerceiveContract` checks this.
- **A predicate a source cannot evaluate is residual — never dropped, never a failure.** An unknown field, an unsupported relation, or a form the source doesn't push down all go back in `residual`. `PerceiveSourceContract` sends unrecognisable predicates of all three forms to check it.
- **`isExact` is derived from `residual`, never stored.** And `evaluated`/`residual` have no defaults: a source cannot build a page without stating what it filtered. `PerceivePage.unfiltered(query, …)` is the zero-effort honest answer and is always correct.
- **The predicate vocabulary is closed at three forms.** `Not` wraps `Equals` only. Field and relation names are source-defined strings; `HasNoRelation` has no qualifier argument — "no *open* blocker" is its own kind (`"blocked-by-open"`). Adding a form needs its own recon-backed ticket and a live consumer.
- **`PerceivePredicate` is a wire type.** Stable `@SerialName`s (`perceive_predicate.*`); queries belong in recorded traces.
- **A precondition is never silently downgraded.** `executeIf` on a kind the sink does not declare returns `ExecuteFailure.PreconditionUnsupported` before touching the transport — never an unconditional write.
- **Declaring a `WritePreconditionKind` means the provider enforces it atomically.** The provider itself must reject the write on mismatch. A sink that emulates it client-side (read, compare, write) has a race between compare and write and must not declare the kind.
- **Unsupported and failed are different outcomes.** `PreconditionUnsupported` is a capability gap (arbitrate at the protocol level instead); `PreconditionFailed` is a lost race (nothing was written; re-plan). Never collapse one into the other.
- **There is no `WritePrecondition.None`.** "No precondition" is `execute`. Adding a `None` case reintroduces a path where `executeIf` behaves unconditionally.
- **The version token is `SourceHandle.etag`.** Receipts carry the post-write version on `handle.etag`; `PreconditionFailed.current` carries the current one. Do not add a parallel version field.
- **`postWriteState` is native, not canon.** It is a `NativePayload`; projecting it is the adapter's job.
- **Every `PerceiveQuery`/`ExecuteReceipt` names its `LinkId`.** Consent and provenance are keyed on it.

## Common operations

- **Implement a source that pushes nothing down** — return `PerceivePage.unfiltered(query, entities, nextCursor, partialFailures)`.
- **Push a predicate down** — apply it natively and list it in `evaluated`; list every other query predicate in `residual`.
- **Consume a page** — `page.applyResidual(evaluator)` with a `PredicateEvaluator<T>` that answers `Equals` and `HasNoRelation` for one entity; the framework handles `Not` and the conjunction. An exact page returns as-is without touching the evaluator.
- **Prove a new source conformant** — subclass `PerceiveSourceContract<T>` in the source's tests, supplying `source()` and `fixturePredicates()`.
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

- *Reading `page.entities` directly when `isExact` is false* — that is the over-approximation; a blocked or gated item is still in it.
- *Dropping a predicate the source can't evaluate* — the page then claims exactness it doesn't have. Return it as residual.
- *Failing `perceive` over an unrecognised predicate* — unknown is residual, not an error; the caller may well know how to evaluate it.
- *Adding a qualifier to `HasNoRelation` or widening `Not`* — both are vocabulary growth; name a new relation kind instead, or open a recon ticket.
- *Preconditions replace protocol arbitration* — they don't. For providers without CAS (e.g. the Linear-over-MCP work source, AMPR-305), comment-arbitrated claim-by-write-then-verify remains the documented pattern; `executeIf` only removes the need for it where the provider enforces the condition.
- *Overriding `executeIf` to call `execute`* — that is the silent downgrade the default exists to prevent.
- *A decorator that forwards `execute` but not `executeIf`/`supportedPreconditions`* — it silently strips the capability (the default refuses, so this is loud, but it still loses a capability the inner sink had).
- *Declaring `MATCH_VERSION` because the sink re-reads before writing* — client-side compare is not atomic.
