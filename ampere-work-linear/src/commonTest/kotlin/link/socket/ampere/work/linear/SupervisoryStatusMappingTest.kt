package link.socket.ampere.work.linear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import link.socket.ampere.canon.CanonWorkStatus

/**
 * The AMPR-314 mapping contract: every canonical status has one provider
 * expression, every provider expression has one canonical status, and the two
 * compose to the identity.
 *
 * The round trip is the admission evidence. Four members were admitted to a
 * closed set on the strength of being *provider-translatable in both
 * directions*, and a translation that only worked one way would mean the canon
 * had gained a word it cannot read back — the exact failure `providerStatus`
 * strings already had.
 */
class SupervisoryStatusMappingTest {

    @Test
    fun `every canon status round-trips through its provider expression`() {
        CanonWorkStatus.entries.forEach { status ->
            val expression = SupervisoryStatusMapping.expressionFor(status)

            assertEquals(
                status,
                SupervisoryStatusMapping.canonStatusFor(expression),
                "$status expressed as $expression did not read back as itself",
            )
        }
    }

    @Test
    fun `the four supervisory members express the ratified composition`() {
        // The AMPR-289 verdict's lifecycle mapping, as a literal table: this is
        // the thing a human ratified, so it is asserted rather than derived.
        assertEquals(
            SupervisoryExpression(
                stateName = WorkSourceStates.IN_PROGRESS,
                statusType = WorkItemStatusType.STARTED,
                claimed = true,
            ),
            SupervisoryStatusMapping.expressionFor(CanonWorkStatus.CLAIMED),
        )
        assertEquals(
            SupervisoryExpression(
                stateName = WorkSourceStates.IN_REVIEW,
                statusType = WorkItemStatusType.STARTED,
            ),
            SupervisoryStatusMapping.expressionFor(CanonWorkStatus.VERIFYING),
        )
        assertEquals(
            SupervisoryExpression(
                stateName = WorkSourceStates.IN_REVIEW,
                statusType = WorkItemStatusType.STARTED,
                labels = setOf(WorkSourceLabels.GATE_AWAITING_VERDICT),
            ),
            SupervisoryStatusMapping.expressionFor(CanonWorkStatus.VERDICT_REQUESTED),
        )
        assertEquals(
            SupervisoryExpression(
                stateName = null,
                statusType = null,
                labels = setOf(WorkSourceLabels.GATE_ESCALATED),
            ),
            SupervisoryStatusMapping.expressionFor(CanonWorkStatus.ESCALATED),
            "an escalated ticket escalates from wherever it is; a state here would overwrite " +
                "the record of where the work stopped",
        )
    }

    @Test
    fun `a terminal category outranks a stale gate label`() {
        // A gate label nobody tidied up after the ticket closed. Reading it as a
        // stop would strand finished work in a queue waiting on a human who has
        // nothing left to do.
        val closedAndGated = SupervisoryExpression(
            stateName = WorkSourceStates.DONE,
            labels = setOf(WorkSourceLabels.GATE_ESCALATED, WorkSourceLabels.GATE_AWAITING_VERDICT),
        )

        assertEquals(CanonWorkStatus.DONE, SupervisoryStatusMapping.canonStatusFor(closedAndGated))

        val cancelledAndGated = closedAndGated.copy(
            stateName = WorkSourceStates.CANCELED,
            statusType = WorkItemStatusType.CANCELED,
        )

        assertEquals(CanonWorkStatus.CANCELLED, SupervisoryStatusMapping.canonStatusFor(cancelledAndGated))
    }

    @Test
    fun `an escalation outranks a verdict gate`() {
        // Both gates at once is legitimate: an unplanned stop never tidies away
        // the record of a planned one, and the unplanned one is what a human has
        // to act on.
        val both = SupervisoryExpression(
            stateName = WorkSourceStates.IN_REVIEW,
            labels = setOf(WorkSourceLabels.GATE_AWAITING_VERDICT, WorkSourceLabels.GATE_ESCALATED),
        )

        assertEquals(CanonWorkStatus.ESCALATED, SupervisoryStatusMapping.canonStatusFor(both))
    }

