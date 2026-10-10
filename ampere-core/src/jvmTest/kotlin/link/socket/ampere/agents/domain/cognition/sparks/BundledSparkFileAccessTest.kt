package link.socket.ampere.agents.domain.cognition.sparks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.cognition.FileAccessScope
import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.agents.domain.cognition.SparkStack
import link.socket.ampere.agents.domain.cognition.with

/**
 * AMPR-414: the composed file access of the *bundled* sparks, for the stacks
 * production actually builds.
 *
 * The composition bug was only visible here. Each fixture in isolation looks
 * reasonable; it is the intersection of `role-code`, `project-ampere` and
 * `language-kotlin` — the stack `AgentFactory` gives every CODE agent — that
 * used to come out as "no write access to anything", because `ProjectSpark`
 * declared `writePatterns = emptySet()` and `∅ ∩ anything = ∅`.
 */
class BundledSparkFileAccessTest {

    @Test
    fun `the production CODE stack permits Kotlin sources and refuses the rest`() = runTest {
        val scope = codeAgentStack().effectiveFileAccess()

        assertTrue(scope.allowsWrite("src/Foo.kt"), scope.describeWrite("src/Foo.kt"))
        assertTrue(
            scope.allowsWrite("ampere-core/src/commonMain/kotlin/link/socket/ampere/Thing.kt"),
            scope.describeWrite("a deeply nested source file"),
        )
        assertTrue(scope.allowsWrite("build.gradle.kts"), scope.describeWrite("build.gradle.kts"))

        assertFalse(
            scope.allowsWrite("build/out.kt"),
            "`**/build/**` is on role-code's deny-list and a deny-list entry outranks every allow",
        )
        assertFalse(
            scope.allowsWrite(".env"),
            "the sensitive-file list arrives from both role-code and project-ampere",
        )
        assertFalse(
            scope.allowsWrite("README.md"),
            "role-code permits `**/*.md` but language-kotlin does not, and composition is " +
                "intersection — a CODE agent narrowed to Kotlin writes Kotlin",
        )
    }

    @Test
    fun `the production CODE stack reads what language-kotlin names`() = runTest {
        val scope = codeAgentStack().effectiveFileAccess()

        assertTrue(scope.allowsRead("src/Foo.kt"))
        assertTrue(scope.allowsRead("ampere-android/src/main/AndroidManifest.xml"))
        assertTrue(scope.allowsRead("module/build.gradle.kts"))
        assertFalse(
            scope.allowsRead("docs/concepts/spark-system.md"),
            "role-code reads anywhere but language-kotlin narrows reads to Kotlin-adjacent files",
        )
        assertFalse(scope.allowsRead("secrets.json"))
    }

    @Test
    fun `the production CODE stack composes to the narrower pattern set`() = runTest {
        val scope = codeAgentStack().effectiveFileAccess()

        assertEquals(
            setOf("**/*.kt", "**/*.kts"),
            scope.writePatterns,
            "literal string intersection produced the empty set here, which is the bug",
        )
        assertEquals(
            setOf("**/*.kt", "**/*.kts", "**/*.xml", "**/*.gradle*"),
            scope.readPatterns,
        )
        assertTrue(
            FileAccessScope.SensitiveFileForbiddenPatterns.all { it in scope.forbiddenPatterns },
            "the deny-list is a union so nothing drops out of it",
        )
        assertTrue("**/build/**" in scope.forbiddenPatterns)
    }

