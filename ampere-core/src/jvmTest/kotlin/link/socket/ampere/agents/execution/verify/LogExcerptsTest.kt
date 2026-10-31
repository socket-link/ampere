package link.socket.ampere.agents.execution.verify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogExcerptsTest {

    @Test
    fun `gradle's anchors are taken, and nothing else`() {
        val log = listOf(
            "> Task :ampere-core:compileKotlinJvm UP-TO-DATE",
            "FooTest > bar FAILED",
            "    java.lang.AssertionError at /Users/someone/work/ampere/FooTest.kt:12",
            "> Task :ampere-core:jvmTest FAILED",
            "",
            "FAILURE: Build failed with an exception.",
            "",
            "* What went wrong:",
            "Execution failed for task ':ampere-core:jvmTest'.",
            "> There were failing tests. See the report at: file:///Users/someone/work/ampere/build/index.html",
        )

        assertEquals(
            listOf(
                "> Task :ampere-core:jvmTest FAILED",
                "* What went wrong:",
                "Execution failed for task ':ampere-core:jvmTest'.",
            ),
            LogExcerpts.anchored(log),
        )
    }

    @Test
    fun `xcodebuild's markers are taken`() {
        val log = listOf("Testing started", "** TEST EXECUTE FAILED **", "Something else")
        assertEquals(listOf("** TEST EXECUTE FAILED **"), LogExcerpts.anchored(log))
    }

    @Test
    fun `the free-text line loses paths, home directories and URL credentials`() {
        val line = LogExcerpts.sanitize(
            "Could not read '/Users/someone/ampere/local.properties' via https://user:token@example.com/x and ~/secret",
        )
        assertFalse("/Users/someone" in line, line)
        assertFalse("token" in line, line)
        assertFalse("~/secret" in line, line)
        assertTrue(line.startsWith("Could not read '"), line)
    }

    @Test
    fun `a long line is cut`() {
        assertEquals(LogExcerpts.MAX_LINE, LogExcerpts.sanitize("x".repeat(500)).length)
    }
}
