package link.socket.ampere.agents.domain.reasoning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.provider.AIProvider_Google

/**
 * AMPR-450 (AMPR-385 row H20): the planner's `requiresPreviousStep` becomes
 * [Task.Step.dependsOn].
 *
 * The prompt has asked for the key on every plan since there were plans, and nothing
 * read it back. `true` is a dependency on the step immediately before; `false`, a
 * missing key, and `true` on a first step with nothing before it are no dependency.
 * The prompt itself is unchanged — `PlanningPromptGoldenTest` pins its bytes.
 */
class PlanGeneratorDependsOnTest {

    private val task = Task.Step(
        id = "task-1",
        status = TaskStatus.Pending,
        description = "Answer the thread",
    )

    @Test
    fun `requiresPreviousStep true depends on the step before and false or missing depends on nothing`() =
        runTest {
            val plan = generatorRespondingWith(FOUR_STEPS).generate(
                task = task,
                ideas = emptyList(),
                agentRole = "Engineer",
            )

            assertEquals(
                listOf(
                    emptyList(),
                    emptyList(),
                    listOf("step-2-task-1"),
                    emptyList(),
                ),
                plan.tasks.map { (it as Task.Step).dependsOn },
                "true on the first step has nothing before it; false and missing are independent",
            )
        }

    @Test
    fun `a later cycle's dependency names the step by its cycle id`() = runTest {
        val plan = generatorRespondingWith(FOUR_STEPS).generate(
            task = task,
            ideas = emptyList(),
            agentRole = "Engineer",
            priorResults = listOf(
                StepOutcome.Success(
                    id = "step-1-task-1",
                    stepDescription = "searched",
                    startTimestamp = Instant.fromEpochSeconds(0),
                    endTimestamp = Instant.fromEpochSeconds(0),
                    details = "found it",
                ),
            ),
            cycle = 2,
        )

        assertEquals(
            listOf("c2-step-2-task-1"),
            (plan.tasks[2] as Task.Step).dependsOn,
            "the id the plan actually holds",
        )
    }

    @Test
    fun `a code plan has nowhere to put a dependency and is unchanged`() = runTest {
        val codeGoal = Task.CodeChange(
            id = "task-1",
            status = TaskStatus.Pending,
            description = "Answer the thread",
        )
        val plan = generatorRespondingWith(FOUR_STEPS).generate(
            task = codeGoal,
            ideas = emptyList(),
            agentRole = "Engineer",
        )

        assertEquals(4, plan.tasks.size)
        assertEquals(
            Task.CodeChange(id = "step-3-task-1", status = TaskStatus.Pending, description = "Draft the reply"),
            plan.tasks[2],
        )
    }

    private fun generatorRespondingWith(response: String): PlanGenerator = PlanGenerator(
        AgentLLMService(
            agentConfiguration = AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = AIConfiguration_Default(AIProvider_Google, AIModel_Gemini.Flash_2_5),
                llmProvider = { response },
            ),
        ),
    )

    private companion object {
        const val FOUR_STEPS = """
            {
              "steps": [
                { "description": "Search the archive", "toolToUse": null, "requiresPreviousStep": true },
                { "description": "Read the thread", "toolToUse": null, "requiresPreviousStep": false },
                { "description": "Draft the reply", "toolToUse": null, "requiresPreviousStep": true },
                { "description": "Note the outcome", "toolToUse": null }
              ],
              "estimatedComplexity": 4,
              "requiresHumanInput": false
            }
        """
    }
}
