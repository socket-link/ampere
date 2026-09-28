package link.socket.ampere.work.linear

import kotlin.jvm.JvmInline
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject

/**
 * Identity of one supervisor *process*, and the only thing that distinguishes
 * two claimants.
 *
 * Every write this adapter makes shares one API identity, so the work source's
 * assignee field cannot say which supervisor claimed a ticket (the AMPR-289
 * recon's gap M5). The claim comment carries this instead — see
 * [SupervisoryComment].
 *
 * Colon-free and newline-free by construction, because
 * [SupervisoryComment.render] separates fields with `:` on one line: an id
 * carrying either would make its own claim comment ambiguous to parse, and an
 * ambiguous claim comment is a lost race that reads as a won one.
 */
@JvmInline
value class SupervisorInstanceId(val value: String) {
    init {
        require(value.isNotBlank()) { "SupervisorInstanceId must not be blank" }
        require(SupervisoryComment.FIELD_SEPARATOR !in value) {
            "SupervisorInstanceId must not contain '${SupervisoryComment.FIELD_SEPARATOR}': $value"
        }
        require('\n' !in value) { "SupervisorInstanceId must not contain a newline: $value" }
    }

    override fun toString(): String = value
}

/**
 * The work source's own notion of where an issue sits in its lifecycle,
 * independent of the workspace's state *names*.
 *
 * Two facts make this worth a type. First, [isOpen] is the whole of the
 * ready-queue's blocker rule: "no open blocker" needs *completed or cancelled*
 * to be decidable without knowing that this workspace happens to call its
 * terminal state "Done". Second, an unrecognised member is the loudest signal
 * of vendor drift the read path has, so [fromWire] returns null rather than
 * guessing — see [WorkItemCanonAdapter], which turns a null into a typed
 * projection failure.
 */
enum class WorkItemStatusType(val wireName: String) {
    TRIAGE("triage"),
    BACKLOG("backlog"),
    UNSTARTED("unstarted"),
    STARTED("started"),
    COMPLETED("completed"),
    CANCELED("canceled"),
    ;

    /** Not terminal: a blocker in this state still blocks. */
    val isOpen: Boolean get() = this != COMPLETED && this != CANCELED

    companion object {
        fun fromWire(wireName: String): WorkItemStatusType? =
            entries.firstOrNull { it.wireName == wireName }
    }
}

/**
 * A reference to another issue, as the work source reports it inside a
 * relation.
 *
 * @property identifier The referenced issue's human identifier, e.g.
 *   `AMPR-314`. It is all a relation carries — verified: `get_issue`'s
 *   `relations.blockedBy` members hold `id` and `title` and **no state** — so
 *   deciding whether a blocker is still open costs one read per blocker.
 * @property statusType Null until something resolves it. Never defaulted to a
 *   value: "we did not look" and "it is open" must not be the same fact.
 */
data class WorkSourceIssueRef(
    val identifier: String,
    val title: String? = null,
    val statusType: WorkItemStatusType? = null,
)

/**
 * One span an issue spent in one state, from the work source's own audit trail.
 *
 * Verified in the AMPR-289 probe and re-verified against a live `get_issue`
 * response: the work source records a timestamped span per state with no
 * transition guards on the writes that produce them. This is the free evidence
 * the claim protocol's interference check runs on — see
 * [ClaimInterference].
 *
 * @property endedAt Null on the current span, and only there.
 */
data class WorkSourceStateSpan(
    val stateName: String,
    val stateType: WorkItemStatusType? = null,
    val stateId: String? = null,
    val startedAt: Instant? = null,
    val endedAt: Instant? = null,
)

/**
 * One comment on an issue.
 *
 * @property createdAt **Server-assigned, millisecond-resolution, and the
 *   arbiter of every claim race.** Comments are append-only with a server-side
 *   total order (verified, AMPR-289), which is the one ordering guarantee this
 *   work source offers and the reason the claim protocol is comment-arbitrated
 *   rather than write-arbitrated.
 */
data class WorkSourceComment(
    val id: String,
    val body: String,
    val createdAt: Instant,
    val parentId: String? = null,
    val authorName: String? = null,
)

/**
 * One issue as this adapter reads it: native fields, projected into typed ones
 * where the adapter depends on them, with the verbatim object retained.
 *
 * This is the `T` of [WorkSourceIssueSource], not a canon entity. The canon
 * projection is [WorkItemCanonAdapter]'s job, and it runs off [native] — the
 * split [link.socket.ampere.plug.spi.PerceiveSource]'s KDoc describes, where
 * the source is the operation and the adapter is the projection underneath it.
 *
 * @property identifier The human identifier, e.g. `AMPR-305`. Both this and
 *   [uuid] address the same issue on every read and write tool, and both are
 *   kept: [identifier] is what a relation and a claim comment name, [uuid] is
 *   what survives an issue moving team.
 * @property statusName The workspace's own name for the current state, e.g.
 *   `In Progress`. This is what a transition writes and what the supervisory
 *   lifecycle rides in ([SupervisoryState]).
 * @property blockedBy **Null means "not read", not "none".** Relations arrive
 *   only when a read asks for them, so a list that defaulted to empty would let
 *   the ready-queue's blocker rule pass a ticket nobody ever checked. The
 *   distinction is load-bearing: [WorkSourceIssueEvaluator] fetches on null and
 *   trusts an empty list.
 * @property stateHistory Null on the same terms as [blockedBy]. The claim
 *   protocol will not revert a transition against a null history — see
 *   [ClaimInterference.HistoryUnavailable].
 * @property projectName The project's display name. Kept alongside [projectId]
 *   because the two reads disagree about which one they return — a listed issue
 *   carries the id, a fetched one carries both — and the query surface accepts
 *   either spelling. Same for [teamName] and [teamId].
 * @property native The verbatim issue object, carried onto
 *   [link.socket.ampere.canon.CanonProvenance.nativePayload] so the canon
 *   projection's losses stay recoverable.
 */
data class WorkSourceIssue(
    val identifier: String,
    val uuid: String? = null,
    val title: String,
    val statusName: String,
    val statusType: WorkItemStatusType? = null,
    val labels: List<String> = emptyList(),
    val projectId: String? = null,
    val projectName: String? = null,
    val teamId: String? = null,
    val teamName: String? = null,
    val url: String? = null,
    val updatedAt: Instant? = null,
    val blockedBy: List<WorkSourceIssueRef>? = null,
    val stateHistory: List<WorkSourceStateSpan>? = null,
    val native: JsonObject = JsonObject(emptyMap()),
) {
    /** The wave ids this issue is tagged with, per [WorkSourceLabels.WAVE_PREFIX]. */
    val waves: List<String> get() = labels.mapNotNull { WorkSourceLabels.waveId(it) }

    /** The gate labels stopping this issue, per [WorkSourceLabels.GATES]. */
    val gates: List<String> get() = labels.filter { it in WorkSourceLabels.GATES }
}
