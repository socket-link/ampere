package link.socket.ampere.domain.arc

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.definition.SparkAgentFactory
import link.socket.ampere.agents.domain.event.GoalEvent
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.goal.GoalBranch
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

        val spawnScope = CoroutineScope(SupervisorJob())
        val agents = try {
            ArcAgentSpawner(
                SparkAgentFactory(
                    scope = spawnScope,
                    workspace = ExecutionWorkspace(baseDirectory = "/tmp/ampr300-test-workspace"),
                ),
            ).spawn(arcConfig, context)
        } finally {
            spawnScope.cancel()
        }

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

        val result = ChargePhase(
            arcConfig = arcConfig,
            projectDir = tempDir.toString().toPath(),
            agentScope = backgroundScope,
        ).execute("Implement auth and add tests")

        assertEquals("ChargeProject", result.projectContext.projectId)
        assertTrue(result.projectContext.techStack.isNotEmpty())
        assertEquals("Implement auth and add tests", result.goalTree.root.description)
        assertEquals(1, result.agents.size)
    }

    /**
     * AMPR-419: Charge puts the tree it builds onto the Goal branch, and what it hands Flow is the
     * tree read back from the store — so a second Charge of the same run gets the stored tree and
     * forms nothing new.
     */
    @Test
    fun `charge phase persists its goal tree and hands back the stored one`() = runBlocking<Unit> {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handle = InMemoryEventApi.open(agentId = CompletionManifestSink.DEFAULT_AGENT_ID, scope = scope)
        try {
            val branch = GoalBranch.over(handle.database, handle.api)
            val projectDir = chargeableProjectDir()
            val runId = "ampr419-charge"
            val userGoal = "Implement auth and add tests"
            fun charge() = ChargePhase(
                arcConfig = ArcConfig(name = "charge-goal-arc", agents = listOf(ArcAgentConfig(role = "code"))),
                projectDir = projectDir,
                agentScope = scope,
                runId = runId,
                goalBranch = branch,
            )
            suspend fun formedCount() =
                handle.repository.getAllEvents().getOrThrow().count { it is GoalEvent.GoalFormed }

            val first = charge().execute(userGoal)

            assertEquals(GoalTreeBuilder().build(userGoal), first.goalTree)
            assertEquals(first.goalTree, branch.tree(runId).getOrThrow())
            assertEquals(3, formedCount(), "one GoalFormed per node")

            val second = charge().execute(userGoal)

            assertEquals(first.goalTree, second.goalTree)
            assertEquals(3, formedCount(), "a tree already on the branch is not formed again")
        } finally {
            scope.cancel()
            handle.close()
        }
    }

    /** A project [ChargePhase] accepts: sources present, with a tech stack, architecture and conventions. */
    private fun chargeableProjectDir() = createTempDirectory("charge-goal-branch").also { dir ->
        dir.resolve("README.md").writeText("# GoalBranchProject\n\nA project whose goals are persisted.")
        dir.resolve("AGENTS.md").writeText(
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
    }.toString().toPath()
}
