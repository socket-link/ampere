package link.socket.ampere.agents.demo

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.definition.CodeAgent
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.results.ExecutionResult
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic

/**
 * Manual demonstration and validation of the PROPEL cognitive cycle.
 *
 * This test demonstrates:
 * 1. runLLMToEvaluatePerception - Analyze current state
 * 2. runLLMToPlan - Generate concrete plan
 * 3. runLLMToExecuteTask - Execute with LLM-generated code
 * 4. runLLMToEvaluateOutcomes - Extract learnings
 *
 * To run this demo:
 * 1. Configure your LLM credentials in local.properties
 * 2. Run: ./gradlew :ampere-core:jvmTest --tests CognitiveCycleDemo
 *
 * Expected output: Complete trace of the cognitive cycle with detailed logging.
 */
class CognitiveCycleDemo {

    @Test
    fun `demonstrate complete PROPEL cognitive cycle`() {
        runBlocking {
            println("=".repeat(80))
            println("PROPEL COGNITIVE CYCLE DEMONSTRATION")
            println("=".repeat(80))
            println()

            // Create a mock tool that simulates file writing
            val mockWriteCodeFile = createDemoWriteCodeFileTool()

            // Create agent configuration
            val agentConfig = AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = AIConfiguration_Default(
                    provider = AIProvider_Anthropic,
                    model = AIModel_Claude.Sonnet_4,
                ),
            )

            // Create the CodeWriterAgent
            val agent = CodeAgent(
                initialState = CodeState.blank,
                agentConfiguration = agentConfig,
                toolWriteCodeFile = mockWriteCodeFile,
                coroutineScope = this,
                executor = FunctionExecutor.create(),
            )

            println("✓ Agent initialized: ${agent.id}")
            println()

            // Define a realistic task for the demo
            val task = Task.CodeChange(
                id = "demo-task-001",
                status = TaskStatus.Pending,
                description = "Create a simple User data class with fields: " +
                    "name (String), email (String), and age (Int)",
            )

            println("Task: ${task.description}")
            println()
            println("-".repeat(80))
            println()

            // ==================== PHASE 1: PERCEIVE ====================

            println("PHASE 1: PERCEIVE")
            println("Analyzing current state and generating insights...")
            println()

            val perception = agent.perceiveState(agent.getCurrentState())

            println("Perception ID: ${perception.id}")
            println("Ideas Generated: ${perception.ideas.size}")
            perception.ideas.forEach { idea ->
                println()
                println("  Idea: ${idea.name}")
                println("  Description:")
                idea.description.lines().forEach { line ->
                    println("    $line")
                }
            }
            println()
            println("-".repeat(80))
            println()

            // ==================== PHASE 2: PLAN ====================

            println("PHASE 2: PLAN")
            println("Generating concrete execution plan...")
            println()

            val plan = agent.determinePlanForTask(
                task = task,
                ideas = arrayOf(perception.ideas.first()),
                relevantKnowledge = emptyList(),
            )

            println("Plan ID: ${plan.id}")
            println("Estimated Complexity: ${plan.estimatedComplexity}")
            println("Steps: ${plan.tasks.size}")
            plan.tasks.forEachIndexed { index, step ->
                println()
                println("  Step ${index + 1}:")
                when (step) {
                    is Task.CodeChange -> {
                        println("    Type: Code Change")
                        println("    Description: ${step.description}")
                        println("    Status: ${step.status}")
                    }
                    else -> {
                        println("    Type: ${step::class.simpleName}")
                        println("    ID: ${step.id}")
                    }
                }
            }
            println()
            println("-".repeat(80))
            println()

            // ==================== PHASE 3: EXECUTE ====================

            println("PHASE 3: EXECUTE")
            println("Executing plan through executor abstraction...")
            println()

            val outcome = agent.executePlan(plan)

            println("Outcome ID: ${outcome.id}")
            println("Outcome Type: ${outcome::class.simpleName}")

            when (outcome) {
                is ExecutionOutcome.CodeChanged.Success -> {
                    println("Status: ✓ SUCCESS")
                    println("Files Changed: ${outcome.changedFiles.size}")
                    outcome.changedFiles.forEach { file ->
                        println("  - $file")
                    }
                    println("Duration: ${outcome.executionEndTimestamp - outcome.executionStartTimestamp}")
                }
                is ExecutionOutcome.CodeChanged.Failure -> {
                    println("Status: ✗ FAILURE")
                    println("Error: ${outcome.error}")
                    outcome.partiallyChangedFiles?.let { files ->
                        if (files.isNotEmpty()) {
                            println("Partially Changed Files: ${files.size}")
                        }
                    }
                }
                else -> {
                    println("Status: ${outcome::class.simpleName}")
                }
            }
            println()
            println("-".repeat(80))
            println()

            // ==================== PHASE 4: LEARN ====================

            println("PHASE 4: LEARN")
            println("Extracting knowledge and generating learnings...")
            println()

            // Extract knowledge from the outcome
            val knowledge = agent.extractKnowledgeFromOutcome(outcome, task, plan)

            println("Knowledge Type: ${knowledge::class.simpleName}")
            println("Approach: ${knowledge.approach}")
            println("Timestamp: ${knowledge.timestamp}")
            println()
            println("Learnings:")
            knowledge.learnings.lines().forEach { line ->
                println("  $line")
            }
            println()

            // Evaluate outcomes to generate actionable insights
            val learningIdea = agent.evaluateNextIdeaFromOutcomes(outcome)

            println("Learning Idea: ${learningIdea.name}")
            println("Description:")
            learningIdea.description.lines().forEach { line ->
                println("  $line")
            }
            println()
            println("-".repeat(80))
            println()

            // ==================== SUMMARY ====================

            println("COGNITIVE CYCLE SUMMARY")
            println()
            println("✓ Perception: Generated ${perception.ideas.size} insight(s)")
            println("✓ Planning: Created ${plan.tasks.size}-step plan (complexity: ${plan.estimatedComplexity})")
            println("✓ Execution: ${if (outcome is ExecutionOutcome.Success) "Succeeded" else "Failed"}")
            println("✓ Learning: Extracted knowledge and generated ${learningIdea.name}")
            println()

            // Check agent state
            val finalState = agent.getCurrentState()
            val pastMemory = finalState.getPastMemory()

            println("Agent Memory State:")
            println("  Ideas: ${pastMemory.ideas.size}")
            println("  Perceptions: ${pastMemory.perceptions.size}")
            println("  Plans: ${pastMemory.plans.size}")
            println("  Tasks: ${pastMemory.tasks.size}")
            println("  Outcomes: ${pastMemory.outcomes.size}")
            println("  Knowledge Entries: ${pastMemory.knowledgeFromOutcomes.size}")
            println()

            println("=".repeat(80))
            println("DEMONSTRATION COMPLETE")
            println()
            println("The PROPEL cognitive cycle executed successfully:")
            println("  Vague Requirement → Perception → Plan → Execution → Learning")
            println()
            println("This demonstrates autonomous agency: the ability to transform")
            println("high-level intent into concrete action while learning from experience.")
            println("=".repeat(80))

            // Add test assertions to validate the cycle worked
            assertNotNull(perception, "Perception should be generated")
            assertTrue(perception.ideas.isNotEmpty(), "Perception should generate ideas")
            assertNotNull(plan, "Plan should be generated")
            assertTrue(plan.tasks.isNotEmpty(), "Plan should contain tasks")
            assertNotNull(outcome, "Outcome should be generated")
            assertNotNull(knowledge, "Knowledge should be extracted")
            assertNotNull(learningIdea, "Learning idea should be generated")
        }
    }

    /**
     * Creates a demo write_code_file tool that simulates file writing.
     *
     * For a real demo, this could actually write to a temporary directory.
     * For safety, this version just simulates success.
     */
    private fun createDemoWriteCodeFileTool(): Tool<ExecutionContext.Code.WriteCode> {
        return FunctionTool(
            id = "write_code_file",
            name = "Write Code File",
            description = "Writes or overwrites code files with the specified content",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request ->
                val now = Clock.System.now()

                // Simulate file writing (in real version, would actually write files)
                val changedFiles = request.context.instructionsPerFilePath.map { (path, content) ->
                    println("  [SIMULATED] Writing file: $path")
                    println("  [SIMULATED] Content length: ${content.length} chars")
                    path
                }

                // Simulate a brief execution time
                val endTime = now + 150.milliseconds

                ExecutionOutcome.CodeChanged.Success(
                    executorId = "demo-executor",
                    ticketId = request.context.ticket.id,
                    taskId = request.context.task.id,
                    executionStartTimestamp = now,
                    executionEndTimestamp = endTime,
                    changedFiles = changedFiles,
                    validation = ExecutionResult(
                        codeChanges = null,
                        compilation = null,
                        linting = null,
                        tests = null,
                    ),
                )
            },
        )
    }

    /**
     * Alternative version that ACTUALLY writes files to a temporary directory.
     *
     * To use this version, replace createDemoWriteCodeFileTool() with this function.
     */
    private fun createRealWriteCodeFileTool(): Tool<ExecutionContext.Code.WriteCode> {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "ampere-demo-${System.currentTimeMillis()}")
        tempDir.mkdirs()

        println("  [INFO] Writing files to: ${tempDir.absolutePath}")
        println()

        return FunctionTool(
            id = "write_code_file",
            name = "Write Code File",
            description = "Writes or overwrites code files with the specified content",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request ->
                val now = Clock.System.now()

                val changedFiles = request.context.instructionsPerFilePath.map { (path, content) ->
                    val file = File(tempDir, path)
                    file.parentFile?.mkdirs()
                    file.writeText(content)

                    println("  ✓ Wrote file: ${file.absolutePath}")
                    println("    Size: ${content.length} chars")

                    path
                }

                val endTime = Clock.System.now()

                ExecutionOutcome.CodeChanged.Success(
                    executorId = "real-executor",
                    ticketId = request.context.ticket.id,
                    taskId = request.context.task.id,
                    executionStartTimestamp = now,
                    executionEndTimestamp = endTime,
                    changedFiles = changedFiles,
                    validation = ExecutionResult(
                        codeChanges = null,
                        compilation = null,
                        linting = null,
                        tests = null,
                    ),
                )
            },
        )
    }
}
