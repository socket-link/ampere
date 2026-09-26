package link.socket.ampere.work.linear

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import link.socket.ampere.agents.execution.tools.McpTool
import link.socket.ampere.agents.tools.mcp.protocol.McpToolDescriptor

/**
 * One tool this adapter depends on, and the parts of its schema it depends on.
 *
 * @property tool The remote tool name, exactly as the adapter calls it.
 * @property arguments Argument names that must appear in the tool's
 *   `inputSchema.properties`. Presence, not requiredness: the adapter's
 *   dependency is that the argument exists and is spelled this way.
 * @property argumentValues Enum members an argument's schema must still offer,
 *   keyed by argument name. Read from `properties.<arg>.enum` or, for an array
 *   argument, `properties.<arg>.items.enum`.
 */
data class WorkSourceToolPin(
    val tool: String,
    val arguments: Set<String>,
    val argumentValues: Map<String, Set<String>> = emptyMap(),
)

/**
 * The pinned MCP tool surface — the shape this adapter is written against,
 * asserted rather than assumed.
 *
 * ## Why pins exist
 *
 * MCP tool schemas here are **vendor-controlled and unversioned**: there is no
 * version to negotiate, no deprecation window, and no signal when an argument
 * is renamed. The AMPR-289 recon flagged that as an *Inferred* risk, and the
 * failure it produces is the quiet kind — a renamed `label` argument does not
 * error, it returns a page that ignored the filter, and the ready-queue starts
 * offering tickets from every wave. [verify] converts that into a loud failure
 * at wire-up: [LinearWorkSource.open] calls it before the adapter makes a
 * single call.
 *
 * Pins cover *requests*. Response shapes are pinned the only way they can be —
 * by decoding recorded responses in this module's tests — because `inputSchema`
 * says nothing about what comes back.
 */
object WorkSourceToolPins {

    const val LIST_ISSUES: String = "list_issues"
    const val GET_ISSUE: String = "get_issue"
    const val SAVE_ISSUE: String = "save_issue"
    const val LIST_COMMENTS: String = "list_comments"
    const val SAVE_COMMENT: String = "save_comment"

    /**
     * The `fields` members the ready-queue asks for on every
     * [LIST_ISSUES] call.
     *
     * Pinned as enum values because this argument is a closed vocabulary: the
     * server rejects a member it does not know, so a rename here breaks every
     * ready-queue read rather than degrading it.
     */
    val ISSUE_FIELDS: Set<String> = setOf(
        "id",
        "uuid",
        "title",
        "status",
        "statusType",
        "labels",
        "updatedAt",
        "url",
        "projectId",
        "team",
    )

    val PINS: List<WorkSourceToolPin> = listOf(
        // The ready-queue's over-approximating fetch. `label`, `state` and
        // `project` are the three predicates that push down; `fields` selects
        // the response shape; `cursor`/`limit` page it.
        WorkSourceToolPin(
            tool = LIST_ISSUES,
            arguments = setOf("label", "state", "project", "team", "limit", "cursor", "fields", "updatedAt"),
            argumentValues = mapOf("fields" to ISSUE_FIELDS),
        ),
        // Single-issue read. `includeRelations` is what makes blockers visible
        // at all — without it the blocker rule has nothing to evaluate.
        WorkSourceToolPin(
            tool = GET_ISSUE,
            arguments = setOf("id", "includeRelations"),
        ),
        // Every write to an issue: the transition and both label edits.
        // `addLabels`/`removeLabels` rather than `labels`, which replaces the
        // whole set and would silently drop a wave tag.
        WorkSourceToolPin(
            tool = SAVE_ISSUE,
            arguments = setOf("id", "state", "addLabels", "removeLabels"),
        ),
        // Claim arbitration reads every comment, ordered by creation.
        WorkSourceToolPin(
            tool = LIST_COMMENTS,
            arguments = setOf("issueId", "limit", "cursor", "orderBy"),
            argumentValues = mapOf("orderBy" to setOf(COMMENT_ORDER_BY)),
        ),
        WorkSourceToolPin(
            tool = SAVE_COMMENT,
            arguments = setOf("issueId", "body"),
        ),
    )

    /**
     * Checks an advertised tool surface against [PINS].
     *
     * Reports *everything* that drifted in one [WorkSourceFailure.SchemaDrift]
     * rather than failing at the first miss — a vendor renaming a family of
     * arguments should produce one diagnosis, not five round trips through the
     * same failure.
     *
     * @param schemasByTool Tool name to its `inputSchema`. A tool absent from
     *   the map is a missing tool; a tool present with a null schema has every
     *   pinned argument reported missing, because a schema-less tool is one the
     *   adapter cannot call with confidence.
     */
    fun verify(schemasByTool: Map<String, JsonObject?>): Result<Unit> {
        val missingTools = mutableSetOf<String>()
        val missingArguments = mutableMapOf<String, Set<String>>()
        val missingArgumentValues = mutableMapOf<String, Set<String>>()

        PINS.forEach { pin ->
            if (pin.tool !in schemasByTool) {
                missingTools += pin.tool
                return@forEach
            }

            val properties = schemasByTool[pin.tool]?.get("properties") as? JsonObject
            val absent = pin.arguments.filter { properties?.containsKey(it) != true }.toSet()
            if (absent.isNotEmpty()) missingArguments[pin.tool] = absent

            pin.argumentValues.forEach { (argument, expected) ->
                if (argument in absent) return@forEach
                val offered = (properties?.get(argument) as? JsonObject)?.enumValues().orEmpty()
                val gone = expected - offered
                if (gone.isNotEmpty()) missingArgumentValues["${pin.tool}.$argument"] = gone
            }
        }

        val drift = WorkSourceFailure.SchemaDrift(
            missingTools = missingTools,
            missingArguments = missingArguments,
            missingArgumentValues = missingArgumentValues,
        )

        return if (drift.isDrift) workSourceFailure(drift) else Result.success(Unit)
    }

    /** [verify] over the descriptors a [WorkSourceToolCaller] lists. */
    fun verifyDescriptors(descriptors: List<McpToolDescriptor>): Result<Unit> =
        verify(descriptors.associate { it.name to it.inputSchema })

    /**
     * [verify] over the tools a
     * [link.socket.ampere.plug.PlugContext] discovered, keyed by
     * [McpTool.remoteToolName] — the name on the wire, not the
     * `dependency:tool` local id.
     */
    fun verifyTools(tools: List<McpTool>): Result<Unit> =
        verify(tools.associate { it.remoteToolName to it.inputSchema as? JsonObject })

    /** The comment ordering the claim arbiter asks for. */
    internal const val COMMENT_ORDER_BY: String = "createdAt"

    /** An enum at the schema node itself, or on an array schema's `items`. */
    private fun JsonObject.enumValues(): Set<String> {
        val node = this["enum"] ?: (this["items"] as? JsonObject)?.get("enum")
        return (node as? JsonArray).orEmpty().mapNotNull { it.asContent() }.toSet()
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    private fun JsonElement.asContent(): String? = (this as? JsonPrimitive)?.contentOrNull
}
