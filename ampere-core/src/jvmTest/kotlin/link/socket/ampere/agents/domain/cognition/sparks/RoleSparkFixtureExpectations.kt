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

/**
 * The bundled `role-*.spark.md` fixtures, as data, so every test that reads
 * them asserts against one source.
 *
 * `allowedTools` is deliberately a *superset* of `requestedToolIds` and is the
 * load-bearing half of the pair (AMPR-400): since the spark stack enforces
 * narrowing by intersection, any tool id missing from `allowedTools` is
 * withdrawn from the agent at dispatch. It therefore has to list every tool
 * `AgentFactory` actually hands the role — the git tool set and `plan_steps`
 * included — plus the ids the fixtures were originally authored against
 * (`run_command`, `search_codebase`, `web_search`, `create_issue`,
 * `query_issues`, `update_issue`), which no tool in the repo carries yet and
 * which an intersection simply ignores. Trimming this set to "what the role
 * needs" is how you silently take git away from the Code agent.
 *
 * `fileAccessScope` became load-bearing in the same way in AMPR-414: the
 * file-touching tools now gate on it, so a write pattern missing from a role
 * is a write the role cannot make, and a `forbiddenPatterns` entry blocks
 * reads as well as writes. Read the composed result, not the fixture alone —
 * a production CODE agent stacks `role-code`, `project-ampere` and
 * `language-kotlin`, and the composition is an intersection.
 */
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
            "plan_steps",
            "read_code_file",
            "read_codebase",
            "write_code_file",
            "run_tests",
            "run_command",
            "ask_human",
            "search_codebase",
            "knowledge_query",
            "git_create_branch",
            "git_checkout",
            "git_stage",
            "git_commit",
            "git_push",
            "git_create_pr",
            "git_status",
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
            "plan_steps",
            "web_search",
            "read_code_file",
            "read_codebase",
            "ask_human",
            "search_codebase",
            "knowledge_query",
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
            "plan_steps",
            "run_command",
            "run_tests",
            "read_code_file",
            "read_codebase",
            "ask_human",
            "search_codebase",
            "git_status",
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
            // AMPR-414: the fixture used to forbid `**/*.kt` and the other
            // source extensions outright. `forbiddenPatterns` is a single
            // deny-list spanning reads *and* writes, so that policy left an
            // Operations agent — one whose `allowedTools` include
            // `read_code_file` — unable to open a single source file in a
            // Kotlin repository. Writes to source files are already refused
            // by the write allow-list above not naming them, which is where
            // "operations does not edit code" belongs.
            forbiddenPatterns = FileAccessScope.SensitiveFileForbiddenPatterns,
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
            "plan_steps",
            "create_issues",
            "create_issue",
            "query_issues",
            "update_issue",
            "ask_human",
            "read_code_file",
            "read_codebase",
            "search_codebase",
            "knowledge_query",
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
