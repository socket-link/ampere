package link.socket.ampere.canon

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Ring 3 — Service. Typed declarations with minimal properties.
 *
 * These arrive over a non-OS Link: `Mcp`, `OAuthRest`, `FolderMount`, or `Cli`.
 * They have no platform binding at all, which makes the native payload the only
 * lossless record — Ring 3 adapters that write should be especially careful to
 * merge rather than replace.
 *
 * `Message` and `Note` were Ring 1 candidates. Neither has an Apple
 * assistant-schema domain, and on iOS neither has a public read API, so their
 * realistic sources are service Links (Slack, Twilio, Notion) and folder mounts
 * (an Obsidian vault). Housing `Note` here is why Ring 3's definition widened
 * from "Mcp/OAuthRest only" to "any non-OS Link".
 */

/** @property bodyText A bounded snippet, not the message's full text — see [CanonProse]. */
@Serializable
@SerialName("canon.message")
data class CanonMessage(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val bodyText: CanonProse,
    val sender: CanonPerson? = null,
    val conversationId: String? = null,
    val sentAt: Instant? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.MESSAGE
}

/** @property bodyText A bounded snippet, not the note's full text — see [CanonProse]. */
@Serializable
@SerialName("canon.note")
data class CanonNote(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val title: String? = null,
    val bodyText: CanonProse? = null,
    val tags: List<String> = emptyList(),
    val modifiedAt: Instant? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.NOTE
}

@Serializable
@SerialName("canon.ride")
data class CanonRide(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val status: CanonServiceStatus,
    val pickup: CanonPlace? = null,
    val dropoff: CanonPlace? = null,
    val requestedAt: Instant? = null,
    val providerStatus: String? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.RIDE
}

@Serializable
@SerialName("canon.order")
data class CanonOrder(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val status: CanonServiceStatus,
    val merchantName: String? = null,
    val placedAt: Instant? = null,
    val providerStatus: String? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.ORDER
}

@Serializable
@SerialName("canon.delivery")
data class CanonDelivery(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val status: CanonServiceStatus,
    val carrier: String? = null,
    val expectedAt: Instant? = null,
    val destination: CanonPlace? = null,
    val providerStatus: String? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.DELIVERY
}

/**
 * A coarse lifecycle shared by [CanonRide], [CanonOrder], and [CanonDelivery].
 *
 * Ring 3 types arrive from independent services with independent status
 * vocabularies (AMPR-252) — an untyped `status: String` meant an Arc written
 * against one provider's strings silently broke against another's. This is
 * the cross-provider lifecycle only; a provider's exact wording is preserved
 * verbatim in `providerStatus` for callers that need it.
 */
@Serializable
enum class CanonServiceStatus {
    @SerialName("requested")
    REQUESTED,

    @SerialName("in_progress")
    IN_PROGRESS,

    @SerialName("completed")
    COMPLETED,

    @SerialName("cancelled")
    CANCELLED,
}

@Serializable
@SerialName("canon.third_party_playlist")
data class CanonThirdPartyPlaylist(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val title: String,
    val trackCount: Int? = null,
    val ownerDisplayName: String? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.THIRD_PARTY_PLAYLIST
}

// -------------------------------------------------------------------------
// The knowledge-work wave (AMPR-262)
//
// Four nouns admitted in one deliberate reopening of the closed set, with
// provider-intersection evidence in `docs/ampr-262-knowledge-work-canon-wave.md`.
// `SPREADSHEET` was rejected (the file is already `CanonDocument(kind = SPREADSHEET)`;
// the missing concept was the grid, admitted as [CanonTable]), `ROADMAP` was
// rejected (a rendering of projects and milestones over time, not a noun), and
// `INITIATIVE` was deferred until a second provider exposes a durable object
// above the project.
// -------------------------------------------------------------------------

