package link.socket.ampere.work.linear

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.adapter.CanonConversionFailure
import link.socket.ampere.link.LinkId
import link.socket.ampere.plug.spi.PerceivePage
import link.socket.ampere.plug.spi.PerceivePredicate
import link.socket.ampere.plug.spi.PerceiveQuery
import link.socket.ampere.plug.spi.PerceiveSource
import link.socket.ampere.plug.spi.PredicateEvaluator

/**
 * The field names this source understands in a
 * [PerceivePredicate.Equals].
 *
 * Source-defined, as [PerceivePredicate]'s KDoc requires, and named here so a
 * caller writes a predicate against a constant rather than a literal. A field
 * not in this set is not an error — it comes back as residual and, if the
 * caller's evaluator does not know it either, excludes the candidate.
 */
object WorkSourceFields {

    /** A label the issue carries. Multi-valued: `Equals` means *contains*. */
    const val LABEL: String = "label"

    /** The state name, e.g. `Todo`. Pushes down. */
    const val STATE: String = "state"

    /** The project, by name, id, identifier or slug. Pushes down. */
    const val PROJECT: String = "project"

    /** The team, by name or id. Pushes down. */
    const val TEAM: String = "team"

    /** The [WorkItemStatusType] wire name, e.g. `started`. Client-side only. */
    const val STATUS_TYPE: String = "statusType"

    /** Every field this source or its evaluator can answer. */
    val ALL: Set<String> = setOf(LABEL, STATE, PROJECT, TEAM, STATUS_TYPE)
}

/** The relation kinds this source's evaluator understands. */
object WorkSourceRelations {

    /**
     * The issue has at least one blocking issue that is neither completed nor
     * cancelled.
     *
     * The kind carries "open" in its name because
     * [PerceivePredicate.HasNoRelation] takes no qualifier — "no *open* blocker"
     * is its own kind, not `HasNoRelation("blocked-by")` plus a state filter.
     * The string matches `ReadyQueueFixture`'s in `ampere-core`, which was
     * modelled on this queue.
     */
    const val BLOCKED_BY_OPEN: String = "blocked-by-open"
}

/**
 * The ready-queue rule, as predicates.
 *
 * Exactly the AMPR-289 verdict's rule — *carries `wave:<id>`, sits in the queued
 * state, is not gated, has no open blocker* — and it is three-quarters
 * inexpressible server-side. Only the first two terms (plus [project], when
 * given) push down; the negations and the relation come back residual and are
 * applied by [WorkSourceIssueEvaluator]. That split is the whole reason
 * [PerceivePage.isExact] exists: the probe that produced this rule got back a
 * blocked ticket and a gated ticket from the server-side query, and a caller
 * reading `entities` directly would have dispatched both.
 */
fun readyQueueRule(
    wave: String,
    queuedState: String? = SupervisoryState.READY.workSourceState,
    project: String? = null,
): List<PerceivePredicate> = buildList {
    add(PerceivePredicate.Equals(WorkSourceFields.LABEL, WorkSourceLabels.wave(wave)))
    queuedState?.let { add(PerceivePredicate.Equals(WorkSourceFields.STATE, it)) }
    project?.let { add(PerceivePredicate.Equals(WorkSourceFields.PROJECT, it)) }
    WorkSourceLabels.GATES.sorted().forEach { gate ->
        add(PerceivePredicate.Not(PerceivePredicate.Equals(WorkSourceFields.LABEL, gate)))
    }
    add(PerceivePredicate.HasNoRelation(WorkSourceRelations.BLOCKED_BY_OPEN))
}

/**
 * Reads issues out of the work source — the Perceive half of this Plug.
 *
 * ## What pushes down, and the one-label ceiling
 *
 * Server-side filtering covers project, state, team and **one positive label**
 * (verified). So the first [PerceivePredicate.Equals] on each of those four
 * fields is evaluated natively; a *second* label equality goes residual, because
 * the query surface has one `label` argument and sending the second would
 * silently drop the first. Label negation and relation absence are inexpressible
 * server-side and always residual.
 *
 * Every predicate the source cannot evaluate comes back on
 * [PerceivePage.residual] — never dropped, never a failure. `PerceiveSourceContract`
 * in `ampere-core-test-fixtures` holds this source to that.
 *
 * ## The time window is honoured exactly, not approximately
 *
 * [PerceiveQuery.window] has no residual channel, so it cannot be half-applied
 * and confessed. The query surface offers a lower bound only (`updatedAt`), so
 * this source pushes the bound down and applies the upper bound itself before
 * returning the page. An issue whose `updatedAt` is unreadable is excluded when
 * a window is set — a candidate whose position in time is unknown must not pass
 * a filter on time.
 */
