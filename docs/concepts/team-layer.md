---
concept: TeamLayer
status: experimental
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/roster/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/room/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/standup/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/RoomEvent.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/events/messages/MessageChannel.kt
related: [EventSerialBus, ChiProtocol, Emission, Probe, DomainCanon, HostedRun]
last_verified: 2026-10-10
---

# Team Layer

## What it is

A **roster** of roles that plans and tends one project, a **Room** they
coordinate in, and a **Standup** that re-plans weekly over the project's event
stream — Ampere's centralized communication protocol, bound onto primitives it
already had (AMPR-379). `BlueprintRoster` is the first roster: six roles (Planner,
Estimator, Scout, Scheduler, Inspector, Coordinator) for a physical project. The
Room is one `MessageChannel.Room` per project with a `MessageThread` per subject
(`ThreadSubject.General`, one `Milestone` per milestone, one `Verdict` per
`Violated`/`Undetermined` Probe verdict, one `Hazard` per `SafetyProbe` finding). The Coordinator's DM to the human is
`AgentMessageApi.escalateToHuman`, which is a Decision-kind Emission with a
thread attached. The Estimator reads duration calibration through
`EstimateCalibrationSource`, an SPI the consumer implements.

## Why it exists

Socket's Blueprint Arc (socket#1406, decision D31) needed a team deliberating in
the open, with the *why* attached to every plan change, instead of one cloud
persona. Ampere owns roles, the Room, threads and the Meeting; Socket renders the
Room and supplies calibration; the dependency direction is Socket → Ampere only.

Three pressures shaped the cut:

1. **Bind, do not duplicate.** The thread primitive had no subject field, random
   ids, a closed channel set and a per-agent post API. Each gap was closed at the
   primitive (a `Room` channel variant, deterministic thread ids, a service that
   writes as any author) rather than beside it, so the SDK's `ThreadService`, the
   CLI's `thread list` and every `MessageEvent` subscriber see a Room without
   knowing the word.
2. **The identity is the lookup.** `RoomId.forProject(projectId)` and
   `roomThreadId(roomId, subject)` are pure functions, so `open` and `thread` are
   idempotent with no index, no new table and no migration. A thread's subject,
   assignment and a post's card ride in `Message.metadata`.
3. **Watt discipline.** Everything in the lifecycle is deterministic and 0W. The
   standup's narrative is the one metered step, behind `StandupNarrator`, and
   `TemplatedNarrator` is the default. Re-plans between standups are graph work.

## Where it lives

- `roster/RoleConfig.kt` — `RoleConfig(id, title, instructions, tools, reviews, execution)`, the `Roster` interface (`all` and `host` abstract; `verifier`, `resolverFor`, `byId`, `reviewerOf`, `seatRunning` defaulted), `reviewsAreAcyclic`, and `RoleConfig.of` — the plain-id factory Swift authors through.
- `roster/RosterConfig.kt` — `RosterConfig(host, roles, verifier)`, the serializable roster a consumer authors (AMPR-409, AMPR-413), and `RosterConfig.of`.
- `roster/SeatTool.kt` — `SeatTool(seat, tool)`, the `<seat>/<tool>` id and its `parse` (AMPR-413).
- `roster/SeatRouting.kt` — `RoutingContext.withSeat(seat)`, the one seam between a roster and the relay (AMPR-413). The tag vocabulary it writes is `routing/ExecutionTags` — see [CognitiveRelay](cognitive-relay.md).
- `roster/BlueprintRoster.kt` — the six roles; `roster/RosterPrompts.kt` — their versioned prompt texts (`PromptRef`); `roster/RosterTools.kt` — tool ids the consumer binds.
- `roster/calibration/` — `EstimateCategory`, `Calibration`, `EstimateCalibrationSource` + `NoCalibration`, `WorkEstimate`/`CalibratedEstimate`, `Estimator`.
- `room/RoomService.kt`, `room/DefaultRoomService.kt` — open, thread, post, resolve, threads, history, transcript. `room/RoomMetadata.kt` — the metadata keys (wire contract).
- `room/ThreadSubject.kt`, `room/Author.kt`, `room/RoomCard.kt`, `room/RoomMessage.kt`, `room/RoomFailure.kt` — the value types.
- `room/VerdictPolicy.kt` — the pure rules; `room/VerdictThreadBinding.kt` — the bus subscriber that applies them; `room/CoordinatorEscalation.kt` — the DM.
- `room/SafetyReview.kt` — the Inspector's hazard rule (AMPR-380): a `ThreadSubject.Hazard` thread per `SafetyProbe` finding, a `RoomCard.Hazard` posted by the verifier, and the mitigation Tasks inserted into the graph it returns.
- `room/ReviewGate.kt` — the open-for-review path for cards; `room/RoomTranscript.kt` — the text rendering the fixture replay reads.
- `standup/Standup.kt` — the Meeting; `standup/StandupDeliberation.kt` — digest, ordering, scheduling; `standup/StandupNarrator.kt` — `TemplatedNarrator`, `LlmNarrator`.
- `agents/domain/event/RoomEvent.kt` — `RoomOpened`, `ThreadOpened`, `Posted`, `ThreadResolved`, `ReviewRequested`, `ReviewCompleted`.
- `agents/events/messages/MessageChannel.kt` — `MessageChannel.Room` and `ROOM_PREFIX`.
- Tests: `commonTest/.../roster` (`BlueprintRosterTest`, `RosterConfigTest`, `SeatToolTest`, `SeatRoutingTest`), `ampere-ios/iosAppTests/RosterAuthoringTests.swift` (the roster authored across the Objective-C boundary), `room`, `standup`, `agents/domain/event/RoomEventTest`; `jvmTest/.../room` (`DefaultRoomServiceTest`, `ReviewGateTest`, `VerdictThreadBindingTest`), `jvmTest/.../standup/StandupTest`; `ampere-eval/src/jvmTest/.../blueprint/` (the vent fixture and its replay gate).

