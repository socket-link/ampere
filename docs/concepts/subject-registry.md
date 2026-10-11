---
concept: SubjectRegistry
status: experimental
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/subject/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/SubjectEvent.kt
  - ampere-core/src/commonMain/sqldelight/link/socket/ampere/db/field/**
  - ampere-core/src/commonMain/sqldelight/link/socket/ampere/db/9.sqm
  - ampere-core/src/jvmMain/kotlin/link/socket/ampere/data/DatabaseSchemaManager.kt
related: [HostedRun, TeamLayer, EventSerialBus, MemoryProvenance, Emission]
last_verified: 2026-10-11
---

# Subject Registry

## What it is

The registry of the Field's **Subjects** — agents, humans and groups — as lookupable rows,
with team membership as `MemberOf` edges and the Principal as the first Human row
(AMPR-416, F-register row F30). `SubjectStore` and `RelationshipStore` hold the rows;
`SubjectRegistry` is what makes a row a *registration*: a `SubjectEvent` published through
the one door first, then the row, with the event's id as its `Provenance.createdBy`.

## Why it exists

Before it, `AgentId` was a bare `String` with no registry, the public API used a team role
as the agent id (C44), no team id was ever issued (C51), and `EventSource.Human` was an
anonymous singleton — the only human identity anywhere was `InputProvided.respondedBy`.
A fold cannot resolve a holder it cannot look up, and the belief columns (F9) write
`holder` values that must resolve here. The alternative — keeping identity implicit in
whatever string a producer happened to stamp — is exactly what let an agent id be passed
where a goal id was expected and compile.

## Where it lives

- `subject/SubjectStore.kt` — `SubjectStore`, `SqlDelightSubjectStore`, `SubjectKind`
- `subject/RelationshipStore.kt` — `RelationshipStore`, `SqlDelightRelationshipStore`,
  `RelationshipKindCodec`
- `subject/SubjectRegistry.kt` — registration through the door, resolution, `PRINCIPAL_ID`
  and `SYSTEM_ID`, `AgentId.asSubjectRef()`, `EventSource.subjectRef()`
- `subject/SubjectSeed.kt` — the two seeded rows, run inside `DatabaseSchemaManager.ensure`
- `agents/domain/event/SubjectEvent.kt` — `SubjectRegistered`, `RelationshipRecorded`
- `sqldelight/.../db/field/SubjectStore.sq`, `RelationshipStore.sq`, `9.sqm` — schema,
  migration, seed and backfill
- `api/internal/DefaultAgentService.kt` — the first consumer: `team {}` registers,
  `listAll` / `inspect` read
- `jvmTest/.../subject/`, `api/AgentServiceRegistryTest.kt`,
  `data/DatabaseSchemaManagerTest.kt` — behaviour pinned by tests

## Invariants

- A `Ref<Subject.Agent>`'s value **is** the existing `AgentId` string. No translation table.
- The Principal's id is `EventSource.Human.ID` (`"human"`), so every stored event's human
  source resolves to the first Human row without a lookup of its own.
- `system` is a pseudo-Subject of kind `AGENT`; it exists on every database and is never
  listed by `SubjectRegistry.agents()` or `AgentService.listAll`.
- An id names one Subject. Re-registering it under the same kind returns the first row;
  under another kind it is `SubjectIdInUseException`. Nothing re-types a row.
- Registration publishes first, writes second. A row's `created_by` is the id of its
  `SubjectRegistered` / `RelationshipRecorded` event, except the seeded rows (`seed:…`)
  and the migration backfill (`migration:9`), which no event produced.
- Membership is a `MemberOf` edge from member to group and nothing else; `Subject.Group`
  has no member list.
- `kind` on `SubjectStore` is CHECKed to the three `Subject` branches; `kind` on
  `RelationshipStore` is not, because `RelationshipKind` is sealed so a later kind can
  carry data. An unknown edge kind is skipped by list reads and named by `get`.
- The registry lives in `link.socket.ampere.subject`, **not** in `field`: it imports the
  door and the database, which the leaf `field` package may not.

## Common operations

- **Register an agent** — `registry.registerAgent(agentId, displayName)`; idempotent.
- **Register a team** — `DefaultAgentService.registerTeamIfNeeded()`: each role as an
  agent under its role name, then `registerGroup(team.id, "team", members)`.
- **Resolve an event's source** — `registry.resolve(event.eventSource)`.
- **Resolve a responder** — `registry.resolveResponder(inputProvided.respondedBy)`; `null`
  is the Principal, a name must be a registered Human.
- **Add a migration after 9** — the belief columns took `10.sqm` (AMPR-417; `KnowledgeStore.holder` holds a Subject id, `system` for the backfill — a plain id, not a foreign key), and the goal branch owns `11.sqm`.

## Anti-patterns

- *Putting the stores under `field/`* — `FieldBoundaryTest` fails: the stores import
  `agents.events` and `db`, and `field` is the leaf everything else depends on.
- *Seeding only in `DatabaseSchemaManager`* — Android and iOS drivers never call it; `9.sqm`
  writes the same two rows with `OR IGNORE` so a database migrated elsewhere has them.
- *Listing `system` as an agent* — a fresh database would report one agent it does not have,
  and `StatusService.health` would stop saying "No agents configured".
- *Changing `EventSource.Human.ID`* — it is the Principal's primary key and the `source_id`
  of every human-sourced row already in `EventStore`.
- *Minting a fresh UUID per role* — the agent id of a DSL member is its role name (C44) on
  purpose; two runs over the same team must register the same Subjects.
