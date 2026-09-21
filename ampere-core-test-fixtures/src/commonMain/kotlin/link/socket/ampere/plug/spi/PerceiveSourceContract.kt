package link.socket.ampere.plug.spi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.test.runTest
import link.socket.ampere.link.LinkId

/**
 * Structural test suite every [PerceiveSource] must satisfy: each query
 * predicate comes back on every page as either evaluated or residual —
 * never dropped, never both, and never a failure because the source
 * doesn't understand it.
 *
 * A subclass proves nothing about its own filtering logic here; it proves
 * the source is honest about what it filtered. [assertPerceiveContract] is
 * the check itself, public so a test can apply it to a single page.
 */
abstract class PerceiveSourceContract<T> {

    /** The source under test. Called once per test. */
    protected abstract fun source(): PerceiveSource<T>

    /**
     * Predicates this source is expected to understand — typically the ones
     * it pushes down plus any it knowingly leaves residual. The suite
     * requires only that each is accounted for, not which list it lands in.
     */
    protected abstract fun fixturePredicates(): List<PerceivePredicate>

    /** Override when the source needs a particular Link or window to answer at all. */
    protected open fun query(predicates: List<PerceivePredicate>): PerceiveQuery =
        PerceiveQuery(linkId = LinkId("contract-link"), predicates = predicates)

    /** Predicates of all three forms that no real source can recognise. */
    protected val unknownPredicates: List<PerceivePredicate> = listOf(
        PerceivePredicate.Equals(field = "__contract_unknown_field__", value = "x"),
        PerceivePredicate.Not(PerceivePredicate.Equals(field = "__contract_unknown_field__", value = "y")),
        PerceivePredicate.HasNoRelation(kind = "__contract_unknown_relation__"),
    )

    @Test
    fun `a query with no predicates yields an exact page`() = runTest {
        perceiveAll(query(emptyList())).forEach { page ->
            assertEquals(emptyList(), page.evaluated)
            assertEquals(emptyList(), page.residual)
            assertTrue(page.isExact)
        }
    }

    @Test
    fun `predicates the source cannot recognise come back residual`() = runTest {
        val query = query(unknownPredicates)

        perceiveAll(query).forEach { page ->
            assertPerceiveContract(query, page)
            assertEquals(
                unknownPredicates.toMultiset(),
                page.residual.toMultiset(),
                "Unrecognised predicates must be returned as residual, not claimed as evaluated",
            )
        }
    }

    @Test
    fun `each fixture predicate alone is accounted for`() = runTest {
        fixturePredicates().forEach { predicate ->
            val query = query(listOf(predicate))
            perceiveAll(query).forEach { page -> assertPerceiveContract(query, page) }
        }
    }

    @Test
    fun `fixture and unknown predicates together are all accounted for`() = runTest {
        val query = query(fixturePredicates() + unknownPredicates)

        perceiveAll(query).forEach { page -> assertPerceiveContract(query, page) }
    }

    /** Follows [PerceivePage.nextCursor], bounded so a source that never ends a scan fails instead of hanging. */
    private suspend fun perceiveAll(query: PerceiveQuery): List<PerceivePage<T>> {
        val source = source()
        val pages = mutableListOf<PerceivePage<T>>()
        var next: PerceiveQuery? = query
        while (next != null) {
            if (pages.size == MAX_PAGES) fail("Source returned more than $MAX_PAGES pages for a contract query")
            val current = next
            val page = source.perceive(current).getOrElse {
                fail(
                    "perceive() failed for $current — a predicate the source can't evaluate " +
                        "belongs in residual, not in a failure",
                    it,
                )
            }
            pages += page
            next = page.nextCursor?.let { current.copy(cursor = it) }
        }
        return pages
    }

    private companion object {
        const val MAX_PAGES = 100
    }
}

/**
 * Asserts [page] accounts for every predicate in [query] exactly once:
 * `evaluated + residual` equals `query.predicates` as a multiset, and no
 * predicate appears in both lists.
 */
fun assertPerceiveContract(query: PerceiveQuery, page: PerceivePage<*>) {
    val both = page.evaluated.toSet() intersect page.residual.toSet()
    assertTrue(both.isEmpty(), "Predicates listed as both evaluated and residual: $both")

    val expected = query.predicates.toMultiset()
    val actual = (page.evaluated + page.residual).toMultiset()
    if (expected == actual) return

    val dropped = expected.minusCounts(actual)
    val invented = actual.minusCounts(expected)
    fail(
        buildString {
            append("Page does not account for the query's predicates exactly once.")
            if (dropped.isNotEmpty()) append(" Dropped (must be returned as residual): $dropped.")
            if (invented.isNotEmpty()) append(" Not in the query: $invented.")
        },
    )
}

private fun List<PerceivePredicate>.toMultiset(): Map<PerceivePredicate, Int> =
    groupingBy { it }.eachCount()

private fun Map<PerceivePredicate, Int>.minusCounts(other: Map<PerceivePredicate, Int>): List<PerceivePredicate> =
    flatMap { (predicate, count) -> List((count - (other[predicate] ?: 0)).coerceAtLeast(0)) { predicate } }
