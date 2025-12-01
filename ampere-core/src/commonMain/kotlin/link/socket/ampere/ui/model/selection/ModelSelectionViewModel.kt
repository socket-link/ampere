package link.socket.ampere.ui.model.selection

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.provider.AIProvider

@Immutable
data class ProviderFilter(
    val key: String,
    val label: String,
    val isSelected: Boolean,
)

@Immutable
data class FilterState(
    val maxCost: Float = 1f,
    val minReasoning: Float = 60f,
    val maxLatencyMs: Float = 3000f,
    val providers: List<ProviderFilter> = emptyList(),
    val showUnsuitable: Boolean = false,
)

@Immutable
data class SelectableModel(
    val model: AIModel,
    val provider: AIProvider<*, *>,
    val featureNotes: List<Pair<String, Importance>> = emptyList(),
    val tags: List<String> = emptyList(),
)

@Immutable
enum class Importance {
    CRITICAL,
    NOT_CRITICAL,
}

@Immutable
data class ModelSelectionState(
    val modelList: List<SelectableModel>,
    val filterState: FilterState,
    val filteredResults: List<FilteredModelCell>,
)

@Immutable
class ModelSelectionViewModel() {
    private val _stateFlow: MutableStateFlow<ModelSelectionState> =
        MutableStateFlow(
            ModelSelectionState(
                modelList = ALL_SELECTABLE_MODELS,
                filterState = FilterState(
                    providers = ALL_SELECTABLE_MODELS.map { model ->
                        ProviderFilter(
                            key = model.provider.name,
                            label = model.provider.name,
                            isSelected = true,
                        )
                    },
                ),
                filteredResults = ALL_FILTERED_MODEL_ROWS,
            ),
        )

    val stateFlow: StateFlow<ModelSelectionState> = _stateFlow.asStateFlow()

    fun onFilterStateChanged(filterState: FilterState) {
        _stateFlow.value = ModelSelectionState(
            modelList = ALL_SELECTABLE_MODELS,
            filterState = filterState,
            filteredResults = ALL_FILTERED_MODEL_ROWS,
        )
    }

    fun onFilterStateReset() {
        onFilterStateChanged(
            FilterState(),
        )
    }
}
