package link.socket.ampere.agents.domain.cognition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AMPR-414: `FileAccessScope` can gate a real path.
 *
 * Two halves, and the second is the one that used to be wrong. The matcher is
 * the easy half — the glob syntax the KDoc always claimed, implemented in pure
 * `commonMain` because `java.nio.file.PathMatcher` does not exist on four of
 * the five targets. Composition is the hard half: `intersect` was literal set
 * intersection over pattern *strings*, so intersecting the any-path pattern
 * with a Kotlin-extension one came out *empty* — the two strings are not
 * equal, never mind that one subsumes the other — and a gate built on that
 * would have denied every agent everything on day one.
 */
class FileAccessScopeTest {

    // ==================== MATCHER ====================

    @Test
    fun `single star does not cross a path separator`() {
        assertTrue(FileAccessScope.matches("*.kt", "Foo.kt"))
        assertFalse(FileAccessScope.matches("*.kt", "src/Foo.kt"))
        assertTrue(FileAccessScope.matches("src/*.kt", "src/Foo.kt"))
        assertFalse(FileAccessScope.matches("src/*.kt", "src/main/Foo.kt"))
    }

    @Test
    fun `double star crosses separators and matches no segments at all`() {
        assertTrue(FileAccessScope.matches("**/*.kt", "src/main/kotlin/Foo.kt"))
        assertTrue(
            FileAccessScope.matches("**/*.kt", "Foo.kt"),
            "a leading **/ has to match zero directories or a root-level file is unreachable",
        )
        assertTrue(FileAccessScope.matches("**/build/**", "ampere-core/build/out.kt"))
        assertFalse(FileAccessScope.matches("**/build/**", "ampere-core/src/out.kt"))
    }

    @Test
    fun `question mark matches exactly one character within a segment`() {
        assertTrue(FileAccessScope.matches("?.kt", "A.kt"))
        assertFalse(FileAccessScope.matches("?.kt", "Ab.kt"))
        assertFalse(FileAccessScope.matches("?.kt", "/.kt"))
    }

    @Test
    fun `character classes support sets and ranges and negation`() {
        assertTrue(FileAccessScope.matches("[abc].kt", "b.kt"))
        assertFalse(FileAccessScope.matches("[abc].kt", "d.kt"))
        assertTrue(FileAccessScope.matches("[a-z]oo.kt", "foo.kt"))
        assertFalse(FileAccessScope.matches("[a-z]oo.kt", "Foo.kt"))
        assertTrue(FileAccessScope.matches("[!a]oo.kt", "boo.kt"))
        assertFalse(FileAccessScope.matches("[!a]oo.kt", "aoo.kt"))
    }

    @Test
    fun `a path with no leading dot slash matches the same as one with it`() {
        val scope = FileAccessScope(writePatterns = setOf("src/**/*.kt"))

        assertTrue(scope.allowsWrite("src/main/Foo.kt"))
        assertTrue(scope.allowsWrite("./src/main/Foo.kt"))
        assertTrue(scope.allowsWrite("/src/main/Foo.kt"))
        assertTrue(scope.allowsWrite("src//main/Foo.kt"))
        assertTrue(scope.allowsWrite("src/./main/Foo.kt"))
    }

    @Test
    fun `a trailing star in a segment matches an extension suffix`() {
        assertTrue(FileAccessScope.matches("**/*.gradle*", "build.gradle.kts"))
        assertTrue(FileAccessScope.matches("**/*.gradle*", "module/build.gradle"))
        assertFalse(FileAccessScope.matches("**/*.gradle*", "module/gradle.properties"))
    }

    // ==================== GATE ====================

    @Test
    fun `a forbidden pattern overrides an allowed one`() {
        val scope = FileAccessScope(
            readPatterns = setOf("**/*"),
            writePatterns = setOf("**/*.kt"),
            forbiddenPatterns = setOf("**/build/**", "**/.env"),
        )

        assertTrue(scope.allowsWrite("src/Foo.kt"))
        assertFalse(
            scope.allowsWrite("build/generated/Foo.kt"),
            "the write pattern matches but the deny-list wins",
        )
        assertFalse(
            scope.allowsRead("build/generated/Foo.kt"),
            "one deny-list spans reads and writes alike",
        )
        assertEquals("**/build/**", scope.forbiddingPattern("build/generated/Foo.kt"))
        assertEquals(null, scope.forbiddingPattern("src/Foo.kt"))
    }

    @Test
    fun `an empty pattern set denies rather than waving everything through`() {
        assertFalse(FileAccessScope.ReadOnly.allowsWrite("src/Foo.kt"))
        assertTrue(FileAccessScope.ReadOnly.allowsRead("src/Foo.kt"))
        assertFalse(FileAccessScope.NoAccess.allowsRead("src/Foo.kt"))
        assertFalse(FileAccessScope.NoAccess.allowsWrite("src/Foo.kt"))
        assertTrue(FileAccessScope.Permissive.allowsWrite("anything/at/all.txt"))
    }

    // ==================== SUBSUMPTION ====================

    @Test
    fun `any-path subsumes an extension pattern but not the reverse`() {
        assertTrue(FileAccessScope.subsumes("**/*", "**/*.kt"))
        assertFalse(FileAccessScope.subsumes("**/*.kt", "**/*"))
    }

