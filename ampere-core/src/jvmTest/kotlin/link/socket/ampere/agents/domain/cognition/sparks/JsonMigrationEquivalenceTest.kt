package link.socket.ampere.agents.domain.cognition.sparks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import link.socket.ampere.agents.domain.cognition.FileAccessScope

/**
 * Regression guard against drift between the role `.spark.md` fixtures and
 * the frozen capability surface of the Kotlin role singletons they replaced.
 *
 * AMPR-165 also used this file (Wave 1) to assert YAML→JSON parse-equivalence
 * for the seven phase fixtures during the atomic migration. Those assertions
 * served the migration window and were removed once Wave 2 deleted the YAML
 * sources. These checks stay because role fixtures are capability-bearing.
 */
class JsonMigrationEquivalenceTest {

    @Test
    fun `role JSON fixtures match retired Kotlin singleton surfaces`() {
        RoleSparkFixtureExpectations.all.forEach { expected ->
            val role = parseRoleFixture(expected.id)

            assertEquals(expected.id, role.frontmatter.id)
            assertEquals(expected.name, role.frontmatter.name)
            assertEquals(expected.agentRole, role.frontmatter.agentRole)
            assertEquals(expected.requestedToolIds, role.frontmatter.requestedToolIds)
            assertEquals(expected.allowedTools, role.frontmatter.allowedTools)

            val parsedScope = role.frontmatter.fileAccessScope?.toDomain()
            assertNotNull(parsedScope, "role-${expected.id} fixture must declare a fileAccessScope")
            assertEquals(expected.fileAccessScope.readPatterns, parsedScope.readPatterns)
            assertEquals(expected.fileAccessScope.writePatterns, parsedScope.writePatterns)
            assertEquals(expected.fileAccessScope.forbiddenPatterns, parsedScope.forbiddenPatterns)

            assertEquals(
                expected.promptContribution.trim(),
                role.body.trim(),
                "role-${expected.id} body must match the retired singleton promptContribution",
            )

            val sensitive = FileAccessScope.SensitiveFileForbiddenPatterns
            assertTrue(
                sensitive.all { it in parsedScope.forbiddenPatterns },
                "role-${expected.id} fixture missing SensitiveFileForbiddenPatterns entries",
            )
        }
    }

    private fun parseRoleFixture(id: String): DeclarativeSparkSource.Role {
        val raw = readResource("files/sparks/role-$id.spark.md")
            ?: error("role-$id.spark.md is missing from production resources")
        val result = parseSpark(raw)
        assertIs<SparkParseResult.Ok>(result, "role-$id: ${(result as? SparkParseResult.Failed)?.error}")
        return assertIs<DeclarativeSparkSource.Role>(
            result.source,
            "role-$id: expected role variant, got ${result.source::class.simpleName}",
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
