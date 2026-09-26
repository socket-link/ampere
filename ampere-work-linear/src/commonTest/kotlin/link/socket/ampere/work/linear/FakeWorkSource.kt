package link.socket.ampere.work.linear

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import link.socket.ampere.agents.tools.mcp.protocol.McpToolDescriptor
import link.socket.ampere.agents.tools.mcp.protocol.ToolCallResult

/**
 * An in-memory work source that behaves the way the real one was *measured* to
 * behave, answering in the envelopes [Recorded] captured.
 *
 * Not a stub returning canned bodies: the five verified behaviours the adapter is
 * built on are modelled here, so a test exercises the protocol rather than a
 * script of it.
 *
 *  1. **Writes have no preconditions.** Any state to any state, no guards, no
 *     conflict — last write wins.
 *  2. **Comments are append-only with a server-assigned total order.** Each one
 *     gets the next millisecond from [clock]; nothing can be inserted before an
 *     existing comment.
 *  3. **State history is recorded automatically**, one span per state, with the
 *     previous span closed at the same instant the next opens.
 *  4. **Server-side filtering covers one positive label, a state, a project and a
 *     team — and nothing else.** [interfere] aside, this fake will happily return
 *     a blocked or gated issue from a ready-queue query, because the real one
 *     does.
 *  5. **A relation names a blocker and not its state**, so deciding "open" costs
 *     a read per blocker.
 *
 * [calls] records every call in order for request-level assertions, and
 * [interfere] and [failGetIssue] inject the two failure modes the claim protocol
 * has to survive.
 */
