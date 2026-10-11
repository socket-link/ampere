package link.socket.ampere.subject

import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.SubjectEvent
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.field.Primitive
import link.socket.ampere.field.Provenance
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Relationship
import link.socket.ampere.field.RelationshipKind
import link.socket.ampere.field.Subject

/** The Field's handle for an agent: `Ref<Subject.Agent>` wraps the existing [AgentId] string (F30). */
fun AgentId.asSubjectRef(): Ref<Subject.Agent> = Ref(this)

/**
 * The Subject the source of an event resolves to: an agent's own id, or the Principal row for
 * the anonymous [EventSource.Human] (F30).
 */
fun EventSource.subjectRef(): Ref<Subject> = when (this) {
    is EventSource.Agent -> Ref(agentId)
    EventSource.Human -> SubjectRegistry.PRINCIPAL_REF
}

/**
 * The Subject registry (AMPR-416, F30): registration through the one door, resolution against
 * the store.
 *
 * [SubjectStore] and [RelationshipStore] hold rows. This class is what makes a row a
 * *registration*: it publishes a [SubjectEvent] through [AgentEventApi.publish] first, and only
 * then writes the row, with the event's id as the row's `Provenance.createdBy` and the envelope's
 * `recorded_at` as its `createdAt`. The event is the record; the row is the fold of it. A
 * registration whose event the door refused writes nothing and returns the door's failure.
 *
 * Two Subjects exist before any registration — the Principal and `system` — seeded by
 * `DatabaseSchemaManager` (and by `9.sqm` for databases migrated by another platform's driver).
 * They are the only rows whose provenance names no event.
 *
 * ## The Principal
 *
 * The Principal is the first Human row, registered under [PRINCIPAL_ID], which is
 * [EventSource.Human.ID]. That choice is what makes the anonymous human source every stored
 * event already carries resolve to a row with no translation: `EventSource.Human` *is* the
 * Principal ref, the way `Ref<Subject.Agent>` *is* the agent id. `InputProvided.respondedBy`
 * resolves through [resolveResponder]: a null responder is the Principal, and a named one must be
 * a registered Human.
 *
 * What a principal *is* — the device owner, a particular person, a delegated identity — remains
 * the D5 contract's question (`Principal` has only `Ambient`). This registers the one human the
 * system has always implicitly spoken to; it does not resolve identity.
 */
