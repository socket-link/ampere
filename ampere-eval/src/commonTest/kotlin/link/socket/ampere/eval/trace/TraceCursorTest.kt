package link.socket.ampere.eval.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull

/** AMPR-183 task 1.5 validation. */
class TraceCursorTest {

    private fun trace(n: Int): Trace = Trace(
        id = "t",
        runId = "r",
        arcId = "a",
        createdAt = 0L,
        events = (0 until n).map { TraceEvent(it, it.toLong(), "type-$it", JsonNull) },
    )

    @Test
    fun `replayTo(n) returns exactly events 0 through n`() {
        val cursor = TraceCursor(trace(5))
        val result = cursor.replayTo(2)
        assertEquals(3, result.size)
        assertEquals(listOf(0, 1, 2), result.map { it.index })
    }

    @Test
    fun `replayTo(size-1) returns all events`() {
        val t = trace(5)
        val cursor = TraceCursor(t)
        assertEquals(t.events, cursor.replayTo(t.size - 1))
    }

    @Test
    fun `replayTo out-of-range is coerced and never throws`() {
        val cursor = TraceCursor(trace(5))
        // Above range -> whole trace.
        assertEquals(5, cursor.replayTo(100).size)
        // Below range -> empty.
        assertTrue(cursor.replayTo(-1).isEmpty())
        assertTrue(cursor.replayTo(-100).isEmpty())
    }

    @Test
    fun `replayTo on empty trace never throws`() {
        val cursor = TraceCursor(trace(0))
        assertTrue(cursor.replayTo(0).isEmpty())
        assertTrue(cursor.replayTo(10).isEmpty())
        assertTrue(cursor.replayTo(-5).isEmpty())
    }

    @Test
    fun `branchAfter handoff begins at index plus one`() {
        val cursor = TraceCursor(trace(5))
        val branch = cursor.branchAfter(2)
        assertEquals(3, branch.branchIndex)
        assertEquals(listOf(0, 1, 2), branch.replayed.map { it.index })
    }

    @Test
    fun `branchAfter at last index replays everything (degenerate eval case)`() {
        val t = trace(5)
        val branch = TraceCursor(t).branchAfter(t.size - 1)
        assertEquals(5, branch.branchIndex)
        assertEquals(t.events, branch.replayed)
    }

    @Test
    fun `branchAfter(-1) branches from the start`() {
        val branch = TraceCursor(trace(5)).branchAfter(-1)
        assertEquals(0, branch.branchIndex)
        assertTrue(branch.replayed.isEmpty())
    }

    @Test
    fun `branchAfter out-of-range is coerced`() {
        val branch = TraceCursor(trace(5)).branchAfter(100)
        assertEquals(5, branch.branchIndex)
        assertEquals(5, branch.replayed.size)
    }
}
