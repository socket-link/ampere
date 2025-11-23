package link.socket.ampere.agents.tools

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.core.Outcome
import link.socket.ampere.agents.events.tasks.CodeChange

actual class RunTestsToolTest {

    private val stubSourceTask = CodeChange("source", "")

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
            val outcome = tool.execute(stubSourceTask, emptyMap())

            assertIs<Outcome.Success.Full>(outcome)
            val out = outcome.value

            assertEquals(true, out.contains("[STDOUT]"))
            assertEquals(true, out.contains("[STDERR]"))
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    actual fun `execute passes test filter when provided`() = runBlocking {
        val project = makeFakeGradleProject(exitCode = 0, captureArgs = true)
        try {
            val tool = RunTestsTool(project.absolutePath)
            val outcome = tool.execute(stubSourceTask, mapOf("testPath" to "com.example.MyTest"))
            assertIs<Outcome.Success.Full>(outcome)
            val out = outcome.value
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
            val outcome = tool.execute(stubSourceTask, emptyMap())
            assertIs<Outcome.Failure>(outcome)
            assertEquals("Tests failed", outcome.errorMessage)
        } finally {
            project.deleteRecursively()
        }
    }
}
