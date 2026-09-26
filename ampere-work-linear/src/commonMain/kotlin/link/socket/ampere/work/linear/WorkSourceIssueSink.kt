package link.socket.ampere.work.linear

import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.NativePayload
import link.socket.ampere.canon.NativeSchema
import link.socket.ampere.link.LinkId
import link.socket.ampere.plug.spi.ExecuteReceipt
import link.socket.ampere.plug.spi.ExecuteSink
import link.socket.ampere.plug.spi.WritePreconditionKind

/**
 * One write against the work source.
 *
 * A closed set, because each member is a tool call this adapter has pinned. It
 * is deliberately *native*, not canon: a transition into a state canon has no
 * member for, a claim comment, a gate label — none of them are expressible as a
 * [link.socket.ampere.canon.CanonWorkItem] field, which is why
 * [WorkSourceIssueSink.consumes] is empty and
 * [WorkItemCanonAdapter] has no write path.
 *
 * Labels are edited additively ([AddLabels] / [RemoveLabels]) and never
 * replaced wholesale. The work source offers a `labels` argument that replaces
 * the entire set, and using it would drop a ticket's `wave:` tag the first time
 * the supervisor added a gate label.
 */
sealed interface WorkSourceCommand {

    /** The issue to write to, by identifier or uuid. */
    val issue: String

    /** Move the issue to [state], by state name. */
    data class Transition(
        override val issue: String,
        val state: String,
    ) : WorkSourceCommand

    /** Append a comment. Markdown round-trips byte-identically (verified). */
    data class PostComment(
        override val issue: String,
        val markdown: String,
    ) : WorkSourceCommand

    /** Add labels, leaving existing ones alone. */
    data class AddLabels(
        override val issue: String,
        val labels: List<String>,
    ) : WorkSourceCommand

    /** Remove labels, leaving the rest alone. */
    data class RemoveLabels(
        override val issue: String,
        val labels: List<String>,
    ) : WorkSourceCommand
}

/**
 * Writes to the work source — the Execute half of this Plug.
 *
 * ## `supportedPreconditions` is empty, and that is a measurement
 *
 * The work source has **no conditional-write surface of any kind**: no
 * compare-and-swap, no if-match, no precondition parameter. Verified in the
 * AMPR-289 probe — two writes to the same issue both succeeded, last-write-wins,
 * and nothing was reported as a conflict. So this sink declares no
 * [WritePreconditionKind], the inherited [ExecuteSink.executeIf] refuses every
 * precondition with
 * [link.socket.ampere.plug.spi.ExecuteFailure.PreconditionUnsupported], and the
 * refusal is the correct answer rather than a gap to paper over.
 *
 * Declaring [WritePreconditionKind.MATCH_VERSION] here and emulating it with a
 * read-compare-write would be a lie the SPI names explicitly: a client-side
 * compare races between the compare and the write. The answer to the missing
 * capability is protocol-level arbitration —
 * [LinearWorkSource.claim] — not a precondition this provider cannot enforce.
 *
 * For the same reason every [ExecuteReceipt] here carries `handle.etag == null`.
 * The provider issues no version token, and `updatedAt` is not one: nothing
 * would check it at write time.
 *
 * ## Public mirror
 *
 * Every issue and comment written here syncs to a **public** GitHub issue —
 * verified, and part of the write contract rather than a deployment detail.
 * [forbiddenTerms] is the mechanism for that constraint and carries no policy:
 * it is empty by default, and the caller that knows which names must never
 * reach a public mirror supplies them.
 */
