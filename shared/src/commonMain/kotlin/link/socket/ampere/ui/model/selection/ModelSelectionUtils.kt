package link.socket.ampere.ui.model.selection

import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.provider.AIProvider.Companion.ALL_PROVIDERS

val ALL_AI_CONFIGURATIONS: List<AIConfiguration> = ALL_PROVIDERS
    .flatMap { provider ->
        provider.availableModels.mapNotNull { model ->
            /**
             * TODO: Figure out why [model] can be null here
             */
            @Suppress("SENSELESS_COMPARISON")
            if (model != null) {
                AIConfiguration_Default(
                    provider = provider,
                    model = model,
                )
            } else {
                null
            }
        }
    }

val ALL_SELECTABLE_MODELS: List<SelectableModel> = ALL_AI_CONFIGURATIONS
    .map { configuration ->
        SelectableModel(
            model = configuration.model,
            provider = configuration.provider,
            featureNotes = emptyList(), // TODO: Add feature notes for each model
            tags = emptyList(), // TODO: Add tags for each model
        )
    }

val ALL_FILTERED_MODEL_ROWS: List<FilteredModelCell> = ALL_SELECTABLE_MODELS
    .map { model ->
        FilteredModelCell(
            selectableModel = model,
            reasoningScore = "N/A",
            speedScore = "N/A",
            costPerRun = "N/A",
            rateLimitPerMin = "N/A",
            suitability = Suitability.Suitable,
        )
    }

fun SelectableModel.toAIConfiguration(): AIConfiguration =
    AIConfiguration_Default(provider, model)
