package link.socket.ampere.agents.domain.cognition.sparks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.cognition.SparkStack
import link.socket.ampere.agents.domain.cognition.with

/**
 * Behaviour-equivalence check between the declarative [DeclarativeRoleSpark]
 * loaded from `role-code.spark.md` and the [RoleSpark.Code] singleton it
 * replaces. Asserts that on the same affinity, the two sparks produce
 * identical effective prompt, tool, role, and file-access projections —
 * which is the contract the AMPR-165 Wave 4 factory migration depends on.
 */
class DeclarativeRoleSparkTest {

    @Test
    fun `declarative role spark mirrors RoleSpark Code on a fixture stack`() {
        val declarative = loadDeclarativeRoleCode()

        val declarativeStack = SparkStack.withAffinity(CognitiveAffinity.ANALYTICAL).with(declarative)
        val singletonStack = SparkStack.withAffinity(CognitiveAffinity.ANALYTICAL).with(RoleSpark.Code)

        assertEquals(
            singletonStack.buildSystemPrompt().trim(),
            declarativeStack.buildSystemPrompt().trim(),
            "system prompt diverges between declarative and singleton role-code",
        )
        assertEquals(singletonStack.effectiveAgentRole(), declarativeStack.effectiveAgentRole())
        assertEquals(singletonStack.effectiveRequestedTools(), declarativeStack.effectiveRequestedTools())
        assertEquals(singletonStack.effectiveAllowedTools(), declarativeStack.effectiveAllowedTools())
        assertEquals(singletonStack.effectiveFileAccess(), declarativeStack.effectiveFileAccess())
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
