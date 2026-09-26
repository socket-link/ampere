package link.socket.ampere.work.linear

import kotlinx.datetime.Clock
import link.socket.ampere.agents.execution.tools.McpTool
import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.adapter.CanonConversionFailure
import link.socket.ampere.link.LinkDirection
import link.socket.ampere.link.LinkId
import link.socket.ampere.link.LinkRequirement
import link.socket.ampere.link.Transport
import link.socket.ampere.link.TransportRole
import link.socket.ampere.plug.McpServerDependency
import link.socket.ampere.plug.PlugContext
import link.socket.ampere.plug.PlugId
import link.socket.ampere.plug.PlugManifest
import link.socket.ampere.plug.permission.PlugPermission
import link.socket.ampere.plug.spi.ExecuteReceipt
import link.socket.ampere.plug.spi.PerceivePage
import link.socket.ampere.plug.spi.PerceiveQuery
import link.socket.ampere.plug.spi.applyResidual

/**
 * One page of the ready queue, with its own honesty about completeness.
 *
 * @property items Tickets that satisfied **every** term of the rule — the
 *   server-side terms and the client-side ones. Always safe to dispatch from,
 *   whatever [isExact] says.
 * @property isExact Whether this is the *whole* ready set for the page that was
 *   scanned. Derived, never set by hand: false when a candidate could not be
 *   evaluated ([deferred]) or an issue could not be projected
 *   ([partialFailures]).
 *
 *   Note the direction, which is the inverse of
 *   [PerceivePage.isExact]'s. A page from the source over-approximates, so its
 *   inexactness is a risk of dispatching too much. By the time the residual
 *   terms have been applied the risk has flipped: [items] can only be too
 *   *small*, and `isExact == false` means "there may be ready work missing from
 *   this list", never "some of this may not be ready".
 * @property deferred Candidates the client-side filter could not decide on.
 * @property nextCursor Feed back into the next [LinearWorkSource.readyQueue]
 *   call to continue the scan. Null at the end.
 */
data class ReadyQueue(
    val items: List<WorkSourceIssue>,
    val deferred: List<DeferredCandidate> = emptyList(),
    val partialFailures: List<CanonConversionFailure> = emptyList(),
    val nextCursor: String? = null,
) {
    val isExact: Boolean get() = deferred.isEmpty() && partialFailures.isEmpty()
}

/**
 * The work-source adapter: a Chassis SPI Plug over MCP, and the supervisor's
 * only door onto the issue tracker.
 *
 * ## What this is
 *
 * A Plug whose Link binds [Transport.MCP] — the only transport with
 * [Transport.hasImplementation] true, and the one [PlugContext.create] already
 * wires end to end. [source] is the [link.socket.ampere.plug.spi.PerceiveSource]
 * half (the ready queue, issue reads), [sink] is the
 * [link.socket.ampere.plug.spi.ExecuteSink] half (transitions, labels,
 * comments), and the methods here are the protocol built on top of them. Ratified
 * in the AMPR-289 verdict; a framework adapter, not bespoke CLI code, per
 * decision D2 on AMPR-286.
 *
 * It is all mechanism and no policy. It answers *what is ready*, *did I get this
 * ticket*, and *write this down*. Which ticket to dispatch, and when, belongs to
 * the Switchboard (AMPR-308).
 *
 * ## The canon mapping
 *
 * Work items project onto the Ring 3 [CanonType.WORK_ITEM] entity through
 * [WorkItemCanonAdapter], which is where the mapping table and its lossiness
 * live. The four supervisory lifecycle states canon has no member for ride in
 * `providerStatus` and `labels` until AMPR-314 lands — see [SupervisoryState].
 *
 * ## Three provider facts shape everything here
 *
 * 1. **No conditional writes.** Two writes to one issue both succeed,
 *    last-write-wins, no conflict reported (verified). So [claim] arbitrates
 *    with comments instead, and [sink] declares no
 *    [link.socket.ampere.plug.spi.WritePreconditionKind].
 * 2. **Comments are append-only with a server-assigned total order**
 *    (verified). That is the one ordering primitive available, and the whole
 *    claim protocol rests on it.
 * 3. **Every write syncs to a public issue** (verified). Part of the write
 *    contract — see [WorkSourceIssueSink.forbiddenTerms].
 *
 * ## Wiring
 *
 * Manual constructor injection, no container. Production wiring goes through
 * [open], which resolves the MCP client a [PlugContext] opened for
 * [DEPENDENCY_NAME] and verifies the pinned tool surface before returning —
 * so vendor drift fails at wire-up, not at the first claim.
 *
 * ```kotlin
 * val context = PlugContext.create(
 *     manifest = LinearWorkSource.manifest(serverUri),
 *     credentialBinding = credentialBinding,
 *     linkResolutionService = linkResolutionService,
 * ).getOrThrow()
 *
 * val workSource = LinearWorkSource.open(
 *     plugContext = context,
 *     linkId = resolvedLink.id,
 *     instanceId = SupervisorInstanceId("supervisor-$pid"),
 * ).getOrThrow()
 * ```
 */
