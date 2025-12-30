package link.socket.ampere.ui.model

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_Google
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI
import link.socket.ampere.ui.model.selection.FilterState
import link.socket.ampere.ui.model.selection.FilteredModelCell
import link.socket.ampere.ui.model.selection.Importance
import link.socket.ampere.ui.model.selection.ModelFilteredResultsSection
import link.socket.ampere.ui.model.selection.ModelFiltersSection
import link.socket.ampere.ui.model.selection.ModelRecommendationSection
import link.socket.ampere.ui.model.selection.ProviderFilter
import link.socket.ampere.ui.model.selection.SelectableModel
import link.socket.ampere.ui.model.selection.Suitability

@Preview
@Composable
private fun ModelAdvancedFiltersScreenPreview() {
    ModelFiltersSection(
        state = FilterState(
            providers = listOf(
                ProviderFilter(
                    key = "1",
                    label = "Provider 1",
                    isSelected = true,
                ),
                ProviderFilter(
                    key = "2",
                    label = "Provider 2",
                    isSelected = false,
                ),
            ),
        ),
        onStateChange = {},
        onReset = {},
    )
}

@Preview
@Composable
private fun ModelFilteredModelsSectionPreview() {
    ModelFilteredResultsSection(
        totalCount = 3,
        shownCount = 2,
        items = listOf(
            FilteredModelCell(
                selectableModel = SelectableModel(
                    model = AIModel_OpenAI.GPT_5,
                    provider = AIProvider_OpenAI,
                    featureNotes = listOf(
                        "Feature Note 1" to Importance.CRITICAL,
                        "Feature Note 2" to Importance.NOT_CRITICAL,
                    ),
                    tags = listOf("Tag 1", "Tag 2"),
                ),
                reasoningScore = "Reasoning Score",
                speedScore = "Speed Score",
                costPerRun = "Cost Per Run",
                rateLimitPerMin = "Rate Limit Per Min",
                suitability = Suitability.Suitable,
            ),
        ),
        onModelSelected = {},
    )
}

@Preview
@Composable
private fun ModelRecommendationScreenPreview() {
    ModelRecommendationSection(
        recommended = SelectableModel(
            model = AIModel_OpenAI.GPT_5,
            provider = AIProvider_OpenAI,
            featureNotes = listOf(
                "Feature Note 1" to Importance.CRITICAL,
                "Feature Note 2" to Importance.NOT_CRITICAL,
            ),
            tags = listOf("Tag 1", "Tag 2"),
        ),
        others = listOf(
            SelectableModel(
                model = AIModel_Gemini.Pro_2_5,
                provider = AIProvider_Google,
                featureNotes = listOf(
                    "Feature Note 1" to Importance.CRITICAL,
                    "Feature Note 2" to Importance.NOT_CRITICAL,
                ),
                tags = listOf("Tag 1", "Tag 2"),
            ),
        ),
        onModelSelected = {},
    )
}
