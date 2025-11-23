package link.socket.ampere.agents.tools

import java.io.File
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.core.Outcome
import link.socket.ampere.agents.events.tasks.CodeChange

actual class ReadCodebaseToolTest {

    private val stubSourceTask = CodeChange("source", "")

    @Test
    actual fun `validateParameters requires path string`() {
        val tool = ReadCodebaseTool("/tmp")
        assertEquals(true, tool.validateParameters(mapOf("path" to "README.md")))
        assertEquals(false, tool.validateParameters(emptyMap()))
        assertEquals(false, tool.validateParameters(mapOf("path" to 123)))
    }

    @Test
    actual fun `execute reads file content`() = runBlocking {
        val tempDir = createTempDirectory(prefix = "ampere-read-")
        try {
            val base = tempDir.toFile()
            val file = File(base, "Example.txt")
            val content = "Hello, Ampere!".trim()
            file.writeText(content)

            val tool = ReadCodebaseTool(tempDir.absolutePathString())
            val outcome = tool.execute(stubSourceTask, mapOf("path" to "Example.txt"))

            assertIs<Outcome.Success.Full>(outcome)
            assertEquals(content, outcome.value.trim())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    actual fun `execute lists directory contents`() = runBlocking {
        val tempDir = createTempDirectory(prefix = "ampere-read-dir-")
        try {
            val base = tempDir.toFile()
            File(base, "a.txt").writeText("a")
            File(base, "b.txt").writeText("b")

            val tool = ReadCodebaseTool(tempDir.absolutePathString())
            val outcome = tool.execute(stubSourceTask, mapOf("path" to "."))

            assertIs<Outcome.Success.Full>(outcome)
            val listing = outcome.value
            assertNotNull(listing)
            assertEquals(true, listing.contains("a.txt"))
            assertEquals(true, listing.contains("b.txt"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    actual fun `execute returns error for non-existent path`() = runBlocking {
        val tempDir = createTempDirectory(prefix = "ampere-read-missing-")
        try {
            val tool = ReadCodebaseTool(tempDir.absolutePathString())
            val outcome = tool.execute(stubSourceTask, mapOf("path" to "missing.txt"))

            assertIs<Outcome.Failure>(outcome)
            assertEquals(true, outcome.errorMessage.contains("does not exist"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    actual fun `execute blocks traversal outside root directory`() = runBlocking {
        val tempDir = createTempDirectory(prefix = "ampere-read-sandbox-")
        try {
            val tool = ReadCodebaseTool(tempDir.absolutePathString())
            val outcome = tool.execute(stubSourceTask, mapOf("path" to "../outside.txt"))
            assertIs<Outcome.Failure>(outcome)
            assertEquals(true, outcome.errorMessage.contains("outside root"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
