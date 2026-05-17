package link.socket.ampere.agents.domain.cognition.sparks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import link.socket.ampere.agents.domain.cognition.FileAccessScope

/**
 * Regression guard against drift between the `role-code.spark.md` fixture and
 * the [RoleSpark.Code] Kotlin singleton it replaces at the
 * [link.socket.ampere.agents.definition.SparkBasedAgent] factory callsites.
 *
 * AMPR-165 also used this file (Wave 1) to assert YAML→JSON parse-equivalence
 * for the seven phase fixtures during the atomic migration. Those assertions
 * served the migration window and were removed once Wave 2 deleted the YAML
 * sources; this single role-code check stays because the fixture and the
 * singleton coexist until the follow-up that retires the singleton entirely.
 */
class JsonMigrationEquivalenceTest {

    @Test
    fun `role-code JSON fixture matches RoleSpark Code singleton`() {
        val role = parseRoleFixture()

        assertEquals("code", role.frontmatter.id)
        assertEquals(RoleSpark.Code.name, role.frontmatter.name)
        assertEquals(RoleSpark.Code.agentRole, role.frontmatter.agentRole)
        assertEquals(RoleSpark.Code.requestedToolIds, role.frontmatter.requestedToolIds)
        assertEquals(RoleSpark.Code.allowedTools, role.frontmatter.allowedTools)

        val parsedScope = role.frontmatter.fileAccessScope?.toDomain()
        assertNotNull(parsedScope, "role-code fixture must declare a fileAccessScope")
        assertEquals(RoleSpark.Code.fileAccessScope.readPatterns, parsedScope.readPatterns)
        assertEquals(RoleSpark.Code.fileAccessScope.writePatterns, parsedScope.writePatterns)
        assertEquals(RoleSpark.Code.fileAccessScope.forbiddenPatterns, parsedScope.forbiddenPatterns)

        assertEquals(
            RoleSpark.Code.promptContribution.trim(),
            role.body.trim(),
            "role-code body must match RoleSpark.Code.promptContribution",
        )

        val sensitive = FileAccessScope.SensitiveFileForbiddenPatterns
        assertTrue(
            sensitive.all { it in parsedScope.forbiddenPatterns },
            "role-code fixture missing SensitiveFileForbiddenPatterns entries",
        )
    }

    private fun parseRoleFixture(): DeclarativeSparkSource.Role {
        val raw = readResource("files/sparks/role-code.spark.md")
            ?: error("role-code.spark.md is missing from production resources")
        val result = parseSpark(raw)
        assertIs<SparkParseResult.Ok>(result, "role-code: ${(result as? SparkParseResult.Failed)?.error}")
        return assertIs<DeclarativeSparkSource.Role>(
            result.source,
            "role-code: expected role variant, got ${result.source::class.simpleName}",
        )
    }

    private fun readResource(path: String): String? {
        val candidates = listOf(
            path,
            "composeResources/link.socket.ampere.resources/$path",
        )
        val classLoaders = listOfNotNull(
            Thread.currentThread().contextClassLoader,
            JsonMigrationEquivalenceTest::class.java.classLoader,
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
}
