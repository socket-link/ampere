package link.socket.ampere.api.service

import link.socket.ampere.api.AmpereExperimentalApi
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
    }
}
