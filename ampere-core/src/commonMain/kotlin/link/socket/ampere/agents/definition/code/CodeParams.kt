package link.socket.ampere.agents.definition.code

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.execution.ParameterStrategy
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool

/**
 * Parameter strategies for Code Agent tools.
 *
 * Each strategy handles:
 * - Prompt generation for the LLM
 * - Response parsing and request enrichment
 */
sealed class CodeParams {

    /**
     * Strategy for the write_code_file tool.
     *
     * Generates prompts for creating production-quality code and parses the LLM's
     * response into enriched execution requests with file paths and content.
     */
    class CodeWriting : ParameterStrategy {

        override val systemMessage: String =
            "You are a precise code generation system. Generate production-quality Kotlin code."

        override val maxTokens: Int = 4000

        override fun buildPrompt(
            tool: Tool<*>,
            request: ExecutionRequest<*>,
            intent: String,
        ): String {
            val context = request.context
            val workspace = if (context is ExecutionContext.Code) {
                context.workspace.baseDirectory
            } else {
                "."
            }

            return """
                You are a precise code generation system for the CodeWriterAgent.
                Your task is to generate production-quality Kotlin code based on the given intent.

                Intent: $intent

                Workspace: $workspace

                Generate COMPLETE, WORKING code that:
                1. Is syntactically correct and follows Kotlin conventions
                2. Uses idiomatic Kotlin patterns (data classes, sealed classes, extension functions where appropriate)
                3. Includes proper package declarations inferred from file paths
                4. Includes all necessary imports
                5. Has no TODOs, placeholders, or incomplete implementations
                6. Includes appropriate documentation comments for public APIs

                Package naming convention:
                - For file path "src/commonMain/kotlin/link/socket/ampere/User.kt" → package link.socket.ampere
                - For file path "src/main/kotlin/com/example/Foo.kt" → package com.example

                Format your response as a JSON object:
                {
                  "files": [
                    {
                      "path": "relative/path/to/file.kt",
                      "content": "the complete file content",
                      "reason": "brief explanation of what this file does"
                    }
                  ]
                }

                Important:
                - Generate at least one file
                - Use relative paths from the workspace root
                - Include COMPLETE file content, not snippets
                - Start file content with package declaration (if applicable)

                Respond ONLY with the JSON object, no other text.
            """.trimIndent()
        }

        override fun parseAndEnrichRequest(
            jsonResponse: String,
            originalRequest: ExecutionRequest<*>,
        ): ExecutionRequest<*> {
            val json = Json { ignoreUnknownKeys = true }

            val cleanedResponse = jsonResponse
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val responseJson = json.parseToJsonElement(cleanedResponse).jsonObject

            val filesArray = responseJson["files"]?.jsonArray
                ?: throw IllegalStateException("No 'files' array in LLM response")

            if (filesArray.isEmpty()) {
                throw IllegalStateException("Empty 'files' array in LLM response")
            }

            val instructionsPerFilePath = filesArray.map { element ->
                val fileObj = element.jsonObject
                val path = fileObj["path"]?.jsonPrimitive?.content
                    ?: throw IllegalStateException("File missing 'path'")
                val content = fileObj["content"]?.jsonPrimitive?.content
                    ?: throw IllegalStateException("File missing 'content'")

                path to content
            }

            val originalContext = originalRequest.context
            val workspace = if (originalContext is ExecutionContext.Code) {
                originalContext.workspace
            } else {
                ExecutionWorkspace(baseDirectory = ".")
            }

            val enrichedContext = ExecutionContext.Code.WriteCode(
                executorId = originalContext.executorId,
                ticket = originalContext.ticket,
                task = originalContext.task,
                instructions = originalContext.instructions,
                workspace = workspace,
                instructionsPerFilePath = instructionsPerFilePath,
                knowledgeFromPastMemory = originalContext.knowledgeFromPastMemory,
            )

            return ExecutionRequest(
                context = enrichedContext,
                constraints = originalRequest.constraints,
            )
        }
    }

    /**
     * Strategy for the read_code_file tool.
     *
     * Generates prompts for determining which files to read and parses the LLM's
     * response into enriched execution requests with file paths.
     */
    class CodeReading : ParameterStrategy {

        override val systemMessage: String =
            "You are a code analysis system. Determine which files need to be read."

        override val maxTokens: Int = 1000

