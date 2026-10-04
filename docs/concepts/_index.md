# AMPERE Concept Cells

Persistent, retrievable context that AMPERE coding agents inherit at session init.
These files are not a glossary — they encode invariants, rationale, and known
anti-patterns for AMPERE's load-bearing primitives. Read [`AGENTS.md`](../../AGENTS.md)
for the contract that ties this directory to agent behaviour and CI.

> **For agents:** before working on any of the files listed under
> `tracked_sources` in a concept, read that concept file in full. Treat its
> `Invariants` and `Anti-patterns` as binding. When your changes touch
> `tracked_sources`, update the concept file or include a
> `Concept-Verified: <ConceptName>` trailer in the commit message.

## Cognition

How the agent thinks: the loop, the routing, the memory, the differentiation, the trace.

| Concept | Status | One-line summary |
|---------|--------|------------------|
| [PropelLoop](propel-loop.md) | stable | Six-phase autonomous cognitive cycle (Perceive → Recall → Observe → Plan → Execute → Learn). Recall must precede Plan. |
| [CognitiveRelay](cognitive-relay.md) | stable | Provider-agnostic LLM routing: declarative rules pick an `AIConfiguration` per `RoutingContext`. Cognition layer never imports provider SDKs. |
| [MemoryProvenance](memory-provenance.md) | stable | Episodic (Outcome) and semantic (Knowledge) memory cells. Every cell is timestamped, attributable, and indexed by `run_id` for time-travel. |
| [SparkSystem](spark-system.md) | stable | Cellular differentiation: Sparks layer onto a single agent class to narrow capability. Sparks can only narrow, never expand. |
| [DreamCycle](dream-cycle.md) | experimental | Async memory consolidation. Target shape only — no implementation yet. |
| [CognitionTrace](cognition-trace.md) | stable | Per-`run_id` Arc trace projection: phases, model invocations, memory writes, tool calls, Watt cost, and the completion manifest of a run cut short. The glass-brain read model. |

## Coordination

How agents reach consensus and avoid stepping on each other.

| Concept | Status | One-line summary |
|---------|--------|------------------|
| [EventSerialBus](event-serial-bus.md) | stable | The nervous system. Agents coordinate by publishing typed `Event`s, not by direct method calls. Bus only dispatches; persistence lives one layer up. |
| [CoordinatorDigestStep](coordinator-digest.md) | experimental | Anti-lazy-delegation primitive: a coordinator must produce a digest before re-delegating. Target shape only — no implementation yet. |
| [LifecycleTypes](lifecycle-types.md) | experimental | Three plain-data types for a human-judgment stop: `ReconFinding` (claim + evidence + verified/inferred/untested), `DecisionRegister` (options, explicit default, lock), `LifecycleGate` (open / awaiting a person / closed with an outcome). Not canon entities; `Open` means unresolved, not passable. |
| [CancellationAddress](cancellation-address.md) | experimental | D4 applied to local subprocesses: every spawn that can outlive its spawner gets its own process group, and the caller holds a serializable address to stop it. Stop = SIGTERM group → bounded wait → SIGKILL, and works from the address alone after a restart. |
| [DispatchJournal](dispatch-journal.md) | experimental | The supervisor's claim-record journal: one append-only file per supervisor instance, written by atomic rename, holding one `DispatchRecord` per dispatch phase. A missing clean-shutdown marker is what makes a dispatch suspect; an unparseable line is quarantined, never dropped. |
| [TeamLayer](team-layer.md) | experimental | A roster of roles (`BlueprintRoster`: Planner, Estimator, Scout, Scheduler, Inspector, Coordinator), a Room bound onto `MessageThread` with one thread per subject, verdict threads opened from `ProbeEvent.VerdictReached`, the Coordinator's DM over `escalateToHuman`, and a weekly Standup Meeting that is 0W apart from its narrative. `EstimateCalibrationSource` is the consumer-implemented SPI. |
| [TicketConventions](ticket-conventions.md) | experimental | The seven pieces of metadata a ticket carries so a machine can dispatch it: an `ampere-scope` block declaring the repos and path globs it writes, one `wave:` label, the two `gate:` labels, `claim:`/`esc:` comments, and a model/effort line. `.ampere/verify.yml` is deliberately not a ticket field. |

## Surface

How the cognitive substrate meets the user, the platform, and the plug ecosystem.

| Concept | Status | One-line summary |
|---------|--------|------------------|
| [AgentSurface](agent-surface.md) | stable | Typed, serializable UI render request (Form, Choice, Confirmation, Card). Plugs emit; platform renderers translate. No platform types in the contract. |
| [ChiProtocol](chi.md) | experimental | Computer-Human Interface: the inverse of HCI. Runtime protocol for computer-initiated human contact. `HumanInteractionEvent` already collapsed into `EmissionEvent` (AMPR-180); three uncoordinated paths remain (`ToolAskHuman`, `MessageEvent.EscalationRequested`, `AgentPause`). |
| [Emission](emission.md) | experimental | The unifying CHI primitive. Typed domain object + `EmissionEvent` family on the bus. Four kinds (Prose, Decision, Confirmation, Sensor). AMPERE owns the noun; Socket owns rendering. |
| [EmissionDedup](emission-dedup.md) | experimental | Content-based dedup via `dedupKey` (SHA-256, 16 hex chars). Optional and never overloaded onto `EmissionId`. Window length is a consumer-side policy. |
| [PlugPermissions](plug-permissions.md) | stable | Deterministic gate that runs *before* any plug tool dispatch. Compares manifest + tool-requested permissions against user grants. |
| [LinkLayer](link-layer.md) | stable | A Plug connects through a Link and powers Arcs. Transport belongs to the Link; Links are directional, shared across Plugs, and resolved at Arc execution time. |
| [DomainCanon](domain-canon.md) | stable | Closed catalogue of provenance-carrying domain types in three rings. The IR Arc logic compiles against. Write-back preserves-and-merges by construction. |
| [ChassisSpi](chassis-spi.md) | experimental | The Plug operation boundary: `PerceiveSource`, `ExecuteSink`, `AssetResolver`. Perceive pages account for every query predicate as evaluated or residual (`applyResidual` makes them exact); conditional writes via `executeIf` refuse loudly unless the provider enforces them atomically; receipts carry post-write etag and state. |
| [VerificationManifest](verification-manifest.md) | experimental | `.ampere/verify.yml`: a repository's definition of done as data. One task per gate, exit code is the only pass/fail contract, `fresh` forces re-execution, and infrastructure failure is an outcome that does not burn agent retries. |
| [Probe](probe.md) | experimental | Predicate over a static artifact with a four-valued `Verdict`. `FreshnessProbe : Probe<Observed>` — max-age is on the Probe, stale is `Undetermined(STALE)`, `observedAt` binds once at the source. `SafetyProbe : Probe<WorkPlanSubject>` — a closed hazard taxonomy, `Warn` and never `Violated`, one mitigation Task per finding. |
| [Ampere](ampere.md) | stable | The meta-concept: what makes a framework an AMPERE framework. Glass brain, AniMA agents, electrical metaphor, event-first coordination. |
