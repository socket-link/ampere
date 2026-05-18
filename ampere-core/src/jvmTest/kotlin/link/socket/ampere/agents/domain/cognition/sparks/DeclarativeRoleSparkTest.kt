package link.socket.ampere.agents.domain.cognition.sparks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.cognition.SparkStack
import link.socket.ampere.agents.domain.cognition.with

/**
 * Behaviour check for the declarative [DeclarativeRoleSpark] loaded from
 * `role-code.spark.md`. Asserts that on the same affinity, the fixture
 * produces the retired singleton's effective prompt, tool, role, and
 * file-access projections.
 */
class DeclarativeRoleSparkTest {

    @Test
    fun `declarative role spark mirrors retired Code role surface on a fixture stack`() {
        val expected = RoleSparkFixtureExpectations.code
        val declarative = loadDeclarativeRoleCode()

        val declarativeStack = SparkStack.withAffinity(CognitiveAffinity.ANALYTICAL).with(declarative)

        assertTrue(
            declarativeStack.buildSystemPrompt().contains(expected.promptContribution),
            "system prompt should include role-code prompt contribution",
        )
        assertEquals(expected.agentRole, declarativeStack.effectiveAgentRole())
        assertEquals(expected.requestedToolIds, declarativeStack.effectiveRequestedTools())
        assertEquals(expected.allowedTools, declarativeStack.effectiveAllowedTools())
        assertEquals(expected.fileAccessScope, declarativeStack.effectiveFileAccess())
    }

    @Test
    fun `declarative role spark name preserves Role colon Subtype trace bucket`() {
        val declarative = loadDeclarativeRoleCode()
        assertEquals("Role:Code", declarative.name)
        assertEquals("code", declarative.sparkId)
    }

    @Test
    fun `narrowing composes via SparkStack intersection`() {
        // A second spark that further narrows to write_code_file only must
        // intersect with the declarative role-code's allowed tools, never
        // expand them — the SparkStack does the work; the adapter just
        // surfaces the per-spark sets.
        val declarative = loadDeclarativeRoleCode()
        val narrowing = TestNarrowingSpark(
            allowedTools = setOf("write_code_file"),
            fileAccessScope = null,
        )
        val stack = SparkStack.withAffinity(CognitiveAffinity.ANALYTICAL).with(declarative, narrowing)

        assertEquals(setOf("write_code_file"), stack.effectiveAllowedTools())
    }

    private fun loadDeclarativeRoleCode(): DeclarativeRoleSpark {
        val raw = readResource("files/sparks/role-code.spark.md")
            ?: error("role-code.spark.md is missing from production resources")
        val result = parseSpark(raw)
        assertIs<SparkParseResult.Ok>(result, "role-code parse failed: ${(result as? SparkParseResult.Failed)?.error}")
        val role = assertIs<DeclarativeSparkSource.Role>(result.source)
        val converted = role.toRoleSpark()
        assertNotNull(converted)
        return converted
    }

    private fun readResource(path: String): String? {
        val candidates = listOf(
            path,
            "composeResources/link.socket.ampere.resources/$path",
        )
        val classLoaders = listOfNotNull(
            Thread.currentThread().contextClassLoader,
            DeclarativeRoleSparkTest::class.java.classLoader,
        )
        for (cl in classLoaders) {
            for (candidate in candidates) {
                cl.getResourceAsStream(candidate)?.use { stream ->
                    return stream.readBytes().decodeToString()
                }
            }
        }
        return null
    }

    private data class TestNarrowingSpark(
        override val allowedTools: Set<String>?,
        override val fileAccessScope: link.socket.ampere.agents.domain.cognition.FileAccessScope?,
    ) : link.socket.ampere.agents.domain.cognition.Spark {
        override val name: String = "Test:Narrowing"
        override val promptContribution: String = ""
    }
}
