---
concept: GoalBranch
status: experimental
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/goal/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/GoalEvent.kt
  - ampere-core/src/commonMain/sqldelight/link/socket/ampere/db/field/GoalStore.sq
  - ampere-core/src/commonMain/sqldelight/link/socket/ampere/db/11.sqm
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/domain/arc/ChargePhase.kt
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/definition/project/ProjectState.kt
related: [SubjectRegistry, DomainCanon, PropelLoop, EventSerialBus, CognitionTrace]
last_verified: 2026-10-19
---

# Goal Branch

## What it is

The Field's **Goal** branch, persisted (AMPR-419, F-register row F8): the agent's own goals as
`GoalStore` rows, one per `GoalNode` of an Arc run's `GoalTree`, each with a parent, a sibling
position, a status, the Subject whose goal it is, and the event that formed it. A tracker's view
of the same work — a perceived `CanonWorkItem`, `CanonProject` or `CanonMilestone` — is not a
goal: it is Environment, linked to the goal by a `Mirrors` edge in the `RelationshipStore`.
`GoalBranch` is what makes a row a goal on the record: a `GoalEvent` through the one door first,
then the row with that event as its provenance.

## Why it exists

Before it, Charge built a `GoalTree` in memory that was gone when the run settled, the only other
goal type was the string-typed `ProjectState.Goal` (C28: a priority with no scale, a status with
no states), and nothing linked either to the tracker items a Plug perceives. Letting the tracker's
item *be* the goal re-creates the two-model problem inside the Field: the tracker's status and the
agent's intent would be one column, and whichever wrote last would win. Keeping them apart, joined
by an edge, means each can be read on its own and reconciled rather than merged.

## Where it lives

- `goal/GoalStore.kt` — `GoalStore`, `SqlDelightGoalStore`, `GoalRecord`, `GoalStatus`
- `goal/GoalBranch.kt` — formation and status moves through the door, tree read-back, `mirror`,
  `dependencies`, `goalRef` / `nodeId`, `GoalBranch.over(database, eventApi)`
- `goal/CanonGoalMirrors.kt` — the pure mapper: which canon types mirror a goal, and
  `dependsOn` → `Rule.Dependency`
- `agents/domain/event/GoalEvent.kt` — `GoalFormed`, `GoalUpdated`
- `sqldelight/.../db/field/GoalStore.sq`, `11.sqm` — schema and migration
- `domain/arc/ChargePhase.kt` (forms the tree), `FlowPhase.kt` (`onGoalCompleted`),
  `AmpereRuntime.kt` (`goalBranch`), `bridge/ArcSession.kt` and the CLI's `AmpereContext` (wiring)
- `propel/RosterHostedRun.kt` — a hosted run's one root goal (AMPR-473), wired by `rosterRunHost`
  and `api/AmpereFromEnvironment.kt`
- `jvmTest/.../goal/`, `domain/arc/ChargePhaseTest.kt`, `ArcGoalHandoffTest.kt`,
  `propel/HostedRunGoalBranchTest.kt`, `data/DatabaseSchemaManagerTest.kt` — behaviour pinned by tests

## Invariants

- The tracker's view mirrors a goal and never replaces it. A `Mirrors` edge runs **from** the
  entity's `environmentRef` **to** the goal; nothing copies a tracker's status, title or assignee
  onto a goal row.
- The canon end of an edge is the Link-qualified `canon/<link>/<id>` ref AMPR-418 defined
  (`field.observe.canonEnvironmentRef`), the same subject that entity's Observations are about.
  A `dependsOn` id is qualified by its item's own Link (`CanonGoalMirrors.dependencyRef`).
- Only `CanonWorkItem`, `CanonProject` and `CanonMilestone` mirror goals; any other canon type is
  `NotTrackerWorkException`. A mirror of a goal nothing formed is refused, not recorded dangling.
- Announce first, write second. A goal row's `created_by_event` is its `GoalFormed`, and its
  `updated_by_event` is the latest `GoalUpdated` (or the `GoalFormed` until one exists). Both ride
  under the run id on the envelope, so the run's trace carries them.
