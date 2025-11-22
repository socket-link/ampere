package link.socket.kore.ui.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import link.socket.kore.domain.ai.model.AIModel
import link.socket.kore.domain.ai.provider.AIProvider
import link.socket.kore.ui.theme.themeColors
import link.socket.kore.ui.theme.themeTypography

@Composable
fun SuggestedModelsSection(
    selectedModelId: String,
    suggestedModels: List<Pair<AIProvider<*, *>, AIModel>>?,
    onModelSelected: (AIModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
    ) {
        Text(
            modifier = Modifier
                .fillMaxWidth(),
            style = themeTypography().h6,
            textAlign = TextAlign.Start,
            text = "Suggested Models",
        )

        Spacer(modifier = Modifier.requiredHeight(24.dp))

        LazyRow(
            modifier = Modifier
                .requiredHeight(200.dp)
                .padding(horizontal = 8.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement
                .SpaceBetween,
        ) {
            suggestedModels ?: return@LazyRow

            items(suggestedModels) { (provider, model) ->
                val isSelected = remember(model, selectedModelId) {
                    model.name == selectedModelId
                }

                SuggestedModelCard(
                    modifier = Modifier
                        .fillParentMaxHeight()
                        .fillParentMaxWidth(0.3f),
                    isSelected = isSelected,
                    provider = provider,
                    model = model,
                    onClick = {
                        onModelSelected(model)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun SuggestedModelCard(
    isSelected: Boolean,
    provider: AIProvider<*, *>,
    model: AIModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val themeColors = themeColors()

    val color = remember(themeColors, isSelected) {
        if (isSelected) {
            themeColors.primary
        } else {
            themeColors.surface
        }
    }

    Card(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        backgroundColor = color,
        elevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(
                    horizontal = 16.dp,
                    vertical = 12.dp,
                ),
        ) {
            Text(
                text = provider.name,
                style = MaterialTheme
                    .typography.subtitle2,
            )

            Spacer(modifier = Modifier.requiredHeight(8.dp))

            Text(
                text = model.displayName,
                style = MaterialTheme
                    .typography.subtitle1,
            )

            Spacer(modifier = Modifier.requiredHeight(8.dp))

            Text(
                text = model.description,
                style = MaterialTheme
                    .typography.caption,
            )
        }
    }
}