    @Test
    fun `the in-review state is what separates verifying from in progress`() {
        // Both are `started` to the provider — the category cannot tell them
        // apart, which is why the read matches on the state name first.
        val inReview = SupervisoryExpression(WorkSourceStates.IN_REVIEW)
        val inProgress = SupervisoryExpression(WorkSourceStates.IN_PROGRESS)

        assertEquals(inReview.statusType, inProgress.statusType)
        assertEquals(CanonWorkStatus.VERIFYING, SupervisoryStatusMapping.canonStatusFor(inReview))
        assertEquals(CanonWorkStatus.IN_PROGRESS, SupervisoryStatusMapping.canonStatusFor(inProgress))
    }

    @Test
    fun `claim evidence changes the answer only in the in-progress state`() {
        val inProgress = SupervisoryExpression(WorkSourceStates.IN_PROGRESS)

        assertTrue(SupervisoryStatusMapping.claimEvidenceMatters(inProgress))
        assertEquals(
            CanonWorkStatus.CLAIMED,
            SupervisoryStatusMapping.canonStatusFor(inProgress.copy(claimed = true)),
        )

        // Everywhere else the comment scan cannot change the answer and a caller
        // must not pay for it.
        listOf(
            SupervisoryExpression(WorkSourceStates.TODO),
            SupervisoryExpression(WorkSourceStates.BACKLOG),
            SupervisoryExpression(WorkSourceStates.IN_REVIEW),
            SupervisoryExpression(WorkSourceStates.DONE),
            SupervisoryExpression(WorkSourceStates.CANCELED),
            SupervisoryExpression(
                stateName = WorkSourceStates.IN_PROGRESS,
                labels = setOf(WorkSourceLabels.GATE_ESCALATED),
            ),
        ).forEach { expression ->
            assertFalse(
                SupervisoryStatusMapping.claimEvidenceMatters(expression),
                "$expression does not need a comment read",
            )
            assertEquals(
                SupervisoryStatusMapping.canonStatusFor(expression),
                SupervisoryStatusMapping.canonStatusFor(expression.copy(claimed = true)),
                "a claim comment must not move $expression",
            )
        }
    }

    @Test
    fun `a state name this build does not know falls back to the category`() {
        // A workspace that renamed its states loses the supervisory detail and
        // keeps the coarse truth. It does not fail: an unknown state *name* is
        // someone's configuration, while an unknown *category* is vendor drift —
        // which WorkItemStatusType.fromWire refuses instead.
        val renamed = SupervisoryExpression(
            stateName = "Doing",
            statusType = WorkItemStatusType.STARTED,
        )

        assertEquals(CanonWorkStatus.IN_PROGRESS, SupervisoryStatusMapping.canonStatusFor(renamed))

        assertEquals(
            CanonWorkStatus.BACKLOG,
            SupervisoryStatusMapping.canonStatusFor(renamed.copy(statusType = WorkItemStatusType.TRIAGE)),
            "an untriaged issue has not been committed to and TODO is canon's committed-not-started",
        )
    }

    @Test
    fun `no signal at all reads as todo and never as backlog`() {
        // The rule CanonWorkStatus states: TODO keeps work visible and a guessed
        // BACKLOG hides it.
        assertEquals(
            CanonWorkStatus.TODO,
            SupervisoryStatusMapping.canonStatusFor(SupervisoryExpression(stateName = null)),
        )
    }

    @Test
    fun `an issue's own expression is what the read path sees`() {
        val issue = WorkSourceIssue(
            identifier = "AMPR-1",
            title = "Gated",
            statusName = WorkSourceStates.IN_REVIEW,
            statusType = WorkItemStatusType.STARTED,
            labels = listOf("wave:w0", WorkSourceLabels.GATE_AWAITING_VERDICT),
        )

        assertEquals(
            SupervisoryExpression(
                stateName = WorkSourceStates.IN_REVIEW,
                statusType = WorkItemStatusType.STARTED,
                labels = setOf("wave:w0", WorkSourceLabels.GATE_AWAITING_VERDICT),
            ),
            issue.supervisoryExpression(),
        )
        assertEquals(
            CanonWorkStatus.VERDICT_REQUESTED,
            SupervisoryStatusMapping.canonStatusFor(issue.supervisoryExpression()),
        )
    }