## Invariants

- **One Room per project, one thread per subject, by construction.** Both ids are derived; opening twice names the same things and publishes nothing new. `RoomEvent.RoomOpened` fires once.
- **A Room is made of the thread primitive.** Every Room thread is a `MessageThread` on a `MessageChannel.Room`; every post is a `Message`; `ThreadCreated`, `MessagePosted` and `ThreadStatusChanged` fire for Room writes exactly as for any other thread. `RoomEvent`s add facts, never replace those.
- **Roles may not talk over the human.** A role's post into a `WaitingForHuman` or `Resolved` thread is refused with a typed failure. The human may post into either; that reopens the thread, and the reopening is published, not silent.
- **A verdict thread is assigned by the Probe, not the subject.** `Roster.resolverFor(probeId)`: the sequence and safety Probes' verdicts go to the Planner — both are settled by changing the graph — and every other Probe's to the Scout.
- **A roster's only required seats are `all()` and `host`.** `verifier` and `resolverFor` default to null (AMPR-409), because a one-seat roster has nobody to review and a consumer that runs no Probes has nothing to review; a roster that had to name an Inspector to compile would be a roster the type system invented. `BlueprintRoster` narrows both back to non-null, so a caller holding it statically still sees two seats.
- **No reviewing seat means no verdict and no hazard threads — not a thread nobody holds.** With a null `verifier`, `VerdictPolicy.decide` returns no action for every verdict kind and `VerdictThreadBinding` writes nothing (it still counts what it saw, so a replay's `awaitVerdictsSeen` finishes). With a null `resolverFor` for the Probe, the same: a thread nobody owns is one the "one pass, then the human" rule can never advance. An already-open thread keeps the assignee it opened with. `SafetyReview` still mitigates the plan — the mitigation Tasks are graph work — and opens no thread.
- **A roster a consumer authors is a value with three checks.** `RosterConfig` requires distinct role ids, a host that holds one of the seats, and — when it names one — a verifier that holds one, on construction and therefore on decode. Review acyclicity is *not* checked there: `reviewsAreAcyclic()` is asked by whoever is about to rely on the answer, and a roster is a legitimate value while its edges are still being filled in.
- **A named verifier is a value; which seat settles a Probe is still an override.** `RosterConfig.verifier` is authorable data (AMPR-413) because the seat that runs the Probes is a fact about the roster. `resolverFor` stays a method: it answers per `ProbeId`, which is a question about the Probe catalogue rather than about the roster, so an authored roster names a verifier and still opens no verdict thread until a consumer overrides it.
- **A seat's execution assignment is a tag, never a route.** `RoleConfig.execution` reaches routing only through `RoutingContext.withSeat(seat)`, which unions `ExecutionTags` onto the tags a call already carries and returns the context *unchanged* for a seat that declares none. Nothing in `roster` resolves a model id to an `AIConfiguration` — the id is opaque (`ExecutionAssignment.model`), and only the consumer's rule set knows the mapping.
- **A tool id is namespaced by seat, and the seat must have declared the tool.** `SeatTool` is a convention over `ToolId` (a `String`), so `scout/web_search` and `inspector/web_search` are two runnable things backed by one tool. `Roster.seatRunning` returns null for a bare id, for a seat the roster does not hold, *and* for a tool the named seat never declared — all three mean the step cannot be dispatched as written. `parse` splits at the first separator, so a tool id carrying one of its own arrives whole.
- **The human is asked once per thread, and only when a role cannot settle it.** `Undetermined` escalates at once (no evidence exists). `Violated` escalates only on a reconviction after at least one pass by the assigned role. A thread already waiting is never escalated twice.
- **Holds closes only what the same Probe convicted.** A different Probe holding on the same subject leaves the thread open.
- **`Warn` opens no *verdict* thread, and a hazard is the one `Warn` the Room does speak about.** `VerdictPolicy` still returns no action for `Warn`: a Room that threaded every decided-and-not-disqualifying verdict would shout. The hazard rule is not an exception to that — it opens a `ThreadSubject.Hazard`, which is a different subject and a different conversation, because the remedy is a Task rather than an argument about evidence. It is also not on the bus path: `SafetyReview` reads the findings from the Probe, since a verdict event carries no findings (see [Probe](probe.md)).
- **A hazard thread stays open until somebody does the work.** The mitigation Task is in the plan and nothing in Ampere knows it was performed, so nothing resolves the thread. The vent replay gate refuses a transcript where a hazard thread reads as resolved, for the same reason it refuses a resolved `Undetermined`.
- **A hazard review is quiet on a plan it has already seen.** Thread ids are derived from the finding and `MitigationPlan` is idempotent, so re-reviewing an unchanged plan opens no thread, posts no card and returns the graph unchanged. A plan edit that introduces a hazard produces exactly one new thread.
- **The DM is the AskHuman path.** `CoordinatorEscalation` calls `escalateToHuman(awaitReply = true)`, launched off the handler, so the thread's `WaitingForHuman` is durable before the Decision Emission is produced and the reply lands in the thread as the human. No fourth CHI path.
- **A reviewed role's card never reaches the Room unreviewed.** `ReviewGate` holds it until the reviewer's `ProbeReport`; `Holds`/`Warn` release, `Violated`/`Undetermined` withhold, and the reviewer's `Verdict` card is posted either way.
- **The review graph is acyclic.** `reviewsAreAcyclic()` over `Roster.all()`; pinned for `BlueprintRoster`.
- **Calibration is per category and reads are counted.** The Estimator reads the source once per distinct category and keeps both the baseline and the `Calibration` on every `CalibratedEstimate`, so the Room can show the multiplier and its sample count.
- **Waiting is never shortened.** A category's multiplier applies to that category only; nothing rescales `WAITING` to move a finish.
- **The standup's only Watts are the narrative.** `Standup.run` is deterministic apart from `StandupNarrator`; a narrator failure falls back to the template rather than failing the meeting.
- **A standup is a Meeting.** `MeetingEvent.MeetingStarted` and `MeetingCompleted` (primitives-only `MeetingOutcome`s) go through the door; the row is written when a `MeetingRepository` is supplied. `MeetingOrchestrator` is not used: its `scheduleMeeting` requires a future wall-clock time.
- **Blueprint vocabulary lives in prompts and fixtures.** No type in `roster`, `room` or `standup` names a part, a finish or a CFM. `RosterPrompts` and the eval fixture do.
- **Prompts are versioned.** Editing a prompt bumps its `PromptRef.version`; old text stays reachable from `RosterPrompts.text` until nothing references it.
- **An undetermined verdict never renders as resolved.** `RoomTranscript` and the vent replay gate both refuse it.

## Common operations

- **Author a roster** — `RosterConfig(host = planner.id, roles = listOf(planner, inspector), verifier = inspector.id)`; `@Serializable`, so it reads back out of the file the rest of a consumer's workflow is declared in. To assign verdict threads too, implement `Roster` and override `resolverFor`; nothing else on the interface is abstract.
- **Author one from Swift** — `RoleConfig.of(id:title:instructions:tools:reviews:execution:)` and `RosterConfig.of(host:roles:verifier:)` take plain `String` ids. `RoleId` is an inline value class, which the Objective-C export erases to `Any?` and whose companion it does not export, so the constructors are unreachable from Swift and these factories are the whole authoring surface. A bad roster still fails as a `require`, which terminates rather than throwing into Swift — validate before you build one there.
- **Give a seat a model and an effort** — `RoleConfig(..., execution = ExecutionAssignment(model = "…", effort = EffortLevel.HIGH))`, then pass each of that seat's calls through `routingContext.withSeat(seat)`. The consumer's `RelayConfig` turns the tag into a route: `RoutingRule.ByTag(ExecutionTags.model("…"), configuration)`.
- **Share one tool between two seats** — both declare it bare in `RoleConfig.tools`; the plan step names `seat.seatTools()`'s qualified id, and `roster.seatRunning(id)` resolves it back to the seat that runs it.
- **Open a Room** — `room.open(graph)`; the graph is the project plus its milestones. `RoomId.forProject(graph.project.canonId)` names it.
- **Post as a role or as the human** — `room.post(threadId, Author.Role(roster.scout.id), body, card)`; `Author.Human` for the person.
- **Bind verdicts** — `VerdictThreadBinding(room, roomId, roster, CoordinatorEscalation(AgentMessageApi(roster.host.value, …), scope), bus, scope, subjects).start()`.
- **Review a card** — `reviewGate.submit(roomId, threadId, Author.Role(planner), body, card)` → `Pending(reviewId, reviewer)`; then `reviewGate.review(reviewId, Author.Role(inspector), ProbeReport(...))`.
- **Run the standup** — `Standup(room, reviewGate, eventApi, narrator = LlmNarrator(provider)).run(graph, since, calibration, baseline, availability)`.
- **Check a plan for hazards** — `SafetyReview(room, roomId, roster, SafetyProbe(KeywordHazardClassifier), clock).review(WorkPlan(graph, lines))`; the outcome's `graph` is the plan with the mitigation Tasks in it, and the caller decides whether to adopt it (D21).
- **Read the Room** — `room.history(roomId)` for everything so far, `room.transcript(roomId)` for what happens next, `RoomTranscript.render(threads, messages)` for text.
- **Replay the vent fixture** — `:ampere-eval:jvmTest --tests "*BlueprintVentReplayTest*"`; the transcript is written to `ampere-eval/build/reports/blueprint/vent-transcript.md`.

## Anti-patterns

- *Adding a `Room` table or a subject column to `MessageThread`* — the ids already carry the identity, and a schema change would make every consumer's thread store migrate for a feature only Rooms use.
- *Posting a Room message through `AgentMessageApi.postMessage`* — it attributes every post to the one agent it was built for; a Room has six authors and the human.
- *A fourth "ask the human" primitive for the DM* — `escalateToHuman` already is the Decision Emission with a thread; wrap it, do not rival it (see [ChiProtocol](chi.md)).
- *Escalating a `Violated` verdict on first sight* — the assigned role gets one pass; asking the human before it has looked is the single-persona behaviour this layer exists to replace.
- *Naming a bare tool id in a step on a multi-seat roster* — it says what to run without saying who runs it, and the two seats sharing that tool make the step undispatchable. `seatRunning` answers null rather than guessing a seat, because picking one would silently run the work under the wrong prompt, model and effort.
- *A routing rule kind for a seat's model* — the relay already acts on a fact about a call through `RoutingRule.ByTag`, and a model id on an assignment is a consumer's alias as often as a provider's id. A sixth rule kind would have the framework resolving names only the consumer can resolve (see [CognitiveRelay](cognitive-relay.md)).
- *Treating a seat with no `execution` as "empty tags"* — `withSeat` returns the context untouched, so such a call routes exactly as the same call made outside a roster. A context carrying an empty tag set would still be a different value, and a rule set that grew a tag-count predicate would start treating a declined preference as a declared one.
- *Making a consumer invent a verifier* — a mandatory `verifier` turned "which agent fills a seat is the consumer's" into "every consumer ships an Inspector"; that was bug B15.
- *Falling the resolver back to the verifier or the host when the roster names none* — both already post in the verdict thread (the verifier the card, the host the "Asking the human" notice), and `VerdictThreadBinding.countPass` counts a post by the assigned role as a resolver pass. Either fallback would have the Room count its own bookkeeping as the seat having taken a look.
- *Reading `Warn` as a verdict thread* — it is decided and not disqualifying; a verdict thread for it would make the Room shout. A hazard gets a `ThreadSubject.Hazard` instead, keyed by the Task or line and the category, so `VerdictKind` stays the two values that convict.
- *Publishing a second `ProbeEvent.VerdictReached` from `SafetyReview`* — the Probe belongs in the host's `ProbeSuite`, which publishes every verdict in one place. Running the deterministic Probe twice is free; two verdict events for one plan edit is a trace that lies about how many times the plan was judged.
- *Resolving a thread on any `Holds`* — a sequence Probe holding says nothing about a part's lead time; match the Probe.
- *Keying calibration per person instead of per category* — one multiplier averages optimism about physical work into waiting that nobody can shorten.
- *Running the standup narrative for each re-plan* — re-plans between standups are graph work; the narrative is weekly and metered.
- *Putting `MeetingType.Standup` through `MeetingOrchestrator.scheduleMeeting`* — it rejects a time that is not in the future against `Clock.System`, so a "run now" standup cannot start that way.
- *Resolving a hazard thread because the Probe stopped warning* — it will not stop: the hazardous step is still in the plan, and a mitigated plan still warns.
- *Naming a part or finish in a `roster`/`room`/`standup` type* — the domain boundary lives in `RosterPrompts`; a type that knows what a duct is cannot serve the next kind of project.
