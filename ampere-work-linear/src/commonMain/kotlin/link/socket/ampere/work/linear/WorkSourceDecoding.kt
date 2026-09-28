package link.socket.ampere.work.linear

import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.adapter.CanonConversionFailure

/**
 * Native JSON to this adapter's read model.
 *
 * Separate from [WorkItemCanonAdapter] on purpose: this is the *decode* step
 * (is the response the shape the adapter reads?), the canon adapter is the
 * *projection* step (does the native object become a `CanonWorkItem`?). They
 * report failures into different channels — a response that will not decode
 * fails the call, while an issue that will not project lands in
 * [link.socket.ampere.plug.spi.PerceivePage.partialFailures] with the rest of
 * the page intact.
 *
 * Every decode here is lenient about *extra* fields and strict about the ones
 * the adapter reads. Extra fields are the vendor adding capability; a missing
 * `id` or `status` is the vendor changing the contract.
 */
internal object WorkSourceDecoding {

    /**
     * One issue object into [WorkSourceIssue], or a typed projection failure.
     *
     * [WorkSourceIssue.blockedBy] and [WorkSourceIssue.stateHistory] stay null
     * unless the response actually carried `relations` / `stateHistory` —
     * a read that did not ask for relations must not look like a read that found
     * none.
     */
    fun issue(fields: JsonObject): Result<WorkSourceIssue> {
        val identifier = fields.stringOrNull("id")
            ?: return missing("id")
        val title = fields.stringOrNull("title")
            ?: return missing("title")
        val statusName = fields.stringOrNull("status")
            ?: return missing("status")

        val rawStatusType = fields.stringOrNull("statusType")
        val statusType = rawStatusType?.let { WorkItemStatusType.fromWire(it) }
        if (rawStatusType != null && statusType == null) {
            return malformed("statusType", "no known status type maps to '$rawStatusType'")
        }

        val updatedAt = fields.stringOrNull("updatedAt")?.let { raw ->
            parseInstant(raw) ?: return malformed("updatedAt", "not an ISO-8601 instant: '$raw'")
        }

        val relations = fields["relations"] as? JsonObject
        val blockedBy = relations?.let { present ->
            (present["blockedBy"] as? JsonArray).orEmpty().mapNotNull { member ->
                val blocker = member as? JsonObject ?: return@mapNotNull null
                blocker.stringOrNull("id")?.let { blockerId ->
                    WorkSourceIssueRef(
                        identifier = blockerId,
                        title = blocker.stringOrNull("title"),
                    )
                }
            }
        }

        val stateHistory = (fields["stateHistory"] as? JsonArray)?.let { spans ->
            spans.mapNotNull { member -> (member as? JsonObject)?.let { stateSpan(it) } }
        }

        return Result.success(
            WorkSourceIssue(
                identifier = identifier,
                uuid = fields.stringOrNull("uuid"),
                title = title,
                statusName = statusName,
                statusType = statusType,
                labels = fields.stringList("labels"),
                projectId = fields.stringOrNull("projectId"),
                projectName = fields.stringOrNull("project"),
                teamId = fields.stringOrNull("teamId"),
                teamName = fields.stringOrNull("team"),
                url = fields.stringOrNull("url"),
                updatedAt = updatedAt,
                blockedBy = blockedBy,
                stateHistory = stateHistory,
                native = fields,
            ),
        )
    }

    /**
     * A `{issues: [...], hasNextPage, cursor}` envelope: the issues that
     * decoded, plus a typed failure per issue that did not.
     *
     * A page never shrinks silently. An issue whose `statusType` the vendor
     * renamed comes back as a [CanonConversionFailure], and the other
     * forty-nine issues still reach the caller — the contract
     * [link.socket.ampere.plug.spi.PerceivePage.partialFailures] exists for.
     */
    fun issuePage(body: JsonObject, key: String = "issues"): DecodedPage<WorkSourceIssue> {
        val decoded = mutableListOf<WorkSourceIssue>()
        val failures = mutableListOf<CanonConversionFailure>()

        (body[key] as? JsonArray).orEmpty().forEach { member ->
            val fields = member as? JsonObject
            if (fields == null) {
                failures += CanonConversionFailure.MalformedField(
                    canonType = CanonType.WORK_ITEM,
                    field = key,
                    reason = "member is not a JSON object",
                )
                return@forEach
            }
            issue(fields).fold(
                onSuccess = { decoded += it },
                onFailure = { error ->
                    failures += (error as? WorkSourceDecodeException)?.failure
                        ?: CanonConversionFailure.MalformedField(
                            canonType = CanonType.WORK_ITEM,
                            field = key,
                            reason = error.message ?: error.toString(),
                        )
                },
            )
        }

        return DecodedPage(
            entities = decoded,
            failures = failures,
            nextCursor = body.nextCursor(),
        )
    }

