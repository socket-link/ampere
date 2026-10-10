package link.socket.ampere.agents.domain.reasoning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.provider.AIProvider_Google

/**
 * [PlanGenerator] parsing (AMPR-398).
 *
 * The planning prompt asks the model for `requiresHumanInput` alongside the
 * steps; before AMPR-398 the answer was paid for and dropped. These tests pin
 * that the parsed [Plan] carries it, both when the planner asks for a person
 * and when it does not.
 */
class PlanGeneratorTest {

    private val task = Task.CodeChange(
        id = "task-1",
        status = TaskStatus.Pending,
        description = "Rename a private helper",
    )

    @Test
    fun `carries requiresHumanInput when the planner asks for a person`() = runTest {
        val plan = generatorRespondingWith(planJson(requiresHumanInput = "true")).generate(
            task = task,
            ideas = emptyList(),
            agentRole = "Engineer",
        )

        assertTrue(plan.requiresHumanInput)
        assertEquals(1, plan.tasks.size)
        assertEquals(4, plan.estimatedComplexity)
    }

    @Test
    fun `carries requiresHumanInput when the planner does not ask for a person`() = runTest {
        val plan = generatorRespondingWith(planJson(requiresHumanInput = "false")).generate(
            task = task,
            ideas = emptyList(),
            agentRole = "Engineer",
        )

        assertFalse(plan.requiresHumanInput)
        // Complexity 4 is the fixture's; a fallback plan would report 3, so this
        // pins that `false` came from the planner and not from a silent fallback.
        assertEquals(4, plan.estimatedComplexity)
        assertEquals(1, plan.tasks.size)
    }

    @Test
    fun `reads requiresHumanInput written as a quoted string`() = runTest {
        val plan = generatorRespondingWith(planJson(requiresHumanInput = "\"true\"")).generate(
            task = task,
            ideas = emptyList(),
            agentRole = "Engineer",
        )

        assertTrue(plan.requiresHumanInput)
        assertEquals(4, plan.estimatedComplexity)
    }

    @Test
    fun `defaults requiresHumanInput to false when the planner omits it`() = runTest {
        val response = """
            {
              "steps": [
                { "description": "Rename it", "toolToUse": null, "requiresPreviousStep": false }
              ],
              "estimatedComplexity": 4
            }
        """.trimIndent()

        val plan = generatorRespondingWith(response).generate(
            task = task,
            ideas = emptyList(),
            agentRole = "Engineer",
        )

        assertFalse(plan.requiresHumanInput)
        assertEquals(4, plan.estimatedComplexity)
    }

    @Test
    fun `a fallback plan asks for nobody`() = runTest {
        val plan = generatorRespondingWith("not json at all").generate(
            task = task,
            ideas = emptyList(),
            agentRole = "Engineer",
        )

        assertFalse(plan.requiresHumanInput)
        assertEquals(1, plan.tasks.size)
        assertEquals(3, plan.estimatedComplexity)
    }

    @Test
    fun `the prompt asks for requiresHumanInput that the parser reads`() = runTest {
        var prompt: String? = null
        val generator = PlanGenerator(
            AgentLLMService(
                agentConfiguration = AgentConfiguration(
                    agentDefinition = WriteCodeAgent,
                    aiConfiguration = AIConfiguration_Default(AIProvider_Google, AIModel_Gemini.Flash_2_5),
                    llmProvider = { sent ->
                        prompt = sent
                        planJson(requiresHumanInput = "true")
                    },
                ),
            ),
        )

        generator.generate(task = task, ideas = emptyList(), agentRole = "Engineer")

        // The key the prompt asks for is the key parsePlanFromResponse looks up.
        assertTrue(prompt.orEmpty().contains("\"requiresHumanInput\""))
    }

    @Test
    fun `a blank task plans nothing and asks for nobody`() = runTest {
        val plan = generatorRespondingWith(planJson(requiresHumanInput = "true")).generate(
            task = Task.Blank,
            ideas = emptyList(),
            agentRole = "Engineer",
        )

        assertEquals(Plan.Blank, plan)
        assertFalse(plan.requiresHumanInput)
    }

    private fun planJson(requiresHumanInput: String): String = """
        {
          "steps": [
            { "description": "Rename it", "toolToUse": null, "requiresPreviousStep": false }
          ],
          "estimatedComplexity": 4,
          "requiresHumanInput": $requiresHumanInput
        }
    """.trimIndent()

    private fun generatorRespondingWith(response: String): PlanGenerator = PlanGenerator(
        AgentLLMService(
            agentConfiguration = AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = AIConfiguration_Default(AIProvider_Google, AIModel_Gemini.Flash_2_5),
                llmProvider = { response },
            ),
        ),
    )
}