class WorkSourceIssueSink(
    private val tools: WorkSourceToolCaller,
    private val linkId: LinkId,
    /**
     * Substrings no outbound text may carry, matched case-insensitively. A hit
     * fails the write with [WorkSourceFailure.ForbiddenTerm] **before** it
     * leaves, because a comment cannot be unpublished from a public mirror.
     *
     * Empty by default. The adapter provides mechanism; naming the terms is the
     * supervisor's business, per AMPR-305's "no supervisor policy in the
     * adapter".
     */
    private val forbiddenTerms: Set<String> = emptySet(),
    private val clock: Clock = Clock.System,
) : ExecuteSink<WorkSourceCommand> {

    /** Empty: every command here is canon-external. See the class KDoc. */
    override val consumes: Set<CanonType> = emptySet()

    /** Empty, measured rather than assumed. See the class KDoc. */
    override val supportedPreconditions: Set<WritePreconditionKind> = emptySet()

    override suspend fun execute(command: WorkSourceCommand): Result<ExecuteReceipt> {
        screen(command)?.let { return workSourceFailure(it) }

        val schema = when (command) {
            is WorkSourceCommand.PostComment -> COMMENT_SCHEMA
            else -> WorkItemCanonAdapter.SCHEMA
        }

        val (tool, arguments) = when (command) {
            is WorkSourceCommand.Transition -> WorkSourceToolPins.SAVE_ISSUE to toolArguments(
                "id" to command.issue.asJson(),
                "state" to command.state.asJson(),
            )

            is WorkSourceCommand.PostComment -> WorkSourceToolPins.SAVE_COMMENT to toolArguments(
                "issueId" to command.issue.asJson(),
                "body" to command.markdown.asJson(),
            )

            is WorkSourceCommand.AddLabels -> WorkSourceToolPins.SAVE_ISSUE to toolArguments(
                "id" to command.issue.asJson(),
                "addLabels" to JsonArray(command.labels.map { JsonPrimitive(it) }),
            )

            is WorkSourceCommand.RemoveLabels -> WorkSourceToolPins.SAVE_ISSUE to toolArguments(
                "id" to command.issue.asJson(),
                "removeLabels" to JsonArray(command.labels.map { JsonPrimitive(it) }),
            )
        }

        val result = tools.call(tool, arguments).getOrElse { return Result.failure(it) }
        result.errorOrNull(tool)?.let { return workSourceFailure(it) }

        // Best-effort, deliberately. The write has landed by the time this runs,
        // so a response body this adapter cannot read costs it the post-write
        // state — not the receipt. Whether this work source echoes the written
        // object at all is unverified, which is exactly why a caller must treat
        // `postWriteState` as absent-tolerant rather than assume it is there.
        val body = result.jsonBody(tool).getOrNull()

        return Result.success(
            ExecuteReceipt(
                linkId = linkId,
                executedAt = clock.now(),
                handle = WorkItemCanonAdapter.handleFor(
                    linkId = linkId,
                    identifier = body?.stringOrNull("id") ?: command.issue,
                ),
                postWriteState = body?.let { NativePayload(schema = schema, fields = it) },
            ),
        )
    }

    /**
     * The forbidden-term check, over the one field of each command that carries
     * free text.
     *
     * State and label names are the supervisor's own vocabulary
     * ([SupervisoryState], [WorkSourceLabels]) and are not screened; a comment
     * body is the only place caller-composed prose reaches the wire.
     */
    private fun screen(command: WorkSourceCommand): WorkSourceFailure? {
        if (forbiddenTerms.isEmpty()) return null
        val text = (command as? WorkSourceCommand.PostComment)?.markdown ?: return null
        val haystack = text.lowercase()
        return forbiddenTerms
            .firstOrNull { it.isNotEmpty() && it.lowercase() in haystack }
            ?.let { WorkSourceFailure.ForbiddenTerm(it) }
    }

    companion object {
        /**
         * The native shape a posted comment comes back as — *not*
         * [WorkItemCanonAdapter.SCHEMA].
         *
         * The created object a comment write hands back is a comment, so tagging
         * its payload with the issue schema would put a receipt on the wire that
         * a `ReadableCanonAdapter.project` would happily schema-check and then
         * misread. [writtenIssue] rejects it for this reason.
         */
        val COMMENT_SCHEMA: NativeSchema = NativeSchema("LinearComment")
    }
}

/**
 * The post-write native state a receipt carries, when the provider returned one.
 *
 * Present so a caller can confirm a write landed without a Perceive round trip —
 * the gap AMPR-312 closed on [ExecuteReceipt]. Whether the work source echoes
 * the written object is *not* verified, so this is null-tolerant by design: a
 * caller that needs the state reads it and re-perceives when it is absent,
 * rather than one that assumes it is always there.
 */
val ExecuteReceipt.writtenIssue: WorkSourceIssue?
    get() = postWriteState
        ?.takeIf { it.schema == WorkItemCanonAdapter.SCHEMA }
        ?.let { WorkSourceDecoding.issue(it.fields).getOrNull() }
