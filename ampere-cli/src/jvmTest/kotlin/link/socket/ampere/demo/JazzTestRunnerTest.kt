package link.socket.ampere.demo

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JazzTestRunnerTest {

    @Test
    fun `findGeneratedJazzFiles returns null when observability spark missing`() {
        val tempDir = createTempDirectory("jazz-test").toFile()
        try {
            val result = findGeneratedJazzFiles(tempDir)
            assertNull(result)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `findGeneratedJazzFiles detects observability spark file`() {
        val tempDir = createTempDirectory("jazz-test").toFile()
        try {
            val baseDir = File(tempDir, "ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/cognition/sparks")
            baseDir.mkdirs()

            val sparkFile = File(baseDir, "ObservabilitySpark.kt")
            sparkFile.writeText("sealed class ObservabilitySpark : Spark")

            val result = findGeneratedJazzFiles(tempDir)
            assertNotNull(result)
            assertEquals(sparkFile.absolutePath, result.observabilitySpark.absolutePath)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