class FakeWorkSource(
    start: Instant = Instant.parse("2026-09-26T12:00:00Z"),
    private val descriptors: List<McpToolDescriptor> = Recorded.TOOL_SURFACE,
) : WorkSourceToolCaller {

    /** One issue, mutable the way the server's is. */
    class Issue(
        val identifier: String,
        var title: String = "Issue $identifier",
        var status: String = "Todo",
        var statusType: WorkItemStatusType = WorkItemStatusType.UNSTARTED,
        val labels: MutableList<String> = mutableListOf(),
        val blockedBy: MutableList<String> = mutableListOf(),
        var projectId: String? = "project-1",
        var projectName: String? = "Act 7",
        var teamName: String? = "Ampere",
        var dueDate: String? = null,
        var description: String? = null,
    ) {
        val uuid: String = "uuid-$identifier"
        val history: MutableList<Span> = mutableListOf()
        var updatedAt: Instant? = null
    }

    /** One span in an issue's state history. */
    class Span(
        val name: String,
        val type: WorkItemStatusType,
        val startedAt: Instant,
        var endedAt: Instant? = null,
    )

    /** One recorded call. */
    data class Call(val tool: String, val arguments: JsonObject)

    private val issues = linkedMapOf<String, Issue>()
    private val comments = linkedMapOf<String, MutableList<WorkSourceComment>>()
    private var now: Instant = start
    private var nextCommentId = 1

    val calls: MutableList<Call> = mutableListOf()

    /**
     * States a third party moves the issue through immediately after the next
     * transition lands — a human reaching for the ticket in the window between a
     * claimant's transition and its arbitration read.
     *
     * Consumed once. `listOf("Done")` produces
     * [ClaimInterference.MovedAway]; `listOf("In Review", "In Progress")`
     * produces [ClaimInterference.ExtraTransitions], where the ticket ends up
     * back in the claimed state and only the span count betrays the visit.
     */
    var interfere: List<String> = emptyList()

    /** Issues whose `get_issue` fails, for the blocker-read deferral path. */
    val failGetIssue: MutableSet<String> = mutableSetOf()

    /** Drops `stateHistory` from every response, as a server that stopped sending it would. */
    var omitStateHistory: Boolean = false

    /**
     * The comment page size when a caller names none — a server whose pages are
     * smaller than the thread, which is the case a claim arbiter must survive.
     */
    var commentPageSize: Int = DEFAULT_LIMIT

    /** Adds an issue, opening its first state span. */
    fun add(issue: Issue): Issue {
        issues[issue.identifier] = issue
        issue.history += Span(issue.status, issue.statusType, now)
        issue.updatedAt = now
        tick()
        return issue
    }

    /** Appends a comment the way the server does: next id, next millisecond. */
    fun addComment(issue: String, body: String): WorkSourceComment {
        val comment = WorkSourceComment(
            id = "comment-${nextCommentId++}",
            body = body,
            createdAt = now,
            authorName = "Supervisor",
        )
        comments.getOrPut(issue) { mutableListOf() } += comment
        tick()
        return comment
    }

    fun issue(identifier: String): Issue = requireNotNull(issues[identifier])

    fun comments(identifier: String): List<WorkSourceComment> = comments[identifier].orEmpty()

    /** Calls to one tool, in order. */
    fun callsTo(tool: String): List<Call> = calls.filter { it.tool == tool }

    /** Transitions written through [WorkSourceToolPins.SAVE_ISSUE], in order. */
    fun transitions(): List<String> = callsTo(WorkSourceToolPins.SAVE_ISSUE)
        .mapNotNull { it.arguments.string("state") }

    override suspend fun listTools(): Result<List<McpToolDescriptor>> = Result.success(descriptors)

    override suspend fun call(tool: String, arguments: JsonObject): Result<ToolCallResult> {
        calls += Call(tool, arguments)

        return when (tool) {
            WorkSourceToolPins.LIST_ISSUES -> ok(listIssues(arguments))
            WorkSourceToolPins.GET_ISSUE -> getIssue(arguments)
            WorkSourceToolPins.SAVE_ISSUE -> saveIssue(arguments)
            WorkSourceToolPins.LIST_COMMENTS -> ok(listComments(arguments))
            WorkSourceToolPins.SAVE_COMMENT -> saveComment(arguments)
            else -> Result.success(textResult("unknown tool $tool", isError = true))
        }
    }

    // -----------------------------------------------------------------
    // Tool handlers
    // -----------------------------------------------------------------

    private fun listIssues(arguments: JsonObject): JsonObject {
        val label = arguments.string("label")
        val state = arguments.string("state")
        val project = arguments.string("project")
        val team = arguments.string("team")
        val since = arguments.string("updatedAt")?.let { Instant.parse(it) }

        // The server filters on exactly these four, and nothing else. A gated or
        // blocked issue matching them comes straight back.
        val matching = issues.values.filter { issue ->
            (label == null || label in issue.labels) &&
                (state == null || state == issue.status) &&
                (project == null || project == issue.projectId || project == issue.projectName) &&
                (team == null || team == issue.teamName) &&
                (since == null || (issue.updatedAt?.let { it >= since } ?: false))
        }

        val limit = arguments["limit"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: DEFAULT_LIMIT
        val from = arguments.string("cursor")?.let { cursor ->
            matching.indexOfFirst { it.identifier == cursor }.takeIf { it >= 0 }?.plus(1) ?: 0
        } ?: 0
        val page = matching.drop(from).take(limit)
        val hasNext = from + page.size < matching.size

        return buildJsonObject {
            putJsonArray("issues") { page.forEach { add(listedIssue(it)) } }
            put("hasNextPage", hasNext)
            if (hasNext) put("cursor", page.last().identifier)
        }
    }

    private fun getIssue(arguments: JsonObject): Result<ToolCallResult> {
        val id = arguments.string("id") ?: return fail("no id")
        if (id in failGetIssue) return fail("unavailable")
        val issue = issues[id] ?: issues.values.firstOrNull { it.uuid == id }
            ?: return fail("not found")

        val includeRelations = (arguments["includeRelations"] as? JsonPrimitive)?.contentOrNull == "true"
        return ok(fetchedIssue(issue, includeRelations))
    }

    /**
     * Unconditional, and that is the point: no precondition argument exists to
     * honour, and two writes in a row both land.
     */
    private fun saveIssue(arguments: JsonObject): Result<ToolCallResult> {
        val id = arguments.string("id") ?: return fail("no id")
        val issue = issues[id] ?: issues.values.firstOrNull { it.uuid == id }
            ?: return fail("not found")

        arguments.string("state")?.let { state ->
            move(issue, state)
            val pending = interfere
            if (pending.isNotEmpty()) {
                interfere = emptyList()
                pending.forEach { move(issue, it) }
            }
        }
        arguments.strings("addLabels").forEach { if (it !in issue.labels) issue.labels += it }
        arguments.strings("removeLabels").forEach { issue.labels -= it }

        issue.updatedAt = now
        tick()
        return ok(fetchedIssue(issue, includeRelations = true))
    }

    private fun listComments(arguments: JsonObject): JsonObject {
        val id = arguments.string("issueId") ?: return buildJsonObject { putJsonArray("comments") { } }
        val all = comments[id].orEmpty()
        val limit = arguments["limit"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: commentPageSize
        val from = arguments.string("cursor")?.let { cursor ->
            all.indexOfFirst { it.id == cursor }.takeIf { it >= 0 }?.plus(1) ?: 0
        } ?: 0
        val page = all.drop(from).take(limit)
        val hasNext = from + page.size < all.size

        return buildJsonObject {
            putJsonArray("comments") { page.forEach { add(comment(it)) } }
            put("hasNextPage", hasNext)
            if (hasNext) put("cursor", page.last().id)
        }
    }

    private fun saveComment(arguments: JsonObject): Result<ToolCallResult> {
        val id = arguments.string("issueId") ?: return fail("no issueId")
        val body = arguments.string("body") ?: return fail("no body")
        val created = addComment(id, body)
        return ok(comment(created))
    }

    // -----------------------------------------------------------------
    // Projection into the recorded envelopes
    // -----------------------------------------------------------------

    private fun listedIssue(issue: Issue): JsonObject = buildJsonObject {
        put("id", issue.identifier)
        put("uuid", issue.uuid)
        put("title", issue.title)
        put("status", issue.status)
        put("statusType", issue.statusType.wireName)
        putJsonArray("labels") { issue.labels.forEach { add(it) } }
        issue.updatedAt?.let { put("updatedAt", it.toString()) }
        put("url", "https://example.invalid/issue/${issue.identifier}")
        issue.projectId?.let { put("projectId", it) }
        issue.teamName?.let { put("team", it) }
    }

    private fun fetchedIssue(issue: Issue, includeRelations: Boolean): JsonObject = buildJsonObject {
        listedIssue(issue).forEach { (key, value) -> put(key, value) }
        issue.projectName?.let { put("project", it) }
        put("dueDate", issue.dueDate?.let { JsonPrimitive(it) } ?: JsonNull)
        issue.description?.let { put("description", it) }
        if (!omitStateHistory) {
            putJsonArray("stateHistory") {
                issue.history.forEach { span ->
                    add(
                        buildJsonObject {
                            putJsonObject("state") {
                                put("id", "state-${span.name}")
                                put("name", span.name)
                                put("type", span.type.wireName)
                            }
                            put("startedAt", span.startedAt.toString())
                            put("endedAt", span.endedAt?.toString()?.let { JsonPrimitive(it) } ?: JsonNull)
                        },
                    )
                }
            }
        }
        if (includeRelations) {
            putJsonObject("relations") {
                putJsonArray("blocks") { }
                putJsonArray("blockedBy") {
                    issue.blockedBy.forEach { blocker ->
                        // A relation carries the blocker's identifier and title —
                        // and deliberately not its state.
                        add(
                            buildJsonObject {
                                put("id", blocker)
                                put("title", issues[blocker]?.title ?: blocker)
                            },
                        )
                    }
                }
                putJsonArray("relatedTo") { }
                put("duplicateOf", JsonNull)
            }
        }
    }

    private fun comment(comment: WorkSourceComment): JsonObject = buildJsonObject {
        put("id", comment.id)
        put("body", comment.body)
        put("createdAt", comment.createdAt.toString())
        put("updatedAt", comment.createdAt.toString())
        put("parentId", comment.parentId?.let { JsonPrimitive(it) } ?: JsonNull)
        putJsonObject("author") { put("name", comment.authorName ?: "Supervisor") }
    }

    // -----------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------

    /** Any state to any state, a closed span and a new one. No guards. */
    private fun move(issue: Issue, state: String) {
        if (issue.status == state) return
        issue.history.lastOrNull()?.endedAt = now
        issue.status = state
        issue.statusType = STATE_TYPES[state] ?: WorkItemStatusType.UNSTARTED
        issue.history += Span(state, issue.statusType, now)
        tick()
    }

    private fun tick() {
        now += 1.milliseconds
    }

    private fun ok(body: JsonObject): Result<ToolCallResult> =
        Result.success(textResult(body.toString()))

    private fun fail(reason: String): Result<ToolCallResult> =
        Result.success(textResult(buildJsonObject { put("error", reason) }.toString(), isError = true))

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    private companion object {
        const val DEFAULT_LIMIT = 50

        /** The workspace's state names, mapped to the types the server reports. */
        val STATE_TYPES: Map<String, WorkItemStatusType> = mapOf(
            "Backlog" to WorkItemStatusType.BACKLOG,
            "Todo" to WorkItemStatusType.UNSTARTED,
            "In Progress" to WorkItemStatusType.STARTED,
            "In Review" to WorkItemStatusType.STARTED,
            "Done" to WorkItemStatusType.COMPLETED,
            "Canceled" to WorkItemStatusType.CANCELED,
        )
    }
}
