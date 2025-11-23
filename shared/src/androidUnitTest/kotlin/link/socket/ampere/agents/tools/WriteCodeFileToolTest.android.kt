package link.socket.ampere.agents.tools

import java.io.File
import junit.framework.TestCase.assertEquals
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

actual class WriteCodeFileToolTest {

    @Test
    actual fun `validateParameters enforces filePath and content strings`() {
        val tool = WriteCodeFileTool("/tmp")
        assertEquals(true, tool.validateParameters(mapOf("filePath" to "A.kt", "content" to "fun a() = Unit")))
        assertEquals(false, tool.validateParameters(emptyMap()))
        assertEquals(false, tool.validateParameters(mapOf("filePath" to 1, "content" to "x")))
        assertEquals(false, tool.validateParameters(mapOf("filePath" to "x", "content" to 2)))
    }

    @Test
    actual fun `execute writes file with content and creates parent directories`() = runBlocking {
        val tempDir = createTempDirectory(prefix = "ampere-writer-")
        try {
            val tool = WriteCodeFileTool(tempDir.absolutePathString())
            val relativePath = "nested/dir/Example.kt"
            val content = "package example\n\nfun main() { println(\"Hi\") }\n"

            val outcome = tool.execute(mapOf("filePath" to relativePath, "content" to content))
            assertEquals(true, outcome.success)

            val file = File(tempDir.toFile(), relativePath)
            assertEquals(true, file.exists())
            assertEquals(content, file.readText())

            assertTrue(outcome.result is String)
            assertEquals(true, outcome.result.contains("File written:"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    actual fun `execute fails gracefully on invalid path`() = runBlocking {
        // Intentionally use an invalid base directory (file that cannot be a directory)
        val tempFile = createTempFile("ampere-writer-invalid-").toFile()
        try {
            val tool = WriteCodeFileTool(tempFile.absolutePath)
            val outcome = tool.execute(mapOf("filePath" to "sub/Bad.kt", "content" to "val x = 1"))
            assertEquals(false, outcome.success)
            assertEquals(null, outcome.result)
            assertEquals(true, outcome.errorMessage?.contains("Failed to write file"))
        } finally {
            tempFile.delete()
        }
    }
}
