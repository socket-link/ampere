package link.socket.ampere.api

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import link.socket.ampere.domain.ai.configuration.AIConfiguration_WithBackups

class AmpereConfigYamlTest {

    @Test
    fun `fromYaml forwards api keys into provider configurations`() {
        val configFile = Files.createTempFile("ampere-config", ".yaml")
        configFile.writeText(
            """
            ai:
              provider: anthropic
              model: sonnet-4
              apiKey: anthro-from-yaml
              backups:
                - provider: openai
                  model: gpt-4.1
                  apiKey: openai-from-yaml
            """.trimIndent(),
        )

        try {
            val config = AmpereConfig.Builder().apply {
                fromYaml(configFile.toString())
            }.build()

            val aiConfiguration = assertIs<AIConfiguration_WithBackups>(config.provider.toAIConfiguration())
            assertEquals(
                listOf("anthro-from-yaml", "openai-from-yaml"),
                aiConfiguration.configurations.map { it.provider.apiToken },
            )
            assertEquals(
                listOf("anthropic", "openai"),
                aiConfiguration.configurations.map { it.provider.id },
            )
        } finally {
            configFile.deleteIfExists()
        }
    }
}
