package link.socket.ampere.demo

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for GoldenOutput.
 *
 * Verifies the golden ObservabilitySpark.kt content is valid and complete.
 */
class GoldenOutputTest {

    // =========================================================================
    // Path Tests
    // =========================================================================

    @Test
    fun `golden output path is in sparks directory`() {
        assertTrue(
            GoldenOutput.OBSERVABILITY_SPARK_PATH.contains("sparks/"),
            "Golden output path should be in sparks directory"
        )
    }

    @Test
    fun `golden output path ends with ObservabilitySpark_kt`() {
        assertTrue(
            GoldenOutput.OBSERVABILITY_SPARK_PATH.endsWith("ObservabilitySpark.kt"),
            "Golden output path should end with ObservabilitySpark.kt"
        )
    }

    // =========================================================================
    // Content Structure Tests
    // =========================================================================

    @Test
    fun `golden output content has package declaration`() {
        assertTrue(
            GoldenOutput.OBSERVABILITY_SPARK_CONTENT.contains("package link.socket.ampere.agents.domain.cognition.sparks"),
            "Content should have correct package declaration"
        )
    }

    @Test
    fun `golden output content has Serializable import`() {
        assertTrue(
            GoldenOutput.OBSERVABILITY_SPARK_CONTENT.contains("import kotlinx.serialization.Serializable"),
            "Content should import @Serializable"
        )
    }

    @Test
    fun `golden output content has SerialName import`() {
        assertTrue(
            GoldenOutput.OBSERVABILITY_SPARK_CONTENT.contains("import kotlinx.serialization.SerialName"),
            "Content should import @SerialName"
        )
    }

    @Test
    fun `golden output content has Spark import`() {
        assertTrue(
            GoldenOutput.OBSERVABILITY_SPARK_CONTENT.contains("import link.socket.ampere.agents.domain.cognition.Spark"),
            "Content should import Spark interface"
        )
    }

    @Test
    fun `golden output content declares sealed class`() {
        assertTrue(
            GoldenOutput.OBSERVABILITY_SPARK_CONTENT.contains("sealed class ObservabilitySpark"),
            "Content should declare sealed class ObservabilitySpark"
        )
    }

    @Test
    fun `golden output content implements Spark interface`() {
        assertTrue(
            GoldenOutput.OBSERVABILITY_SPARK_CONTENT.contains(": Spark"),
            "ObservabilitySpark should implement Spark interface"
        )
    }

    @Test
    fun `golden output content has Verbose data object`() {
        assertTrue(
            GoldenOutput.OBSERVABILITY_SPARK_CONTENT.contains("data object Verbose"),
            "Content should have Verbose data object"
        )
    }

    @Test
    fun `golden output content has SerialName annotation for Verbose`() {
        assertTrue(
            GoldenOutput.OBSERVABILITY_SPARK_CONTENT.contains("@SerialName(\"ObservabilitySpark.Verbose\")"),
            "Verbose should have @SerialName annotation"
        )
    }

    @Test
    fun `golden output content sets allowedTools to null`() {
        assertTrue(
            GoldenOutput.OBSERVABILITY_SPARK_CONTENT.contains("override val allowedTools: Set<ToolId>? = null"),
            "allowedTools should be null (non-restrictive)"
        )
    }

    @Test
    fun `golden output content sets fileAccessScope to null`() {
        assertTrue(
            GoldenOutput.OBSERVABILITY_SPARK_CONTENT.contains("override val fileAccessScope: FileAccessScope? = null"),
            "fileAccessScope should be null (non-restrictive)"
        )
    }

    @Test
    fun `golden output content has promptContribution`() {
        assertTrue(
            GoldenOutput.OBSERVABILITY_SPARK_CONTENT.contains("override val promptContribution: String"),
            "Verbose should override promptContribution"
        )
    }

    @Test
    fun `golden output content mentions observability in prompt`() {
        assertTrue(
            GoldenOutput.OBSERVABILITY_SPARK_CONTENT.contains("Observability"),
            "Prompt contribution should mention observability"
        )
    }

    // =========================================================================
    // File Writing Tests
    // =========================================================================

    @Test
    fun `writeObservabilitySpark creates file with correct content`() {
        val tempDir = createTempDir("golden-output-test")
        try {
            val filePath = GoldenOutput.writeObservabilitySpark(tempDir)
            val file = File(filePath)

            assertTrue(file.exists(), "File should be created")
            assertEquals(
                GoldenOutput.OBSERVABILITY_SPARK_CONTENT,
                file.readText(),
                "File content should match golden output"
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `writeObservabilitySpark creates parent directories`() {
        val tempDir = createTempDir("golden-output-test")
        try {
            val filePath = GoldenOutput.writeObservabilitySpark(tempDir)
            val file = File(filePath)

            assertTrue(file.parentFile.exists(), "Parent directories should be created")
            assertTrue(file.parentFile.isDirectory, "Parent should be a directory")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `writeObservabilitySpark returns correct path`() {
        val tempDir = createTempDir("golden-output-test")
        try {
            val filePath = GoldenOutput.writeObservabilitySpark(tempDir)

            assertTrue(
                filePath.endsWith(GoldenOutput.OBSERVABILITY_SPARK_PATH),
                "Returned path should end with expected path"
            )
            assertTrue(
                filePath.startsWith(tempDir.absolutePath),
                "Returned path should be under output directory"
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun createTempDir(prefix: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "$prefix-${System.currentTimeMillis()}")
        dir.mkdirs()
        return dir
    }
}
