package link.socket.ampere.agents.tools

import java.io.File
import junit.framework.TestCase.assertEquals
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

actual class RunTestsToolTest {

    private fun makeFakeGradleProject(exitCode: Int = 0, captureArgs: Boolean = false): File {
        val dir = createTempDirectory(prefix = "ampere-run-tests-").toFile()
        // Create fake gradlew script
        val script = File(dir, "gradlew")
        val content = buildString {
            appendLine("#!/bin/sh")
            // Print to stdout
            appendLine("echo '[STDOUT] Gradle wrapper invoked'")
            // Print to stderr
            appendLine("echo '[STDERR] Some warning' 1>&2")
            if (captureArgs) {
                appendLine("echo \"[ARGS] $@\"")
            }
            appendLine("exit $exitCode")
        }
        script.writeText(content)
        script.setExecutable(true)
        // Minimal settings file so Gradle would normally exist in a real project, but not needed for fake script
        File(dir, "settings.gradle.kts").writeText("rootProject.name=\"fake\"\n")
        return dir
    }

    @Test
    actual fun `validateParameters allows optional testPath`() {
        val tool = RunTestsTool(".")
        assertEquals(true, tool.validateParameters(emptyMap()))
        assertEquals(true, tool.validateParameters(mapOf("testPath" to "com.example.MyTest")))
        assertEquals(false, tool.validateParameters(mapOf("testPath" to 123)))
    }

    @Test
    actual fun `execute runs tests and captures output success`() = runBlocking {
        val project = makeFakeGradleProject(exitCode = 0)
        try {
            val tool = RunTestsTool(project.absolutePath)
            val outcome = tool.execute(emptyMap())
            assertEquals(true, outcome.success)
            val out = outcome.result as String
            assertEquals(true, out.contains("[STDOUT]"))
            assertEquals(true, out.contains("[STDERR]"))
            assertEquals(null, outcome.errorMessage)
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    actual fun `execute passes test filter when provided`() = runBlocking {
        val project = makeFakeGradleProject(exitCode = 0, captureArgs = true)
        try {
            val tool = RunTestsTool(project.absolutePath)
            val outcome = tool.execute(mapOf("testPath" to "com.example.MyTest"))
            assertEquals(true, outcome.success)
            val out = outcome.result as String
            // Expect gradle args to include: test --tests com.example.MyTest
            assertEquals(true, out.contains("[ARGS] test --tests com.example.MyTest"))
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    actual fun `execute handles failure gracefully`() = runBlocking {
        val project = makeFakeGradleProject(exitCode = 1)
        try {
            val tool = RunTestsTool(project.absolutePath)
            val outcome = tool.execute(emptyMap())
            assertEquals(false, outcome.success)
            assertEquals(true, (outcome.result as String).contains("Gradle wrapper invoked"))
            assertEquals("Tests failed", outcome.errorMessage)
        } finally {
            project.deleteRecursively()
        }
    }
}
