package link.socket.ampere.demo

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JazzTestRunnerTest {

    @Test
    fun `findGeneratedJazzFiles returns null when task command missing`() {
        val tempDir = createTempDirectory("jazz-test").toFile()
        try {
            val result = findGeneratedJazzFiles(tempDir)
            assertNull(result)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `findGeneratedJazzFiles detects task command and optional ampere command`() {
        val tempDir = createTempDirectory("jazz-test").toFile()
        try {
            val baseDir = File(tempDir, "ampere-cli/src/jvmMain/kotlin/link/socket/ampere")
            baseDir.mkdirs()

            val taskCommandFile = File(baseDir, "TaskCommand.kt")
            taskCommandFile.writeText("class TaskCommand")

            val withoutAmpere = findGeneratedJazzFiles(tempDir)
            assertNotNull(withoutAmpere)
            assertEquals(taskCommandFile.absolutePath, withoutAmpere.taskCommand.absolutePath)
            assertNull(withoutAmpere.ampereCommand)

            val ampereCommandFile = File(baseDir, "AmpereCommand.kt")
            ampereCommandFile.writeText("class AmpereCommand")

            val withAmpere = findGeneratedJazzFiles(tempDir)
            assertNotNull(withAmpere)
            assertEquals(taskCommandFile.absolutePath, withAmpere.taskCommand.absolutePath)
            assertEquals(ampereCommandFile.absolutePath, withAmpere.ampereCommand?.absolutePath)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