        override fun buildPrompt(
            tool: Tool<*>,
            request: ExecutionRequest<*>,
            intent: String,
        ): String {
            val context = request.context
            val workspace = if (context is ExecutionContext.Code) {
                context.workspace.baseDirectory
            } else {
                "."
            }

            return """
                You are a code analysis system for the CodeWriterAgent.
                Your task is to determine which files need to be read to accomplish the given intent.

                Intent: $intent

                Workspace: $workspace

                Analyze the intent and determine which files should be read to:
                1. Understand existing code structure
                2. Find relevant classes, functions, or modules
                3. Gather context for code modifications

                Format your response as a JSON object:
                {
                  "filePaths": [
                    "relative/path/to/file1.kt",
                    "relative/path/to/file2.kt"
                  ],
                  "reason": "brief explanation of why these files are needed"
                }

                Respond ONLY with the JSON object, no other text.
            """.trimIndent()
        }

        override fun parseAndEnrichRequest(
            jsonResponse: String,
            originalRequest: ExecutionRequest<*>,
        ): ExecutionRequest<*> {
            val json = Json { ignoreUnknownKeys = true }

            val cleanedResponse = jsonResponse
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val responseJson = json.parseToJsonElement(cleanedResponse).jsonObject

            val filePathsArray = responseJson["filePaths"]?.jsonArray
                ?: throw IllegalStateException("No 'filePaths' array in LLM response")

            val filePaths = filePathsArray.map { it.jsonPrimitive.content }

            val originalContext = originalRequest.context
            val workspace = if (originalContext is ExecutionContext.Code) {
                originalContext.workspace
            } else {
                ExecutionWorkspace(baseDirectory = ".")
            }

            val enrichedContext = ExecutionContext.Code.ReadCode(
                executorId = originalContext.executorId,
                ticket = originalContext.ticket,
                task = originalContext.task,
                instructions = originalContext.instructions,
                workspace = workspace,
                filePathsToRead = filePaths,
                knowledgeFromPastMemory = originalContext.knowledgeFromPastMemory,
            )

            return ExecutionRequest(
                context = enrichedContext,
                constraints = originalRequest.constraints,
            )
        }
    }
}

/**
 * Prompt templates for Code Agent's LLM-driven operations.
 */
object CodePrompts {

    const val SYSTEM_PROMPT = """You are a Code Writer Agent responsible for:
- Generating production-quality Kotlin code from task descriptions
- Writing code files to the workspace
- Reading existing code for context
- Following best practices and coding conventions"""

    /**
     * Generates a perception context prompt from the agent's state.
     */
    fun perceptionContext(state: AgentState, tools: Set<Tool<*>>): String = buildString {
        val codeState = state as? CodeState
        val currentMemory = state.getCurrentMemory()
        val pastMemory = state.getPastMemory()
        val currentTask = currentMemory.task
        val currentOutcome = currentMemory.outcome

        appendLine("# CodeWriterAgent State Analysis")
        appendLine()
        appendLine("## Current Task")
        when (currentTask) {
            is Task.CodeChange -> {
                appendLine("- Type: Code Change")
                appendLine("- ID: ${currentTask.id}")
                appendLine("- Status: ${currentTask.status}")
                appendLine("- Description: ${currentTask.description}")
                currentTask.assignedTo?.let { appendLine("- Assigned To: $it") }
            }
            is Task.Blank -> appendLine("- No active task")
            else -> {
                appendLine("- Type: ${currentTask::class.simpleName}")
                appendLine("- ID: ${currentTask.id}")
                appendLine("- Status: ${currentTask.status}")
            }
        }
        appendLine()
        appendLine("## Current Outcome")
        when (currentOutcome) {
            is Outcome.Success -> appendLine("Status: Success")
            is Outcome.Failure -> appendLine("Status: Failure")
            is Outcome.Blank -> appendLine("Status: No outcome yet")
            else -> appendLine("Status: ${currentOutcome::class.simpleName}")
        }

        if (codeState != null) {
            codeState.currentWorkspace?.let { ws ->
                appendLine()
                appendLine("## Workspace")
                appendLine("- Base Directory: ${ws.baseDirectory}")
                ws.projectType?.let { appendLine("- Project Type: $it") }
            }

            if (codeState.recentlyModifiedFiles.isNotEmpty()) {
                appendLine()
                appendLine("## Recently Modified Files")
                codeState.recentlyModifiedFiles.take(5).forEach { change ->
                    appendLine("- ${change.path} (${change.changeType})")
                }
            }

            codeState.testResults?.let { tests ->
                appendLine()
                appendLine("## Test Results")
                appendLine("- Passed: ${tests.passed}")
                appendLine("- Failed: ${tests.failed}")
                appendLine("- Skipped: ${tests.skipped}")
            }
        }

        if (pastMemory.knowledgeFromOutcomes.isNotEmpty()) {
            appendLine()
            appendLine("## Learned Knowledge")
            pastMemory.knowledgeFromOutcomes.takeLast(3).forEach { knowledge ->
                appendLine("- Approach: ${knowledge.approach}")
                appendLine("  Learnings: ${knowledge.learnings}")
            }
        }

        appendLine()
        appendLine("## Available Tools")
        tools.forEach { tool ->
            appendLine("- ${tool.id}: ${tool.description}")
        }
    }