class WorkSourceIssueSource(
    private val tools: WorkSourceToolCaller,
    private val linkId: LinkId,
) : PerceiveSource<WorkSourceIssue> {

    override val emits: Set<CanonType> = setOf(CanonType.WORK_ITEM)

    override suspend fun perceive(query: PerceiveQuery): Result<PerceivePage<WorkSourceIssue>> =
        if (query.ids.isEmpty()) scan(query) else fetchByIds(query)

    /**
     * Enumerates by predicate. One [WorkSourceToolPins.LIST_ISSUES] call per
     * page, with the pushed-down terms as arguments.
     */
    private suspend fun scan(query: PerceiveQuery): Result<PerceivePage<WorkSourceIssue>> {
        val pushDown = PushDown.from(query.predicates)

        val body = tools.call(
            tool = WorkSourceToolPins.LIST_ISSUES,
            arguments = toolArguments(
                "label" to pushDown.label.asJson(),
                "state" to pushDown.state.asJson(),
                "project" to pushDown.project.asJson(),
                "team" to pushDown.team.asJson(),
                "limit" to query.limit.asJson(),
                "cursor" to query.cursor.asJson(),
                "updatedAt" to query.window?.start?.toString().asJson(),
                "fields" to JsonArray(WorkSourceToolPins.ISSUE_FIELDS.map { JsonPrimitive(it) }),
            ),
        ).mapCatching { it.jsonBody(WorkSourceToolPins.LIST_ISSUES).getOrThrow() }
            .getOrElse { return Result.failure(it) }

        val decoded = WorkSourceDecoding.issuePage(body)
        val windowed = decoded.entities.filter { query.window == null || it.isWithin(query) }

        return Result.success(
            PerceivePage(
                entities = windowed,
                evaluated = pushDown.evaluated,
                residual = pushDown.residual,
                nextCursor = decoded.nextCursor,
                partialFailures = decoded.failures,
            ),
        )
    }

    /**
     * Re-fetches named issues, one [WorkSourceToolPins.GET_ISSUE] call each,
     * with relations so the blocker rule has something to evaluate.
     *
     * Every predicate comes back residual: an id list is not a filter, and the
     * caller's predicates still have to be applied to what it names.
     * [PerceivePage.unfiltered] is that answer, and it is always honest.
     */
    private suspend fun fetchByIds(query: PerceiveQuery): Result<PerceivePage<WorkSourceIssue>> {
        val entities = mutableListOf<WorkSourceIssue>()
        val failures = mutableListOf<CanonConversionFailure>()

        query.ids.forEach { id ->
            readIssue(tools, id).fold(
                onSuccess = { entities += it },
                onFailure = { error ->
                    failures += (error as? WorkSourceDecodeException)?.failure
                        ?: CanonConversionFailure.SourceUnavailable(
                            canonType = CanonType.WORK_ITEM,
                            nativeId = id,
                            reason = error.message ?: error.toString(),
                        )
                },
            )
        }

        return Result.success(
            PerceivePage.unfiltered(
                query = query,
                entities = entities.filter { query.window == null || it.isWithin(query) },
                partialFailures = failures,
            ),
        )
    }

    private fun WorkSourceIssue.isWithin(query: PerceiveQuery): Boolean {
        val window = query.window ?: return true
        val observed = updatedAt ?: return false
        return observed in window
    }

    /**
     * The push-down split: which predicates the query surface took, and which
     * it handed back.
     */
    private data class PushDown(
        val label: String?,
        val state: String?,
        val project: String?,
        val team: String?,
        val evaluated: List<PerceivePredicate>,
        val residual: List<PerceivePredicate>,
    ) {
        companion object {
            fun from(predicates: List<PerceivePredicate>): PushDown {
                var label: String? = null
                var state: String? = null
                var project: String? = null
                var team: String? = null
                val evaluated = mutableListOf<PerceivePredicate>()
                val residual = mutableListOf<PerceivePredicate>()

                predicates.forEach { predicate ->
                    if (predicate !is PerceivePredicate.Equals) {
                        residual += predicate
                        return@forEach
                    }

                    // Only the *first* equality on each field pushes down: the
                    // query surface has one argument per field, so a second value
                    // would overwrite the first and the page would claim to have
                    // filtered on a term it dropped.
                    val free = when (predicate.field) {
                        WorkSourceFields.LABEL -> label == null
                        WorkSourceFields.STATE -> state == null
                        WorkSourceFields.PROJECT -> project == null
                        WorkSourceFields.TEAM -> team == null
                        else -> false
                    }
                    if (!free) {
                        residual += predicate
                        return@forEach
                    }

                    when (predicate.field) {
                        WorkSourceFields.LABEL -> label = predicate.value
                        WorkSourceFields.STATE -> state = predicate.value
                        WorkSourceFields.PROJECT -> project = predicate.value
                        WorkSourceFields.TEAM -> team = predicate.value
                    }
                    evaluated += predicate
                }

                return PushDown(label, state, project, team, evaluated, residual)
            }
        }
    }
}

