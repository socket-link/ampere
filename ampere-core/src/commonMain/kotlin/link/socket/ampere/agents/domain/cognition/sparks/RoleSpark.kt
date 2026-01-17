package link.socket.ampere.agents.domain.cognition.sparks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.cognition.FileAccessScope
import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.agents.domain.cognition.ToolId

/**
 * A Spark that defines the agent's role and associated capabilities.
 *
 * RoleSpark establishes what kind of work the agent is doing and provides
 * appropriate tool access and file permissions. Each role has distinct
 * capabilities that narrow what the agent can do.
 *
 * The four roles map to different work patterns:
 * - **Code**: Reading, writing, and reviewing code
 * - **Research**: Discovering and synthesizing information
 * - **Operations**: Executing and monitoring systems
 * - **Planning**: Decomposing goals and coordinating work
 */
@Serializable
sealed class RoleSpark : Spark {

    /**
     * Role for agents that read, write, and review code.
     *
     * Tools: read_code_file, write_code_file, run_command, ask_human
     * File access: Read/write code files, blocked build outputs
     */
    @Serializable
    @SerialName("RoleSpark.Code")
    data object Code : RoleSpark() {

        override val name: String = "Role:Code"

        override val promptContribution: String = """
## Role: Code

You are operating in a **code-focused** capacity. Your primary responsibilities are:

- Reading and understanding existing code
- Writing new code and modifying existing implementations
- Reviewing code for correctness, style, and potential issues
- Running commands to build, test, and verify changes

### Guidelines

- Follow existing code patterns and conventions in the project
- Write clear, maintainable code with appropriate comments
- Consider edge cases and error handling
- Prefer small, focused changes over large refactors
- Test changes before considering them complete
        """.trimIndent()

        override val allowedTools: Set<ToolId> = setOf(
            "read_code_file",
            "write_code_file",
            "run_command",
            "ask_human",
            "search_codebase",
        )

        override val fileAccessScope: FileAccessScope = FileAccessScope(
            readPatterns = setOf("**/*"),
            writePatterns = setOf(
                "**/*.kt",
                "**/*.kts",
                "**/*.java",
                "**/*.xml",
                "**/*.json",
                "**/*.yaml",
                "**/*.yml",
                "**/*.properties",
                "**/*.md",
                "**/*.txt",
            ),
            forbiddenPatterns = setOf(
                "**/build/**",
                "**/.gradle/**",
                "**/node_modules/**",
                "**/.git/**",
            ) + FileAccessScope.SensitiveFileForbiddenPatterns,
        )
    }

    /**
     * Role for agents that discover and synthesize information.
     *
     * Tools: web_search, read_code_file, ask_human
     * File access: Read everything, write only documentation
     */
    @Serializable
    @SerialName("RoleSpark.Research")
    data object Research : RoleSpark() {

        override val name: String = "Role:Research"

        override val promptContribution: String = """
## Role: Research

You are operating in a **research-focused** capacity. Your primary responsibilities are:

- Discovering and gathering relevant information
- Reading and understanding codebases and documentation
- Synthesizing findings into coherent summaries
- Identifying patterns, relationships, and insights

### Guidelines

- Be thorough in exploration—follow leads that might be relevant
- Document your findings with clear citations and references
- Distinguish between facts and inferences
- Note uncertainties and areas that need more investigation
- Present multiple perspectives when they exist
        """.trimIndent()

        override val allowedTools: Set<ToolId> = setOf(
            "web_search",
            "read_code_file",
            "ask_human",
            "search_codebase",
        )

        override val fileAccessScope: FileAccessScope = FileAccessScope(
            readPatterns = setOf("**/*"),
            writePatterns = setOf(
                "**/*.md",
                "**/docs/**",
                "**/documentation/**",
            ),
            forbiddenPatterns = FileAccessScope.SensitiveFileForbiddenPatterns,
        )
    }

    /**
     * Role for agents that execute and monitor systems.
     *
     * Tools: run_command, read_code_file, ask_human
     * File access: Read everything, write only logs/configs, blocked code files
     */
    @Serializable
    @SerialName("RoleSpark.Operations")
    data object Operations : RoleSpark() {

        override val name: String = "Role:Operations"

        override val promptContribution: String = """
## Role: Operations

You are operating in an **operations-focused** capacity. Your primary responsibilities are:

- Executing commands and scripts
- Monitoring system status and health
- Deploying and configuring systems
- Responding to incidents and issues

### Guidelines

- Prioritize stability and reliability
- Verify commands before executing, especially destructive ones
- Log actions for audit trail
- Escalate immediately when uncertain
- Prefer reversible actions over irreversible ones
- Monitor for unexpected side effects
        """.trimIndent()

        override val allowedTools: Set<ToolId> = setOf(
            "run_command",
            "read_code_file",
            "ask_human",
            "search_codebase",
        )

        override val fileAccessScope: FileAccessScope = FileAccessScope(
            readPatterns = setOf("**/*"),
            writePatterns = setOf(
                "**/logs/**",
                "**/*.log",
                "**/*.config",
                "**/*.conf",
                "**/*.ini",
                "**/*.yaml",
                "**/*.yml",
                "**/config/**",
            ),
            forbiddenPatterns = setOf(
                "**/*.kt",
                "**/*.java",
                "**/*.py",
                "**/*.js",
                "**/*.ts",
            ) + FileAccessScope.SensitiveFileForbiddenPatterns,
        )
    }

    /**
     * Role for agents that decompose goals and coordinate work.
     *
     * Tools: create_issue, query_issues, update_issue, ask_human
     * File access: Read code/docs for context, write only documentation
     */
    @Serializable
    @SerialName("RoleSpark.Planning")
    data object Planning : RoleSpark() {

        override val name: String = "Role:Planning"

        override val promptContribution: String = """
## Role: Planning

You are operating in a **planning-focused** capacity. Your primary responsibilities are:

- Breaking down goals into actionable tasks
- Coordinating work across different areas
- Managing issues and tracking progress
- Communicating status and blockers

### Guidelines

- Create clear, specific, actionable tasks
- Consider dependencies between tasks
- Estimate complexity and identify risks
- Provide sufficient context for implementers
- Update status as work progresses
- Escalate blockers proactively
        """.trimIndent()

        override val allowedTools: Set<ToolId> = setOf(
            "create_issue",
            "query_issues",
            "update_issue",
            "ask_human",
            "read_code_file",
            "search_codebase",
        )

        override val fileAccessScope: FileAccessScope = FileAccessScope(
            readPatterns = setOf("**/*"),
            writePatterns = setOf(
                "**/*.md",
                "**/docs/**",
                "**/documentation/**",
                "**/.github/**",
            ),
            forbiddenPatterns = FileAccessScope.SensitiveFileForbiddenPatterns,
        )
    }
}
