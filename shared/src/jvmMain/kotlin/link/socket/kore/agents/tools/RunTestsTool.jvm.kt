package link.socket.kore.agents.tools

import java.io.File
import link.socket.kore.agents.core.AutonomyLevel
import link.socket.kore.agents.core.Outcome
import link.socket.kore.agents.events.tasks.Task

/**
 * JVM implementation that executes Gradle tests for a project located at [projectRoot].
 * Uses ProcessBuilder to invoke the Gradle wrapper and returns combined output.
 */
actual class RunTestsTool actual constructor(
    private val projectRoot: String,
) : Tool {
    actual override val id: ToolId = "run_tests"
    actual override val name: String = "Verify Gradle Tests"
    actual override val description: String = "Executes tests and returns results"
    actual override val requiredAutonomyLevel: AutonomyLevel = AutonomyLevel.FULLY_AUTONOMOUS

    actual override suspend fun execute(
        sourceTask: Task,
        parameters: Map<String, Any?>,
    ): Outcome {
        val testPath = parameters["testPath"] as? String

        return try {
            val args = mutableListOf("./gradlew")
            if (testPath != null) {
                // Use Gradle's --tests filter when provided
                args.add("test")
                args.add("--tests")
                args.add(testPath)
            } else {
                args.add("test")
            }

            val process = ProcessBuilder()
                .directory(File(projectRoot))
                .command(args)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                Outcome.Success.Full(sourceTask, output)
            } else {
                Outcome.Failure(sourceTask, "Tests failed")
            }
        } catch (e: Exception) {
            Outcome.Failure(sourceTask, "Failed to run tests: ${e.message}")
        }
    }

    actual override fun validateParameters(parameters: Map<String, Any>): Boolean {
        // testPath is optional; when provided it must be a String
        val tp = parameters["testPath"]
        return tp == null || tp is String
    }
}
