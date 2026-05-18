package link.socket.ampere.agents.domain.cognition.sparks

import link.socket.ampere.agents.domain.cognition.FileAccessScope
import link.socket.ampere.agents.domain.cognition.ToolId

internal data class ExpectedRoleSpark(
    val id: String,
    val name: String,
    val agentRole: String,
    val requestedToolIds: Set<ToolId>,
    val allowedTools: Set<ToolId>,
    val fileAccessScope: FileAccessScope,
    val promptContribution: String,
)

internal object RoleSparkFixtureExpectations {
    val code = ExpectedRoleSpark(
        id = "code",
        name = "Role:Code",
        agentRole = "Code Writer",
        requestedToolIds = setOf(
            "read_code_file",
            "write_code_file",
            "run_command",
            "ask_human",
            "search_codebase",
        ),
        allowedTools = setOf(
            "read_code_file",
            "write_code_file",
            "run_command",
            "ask_human",
            "search_codebase",
        ),
        fileAccessScope = FileAccessScope(
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
        ),
        promptContribution = """
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
        """.trimIndent(),
    )

    val research = ExpectedRoleSpark(
        id = "research",
        name = "Role:Research",
        agentRole = "Researcher",
        requestedToolIds = setOf(
            "web_search",
            "read_code_file",
            "ask_human",
            "search_codebase",
        ),
        allowedTools = setOf(
            "web_search",
            "read_code_file",
            "ask_human",
            "search_codebase",
        ),
        fileAccessScope = FileAccessScope(
            readPatterns = setOf("**/*"),
            writePatterns = setOf(
                "**/*.md",
                "**/docs/**",
                "**/documentation/**",
            ),
            forbiddenPatterns = FileAccessScope.SensitiveFileForbiddenPatterns,
        ),
        promptContribution = """
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
        """.trimIndent(),
    )

    val operations = ExpectedRoleSpark(
        id = "operations",
        name = "Role:Operations",
        agentRole = "Operations",
        requestedToolIds = setOf(
            "run_command",
            "read_code_file",
            "ask_human",
            "search_codebase",
        ),
        allowedTools = setOf(
            "run_command",
            "read_code_file",
            "ask_human",
            "search_codebase",
        ),
        fileAccessScope = FileAccessScope(
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
        ),
        promptContribution = """
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
        """.trimIndent(),
    )

    val planning = ExpectedRoleSpark(
        id = "planning",
        name = "Role:Planning",
        agentRole = "Planner",
        requestedToolIds = setOf(
            "create_issue",
            "query_issues",
            "update_issue",
            "ask_human",
            "read_code_file",
            "search_codebase",
        ),
        allowedTools = setOf(
            "create_issue",
            "query_issues",
            "update_issue",
            "ask_human",
            "read_code_file",
            "search_codebase",
        ),
        fileAccessScope = FileAccessScope(
            readPatterns = setOf("**/*"),
            writePatterns = setOf(
                "**/*.md",
                "**/docs/**",
                "**/documentation/**",
                "**/.github/**",
            ),
            forbiddenPatterns = FileAccessScope.SensitiveFileForbiddenPatterns,
        ),
        promptContribution = """
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
        """.trimIndent(),
    )

    val all: List<ExpectedRoleSpark> = listOf(
        code,
        research,
        operations,
        planning,
    )

    val byId: Map<String, ExpectedRoleSpark> = all.associateBy { it.id }
}
