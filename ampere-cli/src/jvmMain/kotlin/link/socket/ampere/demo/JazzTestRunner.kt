package link.socket.ampere.demo

import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.AmpereContext
import link.socket.ampere.agents.definition.CodeWriterAgent
import link.socket.ampere.agents.domain.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.config.AgentConfiguration
import link.socket.ampere.agents.domain.concept.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.TicketEvent
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.tickets.TicketBuilder
import link.socket.ampere.agents.events.tickets.TicketOrchestrator
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.results.ExecutionResult
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic

/**
 * The Jazz Test Runner - Demonstrates end-to-end autonomous agent behavior.
 *
 * This program:
 * 1. Starts the AMPERE environment
 * 2. Creates a CodeWriterAgent that listens for ticket events
 * 3. Creates a ticket: "Implement Fibonacci function in Kotlin"
 * 4. Assigns the ticket to the agent
 * 5. The agent autonomously runs through the PROPEL cognitive cycle
 * 6. All events are emitted and observable via the CLI dashboard
 *
 * To run this:
 *   ./gradlew :ampere-cli:installJvmDist
 *   ./ampere-cli/build/install/ampere-cli-jvm/bin/ampere-cli-jvm jazz-test
 *
 * In another terminal, observe with:
 *   ./ampere-cli/ampere start
 */
