package link.socket.ampere.api.service

import link.socket.ampere.agents.domain.knowledge.KnowledgeRepository
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.api.AmpereExperimentalApi
import link.socket.ampere.api.internal.DefaultFieldService
import link.socket.ampere.db.Database
import link.socket.ampere.field.Field
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Subject

/**
 * SDK service for the Field: Ampere's world state, read as one value (AMPR-439, F6).
 *
 * ```
 * val field = ampere.field?.snapshot(viewer = Ref("agent-1"))?.getOrThrow()
 * field?.goals?.filter { it.state == Field.GoalState.OPEN }
 * ```
 *
 * A read over the stores the W2 doors write — Subjects, Relationships, Goals and Beliefs — not a
 * replay of the event log. Every snapshot is for a viewer (F12): beliefs another Subject holds
 * as `PRIVATE` or `PROTECTED` are left out in the store's own query, never filtered afterwards.
 *
 * Nothing here ranks or weighs anything. Signals are derived from a [Field] by its reader, and
 * how (F28) is open.
 *
 * Without an instance, build one with [over] from a database you already hold (AMPR-474).
 */
@AmpereExperimentalApi
interface FieldService {

    /**
     * The Field as [viewer] may see it, now.
     *
     * @param viewer Whose view; `null` for nobody's, which sees PUBLIC beliefs only.
     * @param beliefLimit At most this many beliefs, newest first. The bound applies to rows the
     *   viewer may see, so a full page is never short because of rows it may not. Must not be
     *   negative.
     */
    suspend fun snapshot(
        viewer: Ref<Subject>?,
        beliefLimit: Int = DEFAULT_BELIEF_LIMIT,
    ): Result<Field>

    companion object {
        /** How many beliefs a snapshot carries unless asked for another number. */
        const val DEFAULT_BELIEF_LIMIT: Int = 100

        /**
         * A Field reader over [database], for a caller that reads the Field outside a run and
         * composes no environment (AMPR-474, F35) — an overview or settings screen whose app
         * has the database open and no event bus, ticket repository or orchestrator to build.
         *
         * ```
         * val field = FieldService.over(database, knowledge, EventRepository(json, scope, database))
         *     .snapshot(viewer = Ref("agent-1"))
         *     .getOrThrow()
         * ```
         *
         * Every instance's [link.socket.ampere.api.AmpereInstance.field] is built here too, so
         * this is the same read, not a second one.
         *
         * @param database The Ampere database the Subject, Relationship, Goal, Observation and
         *   Commitment stores are read from.
         * @param knowledge Where the beliefs come from. Pass the repository your memory reads
         *   and writes — a memory-store override, when you have one — so the Field's beliefs are
         *   the ones recall sees. It must implement `findKnowledgeVisibleTo`; one that does not
         *   makes every snapshot fail rather than report no beliefs.
         * @param events The event log a snapshot is anchored to: [Field.asOfSequence] is its
         *   newest sequence, and the taken Actions are folded from its tool events. Build it over
         *   the same [database].
         */
        @AmpereExperimentalApi
        fun over(
            database: Database,
            knowledge: KnowledgeRepository,
            events: EventRepository,
        ): FieldService = DefaultFieldService.over(database = database, knowledge = knowledge, events = events)
    }
}
