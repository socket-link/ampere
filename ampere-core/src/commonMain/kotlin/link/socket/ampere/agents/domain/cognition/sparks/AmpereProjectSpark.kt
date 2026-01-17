package link.socket.ampere.agents.domain.cognition.sparks

import link.socket.ampere.util.getEnvironmentVariable

/**
 * The ProjectSpark configuration for the AMPERE project.
 *
 * This object provides the canonical ProjectSpark instance for agents working on
 * the AMPERE codebase. It includes project-specific context, conventions, and
 * architectural guidance that helps agents understand and work within the project.
 *
 * Ticket: #227 - Create ProjectSpark Configuration for AMPERE
 */
object AmpereProjectSpark {

    /**
     * The ProjectSpark instance for AMPERE.
     *
     * This spark provides context about:
     * - What AMPERE is and what it does
     * - Key architectural principles
     * - Coding conventions and patterns
     * - Repository structure
     */
    val spark: ProjectSpark by lazy {
        ProjectSpark(
            projectId = "ampere",
            projectDescription = AMPERE_DESCRIPTION,
            repositoryRoot = detectRepositoryRoot(),
            conventions = AMPERE_CONVENTIONS,
        )
    }

    /**
     * Creates a ProjectSpark for AMPERE with a specific repository root.
     *
     * Use this when the repository root needs to be explicitly specified
     * (e.g., in tests or when working in non-standard directory structures).
     *
     * @param repositoryRoot The absolute path to the repository root
     * @return A ProjectSpark configured for AMPERE
     */
    fun withRepositoryRoot(repositoryRoot: String): ProjectSpark = ProjectSpark(
        projectId = "ampere",
        projectDescription = AMPERE_DESCRIPTION,
        repositoryRoot = repositoryRoot,
        conventions = AMPERE_CONVENTIONS,
    )

    /**
     * Detects the repository root from environment or file system.
     *
     * Resolution order:
     * 1. AMPERE_ROOT environment variable
     * 2. Current working directory (fallback)
     */
    private fun detectRepositoryRoot(): String {
        // Try environment variable first
        val envRoot = try {
            getEnvironmentVariable("AMPERE_ROOT")
        } catch (_: Exception) {
            null
        }

        if (!envRoot.isNullOrBlank()) {
            return envRoot
        }

        // Fallback to current directory (best-effort)
        val pwd = try {
            getEnvironmentVariable("PWD")
        } catch (_: Exception) {
            null
        }

        return if (!pwd.isNullOrBlank()) pwd else "."
    }

    private const val AMPERE_DESCRIPTION = """
AMPERE is a Kotlin Multiplatform framework for building autonomous AI agent systems.

## Purpose
AMPERE enables the creation of collaborative AI agent teams that can:
- Communicate through typed events and messages
- Maintain long-term memory and learn from experiences
- Execute tasks autonomously with appropriate tool access
- Coordinate through meetings and tickets

## Key Components
- **Agents**: Autonomous entities with cognitive cycles (PERCEIVE → PLAN → EXECUTE → LEARN)
- **Events**: Typed messages for agent communication (MessagePosted, TicketAssigned, etc.)
- **Sparks**: Cognitive differentiation layers that specialize agent behavior
- **Memory**: Knowledge persistence and recall for experiential learning

## Architecture
- `ampere-core`: Multiplatform core library with agent infrastructure
- `ampere-cli`: JVM command-line interface for running agents
- Kotlin Multiplatform targeting JVM and Android

## The Spark System
Agents use a "cellular differentiation" model where a single SparkBasedAgent class
specializes through accumulated Spark layers:
1. CognitiveAffinity: Base thinking approach (ANALYTICAL, EXPLORATORY, OPERATIONAL, INTEGRATIVE)
2. ProjectSpark: Project context and conventions
3. RoleSpark: Capability focus (Code, Research, Operations, Planning)
4. TaskSpark: Current task context (applied/removed with task lifecycle)
"""

    private const val AMPERE_CONVENTIONS = """
## Kotlin Style
- Follow Kotlin official coding conventions
- Use data classes for immutable value types
- Prefer sealed classes/interfaces for domain modeling
- Use kotlinx.serialization for JSON handling
- Use kotlinx.coroutines for async operations

## Package Structure
- `agents.definition`: Agent class implementations
- `agents.domain.cognition`: Spark system and cognitive types
- `agents.domain.event`: Event types for agent communication
- `agents.domain.memory`: Knowledge and memory persistence
- `agents.events`: Event bus and subscription infrastructure
- `agents.execution`: Tool execution and task running

## Testing
- Write tests in `commonTest` for platform-independent logic
- Use `jvmTest` for JVM-specific tests
- Follow existing test patterns with `@Test` annotation
- Use descriptive test names that explain what's being tested

## Documentation
- Use KDoc for public APIs
- Include examples in documentation where helpful
- Keep comments focused on "why" rather than "what"

## Event Handling
- All agent events should be serializable with kotlinx.serialization
- Use EventSource to track event origin
- Include timestamp in all events
- Prefer immutable event data classes
"""
}