/**
 * A unit of assignable work — a Linear issue, a Jira issue, a GitHub issue.
 *
 * Intersection: Linear ∩ Jira ∩ GitHub Issues. All three carry a title, a
 * coarse lifecycle, an assignee, and labels; the shape is theirs, not an
 * invention.
 *
 * Lossy on every provider: `priority` (three incompatible scales — Linear's
 * 0–4 ordinal, Jira's named object, GitHub's labels-by-convention), `estimate`,
 * `parent`/`children`, and label colour, since [labels] keeps names only.
 *
 * @property dueAt **Lossy by type, not by omission.** Linear `dueDate` and Jira
 *   `duedate` are calendar dates with no time and no zone; adapters normalise to
 *   `00:00Z` on the named date, so a due date read back in a western zone shows
 *   as the previous evening. The verbatim provider value stays in the native
 *   payload. GitHub Issues has no due date at all.
 * @property projectId The project this work belongs to, or null. Null conflates
 *   *not in a project* with *the provider did not say* — an Arc must not read it
 *   as "definitely standalone". Resolvable only against entities from the same
 *   Link, and nothing guarantees the referenced [CanonProject] was ever
 *   perceived. Follows the [CanonEmailMessage.mailboxId] precedent.
 * @property description A bounded snippet, not the item's full description —
 *   see [CanonProse]. Three formats collapse to one field, each losing
 *   structure a flattening to prose cannot carry back: Jira's is **ADF
 *   (Atlassian Document Format), a structured JSON tree, not text** —
 *   flattening it drops mentions, panels, code blocks, and embedded media.
 *   GitHub's is GitHub-flavoured Markdown; Linear's is Markdown. Deferred at
 *   admission (AMPR-262) for failing the bulk rule — a GitHub issue body
 *   routinely exceeds the 32 KiB projection budget — until [CanonProse] gave
 *   it a bounded shape (AMPR-268). The full provider value survives losslessly
 *   in [CanonProvenance.nativePayload] regardless.
 */
@Serializable
@SerialName("canon.work_item")
data class CanonWorkItem(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val title: String,
    val status: CanonWorkStatus,
    val providerStatus: String? = null,
    val assignee: CanonPerson? = null,
    val projectId: CanonId? = null,
    val dueAt: Instant? = null,
    val labels: List<String> = emptyList(),
    val description: CanonProse? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.WORK_ITEM
}

/**
 * A time-boxed effort that work items belong to.
 *
 * Intersection: Linear `Project` ∩ GitHub `ProjectV2`, with a Jira **Epic** as
 * the third leg. A Jira *project* is deliberately not the counterpart — it is an
 * issue container with a key and a lead, closer to a Linear team, carrying
 * neither a status nor a target date.
 *
 * Lossy: Linear's `progress`, `scope`, `health`, `startDate`, `initiatives`, and
 * long-form `description` (distinct from [summary]); GitHub's entire custom-field
 * schema, `readme`, and `items`.
 *
 * @property status Coarsest mapping in the wave on GitHub, which has only
 *   `closed: Boolean` — `false` becomes [CanonWorkStatus.IN_PROGRESS] and so
 *   misreports a planned-but-unstarted project. GitHub leaves [providerStatus]
 *   null, which is the honest signal that the value was derived rather than
 *   observed.
 * @property targetDate **Lossy: Linear target dates carry a resolution.** A
 *   project can be due "Q3" or "2026 H2" (`targetDateResolution`), and projecting
 *   to an [Instant] silently promotes that to a precise timestamp. Adapters
 *   normalise a coarse resolution to the *last* day of the period, so a deadline
 *   is never reported earlier than the provider means it.
 * @property summary A bounded snippet, not the project's full description — see
 *   [CanonProse].
 */
@Serializable
@SerialName("canon.project")
data class CanonProject(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val name: String,
    val status: CanonWorkStatus,
    val providerStatus: String? = null,
    val targetDate: Instant? = null,
    val lead: CanonPerson? = null,
    val summary: CanonProse? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.PROJECT
}

