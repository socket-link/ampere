package link.socket.ampere.demo

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AgentTestRunnerTest {

    @Test
    fun `findGeneratedFiles returns null when observability spark missing`() {
        val tempDir = createTempDirectory("agent-test").toFile()
        try {
            val result = findGeneratedFiles(tempDir)
            assertNull(result)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `findGeneratedFiles detects observability spark file`() {
        val tempDir = createTempDirectory("agent-test").toFile()
        try {
            val baseDir = File(tempDir, "ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/cognition/sparks")
            baseDir.mkdirs()

            val sparkFile = File(baseDir, "ObservabilitySpark.kt")
            sparkFile.writeText("sealed class ObservabilitySpark : Spark")

            val result = findGeneratedFiles(tempDir)
            assertNotNull(result)
            assertEquals(sparkFile.absolutePath, result.observabilitySpark.absolutePath)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
