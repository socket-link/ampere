package link.socket.ampere.plug.spi

import kotlin.test.Test
import kotlin.test.assertFailsWith
import link.socket.ampere.link.LinkId

/** The conformance check itself must reject each way a page can misreport its predicates. */
class PerceiveContractAssertionTest {

    private val query = PerceiveQuery(linkId = LinkId("work-source-link"), predicates = readyQueueRule)

    @Test
    fun `a page that silently drops its predicates fails the contract`() {
        val dropping =
            PerceivePage(entities = listOf(FixtureTicket("AMPR-2")), evaluated = emptyList(), residual = emptyList())

        assertFailsWith<AssertionError> { assertPerceiveContract(query, dropping) }
    }

    @Test
    fun `a page that drops one predicate fails the contract`() {
        val dropping = PerceivePage(
            entities = emptyList<FixtureTicket>(),
            evaluated = readyQueueRule.take(1),
            residual = readyQueueRule.drop(2),
        )

        assertFailsWith<AssertionError> { assertPerceiveContract(query, dropping) }
    }

    @Test
    fun `a predicate listed as both evaluated and residual fails the contract`() {
        val doubled = PerceivePage(
            entities = emptyList<FixtureTicket>(),
            evaluated = readyQueueRule,
            residual = readyQueueRule.take(1),
        )

        assertFailsWith<AssertionError> { assertPerceiveContract(query, doubled) }
    }

    @Test
    fun `a predicate that is not in the query fails the contract`() {
        val invented = PerceivePage(
            entities = emptyList<FixtureTicket>(),
            evaluated = readyQueueRule + PerceivePredicate.HasNoRelation("parent"),
            residual = emptyList(),
        )

        assertFailsWith<AssertionError> { assertPerceiveContract(query, invented) }
    }

    @Test
    fun `an unfiltered page satisfies the contract`() {
        assertPerceiveContract(query, PerceivePage.unfiltered(query, listOf(FixtureTicket("AMPR-1"))))
    }
}