class LinearWorkSource(
    private val tools: WorkSourceToolCaller,
    val linkId: LinkId,
    val instanceId: SupervisorInstanceId,
    forbiddenTerms: Set<String> = emptySet(),
    private val clock: Clock = Clock.System,
) {

    /** The Perceive half. Exposed so a caller can run its own queries. */
    val source: WorkSourceIssueSource = WorkSourceIssueSource(tools, linkId)

    /** The Execute half. Exposed so a caller can issue its own commands. */
    val sink: WorkSourceIssueSink = WorkSourceIssueSink(tools, linkId, forbiddenTerms, clock)

    /** Native → canon projection for anything this source reads. */
    val canonAdapter: WorkItemCanonAdapter = WorkItemCanonAdapter(tools)

    // -----------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------

    /**
     * The tickets in [wave] that are ready to dispatch.
     *
     * Over-approximating fetch, then client-side filtering: the query surface can
     * push down the wave label, the queued state and the project, and nothing
     * else. "Not gated" and "no open blocker" are applied here, by
     * [WorkSourceIssueEvaluator], at a cost of one read per candidate's relations
     * and one per blocker.
     *
     * @param queuedState The state a ready ticket sits in. Pass null to scan
     *   every state.
     */
    suspend fun readyQueue(
        wave: String,
        project: String? = null,
        queuedState: String? = SupervisoryState.READY.workSourceState,
        limit: Int? = null,
        cursor: String? = null,
    ): Result<ReadyQueue> {
        val query = PerceiveQuery(
            linkId = linkId,
            limit = limit,
            cursor = cursor,
            predicates = readyQueueRule(wave = wave, queuedState = queuedState, project = project),
        )

        val page = source.perceive(query).getOrElse { return Result.failure(it) }
        val evaluator = WorkSourceIssueEvaluator(tools)
        val exact = page.applyResidual(evaluator)

        return Result.success(
            ReadyQueue(
                items = exact.entities,
                deferred = evaluator.deferred,
                partialFailures = exact.partialFailures,
                nextCursor = exact.nextCursor,
            ),
        )
    }

    /** One issue, relations and state history included. */
    suspend fun readIssue(issue: String): Result<WorkSourceIssue> = readIssue(tools, issue)

    /**
     * Every comment on an issue, oldest first, across every page.
     *
     * Paging to exhaustion is not an optimisation choice — it is correctness. The
     * claim arbiter has to see *all* claim comments to find the earliest, and a
     * read that stopped at the first page could crown a later claimant and have
     * two processes both believe they own a ticket. The client-side sort is for
     * the same reason: the server's ordering argument is pinned, but the
     * *direction* is not documented, so nothing here relies on it.
     */
    suspend fun readComments(issue: String): Result<List<WorkSourceComment>> {
        val comments = mutableListOf<WorkSourceComment>()
        var cursor: String? = null
        var pages = 0

        while (true) {
            val body = tools.call(
                tool = WorkSourceToolPins.LIST_COMMENTS,
                arguments = toolArguments(
                    "issueId" to issue.asJson(),
                    "cursor" to cursor.asJson(),
                    "orderBy" to WorkSourceToolPins.COMMENT_ORDER_BY.asJson(),
                ),
            ).mapCatching { it.jsonBody(WorkSourceToolPins.LIST_COMMENTS).getOrThrow() }
                .getOrElse { return Result.failure(it) }

            val page = WorkSourceDecoding.commentPage(body)
            comments += page.entities
            cursor = page.nextCursor

            if (cursor == null) break
            if (++pages >= MAX_COMMENT_PAGES) {
                return workSourceFailure(
                    WorkSourceFailure.MalformedResponse(
                        tool = WorkSourceToolPins.LIST_COMMENTS,
                        reason = "the comment scan did not terminate within $MAX_COMMENT_PAGES pages",
                    ),
                )
            }
        }

        return Result.success(comments.sortedWith(compareBy({ it.createdAt }, { it.id })))
    }

    /**
     * An issue's state-transition history, oldest span first.
     *
     * Free evidence: the work source records a timestamped span per state with no
     * transition guards on the writes that produce them (verified). [claim] reads
     * it either side of its own transition to decide whether a revert is safe —
     * ratified rule B4 of the AMPR-291 verdict.
     */
    suspend fun readStateHistory(issue: String): Result<List<WorkSourceStateSpan>> =
        readIssue(issue).mapCatching { read ->
            read.stateHistory ?: throw WorkSourceException(
                WorkSourceFailure.MalformedResponse(
                    tool = WorkSourceToolPins.GET_ISSUE,
                    reason = "the response carried no stateHistory",
                ),
            )
        }

    // -----------------------------------------------------------------
    // Writes
    // -----------------------------------------------------------------

    /** Move an issue to a state, by state name. Unconditional — see [sink]. */
    suspend fun transition(issue: String, state: String): Result<ExecuteReceipt> =
        sink.execute(WorkSourceCommand.Transition(issue, state))

    /** Append a markdown comment. */
    suspend fun postComment(issue: String, markdown: String): Result<ExecuteReceipt> =
        sink.execute(WorkSourceCommand.PostComment(issue, markdown))

    /** Add labels without disturbing the ones already there. */
    suspend fun addLabels(issue: String, labels: List<String>): Result<ExecuteReceipt> =
        sink.execute(WorkSourceCommand.AddLabels(issue, labels))

    /** Remove labels without disturbing the rest. */
    suspend fun removeLabels(issue: String, labels: List<String>): Result<ExecuteReceipt> =
        sink.execute(WorkSourceCommand.RemoveLabels(issue, labels))

    /**
     * Take a ticket: the four-step comment-arbitrated claim, loser-revert
     * included.
     *
     * ```
     * 1. read      the issue, keeping its state and state history
     * 2. write     claim:<issue>:<instance>          ← the server timestamps it
     * 3. write     transition to the claimed state
     * 4. read      every comment; earliest claim wins
     *    won   → done
     *    lost  → re-read; revert only if nothing else moved, else defer (B4)
     * ```
     *
     * Order matters in step 2 and 3: the comment goes **first**, so the claim's
     * position in the total order is fixed before the ticket visibly changes. A
     * transition-first protocol would let a slower claimant with an earlier
     * comment still win, after a faster one had already started work.
     *
     * A failure between steps leaves a claim comment on the ticket and reports
     * the failure. That is deliberate and it is idempotent: this instance's own
     * orphaned comment is still its claim, so a retry re-reads the same total
     * order and reaches the same verdict, rather than racing itself.
     *
     * @param claimedState The state a claimed ticket moves to. Defaults to the
     *   ratified [SupervisoryState.CLAIMED] mapping.
     */
    suspend fun claim(
        issue: String,
        claimedState: String = SupervisoryState.CLAIMED_STATE,
    ): Result<ClaimOutcome> {
        // 1. The pre-read. Both the revert target and the interference baseline.
        val before = readIssue(issue).getOrElse { return Result.failure(it) }
        val claim = SupervisoryComment.Claim(before.identifier, instanceId)

        // 2. Stake the claim, and let the server timestamp it.
        postComment(issue, claim.render()).getOrElse { return Result.failure(it) }

        // 3. Take the ticket.
        transition(issue, claimedState).getOrElse { return Result.failure(it) }

        // 4. Read the total order back and see who was first.
        val comments = readComments(issue).getOrElse { return Result.failure(it) }
        val claims = comments.claimsFor(before.identifier)
        val winner = claims.minWithOrNull(CLAIM_ORDER)

        if (winner != null && winner.instanceId == instanceId) {
            return Result.success(
                ClaimOutcome.Won(issue = before.identifier, claim = winner, state = claimedState),
            )
        }

        // Lost — or, with no claim comment readable at all, unable to prove
        // otherwise. Either way this instance must not hold the ticket, and the
        // revert is the part that has to be careful.
        val after = readIssue(issue).getOrElse { return Result.failure(it) }
        val interference = detectInterference(before, after, claimedState)

        if (interference != null) {
            return Result.success(
                ClaimOutcome.Deferred(
                    issue = before.identifier,
                    winner = winner,
                    interference = interference,
                    claimedState = claimedState,
                ),
            )
        }

        transition(issue, before.statusName).getOrElse { return Result.failure(it) }

        return Result.success(
            ClaimOutcome.Lost(
                issue = before.identifier,
                winner = winner,
                revertedTo = before.statusName,
            ),
        )
    }

    /**
     * Hand a ticket to a human: the [WorkSourceLabels.GATE_ESCALATED] label plus
     * an `esc:` comment carrying the reason.
     *
     * The ticket's state is left exactly where it is — the ratified
     * [SupervisoryState.ESCALATED] mapping changes no state, because where the
     * work stopped is the most useful thing about an escalated ticket. The label
     * is what takes it out of [readyQueue]; the comment is what tells the human
     * why.
     *
     * The body is screened against [WorkSourceIssueSink.forbiddenTerms] before it
     * leaves, and the comment lands on a public mirror.
     */
    suspend fun escalate(issue: String, reason: String): Result<ExecuteReceipt> {
        val identifier = readIssue(issue).getOrElse { return Result.failure(it) }.identifier
        val comment = SupervisoryComment.Escalation(identifier, instanceId, reason)

        postComment(issue, comment.render()).getOrElse { return Result.failure(it) }

        return addLabels(issue, listOf(WorkSourceLabels.GATE_ESCALATED))
    }

    /**
     * Mark a ticket as waiting on a human verdict:
     * [SupervisoryState.VERDICT_REQUESTED]'s state plus its gate label.
     */
    suspend fun requestVerdict(issue: String): Result<ExecuteReceipt> {
        val state = SupervisoryState.VERDICT_REQUESTED
        state.workSourceState?.let { target ->
            transition(issue, target).getOrElse { return Result.failure(it) }
        }
        return addLabels(issue, listOfNotNull(state.label))
    }

    private fun List<WorkSourceComment>.claimsFor(identifier: String): List<ClaimRecord> =
        mapNotNull { comment ->
            (SupervisoryComment.parse(comment.body) as? SupervisoryComment.Claim)
                ?.takeIf { it.issue == identifier }
                ?.let { ClaimRecord(it, comment.id, comment.createdAt) }
        }

    companion object {

        /**
         * The name the manifest's MCP dependency and Link requirement share.
         *
         * They must match: [PlugContext.create] resolves the Link for a
         * dependency by looking up the requirement of the same name, and a
         * mismatch surfaces as
         * [link.socket.ampere.plug.UnresolvedPlugLinkException] on a
         * per-server failure rather than as a build error.
         */
        const val DEPENDENCY_NAME: String = "work-source"

        /**
         * `PlugId` is constrained to `[a-z0-9_-]+` so it is addressable by the
         * consent ledger and the marketplace — hence a slug, not a package name.
         */
        val PLUG_ID: PlugId = PlugId("work-source-linear")

        /** Comment scans terminate; a server that never stops paging fails loudly. */
        private const val MAX_COMMENT_PAGES = 100

        /**
         * The Plug manifest for a work source at [serverUri].
         *
         * The Link requirement binds [Transport.MCP] in the
         * [TransportRole.CONSUMER] role and [LinkDirection.READ_WRITE] — this
         * adapter both reads the queue and writes claims, so a read-only Link
         * must fail resolution rather than fail at the first claim.
         *
         * [PlugManifest.emits] names [CanonType.WORK_ITEM] and nothing else, and
         * the manifest is *not* canon-external: the Perceive side really does
         * produce a canon type. [PlugManifest.consumes] is empty because every
         * command is native — see [WorkSourceCommand].
         */
        fun manifest(
            serverUri: String,
            version: String = "1.0.0",
        ): PlugManifest = PlugManifest(
            id = PLUG_ID,
            name = "Work Source (Linear over MCP)",
            version = version,
            description = "Ready queue, claim protocol and status lifecycle for the " +
                "supervisor's work source, over MCP.",
            requiredPermissions = listOf(PlugPermission.MCPServer(serverUri)),
            mcpServers = listOf(
                McpServerDependency(
                    name = DEPENDENCY_NAME,
                    uri = serverUri,
                    requiredPermissions = listOf(PlugPermission.MCPServer(serverUri)),
                ),
            ),
            requiredLinks = listOf(
                LinkRequirement(
                    name = DEPENDENCY_NAME,
                    transport = Transport.MCP,
                    direction = LinkDirection.READ_WRITE,
                    minimumScope = setOf(CanonType.WORK_ITEM),
                    role = TransportRole.CONSUMER,
                ),
            ),
            emits = setOf(CanonType.WORK_ITEM),
        )

        /**
         * Builds an adapter from a live [PlugContext], verifying the pinned tool
         * surface first.
         *
         * Fails with [WorkSourceFailure.SchemaDrift] when the server's advertised
         * tools no longer match [WorkSourceToolPins] — before any call goes out,
         * so a renamed argument cannot turn into a ready queue that quietly
         * ignored its filter.
         *
         * @param linkId The Link [PlugContext.create] resolved for
         *   [DEPENDENCY_NAME]. Passed in rather than read off the context, which
         *   surfaces no resolved-Link accessor: every page and receipt this
         *   adapter produces names a Link for consent and provenance, and
         *   substituting the requirement's *name* for the resolved Link's id
         *   would put a value in that field that no consent ledger can match.
         */
        suspend fun open(
            plugContext: PlugContext,
            linkId: LinkId,
            instanceId: SupervisorInstanceId,
            forbiddenTerms: Set<String> = emptySet(),
            clock: Clock = Clock.System,
        ): Result<LinearWorkSource> {
            val mcpTools = plugContext.availableTools().filterIsInstance<McpTool>()

            WorkSourceToolPins.verifyTools(mcpTools).getOrElse { return Result.failure(it) }

            val anyPinned = mcpTools.firstOrNull { it.remoteToolName == WorkSourceToolPins.GET_ISSUE }
                ?: return workSourceFailure(
                    WorkSourceFailure.SchemaDrift(missingTools = setOf(WorkSourceToolPins.GET_ISSUE)),
                )

            val client = plugContext.mcpClientFor(anyPinned)
                ?: return workSourceFailure(
                    WorkSourceFailure.ToolCallFailed(
                        tool = WorkSourceToolPins.GET_ISSUE,
                        reason = "the plug context has no MCP client for server ${anyPinned.serverId}",
                    ),
                )

            if (plugContext.manifest.requiredLinks.none { it.name == DEPENDENCY_NAME }) {
                return workSourceFailure(
                    WorkSourceFailure.ToolCallFailed(
                        tool = WorkSourceToolPins.GET_ISSUE,
                        reason = "the manifest declares no \"$DEPENDENCY_NAME\" Link requirement",
                    ),
                )
            }

            return Result.success(
                LinearWorkSource(
                    tools = McpWorkSourceToolCaller(client),
                    linkId = linkId,
                    instanceId = instanceId,
                    forbiddenTerms = forbiddenTerms,
                    clock = clock,
                ),
            )
        }
    }
}
