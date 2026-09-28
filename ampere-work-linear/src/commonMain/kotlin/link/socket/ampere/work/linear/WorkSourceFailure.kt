package link.socket.ampere.work.linear

import link.socket.ampere.canon.CanonWorkStatus

/**
 * Why a work-source call could not complete, for the failures this adapter
 * itself defines.
 *
 * Closed, so a caller can `when` over it exhaustively — the same shape
 * [link.socket.ampere.plug.spi.ExecuteFailure] and
 * [link.socket.ampere.canon.adapter.CanonConversionFailure] take. Projection
 * failures are *not* re-expressed here: an issue that decodes as JSON but does
 * not project into canon comes back as a `CanonConversionFailure`, on
 * [link.socket.ampere.plug.spi.PerceivePage.partialFailures] rather than as a
 * failed call.
 */
sealed interface WorkSourceFailure {

    /**
     * The MCP server ran the tool and reported an error, or the transport
     * failed outright.
     *
     * @property tool The pinned tool name, so a drifted rename reads as the
     *   name this adapter asked for rather than the one the vendor now offers.
     */
    data class ToolCallFailed(
        val tool: String,
        val reason: String,
    ) : WorkSourceFailure

    /**
     * The call succeeded and the body was not the shape this adapter reads —
     * no text content, unparseable JSON, or a field of the wrong kind.
     *
     * Distinct from [ToolCallFailed] because the responses are as
     * vendor-controlled and unversioned as the request schemas (the AMPR-289
     * recon's *Inferred* risk), and "the server said no" and "the server said
     * something we cannot read" call for different fixes.
     */
    data class MalformedResponse(
        val tool: String,
        val reason: String,
    ) : WorkSourceFailure

    /**
     * The server's advertised tool surface no longer matches
     * [WorkSourceToolPins] — a tool or an argument this adapter depends on was
     * renamed or removed.
     *
     * Raised at [LinearWorkSource.open], before any call goes out, so vendor
     * drift fails loudly at wire-up instead of silently at the first claim.
     *
     * @property missingTools Pinned tools absent from the server's `tools/list`.
     * @property missingArguments Pinned argument names absent from a present
     *   tool's `inputSchema.properties`, keyed by tool.
     * @property missingArgumentValues Pinned enum members absent from a present
     *   argument's schema, keyed by `tool.argument`.
     */
    data class SchemaDrift(
        val missingTools: Set<String> = emptySet(),
        val missingArguments: Map<String, Set<String>> = emptyMap(),
        val missingArgumentValues: Map<String, Set<String>> = emptyMap(),
    ) : WorkSourceFailure {

        val isDrift: Boolean
            get() = missingTools.isNotEmpty() ||
                missingArguments.isNotEmpty() ||
                missingArgumentValues.isNotEmpty()
    }

    /**
     * An outbound write carried a term the caller forbade, and nothing was
     * written.
     *
     * Every issue and comment written to this work source syncs to a **public**
     * GitHub issue — verified in the AMPR-289 probe, where sandbox writes
     * mirrored to public issues #704–#709 — which makes "what may cross this
     * wire" part of the write contract rather than a caller's private concern.
     * The adapter supplies the check and no list: see
     * [WorkSourceIssueSink.forbiddenTerms].
     */
    data class ForbiddenTerm(
        val term: String,
    ) : WorkSourceFailure

    /**
     * A caller asked [LinearWorkSource.markStatus] for a canonical status whose
     * provider expression is a protocol rather than a write, and nothing was
     * written.
     *
     * Refusing is the point. [CanonWorkStatus.CLAIMED] and
     * [CanonWorkStatus.ESCALATED] are each composed with a comment that is
     * load-bearing — the one arbitrates a race, the other carries the reason a
     * human is being called — so writing the status half alone produces a ticket
     * that either reads back as [CanonWorkStatus.IN_PROGRESS] (a claim nobody
     * can see) or stops a human with no context. Same shape as
     * [link.socket.ampere.plug.spi.ExecuteFailure.PreconditionUnsupported]: the
     * loud refusal is the correct answer, not a gap to paper over.
     *
     * @property use The entry point that performs the protocol, per
     *   [SupervisoryStatusMapping.PROTOCOL_STATUSES].
     */
    data class StatusNeedsProtocol(
        val status: CanonWorkStatus,
        val use: String,
    ) : WorkSourceFailure
}

/**
 * Carries a [WorkSourceFailure] through [Result.failure], the role
 * [link.socket.ampere.plug.spi.ExecuteException] plays for
 * [link.socket.ampere.plug.spi.ExecuteFailure].
 */
class WorkSourceException(
    val failure: WorkSourceFailure,
) : Exception("Work source call failed: $failure")

/** Shorthand for the Result-typed failure path. */
fun <T> workSourceFailure(failure: WorkSourceFailure): Result<T> =
    Result.failure(WorkSourceException(failure))

/** One-line rendering, for hosts that surface a failure as text. */
fun WorkSourceFailure.describe(): String = when (this) {
    is WorkSourceFailure.ToolCallFailed ->
        "$tool failed: $reason"

    is WorkSourceFailure.MalformedResponse ->
        "$tool returned a body this adapter cannot read: $reason"

    is WorkSourceFailure.SchemaDrift -> buildString {
        append("The work source's MCP tool surface has drifted from the pinned expectations.")
        if (missingTools.isNotEmpty()) {
            append(" Missing tools: ${missingTools.sorted().joinToString()}.")
        }
        missingArguments.toSortedMap().forEach { (tool, arguments) ->
            append(" $tool is missing arguments: ${arguments.sorted().joinToString()}.")
        }
        missingArgumentValues.toSortedMap().forEach { (argument, values) ->
            append(" $argument is missing values: ${values.sorted().joinToString()}.")
        }
    }

    is WorkSourceFailure.ForbiddenTerm ->
        "The write carried the forbidden term \"$term\"; every write to this work source " +
            "syncs to a public issue, so nothing was written."

    is WorkSourceFailure.StatusNeedsProtocol ->
        "$status is expressed with a comment as well as a status, so it cannot be written " +
            "as a plain transition; use $use. Nothing was written."
}