/** One [WorkSourceToolPins.GET_ISSUE] read, relations included. */
internal suspend fun readIssue(
    tools: WorkSourceToolCaller,
    issue: String,
): Result<WorkSourceIssue> =
    tools.call(
        tool = WorkSourceToolPins.GET_ISSUE,
        arguments = toolArguments(
            "id" to issue.asJson(),
            "includeRelations" to true.asJson(),
        ),
    ).mapCatching { result ->
        WorkSourceDecoding.issue(result.jsonBody(WorkSourceToolPins.GET_ISSUE).getOrThrow()).getOrThrow()
    }

/**
 * Applies the residual half of the ready-queue rule, client-side.
 *
 * ## It reads, so it can fail, so it defers
 *
 * [PredicateEvaluator.hasNoRelation] returns a `Boolean` and has no failure
 * channel, but answering "has no open blocker" on this work source costs reads:
 * one for the candidate's relations when it was listed rather than fetched, then
 * one per blocker, because a relation carries the blocker's identifier and
 * **not its state** (verified). When one of those reads fails, or when a
 * predicate names a field or relation this evaluator does not know, it answers
 * `false` and records the candidate in [deferred].
 *
 * `false` is the safe answer and [deferred] is what stops it from being a silent
 * one: excluding a candidate we could not evaluate means never dispatching
 * unverified work, and the record means the caller can tell "nothing is ready"
 * apart from "we could not tell". [ReadyQueue.isExact] is derived from it.
 *
 * ## Single-use, single-threaded
 *
 * Accumulates state and caches blocker states for the duration of one queue
 * read. Build one per [LinearWorkSource.readyQueue] call;
 * [link.socket.ampere.plug.spi.applyResidual] drives it sequentially.
 */
class WorkSourceIssueEvaluator(
    private val tools: WorkSourceToolCaller,
) : PredicateEvaluator<WorkSourceIssue> {

    private val deferrals = mutableListOf<DeferredCandidate>()
    private val blockerStates = mutableMapOf<String, WorkItemStatusType?>()

    /** Candidates excluded because a predicate could not be evaluated. */
    val deferred: List<DeferredCandidate> get() = deferrals.toList()

    override suspend fun matches(
        entity: WorkSourceIssue,
        predicate: PerceivePredicate.Equals,
    ): Boolean = when (predicate.field) {
        WorkSourceFields.LABEL -> predicate.value in entity.labels
        WorkSourceFields.STATE -> predicate.value == entity.statusName
        WorkSourceFields.STATUS_TYPE -> predicate.value == entity.statusType?.wireName

        // The query surface accepts a project by name, id, identifier or slug,
        // and an issue read carries at most the first two. A hit on either is a
        // match; anything else is undecidable rather than a miss — the value
        // could be a spelling of this very project that the read model cannot
        // see — so it defers instead of quietly excluding.
        WorkSourceFields.PROJECT ->
            matchOrDefer(entity, predicate, entity.projectId, entity.projectName, "project")

        WorkSourceFields.TEAM ->
            matchOrDefer(entity, predicate, entity.teamId, entity.teamName, "team")

        else -> {
            defer(entity, "no field named '${predicate.field}' can be evaluated client-side")
            false
        }
    }

    private fun matchOrDefer(
        entity: WorkSourceIssue,
        predicate: PerceivePredicate.Equals,
        id: String?,
        name: String?,
        what: String,
    ): Boolean {
        if (predicate.value == id || predicate.value == name) return true
        defer(
            entity,
            "'${predicate.value}' is not the $what id or name this issue reports " +
                "(${id ?: "no id"} / ${name ?: "no name"}), and the work source accepts " +
                "spellings this read model cannot resolve",
        )
        return false
    }

    override suspend fun hasNoRelation(
        entity: WorkSourceIssue,
        predicate: PerceivePredicate.HasNoRelation,
    ): Boolean {
        if (predicate.kind != WorkSourceRelations.BLOCKED_BY_OPEN) {
            defer(entity, "no relation named '${predicate.kind}' can be evaluated client-side")
            return false
        }

        val blockers = entity.blockedBy ?: readIssue(tools, entity.identifier).fold(
            onSuccess = { it.blockedBy ?: emptyList() },
            onFailure = { error ->
                defer(entity, "could not read relations: ${error.message ?: error.toString()}")
                return false
            },
        )

        blockers.forEach { blocker ->
            val state = blocker.statusType ?: blockerStates.getOrPut(blocker.identifier) {
                readIssue(tools, blocker.identifier).fold(
                    onSuccess = { it.statusType },
                    onFailure = { null },
                )
            }
            if (state == null) {
                defer(entity, "could not read the state of blocker ${blocker.identifier}")
                return false
            }
            if (state.isOpen) return false
        }

        return true
    }

    private fun defer(entity: WorkSourceIssue, reason: String) {
        deferrals += DeferredCandidate(entity.identifier, reason)
    }
}

/**
 * A candidate the client-side filter could not decide on, and why.
 *
 * Not a failure: the queue read succeeded, and this candidate is simply not in
 * it. The distinction matters to a supervisor deciding whether an empty queue
 * means "the wave is done" or "the work source went quiet mid-scan".
 */
data class DeferredCandidate(
    val issue: String,
    val reason: String,
)