fun main() {
    println("═".repeat(80))
    println("THE JAZZ TEST - Autonomous Agent End-to-End Demonstration")
    println("═".repeat(80))
    println()

    // Create output directory for generated code
    val outputDir = File(System.getProperty("user.home"), ".ampere/jazz-test-output")
    outputDir.mkdirs()

    println("📁 Output directory: ${outputDir.absolutePath}")
    println()

    // Initialize AMPERE context (this creates the database, event bus, etc.)
    val context = AmpereContext()
    context.start()

    val agentScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    try {
        runBlocking {
            println("✅ AMPERE environment started")
            println("📡 Event bus active")
            println("💾 Database ready")
            println()

            // Create the write_code_file tool
            val writeCodeTool = createWriteCodeFileTool(outputDir)

            // Configure the agent
            val agentConfig = AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = AIConfiguration_Default(
                    provider = AIProvider_Anthropic,
                    model = AIModel_Claude.Sonnet_4
                )
            )

            // Create CodeWriterAgent
            val agent = CodeWriterAgent(
                initialState = AgentState(),
                agentConfiguration = agentConfig,
                toolWriteCodeFile = writeCodeTool,
                coroutineScope = agentScope,
                executor = FunctionExecutor.create(),
                memoryServiceFactory = { agentId -> context.createMemoryService(agentId) }
            )

            println("🤖 CodeWriterAgent created")
            println("   Agent ID: ${agent.id}")
            println()

            // Track cognitive cycle completion
            val cognitiveCycleComplete = CompletableDeferred<Unit>()

            // Subscribe the agent to ticket events
            val eventHandler = EventHandler<Event, link.socket.ampere.agents.events.subscription.Subscription> { event, _ ->
                when (event) {
                    is TicketEvent.TicketAssigned -> {
                        if (event.assignedTo == agent.id) {
                            println("🎫 [${agent.id}] Ticket assigned!")
                            println("   Ticket ID: ${event.ticketId}")
                            println()

                            // Launch cognitive cycle in the background
                            agentScope.launch {
                                try {
                                    handleTicketAssignment(agent, event.ticketId, context)
                                    cognitiveCycleComplete.complete(Unit)
                                } catch (e: Exception) {
                                    cognitiveCycleComplete.completeExceptionally(e)
                                }
                            }
                        }
                    }
                    else -> {
                        // Agent ignores other events for now
                    }
                }
            }

            context.subscribeToAll(agent.id, eventHandler)

            println("✅ Agent subscribed to events")
            println()

            // Give the system a moment to stabilize
            delay(500)

            println("─".repeat(80))
            println("CREATING FIBONACCI TICKET")
            println("─".repeat(80))
            println()

            // Build the ticket specification
            val ticketSpec = TicketBuilder()
                .withTitle("Implement Fibonacci function")
                .withDescription("""
                    Create a SINGLE Kotlin file with a Fibonacci function.

                    Requirements:
                    - Function name: fibonacci
                    - Input: n (Int) - the position in the Fibonacci sequence
                    - Output: Long - the Fibonacci number at position n
                    - Use an efficient iterative approach
                    - Handle edge cases (n = 0, n = 1)

                    IMPORTANT:
                    - Create ONLY ONE file named Fibonacci.kt
                    - Do NOT create additional utility files, test files, or helper classes
                    - Keep the implementation simple and self-contained
                """.trimIndent())
                .ofType(TicketType.TASK)
                .withPriority(TicketPriority.HIGH)
                .createdBy("human-jazz-test")
                .assignedTo(agent.id)
                .build()

            // Use the ticket orchestrator from the environment
            val ticketOrchestrator = context.environmentService.ticketOrchestrator

            val result = ticketOrchestrator.createTicket(
                title = ticketSpec.title,
                description = ticketSpec.description,
                type = ticketSpec.type,
                priority = ticketSpec.priority,
                createdByAgentId = ticketSpec.createdByAgentId,
            )

            if (result.isFailure) {
                println("❌ Failed to create ticket: ${result.exceptionOrNull()?.message}")
                return@runBlocking
            }

            val (ticket, thread) = result.getOrThrow()

            // Assign the ticket if specified
            if (ticketSpec.assignedToAgentId != null) {
                ticketOrchestrator.assignTicket(
                    ticketId = ticket.id,
                    targetAgentId = ticketSpec.assignedToAgentId,
                    assignerAgentId = ticketSpec.createdByAgentId,
                )
            }

            println("✅ Ticket created successfully!")
            println("   Ticket ID: ${ticket.id}")
            println("   Title: ${ticket.title}")
            println("   Assigned to: ${ticket.assignedAgentId}")
            println("   Thread ID: ${thread.id}")
            println()

            println("─".repeat(80))
            println("AGENT COGNITIVE CYCLE IN PROGRESS")
            println("─".repeat(80))
            println()
            println("The agent will now autonomously:")
            println("  1. 🧠 PERCEIVE - Analyze the task and generate insights")
            println("  2. 📋 PLAN - Create a concrete execution plan")
            println("  3. ⚡ EXECUTE - Write the Kotlin code")
            println("  4. 📚 LEARN - Extract knowledge from the outcome")
            println()
            println("To observe in real-time, run in another terminal:")
            println("  ./ampere-cli/ampere start")
            println()

            // Wait for the agent to complete (up to 60 seconds)
            val maxWaitSeconds = 60
            var elapsedSeconds = 0

            while (elapsedSeconds < maxWaitSeconds) {
                delay(1.seconds)
                elapsedSeconds++

                // Check if code was generated (search recursively for Fibonacci.kt)
                val fibonacciFile = outputDir.walkTopDown()
                    .firstOrNull { it.name == "Fibonacci.kt" && it.isFile }

                if (fibonacciFile != null && fibonacciFile.exists()) {
                    println()
                    println("═".repeat(80))
                    println("✅ SUCCESS! Agent completed the task in ${elapsedSeconds} seconds")
                    println("═".repeat(80))
                    println()
                    println("📄 Generated file: ${fibonacciFile.absolutePath}")
                    println()
                    println("File contents:")
                    println("─".repeat(80))
                    println(fibonacciFile.readText())
                    println("─".repeat(80))
                    println()

                    // Basic validation
                    val content = fibonacciFile.readText()
                    val hasFunction = content.contains("fun fibonacci")
                    val hasTypes = content.contains("Int") || content.contains("Long")

                    if (hasFunction && hasTypes) {
                        println("✅ Code validation passed")
                        println("   ✓ Contains fibonacci function")
                        println("   ✓ Uses appropriate types")
                    } else {
                        println("⚠️  Code validation warnings:")
                        if (!hasFunction) println("   - Missing 'fun fibonacci'")
                        if (!hasTypes) println("   - Missing type annotations")
                    }

                    println()
                    break
                }

                // Progress indicator every 10 seconds
                if (elapsedSeconds % 10 == 0) {
                    println("   ⏳ Waiting for agent... (${elapsedSeconds}s elapsed)")
                }
            }

            if (elapsedSeconds >= maxWaitSeconds) {
                println()
                println("⏱️  Timeout reached (${maxWaitSeconds}s)")
                println()
                println("Possible reasons:")
                println("  - LLM API credentials not configured in local.properties")
                println("  - Agent is still processing (check dashboard)")
                println("  - Error occurred during cognitive cycle")
                println()
                println("Check the dashboard for agent status and events")
            }

            // Wait for cognitive cycle to complete before shutting down
            println()
            println("⏳ Waiting for cognitive cycle to complete...")
            try {
                cognitiveCycleComplete.await()
                println("✅ Cognitive cycle finished")
            } catch (e: Exception) {
                println("⚠️  Cognitive cycle ended with error: ${e.message}")
            }

            println()
            println("═".repeat(80))
            println("JAZZ TEST COMPLETE")
            println("═".repeat(80))
            println()
            println("Environment will remain running for 10 more seconds...")
            println("Use Ctrl+C to exit immediately")
            println()

            delay(10.seconds)
        }
    } catch (e: Exception) {
        println()
        println("❌ Error: ${e.message}")
        e.printStackTrace()
    } finally {
        println("🛑 Shutting down...")
        agentScope.cancel()
        context.close()
        println("👋 Environment stopped cleanly")
    }
}