    /**
     * Generates a planning prompt for the given task.
     */
    fun planning(task: Task, ideas: List<Idea>, tools: Set<Tool<*>>): String = buildString {
        appendLine("You are the planning module of an autonomous code-writing agent.")
        appendLine()
        appendLine("Task: ${extractTaskDescription(task)}")
        appendLine()
        if (ideas.isNotEmpty()) {
            appendLine("Insights from Perception:")
            ideas.forEach { idea ->
                appendLine("${idea.name}:")
                appendLine(idea.description)
                appendLine()
            }
        }
        appendLine("Available Tools:")
        tools.forEach { tool ->
            appendLine("- ${tool.id}: ${tool.description}")
        }
        appendLine()
        appendLine("Create a step-by-step plan where each step is a concrete task that can be executed.")
        appendLine("For simple tasks, create a 1-2 step plan.")
        appendLine("For complex tasks, break down into logical phases (3-5 steps typically).")
        appendLine()
        appendLine("Format your response as a JSON object:")
        appendLine("""{"steps": [{"description": "...",""")
        appendLine(""" "toolToUse": "write_code_file|read_code_file|null",""")
        appendLine(""" "requiresPreviousStep": true/false}],""")
        appendLine(""" "estimatedComplexity": 1-10}""")
    }

    /**
     * Generates an outcome evaluation context.
     */
    fun outcomeContext(outcomes: List<Outcome>): String = buildString {
        appendLine("# Code Execution Outcome Analysis")
        appendLine()
        val successCount = outcomes.count { it is Outcome.Success }
        val failedCount = outcomes.count { it is Outcome.Failure }
        appendLine("Total: ${outcomes.size}, Success: $successCount, Failed: $failedCount")
        appendLine()

        outcomes.forEachIndexed { i, outcome ->
            val prefix = "${i + 1}."
            when (outcome) {
                is link.socket.ampere.agents.domain.outcome.ExecutionOutcome.CodeChanged.Success -> {
                    appendLine("$prefix [SUCCESS] Code Changed")
                    appendLine("   Files: ${outcome.changedFiles.size}")
                    outcome.changedFiles.take(3).forEach { file ->
                        appendLine("   - $file")
                    }
                    if (outcome.changedFiles.size > 3) {
                        appendLine("   ... and ${outcome.changedFiles.size - 3} more")
                    }
                }
                is link.socket.ampere.agents.domain.outcome.ExecutionOutcome.CodeChanged.Failure -> {
                    appendLine("$prefix [FAILURE] Code Change Failed")
                    appendLine("   Error: ${outcome.error}")
                }
                is link.socket.ampere.agents.domain.outcome.ExecutionOutcome.CodeReading.Success -> {
                    appendLine("$prefix [SUCCESS] Code Read")
                    appendLine("   Files: ${outcome.readFiles.size}")
                }
                is link.socket.ampere.agents.domain.outcome.ExecutionOutcome.CodeReading.Failure -> {
                    appendLine("$prefix [FAILURE] Code Reading Failed")
                    appendLine("   Error: ${outcome.error}")
                }
                else -> {
                    appendLine("$prefix ${outcome::class.simpleName}")
                }
            }
        }
    }

    private fun extractTaskDescription(task: Task): String = when (task) {
        is Task.CodeChange -> task.description
        is Task.Blank -> ""
        else -> "Task ${task.id}"
    }
}
