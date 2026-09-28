package link.socket.ampere.work.linear

import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonProse
import link.socket.ampere.canon.CanonProvenance
import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.CanonWorkItem
import link.socket.ampere.canon.CanonWorkStatus
import link.socket.ampere.canon.NativePayload
import link.socket.ampere.canon.NativeSchema
import link.socket.ampere.canon.SourceHandle
import link.socket.ampere.canon.adapter.NativeFields
import link.socket.ampere.canon.adapter.ReadableCanonAdapter
import link.socket.ampere.link.LinkId
import link.socket.ampere.plug.spi.PerceiveQuery

/**
 * Work-source issue → [CanonWorkItem]. Read-only, by design.
 *
 * ## Read-only, and why
 *
 * [ReadableCanonAdapter] and not [link.socket.ampere.canon.adapter.WritableCanonAdapter]:
 * canon write-back is a preserve-and-merge of *canon* fields, and no write this
 * adapter makes is one. AMPR-314 did not change that. A supervisory status is
 * expressed as a state transition plus a label plus a comment — three native
 * writes against two objects — so `canonFields` could not name a field to merge
 * even now that the canon has a word for the result. Those writes go through
 * [WorkSourceIssueSink] as native commands, and
 * [LinearWorkSource.markStatus] is the typed entry point that composes them.
 * Declaring `ownedFields` here would claim a canon write footprint the adapter
 * does not have.
 *
 * ## The mapping
 *
 * | Canon field | Native source | Note |
 * | --- | --- | --- |
 * | `canonId` | `id` (the identifier, e.g. `AMPR-305`) | see below |
 * | `title` | `title` | |
 * | `status` | `statusType` + `status` + `labels` | composed — see [SupervisoryStatusMapping] |
 * | `providerStatus` | `status` (the state *name*, e.g. `In Progress`) | verbatim, for the statuses canon coarsens away |
 * | `labels` | `labels` | carries `wave:` and `gate:` conventions verbatim |
 * | `projectId` | `projectId` | |
 * | `dueAt` | `dueDate` | normalised to `00:00Z`, per `CanonWorkItem.dueAt` |
 * | `description` | `description` | bounded to [CanonProse.MAX_CHARS] |
 * | `dependsOn` | `relations.blockedBy[].id` | same-Link `CanonId`s |
 * | `assignee` | — | **not mapped**; see below |
 *
 * **`canonId` is the identifier, not the uuid.** A blocking relation names the
 * blocker by identifier and nothing else — verified: `relations.blockedBy`
 * members carry `id` and `title` only — so [CanonWorkItem.dependsOn] can only
 * hold identifier-derived ids, and a graph whose nodes were keyed on uuid would
 * never resolve an edge. Keying both on the identifier is what makes
 * `CanonWorkGraph` assemble. The cost is real and bounded: an issue that moves
 * team gets a new identifier and so a new `canonId`. The uuid survives on
 * [CanonProvenance.nativePayload].
 *
 * **`assignee` stays null.** All of this adapter's writes share one API
 * identity, so the assignee field cannot say which supervisor holds a ticket
 * (the AMPR-289 recon's gap M5) — that is what the claim comment is for.
 * Projecting a display name into a [link.socket.ampere.canon.CanonPerson] with
 * no handle and no provenance of its own would put a canon entity on the wire
 * that says less than the native field it came from.
 *
 * ## Three supervisory states project; the fourth needs a second read
 *
 * *Verifying*, *verdict-requested* and *escalated* are [CanonWorkStatus]
 * members as of AMPR-314, and this adapter composes each one the way
 * [SupervisoryStatusMapping] specifies: the state *name* separates
 * [CanonWorkStatus.VERIFYING] from [CanonWorkStatus.IN_PROGRESS] (both are
 * `started` to the provider), and a gate label carries the other two. All three
 * are in the issue object, so a plain projection reports them.
 *
 * [CanonWorkStatus.CLAIMED] is not, and cannot be. A claim is a **comment** —
 * that is the whole basis of the claim protocol, comments being the only thing
 * this provider totally orders — and a comment is a different object from the
 * issue, reached by a different tool. `projectFields` sees one native object, so
 * it passes no claim evidence and a claimed ticket projects as
 * [CanonWorkStatus.IN_PROGRESS]: true, just coarse, and the honest answer for a
 * read that did not look. [LinearWorkSource.readCanonWorkItem] is the projection
 * that does look, and [SupervisoryStatusMapping.claimEvidenceMatters] is how it
 * avoids paying for the comment scan when the answer cannot change.
 *
 * ## An unknown status type fails loudly
 *
 * A `statusType` this build does not know is a
 * [link.socket.ampere.canon.adapter.CanonConversionFailure.MalformedField], not
 * a guess. `status` is non-null on [CanonWorkItem], so the alternatives are a
 * typed failure or a fabricated lifecycle position, and a fabricated one would
 * have a supervisor dispatch against a state the vendor invented after this
 * build shipped. The page survives: the failure lands on
 * [link.socket.ampere.plug.spi.PerceivePage.partialFailures] with the other
 * issues intact.
 */