    @Test
    fun `any-path subsumes a directory pattern the segment walk cannot align`() {
        // `**/*` is how a spark says "I constrain nothing on this axis", so it
        // has to subsume every pattern — including shapes whose segments do
        // not line up with its own.
        assertTrue(FileAccessScope.subsumes("**/*", "docs/**"))
        assertTrue(FileAccessScope.subsumes("**/*", "**/build/**"))
        assertTrue(FileAccessScope.subsumes("**/*", "src/main/Foo.kt"))
        assertTrue(FileAccessScope.subsumes("**", "docs/**"))
    }

    @Test
    fun `subsumption reads an extension suffix rather than string equality`() {
        assertTrue(FileAccessScope.subsumes("**/*.kt", "**/*Test.kt"))
        assertFalse(FileAccessScope.subsumes("**/*.kt", "**/*.kts"))
        assertFalse(FileAccessScope.subsumes("**/*.kt", "**/*.java"))
        assertTrue(FileAccessScope.subsumes("**/*.kt", "src/**/*.kt"))
        assertFalse(FileAccessScope.subsumes("src/**/*.kt", "**/*.kt"))
    }

    @Test
    fun `subsumption never claims a containment that does not hold`() {
        // The reverse of every honest claim above, plus the shape that broke
        // the naive glob-as-path trick: `?.kt` matches a subset of `*.kt`
        // paths, so it must not come out as the broader of the two.
        assertFalse(FileAccessScope.subsumes("?.kt", "*.kt"))
        assertTrue(FileAccessScope.subsumes("*.kt", "?.kt"))
        assertFalse(FileAccessScope.subsumes("docs/**", "**/*.md"))
        assertFalse(FileAccessScope.subsumes("**/*.md", "docs/**"))
    }

    // ==================== COMPOSITION ====================

    @Test
    fun `intersect keeps the narrower of two subsuming patterns`() {
        val broad = FileAccessScope(
            readPatterns = setOf("**/*"),
            writePatterns = setOf("**/*.kt", "**/*.md", "**/*.java"),
        )
        val narrow = FileAccessScope(
            readPatterns = setOf("**/*.kt", "**/*.kts"),
            writePatterns = setOf("**/*.kt", "**/*.kts"),
        )

        val composed = broad.intersect(narrow)

        assertEquals(setOf("**/*.kt", "**/*.kts"), composed.readPatterns)
        assertEquals(
            setOf("**/*.kt"),
            composed.writePatterns,
            "`**/*.kts` is not in the broad side and `**/*.md` is not in the narrow one",
        )
    }

    @Test
    fun `intersect unions the deny-lists`() {
        val left = FileAccessScope(
            writePatterns = setOf("**/*"),
            forbiddenPatterns = setOf("**/build/**"),
        )
        val right = FileAccessScope(
            writePatterns = setOf("**/*"),
            forbiddenPatterns = setOf("**/.env"),
        )

        val composed = left.intersect(right)

        assertEquals(setOf("**/build/**", "**/.env"), composed.forbiddenPatterns)
        assertFalse(composed.allowsWrite("build/out.kt"))
        assertFalse(composed.allowsWrite(".env"))
        assertTrue(composed.allowsWrite("src/Foo.kt"))
    }

    @Test
    fun `an empty side still collapses the intersection`() {
        val composed = FileAccessScope.Permissive.intersect(FileAccessScope.ReadOnly)

        assertTrue(composed.allowsRead("src/Foo.kt"))
        assertFalse(
            composed.allowsWrite("src/Foo.kt"),
            "a spark that denies all writes must keep denying them once composed",
        )
    }

    @Test
    fun `composition is monotone over a corpus of paths`() {
        val scopes = listOf(
            FileAccessScope.Permissive,
            FileAccessScope.ReadOnly,
            FileAccessScope.NoAccess,
            FileAccessScope(
                readPatterns = setOf("**/*"),
                writePatterns = setOf("**/*.kt", "**/*.md"),
                forbiddenPatterns = setOf("**/build/**"),
            ),
            FileAccessScope(
                readPatterns = setOf("**/*.kt", "**/*.kts"),
                writePatterns = setOf("**/*.kt"),
            ),
            FileAccessScope(
                readPatterns = setOf("docs/**"),
                writePatterns = setOf("docs/**"),
                forbiddenPatterns = FileAccessScope.SensitiveFileForbiddenPatterns,
            ),
        )

        for (left in scopes) {
            for (right in scopes) {
                val composed = left.intersect(right)
                for (path in CORPUS) {
                    if (composed.allowsRead(path)) {
                        assertTrue(
                            left.allowsRead(path) && right.allowsRead(path),
                            "composing widened read access to $path",
                        )
                    }
                    if (composed.allowsWrite(path)) {
                        assertTrue(
                            left.allowsWrite(path) && right.allowsWrite(path),
                            "composing widened write access to $path",
                        )
                    }
                }
            }
        }
    }

    private companion object {
        val CORPUS = listOf(
            "Foo.kt",
            "src/Foo.kt",
            "src/commonMain/kotlin/link/socket/ampere/Thing.kt",
            "build.gradle.kts",
            "build/generated/Thing.kt",
            "docs/concepts/spark-system.md",
            "README.md",
            ".env",
            ".env.local",
            "config/app.yaml",
            "logs/run.log",
            "secrets.json",
            "keys/service.pem",
        )
    }
}