    @Test
    fun `an Operations agent can read source files but not write them`() = runTest {
        val registry = DefaultPhaseSparkLibrary.load()
        val role = assertNotNull(registry.roleSparkById(RoleSparkIds.OPERATIONS))
        val scope = SparkStack.withAffinity(CognitiveAffinity.OPERATIONAL)
            .with(role, requireProject(registry))
            .effectiveFileAccess()

        assertTrue(
            scope.allowsRead("ampere-core/src/commonMain/kotlin/link/socket/ampere/Thing.kt"),
            "an Operations agent is handed read_code_file; a deny-list spanning every source " +
                "extension left it unable to open a single file in a Kotlin repository",
        )
        assertFalse(
            scope.allowsWrite("ampere-core/src/commonMain/kotlin/link/socket/ampere/Thing.kt"),
            "not editing code is expressed by the write allow-list not naming source files",
        )
        assertTrue(scope.allowsWrite("config/app.yaml"))
        assertFalse(scope.allowsWrite(".env"))
    }

    @Test
    fun `adding any bundled capability spark never widens access`() = runTest {
        val registry = DefaultPhaseSparkLibrary.load()
        val sparks = buildList {
            for (id in ROLE_IDS) {
                add(assertNotNull(registry.roleSparkById(id), "role-$id"))
            }
            for (id in LANGUAGE_IDS) {
                add(assertNotNull(registry.languageSparkById(id), "language-$id"))
            }
            add(requireProject(registry))
        }

        for (first in sparks) {
            val before = SparkStack.withAffinity(CognitiveAffinity.ANALYTICAL)
                .with(first)
                .effectiveFileAccess()
            for (second in sparks) {
                if (second === first) continue
                val after = SparkStack.withAffinity(CognitiveAffinity.ANALYTICAL)
                    .with(first, second)
                    .effectiveFileAccess()
                for (path in CORPUS) {
                    if (after.allowsRead(path)) {
                        assertTrue(
                            before.allowsRead(path),
                            "pushing ${second.name} onto ${first.name} widened read access to $path",
                        )
                    }
                    if (after.allowsWrite(path)) {
                        assertTrue(
                            before.allowsWrite(path),
                            "pushing ${second.name} onto ${first.name} widened write access to $path",
                        )
                    }
                }
            }
        }
    }

    private suspend fun codeAgentStack(): SparkStack {
        val registry = DefaultPhaseSparkLibrary.load()
        // The order AgentFactory builds: the role spark at construction, then
        // the project spark, then the language spark.
        return SparkStack.withAffinity(CognitiveAffinity.ANALYTICAL).with(
            assertNotNull(registry.roleSparkById(RoleSparkIds.CODE), "role-code"),
            requireProject(registry),
            assertNotNull(registry.languageSparkById(LanguageSparkIds.KOTLIN), "language-kotlin"),
        )
    }

    private fun requireProject(registry: SparkRegistry): Spark =
        assertNotNull(registry.projectSparkById(ProjectSparkIds.AMPERE), "project-ampere")

    private fun FileAccessScope.describeWrite(subject: String): String =
        "$subject should be writable; write patterns were ${writePatterns.sorted()} " +
            "and forbidden ${forbiddenPatterns.sorted()}"

    private companion object {
        val ROLE_IDS = listOf(
            RoleSparkIds.CODE,
            RoleSparkIds.RESEARCH,
            RoleSparkIds.OPERATIONS,
            RoleSparkIds.PLANNING,
        )

        val LANGUAGE_IDS = listOf(
            LanguageSparkIds.KOTLIN,
            LanguageSparkIds.JAVA,
            LanguageSparkIds.TYPESCRIPT,
            LanguageSparkIds.PYTHON,
        )

        val CORPUS = listOf(
            "Foo.kt",
            "src/Foo.kt",
            "src/commonMain/kotlin/link/socket/ampere/Thing.kt",
            "src/Thing.java",
            "web/app.ts",
            "scripts/tool.py",
            "build.gradle.kts",
            "build/generated/Thing.kt",
            "node_modules/pkg/index.js",
            "docs/concepts/spark-system.md",
            "README.md",
            ".env",
            ".env.local",
            "config/app.yaml",
            "logs/run.log",
            "secrets.json",
            "keys/service.pem",
            ".git/config",
        )
    }
}