/**
 * A dated checkpoint within a project.
 *
 * Intersection: Linear `ProjectMilestone` ∩ GitHub `Milestone` ∩ Jira `Version`.
 *
 * Lossy: Linear's `sortOrder` and `description`; GitHub's `open_issues`/
 * `closed_issues` and `creator`; Jira's `released`, `archived`, and `startDate`.
 *
 * There is deliberately no `status`. Two of the three providers have something
 * status-shaped and **they disagree on what it means** — GitHub's `closed` is a
 * housekeeping act, Jira's `released` is a shipping event, and Linear has
 * neither. A field whose producers mean different things by it is the exact harm
 * the coarse-lifecycle pattern exists to prevent. [targetDate] and
 * [progressFraction] answer every question a status would.
 *
 * @property progressFraction 0..1. **Observed on one provider, computed on two.**
 *   Linear reports `progress` weighted by issue estimates; GitHub and Jira have
 *   to derive `closed / (open + closed)`, which weights every issue equally. Two
 *   milestones at "0.5" from different Links are not comparable.
 * @property projectId Null on GitHub, whose milestones hang off a repository
 *   rather than a project. Same reference semantics as
 *   [CanonWorkItem.projectId].
 */
@Serializable
@SerialName("canon.milestone")
data class CanonMilestone(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val name: String,
    val targetDate: Instant? = null,
    val projectId: CanonId? = null,
    val progressFraction: Double? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.MILESTONE
}

/**
 * A named grid — a Sheets tab, a Notion database, a folder-mounted CSV.
 *
 * This is the real spreadsheet gap. The *file* is already
 * `CanonDocument(kind = DocumentKind.SPREADSHEET)`; what the canon lacked was the
 * grid inside it, which is why `SPREADSHEET` was rejected as a duplicate and this
 * type admitted in its place. [documentId] is what makes that rejection coherent:
 * one document can hold many tables, and this is the way back to the file.
 *
 * **Bulk rule.** Schema and counts ride the entity; content does not. [preview]
 * is bounded by [CanonTablePreview.bounded] and full rows resolve out of band
 * through [contentRef] — the same lesson as artwork: refs in canon, bytes at the
 * edge.
 *
 * @property columnNames **A name list, not a schema — the largest loss in the
 *   wave.** A Notion database's value is mostly in its column *types* (`select`,
 *   `relation`, `rollup`, `formula`), and canon keeps only names. Sheets and CSV
 *   have no column types at all, only a header row *by convention*. A typed
 *   column model is its own future admission.
 * @property rowCount Populated rows. Note that Sheets' `gridProperties.rowCount`
 *   is the **allocated** grid, not the populated one — an adapter reporting it
 *   verbatim claims a thousand rows for a three-row sheet.
 * @property contentRef Where the full rows live. Resolving it is an
 *   [link.socket.ampere.plug.spi.AssetResolver] concern; see [CanonAssetRef].
 */
@Serializable
@SerialName("canon.table")
data class CanonTable(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val title: String,
    val columnNames: List<String> = emptyList(),
    val rowCount: Int? = null,
    val preview: CanonTablePreview? = null,
    val documentId: CanonId? = null,
    val contentRef: CanonAssetRef? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.TABLE
}

/**
 * A bounded window onto a [CanonTable]'s rows — never the table's content.
 *
 * Enough to show a grid's shape and let a reader infer its column types; far too
 * little to be a data transfer. Build one with [bounded], which truncates to the
 * limits below and records that it did.
 *
 * **Why the bound is a factory and not a `require` in `init`.** Rejecting an
 * oversized preview at *decode* time would make an already-recorded trace
 * permanently undecodable, which is precisely the failure the `@SerialName`
 * stability invariant exists to prevent. A canon type must always decode;
 * bounding is a write-side concern. [isWithinBounds] exposes the rule so it is
 * testable rather than merely stated.
 *
 * @property rows Row-major cells, already truncated.
 * @property truncated True when [bounded] dropped rows, dropped columns, or cut a
 *   cell. A reader must not treat an untruncated preview as the whole table —
 *   [CanonTable.rowCount] is the count, this is only a window.
 */
