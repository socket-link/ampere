---
concept: Probe
status: experimental
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/probe/**
related: [DomainCanon, MemoryProvenance, PropelLoop]
last_verified: 2026-09-02
---

# Probe

## What it is

A `Probe<in S>` is a predicate over one static artifact — a plan graph, a
manifest, a recalled fact — that returns a four-valued `Verdict`: `Holds`,
`Warn` (decided, bad, not disqualifying), `Violated` (decided, disqualifying),
or `Undetermined` (not decided; carries an `UndeterminedCause`). A
`ProbeSuite` runs an ordered list over one subject and yields `ProbeReport`s;
a `ProbeRegistry` lists Probes for discovery (Oscilloscope), not dispatch.

`SequenceProbe : Probe<CanonWorkGraph>` and `FreshnessProbe : Probe<Observed>` are the shipped Probes. `Observed` is
the one-field interface (`observedAt: Instant`) that lets it run over a canon
entity's `CanonProvenance` or a consumer's own binding type without Ampere
importing either.

## Why it exists

Recalled facts go stale — a spec fetched in March is not evidence in June —
and the check is generic to every consumer, so it descends to Ampere (Socket
decision D12). The design pressure came from Blueprint's web observations
being canon-external: `WebPage.fetchedAt` is a Socket type Ampere cannot read.
The resolution is that **the Probe's subject is the Recall binding, not the
observation**: Socket copies `fetchedAt` onto its `ManifestLine` at bind time,
and `Observed` is the name Ampere gives to what that binding implements.
Contravariance in `S` is the whole mechanism; constraining `S` to an Ampere
type would leave foreign subjects with no base to extend.

## Where it lives

- `probe/Probe.kt` — the SPI, `Probe<in S>`.
- `probe/Verdict.kt` — `Verdict` and `UndeterminedCause` (`EVIDENCE_ABSENT`, `EVIDENCE_UNREADABLE`, `STALE`).
- `probe/ProbeSuite.kt`, `probe/ProbeReport.kt`, `probe/ProbeRegistry.kt`, `probe/ProbeId.kt`.
- `probe/Observed.kt` — the timestamp interface; `canon/CanonProvenance.kt` implements it.
- `probe/SequenceProbe.kt` — dangling `dependsOn` and cycles over a `CanonWorkGraph`.
- `probe/FreshnessProbe.kt` — per-Probe `maxAge`, injected `now`.
- `probe/AmpereProbes.kt` — `registerAmpereProbes(freshnessMaxAge, now)`, the one-call wiring for a listing.
- `commonTest/.../probe/` — `ProbeSuiteTest`, `ProbeSerializationTest`, `SequenceProbeTest`, `FreshnessProbeTest`.

## Where `observedAt` binds

Contract 3: the timestamp is bound at the *earliest* moment that can know it,
and is never re-stamped on receipt, cache hit, or Plan.

| Observation kind | Who stamps `observedAt` | Where |
|------------------|-------------------------|-------|
| Plug perceive (canon entity) | The framework's clock at perceive | `ReadableCanonAdapter.project(payload, handle, observedAt)` writes it into `CanonProvenance`; child entities inherit the parent's value via `provenance.forChild` because they were observed in the same read |
| Relay fetch (canon-external, e.g. `WebPage`) | The relay's clock at upstream-response completion | Socket-side; Ampere never sees the page type |
| Recall binding | Nobody new — the binding *copies* the observation's timestamp | Consumer binding type implements `Observed`; a Socket `ManifestLine` copies `WebPage.fetchedAt` at bind time |

## Invariants

- **Max-age is a Probe parameter, never a fact field.** The fact carries the observation (`observedAt`); the consumer carries the policy (`FreshnessProbe.maxAge`). Same split as `Tolerance` on an eval case versus a `Reading`. Adding a `maxAge`/`ttl` to `CanonProvenance` or any `Observed` implementation is a violation.
- **Stale is `Undetermined(STALE)`, not `Violated`.** A stale fact does not prove the constraint false; it means the evidence cannot acquit. Convict-but-not-acquit.
- **`Undetermined` never renders as a soft pass.** A consumer that maps it to "ok" has silently accepted absent evidence.
- **`observedAt` is bound once, at the source.** Re-stamping on cache hit or Plan would make every cached fact look fresh forever.
- **`S` stays unconstrained.** A Probe must be able to check a subject type Ampere never imports.
- **A Probe is not a trace grader.** Grading a recorded run belongs to the eval harness (`EvalCase`, `Meter`s). A freshness verdict cannot be computed from a `MemoryEvent.KnowledgeRecalled` trace alone — it carries no per-fact timestamps — and that is an observability gap, not a reason to move the Probe.

## Common operations

- **Check a canon entity's freshness** — `FreshnessProbe(maxAge = 24.hours, now = Clock.System::now).evaluate(entity.provenance)`.
- **Check a consumer-side binding** — implement `Observed` on the binding, copying the source timestamp at bind time; the same Probe instance applies.
- **Run several Probes over one subject** — `ProbeSuite<Observed>(listOf(freshness, ...)).evaluate(subjectId, subject)`.
- **Expose Probes for listing** — `ProbeRegistry().registerAmpereProbes(freshnessMaxAge = 24.hours)` registers both shipped Probes; add consumer Probes with `register` afterwards. `all()` feeds the Oscilloscope listing.

## Anti-patterns

- **A `Boolean` verdict.** Two independent recons each needed a third value, and different thirds.
- **Reading `WebPage.fetchedAt` from Ampere.** It is a Socket type; the isolation check forbids it and the binding already carries the timestamp.
- **Per-fact tolerances.** Out of scope by design (AMPR-323); a fact does not know how stale is too stale for a given consumer.
