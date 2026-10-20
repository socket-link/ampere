package link.socket.ampere.propel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.llm.decide.stateDigest

/**
 * AMPR-483 (N11): a phase's last product, with what it ran over and when, as a value.
 *
 * Two claims: a cell is a plain value — equal to another over the same triple and to nothing
 * else — and its digest helper is `stateDigest` over the parts with a boundary between them,
 * so a cell over one rendered string joins to a decision record over the same string.
 */
class PhaseCellTest {

    @Test
    fun `two cells over one triple are equal and a cell over another cycle is not`() {
        val idea = Idea(name = "The seats are declared", id = "idea-1", description = "the host can plan")
        val first = PhaseCell(value = idea, inputDigest = PhaseCell.digest("context"), cycle = 1)
        val same = PhaseCell(value = idea, inputDigest = PhaseCell.digest("context"), cycle = 1)

        assertEquals(first, same)
        assertEquals(first.hashCode(), same.hashCode())
        assertNotEquals(first, first.copy(cycle = 2))
        assertNotEquals(first, first.copy(inputDigest = PhaseCell.digest("another context")))
        assertNotEquals(first, first.copy(value = idea.copy(description = "the host cannot plan")))
    }

    @Test
    fun `one part digests exactly as the decision seam digests a state`() {
        val rendered = "Current Task:\n  Description: Answer the thread\n"

        assertEquals(stateDigest(rendered), PhaseCell.digest(rendered))
        assertEquals(64, PhaseCell.digest(rendered).length, "full SHA-256 hex, as stateDigest is")
    }

    @Test
    fun `the boundary between parts is part of the input`() {
        assertEquals(PhaseCell.digest("a", "b"), PhaseCell.digest("a", "b"))
        assertNotEquals(PhaseCell.digest("ab", "c"), PhaseCell.digest("a", "bc"))
        assertNotEquals(PhaseCell.digest("a", "b"), PhaseCell.digest("ab"))
        assertNotEquals(PhaseCell.digest("a", "b"), PhaseCell.digest("b", "a"))
    }
}
