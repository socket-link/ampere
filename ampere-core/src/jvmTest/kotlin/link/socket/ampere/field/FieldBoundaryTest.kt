package link.socket.ampere.field

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Boundary test for the leaf-module rule on `link.socket.ampere.field` (AMPR-415,
 * F-register row F6), in the shape of `TeamEventBoundaryTest` (AMPR-334).
 *
 * The Field primitives are the keystone every W2 ticket imports, so the dependency
 * direction is one-way: `events`, `messages`, `meetings`, `tickets` depend on `field`,
 * never the reverse. This test fails if a file under `field/` imports from any of the
 * packages below. Add to [FORBIDDEN_IMPORTS] when a new leaf consumer appears; never
 * remove from it to make a build pass.
 *
 * jvmTest because the rule walks the source tree with `java.io.File`; the package it
 * checks is `commonMain`, so covering it once on one target is enough.
 */
class FieldBoundaryTest {

    @Test
    fun `field imports nothing from the packages that depend on it`() {
        val offenders = fieldSources()
            .flatMap { file ->
                val relative = file.relativeTo(commonMainRoot()).invariantSeparatorsPath
                file.readLines().mapIndexedNotNull { index, line ->
                    val match = IMPORT_LINE.find(line) ?: return@mapIndexedNotNull null
                    val imported = match.groupValues[1]
                    if (FORBIDDEN_IMPORTS.any { imported.startsWith(it) }) "$relative:${index + 1}: $line" else null
                }
            }
            .sorted()

        assertTrue(
            offenders.isEmpty(),
            "link.socket.ampere.field is a leaf module: events, messages, meetings and " +
                "tickets depend on it, never the reverse. Offending imports:\n${offenders.joinToString("\n")}",
        )
    }

    @Test
    fun `field package exists and is non-empty`() {
        val sources = fieldSources()
        assertTrue(sources.isNotEmpty(), "No Kotlin sources found under $FIELD_PREFIX")
    }

    private fun fieldSources(): List<File> =
        File(commonMainRoot(), FIELD_PREFIX)
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    /**
     * Gradle runs jvmTest with the module directory as the working directory. Walk
     * up from there so the test also works when launched from the repo root or an
     * IDE with a different working directory.
     */
    private fun commonMainRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, COMMON_MAIN)
            if (candidate.isDirectory) return candidate
            val nested = File(dir, "ampere-core/$COMMON_MAIN")
            if (nested.isDirectory) return nested
            dir = dir.parentFile
        }
        error("Could not locate $COMMON_MAIN from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val COMMON_MAIN = "src/commonMain/kotlin"
        const val FIELD_PREFIX = "link/socket/ampere/field"

        val IMPORT_LINE = Regex("^\\s*import\\s+([\\w.]+)")

        /**
         * Package prefixes `field/` may never import. Each is a consumer of the
         * primitives: an import in this direction would make the keystone depend on
         * the arch it holds up.
         */
        val FORBIDDEN_IMPORTS = listOf(
            "link.socket.ampere.agents.domain.",
            "link.socket.ampere.agents.events.",
            "link.socket.ampere.tickets.",
            "link.socket.ampere.meetings.",
            "link.socket.ampere.messages.",
        )
    }
}