- An Arc run's goal is keyed `<run id>/<node id>`, because `GoalTreeBuilder` numbers every run
  from `goal-0`. The in-memory `GoalNode.id` and Flow's task ids are unchanged.
- Forming is idempotent per node: a goal already on the branch is neither announced nor written
  again, so re-forming a run's tree — or finishing one that failed partway — adds nothing.
- A goal's holder is the agent behind the branch's door, registered as a Subject before its first
  goal. For an Arc run that is the runtime's door (`ampere.arc-runtime`); for a hosted run it is
  the host seat's door, and the run's other seats are registered through `registerAgent`.
- A hosted run forms exactly one goal — its root, `<run id>/goal-0` — and never a goal per plan
  step (AMPR-473, F34; HostedRun invariant 27). LEARN moves it to `COMPLETED` or `ABANDONED`.
- `ABANDONED` is a state, not a judgment: the run that formed the goal ended without meeting it
  (failed, partial, stopped or cancelled). A held run's goal stays `OPEN`.
- Persistence never changes how a run goes. A failed write is logged; Charge falls back to the
  in-memory tree and Flow keeps ticking.
- `status` is not CHECKed in the schema, and an unknown status is reported by name
  (`UnknownGoalStatusException`), never read as another status.
- `Rule.Dependency` is produced, never evaluated here. A dependency whose work item mirrors no goal
  is returned in `DependencyMapping.unresolved`, not dropped.
- No weight, rank or priority is stored or derived on a goal (F28 is open).
- The branch lives in `link.socket.ampere.goal`, **not** in `field`: it imports the door, the
  database, the canon and `domain.arc`, which the leaf `field` package may not.

## Common operations

- **Persist an Arc run's goals** — pass `goalBranch = GoalBranch.over(database, eventApi)` to
  `AmpereRuntime` (or `AmpereRuntime.create`). Charge forms the tree; Flow completes goals.
- **Read a run's tree back** — `branch.tree(runId)`; `branch.records(runId)` for statuses.
- **Read the agent's open goals** — `branch.open()`; this is what `ProjectState.toPerceptionText`
  takes instead of the retired string goals.
- **Link a perceived tracker item** — `branch.mirror(canonEntity, GoalBranch.goalRef(runId, nodeId))`.
- **Turn a work item's dependencies into a rule** — `branch.dependencies(workItem)`.
- **Abandon a goal** — `branch.abandon(runId, nodeId)`; the same `GoalUpdated` path as `complete`.
- **Put a hosted run on the branch** — `rosterRunHost(goalBranch = { door -> GoalBranch.over(db, door) })`;
  `fromEnvironment` does this whenever it is given a `Database`.
- **Add a goal status** — add the `GoalStatus` value, and the `Field.GoalState` it maps to in
  `DefaultFieldService.toGoalState()`. No migration: the column is not CHECKed.

## Anti-patterns

- *Persisting `GoalNode.id` as the goal's id* — every run's root is `goal-0`, so the second run's
  goals would silently resolve to the first run's rows.
- *Writing the tracker's status onto the goal row* — that is the conflation this branch exists to
  prevent; reconcile the two by reading both ends of the `Mirrors` edge.
- *Reading `ProjectState.activeGoals`* — deprecated and unread since AMPR-419; it stays one release
  so stored states still decode.
- *Assuming `SparkBasedAgent` or `DefaultEscalationPolicy` read the string goal* — the ticket said
  so; neither does. `SparkBasedAgent.Project` only builds `ProjectState.blank`, and
  `DefaultEscalationPolicy`'s `projectState` is a `ProductState`.
- *Keying a mirror by the bare `CanonId`* — ids are only unique within a Link, so two trackers'
  `wi-1` would mirror as one item, and the edge would name a different subject than the item's
  Observations.
- *Putting the branch under `field/`* — `FieldBoundaryTest` fails for the same reason it would for
  the Subject registry's stores.
- *Merging `11.sqm` ahead of `10.sqm`* — SQLDelight runs a migration only for a database below its
  number, so any database opened in between is stamped past 10 and never gets 10's columns.