class SubjectRegistry(
    private val subjects: SubjectStore,
    private val relationships: RelationshipStore,
    private val eventApi: AgentEventApi,
) {

    /** Register an agent under its existing [AgentId]; idempotent for a known id. */
    suspend fun registerAgent(agentId: AgentId, displayName: String = agentId): Result<Subject.Agent> =
        register(agentId, SubjectKind.AGENT, displayName) { id, provenance ->
            Subject.Agent(Ref(id), displayName, provenance)
        }.map { it as Subject.Agent }

    /** Register a person; idempotent for a known id. */
    suspend fun registerHuman(id: String, displayName: String): Result<Subject.Human> =
        register(id, SubjectKind.HUMAN, displayName) { subjectId, provenance ->
            Subject.Human(Ref(subjectId), displayName, provenance)
        }.map { it as Subject.Human }

    /**
     * Register a group and its membership: one Group row, then one [RelationshipKind.MemberOf]
     * edge from each of [members] to it. Members are not registered here — a group is a set of
     * Subjects that already exist, and an edge to an unregistered member is the kind of dangling
     * ref the caller should notice.
     */
    suspend fun registerGroup(
        id: String,
        displayName: String,
        members: List<Ref<Subject>>,
    ): Result<Subject.Group> = runCatching {
        val group = register(id, SubjectKind.GROUP, displayName) { subjectId, provenance ->
            Subject.Group(Ref(subjectId), displayName, provenance)
        }.getOrThrow() as Subject.Group
        members.forEach { member -> relate(member, group.id, RelationshipKind.MemberOf).getOrThrow() }
        group
    }

    /**
     * Record one edge, announced first: a [SubjectEvent.RelationshipRecorded] through the door,
     * then the row with that event's provenance. The same edge twice is one row and one event
     * per attempt — the store ignores the duplicate, and the first record keeps its provenance.
     */
    suspend fun relate(
        from: Ref<Primitive>,
        to: Ref<Primitive>,
        kind: RelationshipKind,
    ): Result<Relationship<Primitive, Primitive>> {
        val existing = relationships.get(from, to, kind).getOrElse { return Result.failure(it) }
        if (existing != null) return Result.success(existing)

        val kindText = RelationshipKindCodec.encode(kind)
        val event = SubjectEvent.RelationshipRecorded(
            eventId = generateUUID("relationship-recorded", eventApi.agentId),
            timestamp = eventApi.clock.now(),
            eventSource = EventSource.Agent(eventApi.agentId),
            fromId = from.value,
            toId = to.value,
            kind = kindText,
        )
        val stored = eventApi.publish(event).getOrElse { return Result.failure(it) }
        return relationships.record(
            Relationship(
                from = from,
                to = to,
                kind = kind,
                provenance = Provenance(createdBy = event.eventId, createdAt = stored.recordedAt),
            ),
        )
    }

    /** The Subject [ref] names, or [UnknownSubjectException]. */
    suspend fun resolve(ref: Ref<Subject>): Result<Subject> =
        subjects.get(ref).mapCatching { it ?: throw UnknownSubjectException(ref.value) }

    /** The Subject behind an event's source: the agent's own row, or the Principal for a human. */
    suspend fun resolve(source: EventSource): Result<Subject> = resolve(source.subjectRef())

    /** The Principal — the first Human row. Fails only on a database nothing seeded. */
    suspend fun principal(): Result<Subject.Human> = resolve(PRINCIPAL_REF).mapCatching { subject ->
        subject as? Subject.Human
            ?: throw SubjectIdInUseException(PRINCIPAL_ID, SubjectKind.HUMAN, SubjectKind.of(subject))
    }

    /** The `system` pseudo-Subject: the F9 backfill target for memory rows with no known holder. */
    suspend fun system(): Result<Subject> = resolve(SYSTEM_REF)

    /**
     * Who answered an `InputProvided`: the Principal when [respondedBy] is null, otherwise the
     * registered Human it names. A responder nothing registered is [UnknownSubjectException], and
     * a responder that names a non-human row is refused rather than widened.
     */
    suspend fun resolveResponder(respondedBy: String?): Result<Subject.Human> {
        if (respondedBy == null) return principal()
        return resolve(Ref<Subject>(respondedBy)).mapCatching { subject ->
            subject as? Subject.Human ?: throw UnknownSubjectException(respondedBy)
        }
    }

    /**
     * Every registered agent, in registration order, without the `system` pseudo-Subject: that
     * row stands for the process, and listing it beside the agents would make every fresh
     * database report one agent it does not have.
     */
    suspend fun agents(): Result<List<Subject.Agent>> =
        subjects.list(SubjectKind.AGENT).map { list ->
            list.filterIsInstance<Subject.Agent>().filter { it.id.value != SYSTEM_ID }
        }

    /** Every registered group, in registration order. */
    suspend fun groups(): Result<List<Subject.Group>> =
        subjects.list(SubjectKind.GROUP).map { it.filterIsInstance<Subject.Group>() }

    /** The members of [group]: the sources of its `MemberOf` edges, in the order they joined. */
    suspend fun membersOf(group: Ref<Subject.Group>): Result<List<Ref<Subject>>> =
        relationships.to(group, RelationshipKind.MemberOf).map { edges -> edges.map { Ref<Subject>(it.from.value) } }

    /** The groups [member] belongs to: the targets of its `MemberOf` edges. */
    suspend fun groupsOf(member: Ref<Subject>): Result<List<Ref<Subject.Group>>> =
        relationships.from(member).map { edges ->
            edges.filter { it.kind == RelationshipKind.MemberOf }.map { Ref<Subject.Group>(it.to.value) }
        }

    private suspend fun register(
        id: String,
        kind: SubjectKind,
        displayName: String,
        build: (String, Provenance) -> Subject,
    ): Result<Subject> {
        val existing = subjects.get(Ref(id)).getOrElse { return Result.failure(it) }
        if (existing != null) {
            val existingKind = SubjectKind.of(existing)
            return if (existingKind == kind) {
                Result.success(existing)
            } else {
                Result.failure(SubjectIdInUseException(id, requested = kind, existing = existingKind))
            }
        }

        val event = SubjectEvent.SubjectRegistered(
            eventId = generateUUID("subject-registered", eventApi.agentId),
            timestamp = eventApi.clock.now(),
            eventSource = EventSource.Agent(eventApi.agentId),
            subjectId = id,
            kind = kind.name,
            displayName = displayName,
        )
        val stored = eventApi.publish(event).getOrElse { return Result.failure(it) }
        return subjects.register(build(id, Provenance(createdBy = event.eventId, createdAt = stored.recordedAt)))
    }

    companion object {
        /** The Principal's id: [EventSource.Human.ID], so the anonymous human source resolves without a lookup. */
        const val PRINCIPAL_ID: String = EventSource.Human.ID

        /** The pseudo-Subject for the process itself; the F9 backfill target. */
        const val SYSTEM_ID: String = "system"

        val PRINCIPAL_REF: Ref<Subject.Human> = Ref(PRINCIPAL_ID)
        val SYSTEM_REF: Ref<Subject.Agent> = Ref(SYSTEM_ID)

        /** What every seeded row's `created_by` says, since no event produced it. */
        const val SEED_PROVENANCE: String = "seed:subject-registry"
    }
}
