package link.socket.ampere.agents.definition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.definition.product.ProductState
import link.socket.ampere.agents.definition.project.ProjectState
import link.socket.ampere.agents.definition.qa.QualityState
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.state.AgentState

/**
 * `blank` states seed agents' `initialState`. [AgentState] memory is mutated in place,
 * so each access must return a fresh instance or every agent built from it would share memory.
 */
class BlankAgentStateTest {

    @Test
    fun `CodeState blank does not share memory between accesses`() =
        assertBlankIsFresh { CodeState.blank }

    @Test
    fun `QualityState blank does not share memory between accesses`() =
        assertBlankIsFresh { QualityState.blank }

    @Test
    fun `ProductState blank does not share memory between accesses`() =
        assertBlankIsFresh { ProductState.blank }

    @Test
    fun `ProjectState blank does not share memory between accesses`() =
        assertBlankIsFresh { ProjectState.blank }

    private fun assertBlankIsFresh(blank: () -> AgentState) {
        val first = blank()
        val second = blank()
        assertNotSame(first, second)

        first.setNewIdea(Idea(name = "Leaked idea"))

        assertEquals(Idea.blank.id, second.getCurrentMemory().idea.id)
        assertEquals(Idea.blank.id, blank().getCurrentMemory().idea.id)
        assertEquals(emptyList(), blank().getPastMemory().ideas)
    }
}
