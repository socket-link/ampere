package link.socket.ampere.ui.model.selection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import link.socket.ampere.ui.theme.themeColors
import link.socket.ampere.ui.theme.themeShapes
import link.socket.ampere.ui.theme.themeTypography

@Composable
fun ModelRecommendationSection(
    recommended: SelectableModel,
    others: List<SelectableModel>,
    onModelSelected: (SelectableModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = themeColors()
    val typography = themeTypography()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background)
            .padding(
                horizontal = 16.dp,
            ),
    ) {
        Text(
            modifier = Modifier
                .padding(bottom = 16.dp),
            style = typography.h5,
            text = "Model Selection",
        )

        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp),
        ) {
            RecommendedCard(
                modifier = Modifier
                    .weight(1f),
                option = recommended,
                onAccept = {
                    onModelSelected(recommended)
                },
            )

            Spacer(Modifier.requiredWidth(16.dp))

            Column(
                modifier = Modifier
                    .weight(1f),
            ) {
                Text(
                    style = typography.h6,
                    text = "Other Options",
                )

                Spacer(Modifier.requiredHeight(12.dp))

                Column(
                    verticalArrangement = spacedBy(12.dp),
                ) {
                    others.forEach { option ->
                        OtherOptionCard(
                            option = option,
                            onClick = {
                                onModelSelected(option)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecommendedCard(
    option: SelectableModel,
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = themeColors()
    val typography = themeTypography()

    Card(
        modifier = modifier
            .fillMaxWidth(),
        backgroundColor = colors.surface,
        shape = RoundedCornerShape(16.dp),
        elevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp),
            verticalArrangement = spacedBy(12.dp),
        ) {
            Text(
                style = typography.subtitle2,
                color = colors.primary,
                text = "Recommended",
            )

            Text(
                style = typography.h4,
                fontWeight = FontWeight.Bold,
                text = option.model.displayName,
            )

            Text(
                style = typography.subtitle2,
                color = Color(0xFF9CA3AF),
                text = "from ${option.provider}",
            )

            Text(
                style = typography.body1,
                text = option.model.description,
            )

            option.featureNotes.forEach { (label, importance) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        modifier = Modifier
                            .size(18.dp),
                        tint = when (importance) {
                            Importance.CRITICAL -> Color(0xFF34D399)
                            Importance.NOT_CRITICAL -> Color(0xFF60A5FA)
                        },
                        imageVector = when (importance) {
                            Importance.CRITICAL -> Icons.Default.Check
                            Importance.NOT_CRITICAL -> Icons.Default.Warning
                        },
                        contentDescription = null,
                    )

                    Text(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .weight(1f),
                        style = typography.subtitle1,
                        text = label,
                    )

                    Text(
                        style = typography.caption,
                        color = when (importance) {
                            Importance.CRITICAL -> Color(0xFFF59E0B)
                            Importance.NOT_CRITICAL -> Color(0xFF93C5FD)
                        },
                        text = when (importance) {
                            Importance.CRITICAL -> "[CRITICAL]"
                            Importance.NOT_CRITICAL -> "[not critical]"
                        },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onAccept,
                ) {
                    Text(
                        text = "Use ${option.model.displayName}",
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun OtherOptionCard(
    option: SelectableModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = themeColors()
    val typography = themeTypography()
    val shapes = themeShapes()

    val subtitleTextColor = remember(colors) {
        colors.onSurface.copy(alpha = 0.7f)
    }

    Card(
        modifier = modifier
            .fillMaxWidth(),
        onClick = onClick,
        shape = shapes.medium,
        backgroundColor = colors.surface,
        elevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f),
                ) {
                    Text(
                        style = typography.h6,
                        text = option.model.displayName,
                    )

                    Text(
                        style = typography.subtitle2,
                        color = subtitleTextColor,
                        text = "from ${option.provider}",
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                horizontalArrangement = spacedBy(8.dp),
            ) {
                option.tags.forEach { tag ->
                    TagChip(tag)
                }
            }
        }
    }
}

@Composable
private fun TagChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = themeColors()
    val typography = themeTypography()
    val shapes = themeShapes()

    Box(
        modifier = modifier
            .clip(shapes.large)
            .background(colors.surface)
            .padding(
                horizontal = 12.dp,
                vertical = 8.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            style = typography.caption,
            text = text,
        )
    }
}