@Serializable
data class CanonTablePreview(
    val rows: List<List<String>> = emptyList(),
    val truncated: Boolean = false,
) {

    /** Whether this preview honours the [bounded] limits. */
    val isWithinBounds: Boolean
        get() = rows.size <= MAX_ROWS &&
            rows.all { row -> row.size <= MAX_COLUMNS && row.all { it.length <= MAX_CELL_CHARS } }

    companion object {

        /** Enough rows to show shape; not enough to be a data transfer. */
        const val MAX_ROWS: Int = 5

        /** Above the median real table — wider grids truncate rather than blow the budget. */
        const val MAX_COLUMNS: Int = 12

        /** A Notion rich-text cell can hold a paragraph. */
        const val MAX_CELL_CHARS: Int = 120

        /**
         * Truncate [rows] to the limits above, flagging whether anything was cut.
         *
         * The only construction path adapters should use. Worst case is
         * [MAX_ROWS] × [MAX_COLUMNS] × [MAX_CELL_CHARS] = 7,200 UTF-16 units of
         * content. In bytes that peaks at ×3, not ×4 — a four-byte codepoint
         * costs two units, so the worst byte-per-unit ratio belongs to
         * three-byte BMP characters like CJK — giving 21,600 bytes, comfortably
         * inside the budget asserted in `CanonWorkEntitiesTest`.
         */
        fun bounded(rows: List<List<String>>): CanonTablePreview {
            val kept = rows.take(MAX_ROWS).map { row ->
                row.take(MAX_COLUMNS).map { it.takeCells() }
            }
            val truncated = rows.size > MAX_ROWS ||
                rows.any { row -> row.size > MAX_COLUMNS || row.any { it.length > MAX_CELL_CHARS } }

            return CanonTablePreview(rows = kept, truncated = truncated)
        }

        /**
         * [MAX_CELL_CHARS] units, without splitting a surrogate pair.
         *
         * A naive `take` can leave a trailing lone high surrogate, which is not
         * valid UTF-8 — it encodes as a replacement character, so the cell would
         * fail to round-trip through the very serialization these bounds exist
         * to protect.
         */
        private fun String.takeCells(): String = when {
            length <= MAX_CELL_CHARS -> this
            this[MAX_CELL_CHARS - 1].isHighSurrogate() -> substring(0, MAX_CELL_CHARS - 1)
            else -> substring(0, MAX_CELL_CHARS)
        }
    }
}

/**
 * A coarse lifecycle shared by [CanonWorkItem] and [CanonProject].
 *
 * The same abstraction as [CanonServiceStatus], one domain over: providers
 * disagree on status vocabulary, so an Arc written against one provider's strings
 * silently breaks against another's. A provider's exact wording is preserved
 * verbatim in `providerStatus`.
 *
 * The coarsening is not an Ampere invention — Linear performs it already. "In
 * Progress" and "In Review" are distinct statuses sharing one `statusType`; this
 * enum is the same operation, one notch coarser.
 *
 * Mapping, recorded so adapters do not re-derive it:
 *
 * | Member | Linear `statusType` | Jira | GitHub issue |
 * | -- | -- | -- | -- |
 * | [BACKLOG] | `backlog` | category `new`, status named "Backlog" | — |
 * | [TODO] | `unstarted`, `triage` | category `new` otherwise | `open` |
 * | [IN_PROGRESS] | `started` | `indeterminate` | `open` |
 * | [DONE] | `completed` | `done`, resolution not won't-do | `closed` + `completed` |
 * | [CANCELLED] | `canceled`, `duplicate` | `done` + won't-do resolution | `closed` + `not_planned`/`duplicate` |
 *
 * Two rules that are easy to get wrong:
 *
 * - Only Linear elevates *backlog* to a status category, but every provider can
 *   produce one as a named status. Match on the provider's status **name** first
 *   and fall back to its category. Where there is no signal at all, map to
 *   [TODO], never [BACKLOG] — [TODO] keeps work visible, and guessing [BACKLOG]
 *   hides it.
 * - Jira's category lies about abandoned work: a "Won't Do" issue is category
 *   `done`. Read `resolution`, not `statusCategory`, or cancelled work is counted
 *   as completed.
 */
@Serializable
enum class CanonWorkStatus {
    @SerialName("backlog")
    BACKLOG,

    @SerialName("todo")
    TODO,

    @SerialName("in_progress")
    IN_PROGRESS,

    @SerialName("done")
    DONE,

    @SerialName("cancelled")
    CANCELLED,
}
