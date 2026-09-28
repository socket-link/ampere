package link.socket.ampere.work.linear

import link.socket.ampere.link.LinkId
import link.socket.ampere.plug.spi.PerceivePredicate
import link.socket.ampere.plug.spi.PerceiveQuery
import link.socket.ampere.plug.spi.PerceiveSource
import link.socket.ampere.plug.spi.PerceiveSourceContract

/**
 * Holds this source to the chassis SPI's structural contract: every query
 * predicate comes back on every page as evaluated or residual, never dropped,
 * never both, and never a failure because the source did not understand it.
 *
 * A dropped predicate is what turns an over-approximate page into one that claims
 * to be exact — and this queue is the one where that mistake dispatches a blocked
 * or gated ticket.
 */
class WorkSourceIssueSourceContractTest : PerceiveSourceContract<WorkSourceIssue>() {

    override fun source(): PerceiveSource<WorkSourceIssue> = WorkSourceIssueSource(
        tools = FakeWorkSource().apply {
            add(FakeWorkSource.Issue("AMPR-1", labels = mutableListOf("wave:w0")))
            add(
                FakeWorkSource.Issue(
                    identifier = "AMPR-2",
                    labels = mutableListOf("wave:w0"),
                    blockedBy = mutableListOf("AMPR-9"),
                ),
            )
            add(
                FakeWorkSource.Issue(
                    identifier = "AMPR-3",
                    labels = mutableListOf("wave:w0", WorkSourceLabels.GATE_AWAITING_VERDICT),
                ),
            )
            add(FakeWorkSource.Issue("AMPR-4", labels = mutableListOf("wave:w1")))
            add(FakeWorkSource.Issue("AMPR-9", status = "Todo"))
        },
        linkId = LinkId("contract-link"),
    )

    override fun query(predicates: List<PerceivePredicate>): PerceiveQuery =
        PerceiveQuery(linkId = LinkId("contract-link"), limit = 2, predicates = predicates)

    override fun fixturePredicates(): List<PerceivePredicate> =
        readyQueueRule(wave = "w0") +
            // Every field this source or its evaluator claims to understand, so
            // the suite covers the whole declared vocabulary rather than the
            // three terms the ready queue happens to use.
            WorkSourceFields.ALL.map { PerceivePredicate.Equals(it, "x") }
}
