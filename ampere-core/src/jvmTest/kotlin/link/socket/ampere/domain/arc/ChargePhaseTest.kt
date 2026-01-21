package link.socket.ampere.domain.arc

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath

class ChargePhaseTest {

    @Test
    fun `project context extractor captures tech stack conventions architecture`() {
        val tempDir = createTempDirectory("charge-context")
        tempDir.resolve("README.md").writeText(
            """
            # SampleProject

            Sample description for testing.
            """.trimIndent(),
        )
        tempDir.resolve("AGENTS.md").writeText(
            """
            # AGENTS

            ## Important Dependencies
            - Kotlin
            - Ktor

            ## Kotlin Style
            - Use data classes

            ## Architecture
            - Layered modules
            """.trimIndent(),
        )

        val context = ProjectContextExtractor(tempDir.toString().toPath(), FileSystem.SYSTEM).extract()

        assertEquals("SampleProject", context.projectId)
        assertTrue(context.techStack.any { it.contains("Kotlin") })
        assertTrue(context.conventions.contains("data classes"))
        assertTrue(context.architecture.contains("Layered modules"))
    }

    @Test
    fun `goal tree builder splits goal into parts`() {
        val tree = GoalTreeBuilder().build("Ship login and add tests; update docs")

        assertEquals(3, tree.root.children.size)
        assertEquals(
            listOf("Ship login", "add tests", "update docs"),
            tree.root.children.map { it.description },
        )
    }

    @Test
    fun `agent spawner applies role and additional sparks`() {
        val tempDir = createTempDirectory("charge-agents")
        val projectRoot = tempDir.toString().toPath()

        val context = ProjectContext(
            projectId = "demo",
            description = "Demo project",
            repositoryRoot = projectRoot,
            architecture = "Layered",
            conventions = "Use Kotlin",
            techStack = listOf("Kotlin"),
            sources = listOf(ProjectContextSource(projectRoot / "README.md", "Demo")),
        )

        val arcConfig = ArcConfig(
            name = "demo-arc",
            agents = listOf(
                ArcAgentConfig(role = "pm", sparks = listOf("vision")),
                ArcAgentConfig(role = "code", sparks = listOf("kotlin")),
            ),
        )

        val agents = ArcAgentSpawner().spawn(arcConfig, context)

        assertEquals(2, agents.size)

        val pmState = agents[0].cognitiveState
        assertTrue(pmState.contains("Role:Planning"))
        assertTrue(pmState.contains("Custom:vision"))

        val codeState = agents[1].cognitiveState
        assertTrue(codeState.contains("Role:Code"))
        assertTrue(codeState.contains("Language:Kotlin"))
    }

    @Test
    fun `charge phase produces full result`() = runTest {
        val tempDir = createTempDirectory("charge-phase")
        tempDir.resolve("README.md").writeText(
            """
            # ChargeProject

            Project description for charge phase.
            """.trimIndent(),
        )
        tempDir.resolve("AGENTS.md").writeText(
            """
            # AGENTS

            ## Dependencies
            - Kotlin

            ## Conventions
            - Use Kotlin style

            ## Architecture
            - Modular design
            """.trimIndent(),
        )

        val arcConfig = ArcConfig(
            name = "charge-arc",
            agents = listOf(
                ArcAgentConfig(role = "code"),
            ),
        )

        val result = ChargePhase(arcConfig, tempDir.toString().toPath()).execute("Implement auth and add tests")

        assertEquals("ChargeProject", result.projectContext.projectId)
        assertTrue(result.projectContext.techStack.isNotEmpty())
        assertEquals("Implement auth and add tests", result.goalTree.root.description)
        assertEquals(1, result.agents.size)
    }
}