/**
 * Handle ticket assignment by running the cognitive cycle.
 */
private suspend fun handleTicketAssignment(
    agent: CodeWriterAgent,
    ticketId: String,
    context: AmpereContext
) {
    try {
        // Fetch ticket details
        val ticketResult = context.environmentService.ticketRepository.getTicket(ticketId)
        val ticket = ticketResult.getOrNull()
        if (ticket == null) {
            println("   ❌ Could not fetch ticket details")
            return
        }

        println("🔄 [COGNITIVE CYCLE] Starting...")
        println()

        // Convert ticket to task
        val task = link.socket.ampere.agents.domain.concept.task.Task.CodeChange(
            id = "task-$ticketId",
            status = link.socket.ampere.agents.domain.concept.status.TaskStatus.Pending,
            description = ticket.description
        )

        // PHASE 1: PERCEIVE
        println("   🧠 [PHASE 1: PERCEIVE] Analyzing current state...")
        val perception = agent.perceiveState(agent.getCurrentState())
        println("      Generated ${perception.ideas.size} idea(s)")
        println()

        if (perception.ideas.isEmpty()) {
            println("   ❌ No ideas generated, aborting")
            return
        }

        // PHASE 2: PLAN
        println("   📋 [PHASE 2: PLAN] Creating execution plan...")
        val plan = agent.determinePlanForTask(
            task = task,
            ideas = arrayOf(perception.ideas.first()),
            relevantKnowledge = emptyList()
        )
        println("      Created plan with ${plan.tasks.size} step(s)")
        println("      Estimated complexity: ${plan.estimatedComplexity}")
        println()

        // PHASE 3: EXECUTE
        println("   ⚡ [PHASE 3: EXECUTE] Executing plan...")
        println("      📤 Calling LLM to generate code...")
        val outcome = agent.executePlan(plan)
        println("      ✅ Execution completed: ${outcome::class.simpleName}")
        when (outcome) {
            is ExecutionOutcome.CodeChanged.Success -> {
                println("      ✅ Success! Changed ${outcome.changedFiles.size} file(s)")
                outcome.changedFiles.forEach { file ->
                    println("         - $file")
                }
            }
            is ExecutionOutcome.CodeChanged.Failure -> {
                println("      ❌ Failure: ${outcome.error}")
            }
            else -> {
                println("      ℹ️  Outcome: ${outcome::class.simpleName}")
            }
        }
        println()

        // PHASE 4: LEARN
        println("   📚 [PHASE 4: LEARN] Extracting knowledge...")
        println("      🧠 Analyzing outcome and generating learnings...")
        val knowledge = agent.extractKnowledgeFromOutcome(outcome, task, plan)
        println("      ✅ Knowledge extraction complete")
        println("      Approach: ${knowledge.approach}")
        println("      Learnings:")
        knowledge.learnings.lines().take(3).forEach { line ->
            if (line.isNotBlank()) {
                println("         $line")
            }
        }
        println()

        println("✅ [COGNITIVE CYCLE] Complete!")
        println()

        // Allow time for output to flush before shutdown
        delay(500)

    } catch (e: Exception) {
        println("   ❌ [ERROR] Cognitive cycle failed: ${e.message}")
        e.printStackTrace()
    }
}

/**
 * Create the write_code_file tool that writes to the specified directory.
 */
private fun createWriteCodeFileTool(
    outputDir: File
): Tool<link.socket.ampere.agents.execution.request.ExecutionContext.Code.WriteCode> {
    return FunctionTool(
        id = "write_code_file",
        name = "Write Code File",
        description = "Writes or overwrites code files with the specified content",
        requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        executionFunction = { request ->
            val now = Clock.System.now()

            val changedFiles = request.context.instructionsPerFilePath.map { (path, content) ->
                val file = File(outputDir, path)
                file.parentFile?.mkdirs()
                file.writeText(content)

                println("      📝 Wrote file: $path (${content.length} chars)")

                path
            }

            val endTime = Clock.System.now()

            ExecutionOutcome.CodeChanged.Success(
                executorId = "jazz-test-executor",
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
        }
    )
}