    /** A `{comments: [...], hasNextPage, cursor}` envelope. */
    fun commentPage(body: JsonObject): DecodedPage<WorkSourceComment> {
        val decoded = mutableListOf<WorkSourceComment>()
        val failures = mutableListOf<CanonConversionFailure>()

        (body["comments"] as? JsonArray).orEmpty().forEach { member ->
            val fields = member as? JsonObject ?: return@forEach
            val id = fields.stringOrNull("id")
            val createdAt = fields.stringOrNull("createdAt")?.let { parseInstant(it) }
            if (id == null || createdAt == null) {
                failures += CanonConversionFailure.MalformedField(
                    canonType = CanonType.WORK_ITEM,
                    field = "comments",
                    reason = "a comment carried no readable id or createdAt",
                )
                return@forEach
            }
            decoded += WorkSourceComment(
                id = id,
                body = fields.stringOrNull("body") ?: "",
                createdAt = createdAt,
                parentId = fields.stringOrNull("parentId"),
                authorName = (fields["author"] as? JsonObject)?.stringOrNull("name"),
            )
        }

        return DecodedPage(decoded, failures, body.nextCursor())
    }

    private fun stateSpan(fields: JsonObject): WorkSourceStateSpan? {
        val state = fields["state"] as? JsonObject ?: return null
        val stateName = state.stringOrNull("name") ?: return null
        return WorkSourceStateSpan(
            stateName = stateName,
            stateType = state.stringOrNull("type")?.let { WorkItemStatusType.fromWire(it) },
            stateId = state.stringOrNull("id"),
            startedAt = fields.stringOrNull("startedAt")?.let { parseInstant(it) },
            endedAt = fields.stringOrNull("endedAt")?.let { parseInstant(it) },
        )
    }

    /**
     * A cursor only when the server said there is another page.
     *
     * Verified: the envelope carries `cursor` alongside `hasNextPage: true` and
     * omits it when the scan is done. Reading `cursor` without checking
     * `hasNextPage` would loop forever against a server that started echoing it.
     */
    private fun JsonObject.nextCursor(): String? =
        if ((this["hasNextPage"] as? JsonPrimitive)?.booleanOrNull == true) {
            stringOrNull("cursor")
        } else {
            null
        }

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    private fun parseInstant(raw: String): Instant? = runCatching { Instant.parse(raw) }.getOrNull()

    private fun <T> missing(field: String): Result<T> = Result.failure(
        WorkSourceDecodeException(
            CanonConversionFailure.MissingRequiredField(
                canonType = CanonType.WORK_ITEM,
                field = field,
                schema = WorkItemCanonAdapter.SCHEMA,
            ),
        ),
    )

    private fun <T> malformed(field: String, reason: String): Result<T> = Result.failure(
        WorkSourceDecodeException(
            CanonConversionFailure.MalformedField(
                canonType = CanonType.WORK_ITEM,
                field = field,
                reason = reason,
            ),
        ),
    )
}

/** One decoded page: what read, what did not, and where the scan continues. */
internal data class DecodedPage<T>(
    val entities: List<T>,
    val failures: List<CanonConversionFailure>,
    val nextCursor: String?,
)

/**
 * Carries a decode-time [CanonConversionFailure] out of
 * [WorkSourceDecoding.issue] so [WorkSourceDecoding.issuePage] can move it onto
 * `partialFailures` instead of failing the page.
 */
internal class WorkSourceDecodeException(
    val failure: CanonConversionFailure,
) : Exception("Work source decode failed: $failure")