class WorkItemCanonAdapter(
    private val tools: WorkSourceToolCaller,
) : ReadableCanonAdapter<CanonWorkItem>() {

    override val canonType: CanonType = CanonType.WORK_ITEM

    override val nativeSchema: NativeSchema = SCHEMA

    override fun projectFields(
        fields: JsonObject,
        provenance: CanonProvenance,
    ): Result<CanonWorkItem> = NativeFields.project(fields, canonType, nativeSchema) { issue ->
        val rawStatusType = issue.requireString("statusType")
        val statusType = WorkItemStatusType.fromWire(rawStatusType)
            ?: issue.malformed<WorkItemStatusType>(
                "statusType",
                "no known status type maps to '$rawStatusType'; the work source's vocabulary " +
                    "has drifted from this build's",
            )

        val stateName = issue.optionalString("status")
        val labels = fields.stringList("labels")

        CanonWorkItem(
            canonId = CanonId(provenance.sourceHandle.nativeId),
            provenance = provenance,
            title = issue.requireString("title"),
            // `claimed` is left at its default: a claim is a comment, and this
            // projection has only the issue. See the class KDoc.
            status = SupervisoryStatusMapping.canonStatusFor(
                SupervisoryExpression(
                    stateName = stateName,
                    statusType = statusType,
                    labels = labels.toSet(),
                ),
            ),
            providerStatus = stateName,
            projectId = issue.optionalString("projectId")?.let(::CanonId),
            dueAt = issue.optionalString("dueDate")?.let { raw ->
                parseDueDate(raw) ?: issue.malformed("dueDate", "not a date or instant: '$raw'")
            },
            labels = labels,
            description = issue.optionalString("description")?.let { CanonProse.bounded(it) },
            dependsOn = fields.blockerIds(),
        )
    }

    /**
     * Re-reads the issue by handle, with relations, so a caller holding an
     * entity that shed its native payload can still get a complete base.
     *
     * Widened from `protected` deliberately. The framework's only caller of
     * `fetchNative` is [link.socket.ampere.canon.adapter.WritableCanonAdapter]'s
     * merge, and this adapter has no write path — so left protected the method
     * would be unreachable code satisfying an abstract member. Public, it is the
     * re-fetch a caller needs after projecting with `carryNativePayload = false`.
     */
    public override suspend fun fetchNative(handle: SourceHandle): Result<NativePayload> =
        tools.call(
            tool = WorkSourceToolPins.GET_ISSUE,
            arguments = toolArguments(
                "id" to handle.nativeId.asJson(),
                "includeRelations" to true.asJson(),
            ),
        ).mapCatching { result ->
            NativePayload(
                schema = SCHEMA,
                fields = result.jsonBody(WorkSourceToolPins.GET_ISSUE).getOrThrow(),
            )
        }

    /**
     * A due date is a calendar date with no time and no zone, so it normalises
     * to midnight UTC — the lossiness [CanonWorkItem.dueAt] already documents.
     * A full instant is accepted too, for a vendor that starts sending one.
     */
    private fun parseDueDate(raw: String): Instant? =
        runCatching { Instant.parse(raw) }.getOrNull()
            ?: runCatching { Instant.parse("${raw}T00:00:00Z") }.getOrNull()

    private fun JsonObject.stringList(key: String): List<String> =
        ((this[key] as? JsonArray) ?: JsonArray(emptyList()))
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun JsonObject.blockerIds(): List<CanonId> =
        (((this["relations"] as? JsonObject)?.get("blockedBy") as? JsonArray) ?: JsonArray(emptyList()))
            .mapNotNull { member -> (member as? JsonObject)?.stringOrNull("id")?.let(::CanonId) }

    companion object {

        /**
         * The native shape this adapter reads.
         *
         * Named once, here, and referenced from [WorkSourceDecoding] and
         * [WorkSourceIssueSink] too — the duplication [NativeSchema]'s KDoc
         * warns about is exactly what a `NativePayload` built in one file and
         * schema-checked in another produces.
         */
        val SCHEMA: NativeSchema = NativeSchema("LinearIssue")

        /**
         * The provenance label for anything this adapter reads or writes.
         * Coarse and never dispatched on, per [SourceHandle.sourceSystem].
         */
        const val SOURCE_SYSTEM: String = "mcp:linear"

        /**
         * The handle for one issue on one Link.
         *
         * [PerceiveQuery.linkId] is everything a handle needs beyond the
         * identifier, and `etag` is null because this provider issues no version
         * token at all — see [WorkSourceIssueSink.supportedPreconditions].
         */
        fun handleFor(linkId: LinkId, identifier: String): SourceHandle = SourceHandle(
            linkId = linkId,
            sourceSystem = SOURCE_SYSTEM,
            nativeId = identifier,
            etag = null,
        )
    }
}