    @Test
    fun `a target that is not a stop clears the gates the ticket carries`() {
        val gated = listOf("wave:w0", "api", WorkSourceLabels.GATE_AWAITING_VERDICT)

        val edit = SupervisoryStatusMapping.gateEdit(CanonWorkStatus.DONE, gated)

        assertEquals(setOf(WorkSourceLabels.GATE_AWAITING_VERDICT), edit.remove)
        assertEquals(emptySet(), edit.add)
    }

    @Test
    fun `only gate labels are ever removed`() {
        // The provider's `labels` argument replaces the whole set; this edit is
        // additive on purpose, so a wave tag survives every status write.
        val edit = SupervisoryStatusMapping.gateEdit(
            target = CanonWorkStatus.TODO,
            present = listOf("wave:w0", "api", "integration", WorkSourceLabels.GATE_ESCALATED),
        )

        assertEquals(setOf(WorkSourceLabels.GATE_ESCALATED), edit.remove)
        assertTrue(WorkSourceLabels.GATES.containsAll(edit.remove))
    }

    @Test
    fun `a stop never tidies away another stop`() {
        val escalated = listOf(WorkSourceLabels.GATE_ESCALATED)

        val toVerdict = SupervisoryStatusMapping.gateEdit(CanonWorkStatus.VERDICT_REQUESTED, escalated)

        assertEquals(setOf(WorkSourceLabels.GATE_AWAITING_VERDICT), toVerdict.add)
        assertEquals(
            emptySet(),
            toVerdict.remove,
            "the ticket is still stopped and erasing the other gate would erase why",
        )
        assertFalse(SupervisoryStatusMapping.clearsGates(CanonWorkStatus.VERDICT_REQUESTED))
        assertFalse(SupervisoryStatusMapping.clearsGates(CanonWorkStatus.ESCALATED))
    }

    @Test
    fun `a gate already present is not added again`() {
        val edit = SupervisoryStatusMapping.gateEdit(
            target = CanonWorkStatus.VERDICT_REQUESTED,
            present = listOf(WorkSourceLabels.GATE_AWAITING_VERDICT),
        )

        assertTrue(edit.isEmpty)
    }

    @Test
    fun `the dispatch lifecycle and the canon mapping are one table`() {
        // SupervisoryState is the six-state dispatch lifecycle; the mapping is the
        // nine-status vocabulary. Where they overlap they must agree, or a claim
        // would transition to one state and read back as another.
        SupervisoryState.entries.forEach { state ->
            val expression = SupervisoryStatusMapping.expressionFor(state.canonStatus)

            assertEquals(state.workSourceState, expression.stateName, "${state.name} state")
            assertEquals(setOfNotNull(state.label), expression.labels, "${state.name} label")
        }

        assertEquals(
            SupervisoryState.entries.map { it.canonStatus }.toSet().size,
            SupervisoryState.entries.size,
            "two dispatch states claiming one canon status would make the mapping ambiguous",
        )
    }

    @Test
    fun `the gate labels and the stopped statuses are the same fact`() {
        // WorkSourceLabels.GATES is what takes a ticket out of the ready queue,
        // so every gate has to belong to a status that means stopped — otherwise
        // a ticket could read as dispatchable and be filtered out of the queue.
        val stops = CanonWorkStatus.entries.filterNot { SupervisoryStatusMapping.clearsGates(it) }

        assertEquals(
            WorkSourceLabels.GATES,
            stops.flatMap { SupervisoryStatusMapping.expressionFor(it).labels }.toSet(),
        )
    }

    @Test
    fun `the state type table matches the work source that was recorded`() {
        // WorkSourceStates.TYPES is a claim about the server's behaviour, and
        // FakeWorkSource models the server from the same live recordings. The two
        // tables are duplicated on purpose — a fake that read the production table
        // could not catch the production table being wrong — so this is the pin
        // that makes the duplication deliberate.
        assertEquals(WorkSourceStates.TYPES, FakeWorkSource.STATE_TYPES)
    }
}
