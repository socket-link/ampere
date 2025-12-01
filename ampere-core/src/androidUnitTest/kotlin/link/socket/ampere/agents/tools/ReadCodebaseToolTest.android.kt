package link.socket.ampere.agents.tools

import java.io.File
import junit.framework.TestCase.assertEquals
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking

actual class ReadCodebaseToolTest {

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
            val outcome = tool.execute(mapOf("path" to "Example.txt"))

            assertEquals(true, outcome.success)
            assertEquals(content, (outcome.result as String).trim())
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
            val outcome = tool.execute(mapOf("path" to "."))

            assertEquals(true, outcome.success)
            val listing = outcome.result as String
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
            val outcome = tool.execute(mapOf("path" to "missing.txt"))
            assertEquals(false, outcome.success)
            assertEquals(null, outcome.result)
            assertEquals(true, (outcome.errorMessage ?: "").contains("does not exist"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    actual fun `execute blocks traversal outside root directory`() = runBlocking {
        val tempDir = createTempDirectory(prefix = "ampere-read-sandbox-")
        try {
            val tool = ReadCodebaseTool(tempDir.absolutePathString())
            val outcome = tool.execute(mapOf("path" to "../outside.txt"))
            assertEquals(false, outcome.success)
            assertEquals(null, outcome.result)
            assertEquals(true, (outcome.errorMessage ?: "").contains("outside root"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
