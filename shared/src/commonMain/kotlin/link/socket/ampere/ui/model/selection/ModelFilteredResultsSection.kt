package link.socket.ampere.ui.model.selection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.Card
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import link.socket.ampere.ui.theme.themeColors
import link.socket.ampere.ui.theme.themeShapes
import link.socket.ampere.ui.theme.themeTypography

// TODO: Display underneath filters, but with tabbed nav between this list and the scatterplot
@Composable
fun ModelFilteredResultsSection(
    totalCount: Int,
    shownCount: Int,
    items: List<FilteredModelCell>,
    onModelSelected: (SelectableModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = themeColors()
    val typography = themeTypography()

    Column(
        modifier = modifier
            .background(colors.background)
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                style = typography.overline,
                color = Color(0xFF9CA3AF),
                text = "MATCHING MODELS",
            )

            Text(
                style = typography.caption,
                color = Color(0xFF9CA3AF),
                text = "Showing $shownCount of $totalCount models",
            )
        }

        Spacer(Modifier.height(12.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = spacedBy(8.dp),
            verticalArrangement = spacedBy(8.dp),
        ) {
            items(items) { modelCell ->
                FilteredModelCard(
                    modelCell = modelCell,
                    onClick = { filteredModelRow ->
                        onModelSelected(filteredModelRow.selectableModel)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun FilteredModelCard(
    modelCell: FilteredModelCell,
    onClick: (FilteredModelCell) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = themeColors()
    val typography = themeTypography()
    val shapes = themeShapes()

    Card(
        modifier = modifier
            .fillMaxWidth(),
        backgroundColor = colors.surface,
        shape = shapes.medium,
        elevation = 2.dp,
        onClick = {
            onClick(modelCell)
        },
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp),
        ) {
            // TODO: Reuse this icon elsewhere
            // val (icon, tint) = when (modelCell.suitability) {
            //     Suitability.Suitable -> Icons.Default.CheckCircle to Color(0xFF34D399)
            //     Suitability.Warning -> Icons.Default.Error to Color(0xFFF59E0B)
            //     Suitability.Unsuitable -> Icons.Default.HighlightOff to Color(0xFFEF4444)
            // }
            // Icon(
            //     tint = tint,
            //     imageVector = icon,
            //     contentDescription = null,
            // )

            Text(
                style = typography.subtitle1,
                text = modelCell.selectableModel.model.displayName,
            )

            Text(
                style = typography.subtitle2,
                color = Color(0xFF9CA3AF),
                text = "from ${modelCell.selectableModel.provider.name}",
            )

            Spacer(Modifier.height(8.dp))

            Row(
                horizontalArrangement = spacedBy(8.dp),
            ) {
                MetricChip(
                    modifier = Modifier
                        .weight(1f),
                    label = "🧠",
                    value = modelCell.reasoningScore,
                )

                MetricChip(
                    modifier = Modifier
                        .weight(1f),
                    label = "⏳",
                    value = modelCell.speedScore,
                )

                MetricChip(
                    modifier = Modifier
                        .weight(1f),
                    label = "💵",
                    value = modelCell.costPerRun,
                )
            }

            Text(
                modifier = Modifier
                    .fillMaxWidth(),
                style = typography.caption,
                textAlign = TextAlign.Center,
                text = "${modelCell.rateLimitPerMin} req/min",
            )
        }
    }
}

@Composable
private fun MetricChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colors = themeColors()
    val typography = themeTypography()
    val shapes = themeShapes()

    Column(
        modifier = modifier
            .clip(shapes.large)
            .background(colors.surface)
            .padding(
                horizontal = 12.dp,
                vertical = 8.dp,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            style = typography.h6,
            color = colors.onSurface,
            text = label,
        )

        Text(
            style = typography.body2,
            color = colors.onSurface,
            text = value,
        )
    }
}

@Immutable
data class FilteredModelCell(
    val selectableModel: SelectableModel,
    val reasoningScore: String,
    val speedScore: String,
    val costPerRun: String,
    val rateLimitPerMin: String,
    val suitability: Suitability,
)

enum class Suitability {
    Suitable,
    Warning,
    Unsuitable,
}
