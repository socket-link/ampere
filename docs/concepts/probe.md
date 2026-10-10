---
concept: Probe
status: experimental
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/probe/**
related: [DomainCanon, MemoryProvenance, PropelLoop, HostedRun]
last_verified: 2026-10-10
---

# Probe

## What it is

A `Probe<in S>` is a predicate over one static artifact — a plan graph, a
manifest, a recalled fact — that returns a four-valued `Verdict`: `Holds`,
`Warn` (decided, bad, not disqualifying), `Violated` (decided, disqualifying),
or `Undetermined` (not decided; carries an `UndeterminedCause`). A
`ProbeSuite` runs an ordered list over one subject and yields `ProbeReport`s;
a `ProbeRegistry` lists Probes for discovery (Oscilloscope), not dispatch. A
suite handed an `AgentEventApi` also publishes one
`ProbeEvent.VerdictReached` per report, so the verdict is legible in the trace
and not only to whoever called `evaluate`. Since AMPR-393 it also takes a
`runId`, stamped on each verdict's envelope: `VerdictReached` carries no run in
its payload, so the envelope is the only place a verdict can say which run
reached it, and a verdict `ArcTraceProjection.project(runId)` cannot find is a
verdict outside the record. A roster-hosted run's OBSERVE passes its own and
builds the suite around the verifier seat's door, so a verdict is attributed to
the seat that convicted (see [HostedRun](hosted-run.md)); null stays right for a
Bench fixture or a unit test.

`SequenceProbe : Probe<CanonWorkGraph>`, `FreshnessProbe : Probe<Observed>` and
`SafetyProbe : Probe<WorkPlanSubject>` are the shipped Probes. `Observed` is
the one-field interface (`observedAt: Instant`) that lets it run over a canon
entity's `CanonProvenance` or a consumer's own binding type without Ampere
importing either; `WorkPlanSubject` and `LineRef` are the same move for a plan
and its manifest.

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

## The hazard Probe

`SafetyProbe` (AMPR-380) asks whether a plan tells a person to do something
dangerous without a step that makes it safe first. It is here, not in the
consumer, because the question is generic to any plan of physical work and both
halves of the evidence are Ampere-visible: the Tasks are a `CanonWorkGraph`, and
the manifest arrives through `LineRef` exactly as an observation arrives through
`Observed`. The vocabulary is closed and small — seven `HazardCategory` values,
seven `MitigationHint` values — and names no part, finish or domain noun, so the
same seven words serve a vent, a sensor build and a garden job, while a software
Blueprint simply matches none of them.

The verdict is the whole design: **a hazard is `Warn`**. The plan is not wrong;
it needs a mitigation Task. `Violated` would turn a safety line into a refusal
to plan. A Task the classifier cannot read is `Undetermined`, and that *outranks*
the warning — reporting the hazards found in the rest as the plan's verdict would
be a soft pass over the Tasks nobody read.

Findings do not ride the verdict event. `VerdictReached` is primitives plus
`Verdict` by design, so the Inspector rule
(`link.socket.ampere.room.SafetyReview`) calls `SafetyProbe.inspect` and gets
`HazardFinding`s typed. That is sound precisely because the classifier is
deterministic — the same property that lets a verdict be recomputed rather than
replayed. Where the findings *do* become events is the Room: a `RoomCard.Hazard`
on `RoomEvent.Posted` carries category, hint and evidence as data.

## Where it lives

- `probe/Probe.kt` — the SPI, `Probe<in S>`.
- `probe/Verdict.kt` — `Verdict` and `UndeterminedCause` (`EVIDENCE_ABSENT`, `EVIDENCE_UNREADABLE`, `STALE`).
- `probe/ProbeSuite.kt`, `probe/ProbeReport.kt`, `probe/ProbeRegistry.kt`, `probe/ProbeId.kt`.
- `probe/Observed.kt` — the timestamp interface; `canon/CanonProvenance.kt` implements it.
- `probe/SequenceProbe.kt` — dangling `dependsOn` and cycles over a `CanonWorkGraph`.
- `probe/FreshnessProbe.kt` — per-Probe `maxAge`, injected `now`.
- `probe/AmpereProbes.kt` — `registerAmpereProbes(freshnessMaxAge, now, hazardClassifier)`, the one-call wiring for a listing.
- `probe/safety/HazardCategory.kt` — the closed `HazardCategory` and `MitigationHint` vocabularies; `MitigationHint.imperative` is the mitigation Task's fallback title.
- `probe/safety/HazardFinding.kt` — `HazardSubject` (a Task or a manifest line) and `HazardFinding`.
- `probe/safety/WorkPlanSubject.kt` — `LineRef`, `WorkPlanSubject`, and the plain `WorkPlan`/`PlanLine` implementations.
- `probe/safety/HazardClassifier.kt` — the classifier SPI, `UnclassifiableSubject`, `UnclassifiedSubject`.
- `probe/safety/KeywordHazardClassifier.kt` — the v1 rules: line kinds, then line labels, then Task text.
- `probe/safety/SafetyProbe.kt` — the Probe and `SafetyInspection` (findings + the verdict they imply).
- `probe/safety/MitigationPlan.kt` — the pure insertion: one mitigation Task per finding, before the hazardous Task.
- `agents/domain/event/ProbeEvent.kt` — `VerdictReached`, the verdict on the bus. It lives in the event package, not here, because `Event` is sealed and Kotlin requires sealed subtypes to share module and package with the base type.
- `commonTest/.../probe/` — `ProbeSuiteTest`, `ProbeSuiteEventTest`, `ProbeSerializationTest`, `SequenceProbeTest`, `FreshnessProbeTest`, and `probe/safety/` (`KeywordHazardClassifierTest`, `SafetyProbeTest`, `MitigationPlanTest`).
- `ampere-eval/src/jvmTest/.../blueprint/` — the three Blueprint fixtures (vent, motion sensor, lawn) and `BlueprintSafetyReplayTest`, which pins each one's findings.

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
- **A Probe is not a trace grader.** Grading a recorded run belongs to the eval harness (`EvalCase`, `Meter`s). A freshness verdict cannot be computed from a `MemoryEvent.KnowledgeRecalled` trace alone — it carries no per-fact timestamps — and that is an observability gap, not a reason to move the Probe. `VerdictReached` narrows the gap from the other side: the verdict a Probe reached is now *in* the trace, even though it was never computable *from* it.
- **A verdict event carries the subject's id, never the subject.** `S` is unconstrained, so a payload holding the subject would either bind the event to Ampere's types or force a foreign type across the boundary. `subjectId`, `probeId`, `Verdict`, and a small `detail` map are the whole payload.
- **Publishing is opt-in and never partial.** A `ProbeSuite` with no `eventBus` is pure — Bench fixtures and unit tests depend on that. A suite with one publishes after every Probe has run, so a subscriber never sees half a verdict set from a suite that threw halfway through.
- **A hazard is `Warn`, and `Undetermined` outranks it.** `Violated` would make a plan containing hazardous work an invalid plan, which is a refusal to plan rather than a safety line; `Holds` over a plan with unreadable Tasks would be a soft pass. The findings survive either verdict on `SafetyInspection`, so the mitigation Tasks are inserted even when the verdict says the plan could not be read in full.
- **The hazard taxonomy is closed, and the manifest's kind vocabulary is not.** `HazardCategory` and `MitigationHint` are enums: a category no renderer and no disclaimer knows is a hazard nobody sees, and widening the set is a versioned change. `LineRef.kind` is a `String` because interface kinds belong to the consumer's manifest; `KeywordHazardClassifier.recognizedLineKinds` is the token set Ampere normalizes, and an unrecognized kind contributes nothing from its kind while its label is still read.
- **A classifier returns `Result`, never a bare list.** An empty list cannot mean both "no hazard" and "could not read"; a classifier whose failure path is an empty list is a Probe that passes everything it cannot judge. This is also what keeps a later model-backed classifier honest — a provider error becomes `Undetermined`, not a clean plan.
- **One finding per subject per category, and at most one mitigation Task per finding.** The rules are walked in a fixed order with the manifest ahead of the words, so the hint a plan gets does not depend on the order its lines happen to be in.
- **A mitigation Task is never inspected.** Its title quotes the step it guards, so a keyword classifier reads the same hazard in it and asks for a mitigation of the mitigation, forever. `MitigationPlan.isMitigation` is the stop, and the Probe applies it before the classifier runs.
- **A mitigation Task is identified, not accumulated.** `MitigationPlan.mitigationId(task, hint)` is pure, so inserting twice inserts nothing — the same identity-is-the-lookup rule the Room's thread ids use. A mitigated plan still warns: `Warn` says the plan contains hazardous work, not that the plan is unfinished.
- **A verdict is recomputed, never replayed.** `PlaybackRelay` replays a recorded run's LLM calls; it does not replay verdicts. A Probe re-evaluated against a replayed subject must reach its verdict again, or a stale judgement outlives the code that formed it.

## Common operations

- **Check a canon entity's freshness** — `FreshnessProbe(maxAge = 24.hours, now = Clock.System::now).evaluate(entity.provenance)`.
- **Check a consumer-side binding** — implement `Observed` on the binding, copying the source timestamp at bind time; the same Probe instance applies.
- **Run several Probes over one subject** — `ProbeSuite<Observed>(listOf(freshness, ...)).evaluate(subjectId, subject)`. Note that a suite cannot mix Probes whose subjects are unrelated types: `CanonWorkGraph` is a final data class, so a `Probe<CanonWorkGraph>` and a `Probe<WorkPlanSubject>` belong to two suites.
- **Check a plan for hazards** — `SafetyProbe(KeywordHazardClassifier).inspect(WorkPlan(graph, lines))`, then `MitigationPlan.insert(graph, inspection.findings, now)`. `SafetyReview` does both and says so in the Room; see [TeamLayer](team-layer.md).
- **Make verdicts visible in the trace** — `ProbeSuite(probes, eventBus = bus)`. `eventSource`, `now`, and `idGenerator` are constructor parameters too, so a test can pin exactly what a published event carries.
- **Expose Probes for listing** — `ProbeRegistry().registerAmpereProbes(freshnessMaxAge = 24.hours)` registers both shipped Probes; add consumer Probes with `register` afterwards. `all()` feeds the Oscilloscope listing.

## Anti-patterns

- **A `Boolean` verdict.** Two independent recons each needed a third value, and different thirds.
- **Reading `WebPage.fetchedAt` from Ampere.** It is a Socket type; the isolation check forbids it and the binding already carries the timestamp.
- **Per-fact tolerances.** Out of scope by design (AMPR-323); a fact does not know how stale is too stale for a given consumer.
- **Routing on a verdict inside Ampere.** Re-plan, escalation to a human, retry — all consumer-side (Socket decision D21). The event is the signal, not the action.
- **A `Violated` hazard verdict, or anything that turns a hazard into a refusal to plan.** The plan a person needs is the one with the mitigation step in it, not an error.
- **Reading `LineRef.appliesTo` as optional.** It is the join between the manifest and the Tasks. A hazardous line it attaches to nothing is reported against the line itself (`HazardSubject.Line`) and inserts no Task — dropping it instead would leave a solvent in the room with nobody told.
- **Stemming or substring-matching the keyword rules.** `substring` reads "execute" as a cutting hazard and "remains" as mains voltage. Tokens are whole words and inflections are listed, so the vocabulary is auditable by reading it.
- **Letting a mitigation Task invent a provider.** It keeps the hazardous Task's `linkId` (the same-Link rule) and says `ampere.probe.safety` in `sourceSystem`. Reusing the provider's `sourceSystem` would make a Task Ampere proposed look perceived.
- **Reaching for a subject-typed verdict event.** The pull is real — a `VerdictReached<S>` would carry more — and it is exactly what makes the event unusable by a consumer whose subject Ampere cannot name.
