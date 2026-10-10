package link.socket.ampere.agents.execution.dispatch

import kotlinx.serialization.Serializable

/**
 * Asks the forge whether a merge request already exists for a head branch.
 *
 * ### Why a lookup is a recovery primitive
 *
 * Merge-request creation is not idempotent — a blind retry opens a second pull
 * request for the same branch (AMPR-291 recon finding, cell note 16). A supervisor
 * killed around the creation call therefore cannot know whether it succeeded, and
 * the only safe move is to *query by head branch* before creating. "Query before
 * create" is the third clause of the pass's ordering principle.
 *
 * The same query answers a second question the pass cannot avoid: a branch with an
 * open merge request must not be deleted as orphaned residue, because the review
 * is the work. One read, two decisions — see
 * [DispatchDisposition.mayOpenMergeRequest] and [BranchVerdict.KEPT_MERGE_REQUEST].
 *
 * Separate from the forge provider that *creates* merge requests so that the
 * recovery path can be wired with a read-only credential, and so a test can answer
 * it without a network.
 */
interface MergeRequestLookup {

    /**
     * Every merge request whose head is [branch], in any state.
     *
     * Any state, not just open ones, because the two questions need different
     * answers: a *merged or closed* request still means creation happened and must
     * not be repeated, while only an *open* one owns the branch.
     *
     * An empty list means the forge was asked and said no. A failure means it was
     * not asked successfully — which the pass must not read as "none", because
     * deleting a branch on a failed lookup is the destructive direction.
     */
    suspend fun forHeadBranch(branch: String): Result<List<MergeRequestRef>>
}

/**
 * One merge request, as much of it as a recovery decision needs.
 *
 * @property number The forge's own number, when it reports one.
 * @property state Open requests own their branch; the rest only prove creation
 *   already happened.
 */
@Serializable
data class MergeRequestRef(
    val url: String,
    val number: Int? = null,
    val state: MergeRequestState = MergeRequestState.UNKNOWN,
)

/** A merge request's state, as far as recovery cares. */
@Serializable
enum class MergeRequestState {
    OPEN,
    MERGED,
    CLOSED,

    /**
     * The forge did not say. Treated as [OPEN] by every decision here: an unknown
     * state must not license deleting the branch out from under a live review.
     */
    UNKNOWN,
    ;

    /** Whether a request in this state still owns its head branch. */
    val ownsBranch: Boolean get() = this == OPEN || this == UNKNOWN
}

/** The request that owns [branch], preferring an open one. Null when none does. */
fun List<MergeRequestRef>.owningBranch(): MergeRequestRef? =
    firstOrNull { it.state.ownsBranch }

/** The request a recovery report should name: the owner if there is one, else the newest known. */
fun List<MergeRequestRef>.mostRelevant(): MergeRequestRef? = owningBranch() ?: firstOrNull()
