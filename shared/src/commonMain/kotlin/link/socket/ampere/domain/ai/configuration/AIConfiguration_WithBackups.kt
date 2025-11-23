package link.socket.ampere.domain.ai.configuration

import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.provider.AIProvider

data class AIConfiguration_WithBackups(
    val configurations: List<AIConfiguration>,
) : AIConfiguration {

    private val mainConfiguration = configurations.first()
    private val backupConfigurationProvider = configurations.getOrNull(1)
    private val secondBackupConfigurationProvider = configurations.getOrNull(2)

    private var usedBackupConfiguration = false
    private var usedSecondBackupConfiguration = false

    override val provider: AIProvider<*, *>
        get() = try {
            val provider = mainConfiguration.provider
            // Try to invoke an exception
            provider.client
            provider
        } catch (e: Exception) {
            e.printStackTrace()

            try {
                val provider = backupConfigurationProvider?.provider
                    ?: throw IllegalStateException(
                        "Failed to initialize client for ${backupConfigurationProvider?.model?.name}",
                    )
                provider.client
                usedBackupConfiguration = true
                provider
            } catch (e: Exception) {
                e.printStackTrace()

                val provider = secondBackupConfigurationProvider?.provider
                    ?: throw IllegalStateException(
                        "Failed to initialize client for ${secondBackupConfigurationProvider?.model?.name}",
                    )
                provider.client
                usedSecondBackupConfiguration = true
                provider
            }
        }

    override val model: AIModel
        get() = if (usedSecondBackupConfiguration) {
            secondBackupConfigurationProvider?.model
                ?: throw IllegalStateException(
                    "Failed to initialize LLM for ${secondBackupConfigurationProvider?.model?.name}",
                )
        } else if (usedBackupConfiguration) {
            backupConfigurationProvider?.model
                ?: throw IllegalStateException(
                    "Failed to initialize LLM for ${backupConfigurationProvider?.model?.name}",
                )
        } else {
            mainConfiguration.model
        }

    override fun getAvailableModels(): List<Pair<AIProvider<*, *>, AIModel>> {
        return configurations.flatMap { configuration ->
            configuration.getAvailableModels()
        }
    }
}
