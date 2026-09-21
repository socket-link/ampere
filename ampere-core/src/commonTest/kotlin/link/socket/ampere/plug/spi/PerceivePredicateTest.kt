package link.socket.ampere.plug.spi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.adapter.CanonConversionFailure
import link.socket.ampere.link.LinkId

class PerceivePredicateTest {

    private val linkId = LinkId("work-source-link")

    private val ready = FixtureTicket("AMPR-1", labels = setOf("wave:w0"))
    private val blocked = FixtureTicket("AMPR-2", labels = setOf("wave:w0"), openBlockers = setOf("AMPR-9"))
    private val gated = FixtureTicket("AMPR-3", labels = setOf("wave:w0", "gate:awaiting-verdict"))
    private val otherWave = FixtureTicket("AMPR-4", labels = setOf("wave:w1"))

    private val refusingEvaluator = object : PredicateEvaluator<FixtureTicket> {
        override suspend fun matches(entity: FixtureTicket, predicate: PerceivePredicate.Equals): Boolean =
            fail("evaluator consulted for an exact page")

        override suspend fun hasNoRelation(entity: FixtureTicket, predicate: PerceivePredicate.HasNoRelation): Boolean =
            fail("evaluator consulted for an exact page")
    }

    @Test
    fun `the ready-queue page is over-approximate until its residual is applied`() = runTest {
        val source = ReadyQueueSource(listOf(ready, blocked, gated, otherWave), pageSize = 10)
        val query = PerceiveQuery(linkId = linkId, predicates = readyQueueRule)

        val page = source.perceive(query).getOrThrow()

        assertFalse(page.isExact)
        assertEquals(listOf(ready, blocked, gated), page.entities)
        assertEquals(readyQueueRule.drop(1), page.residual)

        val exact = page.applyResidual(TicketEvaluator)

        assertTrue(exact.isExact)
        assertEquals(listOf(ready), exact.entities)
        assertEquals(readyQueueRule.toSet(), exact.evaluated.toSet())
    }

    @Test
    fun `an exact page is returned without consulting the evaluator`() = runTest {
        val page = PerceivePage(entities = listOf(blocked), evaluated = readyQueueRule, residual = emptyList())

        assertEquals(page, page.applyResidual(refusingEvaluator))
    }

    @Test
    fun `applyResidual keeps the cursor and partial failures`() = runTest {
        val failure = CanonConversionFailure.MalformedField(
            canonType = CanonType.WORK_ITEM,
            field = "title",
            reason = "empty",
        )
        val page = PerceivePage.unfiltered(
            query = PerceiveQuery(linkId = linkId, predicates = readyQueueRule),
            entities = listOf(ready, gated),
            nextCursor = "next",
            partialFailures = listOf(failure),
        )

        val exact = page.applyResidual(TicketEvaluator)

        assertEquals(listOf(ready), exact.entities)
        assertEquals("next", exact.nextCursor)
        assertEquals(listOf(failure), exact.partialFailures)
    }

    @Test
    fun `unfiltered returns every query predicate as residual`() {
        val query = PerceiveQuery(linkId = linkId, predicates = readyQueueRule)

        val page = PerceivePage.unfiltered(query, listOf(ready))

        assertEquals(emptyList(), page.evaluated)
        assertEquals(readyQueueRule, page.residual)
        assertFalse(page.isExact)
    }

    @Test
    fun `predicates round-trip through JSON under stable serial names`() {
        val serializer = ListSerializer(PerceivePredicate.serializer())

        val encoded = Json.encodeToString(serializer, readyQueueRule)

        assertTrue("\"perceive_predicate.equals\"" in encoded, encoded)
        assertTrue("\"perceive_predicate.not\"" in encoded, encoded)
        assertTrue("\"perceive_predicate.has_no_relation\"" in encoded, encoded)
        assertEquals(readyQueueRule, Json.decodeFromString(serializer, encoded))
    }
}
