package link.socket.ampere.agents.execution.dispatch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Switchboard's trust boundaries, pinned where they are enforced: in what its
 * collaborators *offer* (AMPR-308, Expected Outcome 4).
 *
 * "Attempted gate-label removal is unrepresentable" means there is no method to call.
 * These tests fail the moment one appears — so widening the Switchboard's door onto
 * the work source, or giving it a forge write, is a change somebody has to make here
 * on purpose and defend in review.
 */
class DispatchWorkSourceSurfaceTest {

    /** Declared method names, with the JVM's inline-class mangling suffix dropped. */
    private fun methodsOf(type: Class<*>): Set<String> =
        type.declaredMethods.filterNot { it.isSynthetic }.map { it.name.substringBefore('-') }.toSet()

    @Test
    fun `the Switchboard's work-source door has exactly these operations`() {
        assertEquals(
            setOf(
                "getConventions",
                "readyCandidates",
                "inFlight",
                "ticket",
                "comments",
                "claim",
                "release",
                "escalate",
                "requestVerdict",
            ),
            methodsOf(DispatchWorkSource::class.java),
        )
    }

    @Test
    fun `no operation on it removes a label, writes a state, closes a ticket or merges`() {
        val forbidden = Regex("(?i)remove|label|transition|status|state|done|close|merge|comment(?!s$)|post|write")
        val offending = methodsOf(DispatchWorkSource::class.java).filter { forbidden.containsMatchIn(it) }
        assertTrue(offending.isEmpty(), "the Switchboard's door gained a forbidden write: $offending")
    }

    @Test
    fun `the forge port only reads`() {
        assertEquals(setOf("forHeadBranch"), methodsOf(MergeRequestLookup::class.java))
    }
}
