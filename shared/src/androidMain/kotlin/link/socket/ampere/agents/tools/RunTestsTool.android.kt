package link.socket.ampere.agents.tools

import java.io.File
import link.socket.ampere.agents.core.AutonomyLevel
import link.socket.ampere.agents.core.Outcome

/**
 * Android actual for RunTestsTool using the Gradle wrapper.
 */
actual class RunTestsTool actual constructor(
    private val projectRoot: String,
) : Tool {
    actual override val name: String = "run_tests"
    actual override val description: String = "Executes tests and returns results"
    actual override val requiredAutonomyLevel: AutonomyLevel = AutonomyLevel.FULLY_AUTONOMOUS

    actual override suspend fun execute(parameters: Map<String, Any?>): Outcome {
        val testPath = parameters["testPath"] as? String

        return try {
            val args = mutableListOf("./gradlew")
            if (testPath != null) {
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

            Outcome(
                success = exitCode == 0,
                result = output,
                errorMessage = if (exitCode != 0) "Tests failed" else null,
            )
        } catch (e: Exception) {
            Outcome(false, null, "Failed to run tests: ${e.message}")
        }
    }

    actual override fun validateParameters(parameters: Map<String, Any>): Boolean {
        val tp = parameters["testPath"]
        return tp == null || tp is String
    }
}
