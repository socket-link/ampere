package link.socket.ampere.ui.model.selection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/*
 * TODO: Follow guide here for improved sheet UX:
 * https://proandroiddev.com/improving-the-modal-bottom-sheet-api-in-jetpack-compose-5ca56901ada8
 */
@Composable
fun ModelSelectionBottomSheet(
    viewModel: ModelSelectionViewModel,
    onModelSelected: (SelectableModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewState =
        viewModel.stateFlow.collectAsState()

    Column(
        modifier = modifier
            .fillMaxHeight(0.9f)
            .fillMaxWidth(),
    ) {
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            style = MaterialTheme.typography.h6,
            textAlign = TextAlign.Center,
            text = "Find a Model",
        )

        Row(
            modifier = Modifier
                .padding(
                    horizontal = 24.dp,
                    vertical = 16.dp,
                ),
        ) {
            with(viewState.value) {
                ModelFiltersSection(
                    modifier = Modifier
                        .weight(1.25f)
                        .fillMaxHeight(),
                    state = filterState,
                    onStateChange = viewModel::onFilterStateChanged,
                    onReset = viewModel::onFilterStateReset,
                )

                Spacer(modifier = Modifier.requiredWidth(16.dp))

                ModelFilteredResultsSection(
                    modifier = Modifier
                        .weight(2f),
                    totalCount = modelList.size,
                    shownCount = filteredResults.size,
                    items = filteredResults,
                    onModelSelected = onModelSelected,
                )
            }
        }
    }
}
