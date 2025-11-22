package link.socket.kore.ui.agent.setup

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import link.socket.kore.domain.agent.bundled.AgentDefinition
import link.socket.kore.domain.ai.configuration.AIConfiguration
import link.socket.kore.domain.ai.configuration.AIConfigurationFactory
import link.socket.kore.domain.ai.model.AIModel
import link.socket.kore.domain.ai.provider.AIProvider
import link.socket.kore.ui.model.selection.Importance
import link.socket.kore.ui.model.selection.SelectableModel
import link.socket.kore.ui.model.selection.toAIConfiguration

@Immutable
data class AgentSetupState(
    val modelSuggestionDefault: SelectableModel,
    val modelSuggestionBackups: List<SelectableModel>,
    val selectedModel: SelectableModel = modelSuggestionDefault,
) {
    fun getSelectedAIConfiguration(): AIConfiguration =
        selectedModel.toAIConfiguration()
}

@Immutable
class AgentSetupViewModel(
    aiConfigurationFactory: AIConfigurationFactory,
    selectedAgentDefinition: AgentDefinition,
) {
    private val suggestedAIConfig =
        selectedAgentDefinition.suggestedAIConfigurationBuilder(aiConfigurationFactory)

    private val suggestedModel: AIModel =
        suggestedAIConfig.model

    private val suggestedModelBackups: List<Pair<AIProvider<*, *>, AIModel>> =
        suggestedAIConfig.getAvailableModels()
            // TODO: Move this functionality into AIConfiguration
            .filter { model ->
                model.second.name != suggestedModel.name
            }

    private val suggestedSelectableModel: SelectableModel =
        SelectableModel(
            model = suggestedModel,
            provider = suggestedAIConfig.provider,
            // TODO: Add features/tags for each model
            featureNotes = listOf(
                "test" to Importance.CRITICAL,
                "test2" to Importance.NOT_CRITICAL,
            ),
            tags = listOf("test", "test2"),
        )

    private val suggestedSelectableModelBackups: List<SelectableModel> =
        suggestedModelBackups.map { (provider, model) ->
            SelectableModel(
                model = model,
                provider = provider,
                // TODO: Add features/tags for each model
                featureNotes = listOf(
                    "test" to Importance.CRITICAL,
                    "test2" to Importance.NOT_CRITICAL,
                ),
                tags = listOf("test", "test2"),
            )
        }

    private val _stateFlow: MutableStateFlow<AgentSetupState> = MutableStateFlow(
        AgentSetupState(
            modelSuggestionDefault = suggestedSelectableModel,
            modelSuggestionBackups = suggestedSelectableModelBackups,
        ),
    )

    val stateFlow: StateFlow<AgentSetupState> =
        _stateFlow.asStateFlow()

    fun onModelSelected(model: SelectableModel) {
        _stateFlow.value = _stateFlow.value.copy(
            selectedModel = model,
        )
    }
}
