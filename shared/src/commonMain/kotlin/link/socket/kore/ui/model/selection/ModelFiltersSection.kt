package link.socket.kore.ui.model.selection

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material.Checkbox
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.round
import link.socket.kore.ui.theme.themeColors
import link.socket.kore.ui.theme.themeShapes
import link.socket.kore.ui.theme.themeTypography

@Composable
fun ModelFiltersSection(
    state: FilterState,
    onStateChange: (FilterState) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = themeColors()
    val typography = themeTypography()

    Column(
        modifier = modifier
            .background(colors.background)
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                style = typography.h5,
                text = "Advanced Filters",
            )

            OutlinedButton(
                onClick = onReset,
            ) {
                Text(
                    text = "Reset",
                )
            }
        }

        Spacer(Modifier.requiredHeight(4.dp))

        Text(
            style = typography.overline,
            color = Color(0xFF9CA3AF),
            text = "SET CUSTOM CONSTRAINTS",
        )

        Spacer(Modifier.requiredHeight(12.dp))

        // TODO: Change to discrete steps, rather than a continuous range
        FilterSlider(
            title = "Reasoning",
            emoji = "🧠",
            value = state.minReasoning,
            valueRange = 0f..100f,
            onChange = {
                onStateChange(state.copy(minReasoning = it))
            },
        )

        Spacer(Modifier.requiredHeight(4.dp))

        FilterSlider(
            title = "Speed (ms / run)",
            emoji = "⏳",
            value = state.maxLatencyMs,
            valueRange = 50f..3000f,
            onChange = {
                onStateChange(state.copy(maxLatencyMs = it))
            },
        )

        Spacer(Modifier.requiredHeight(4.dp))

        FilterSlider(
            title = "Cost ($ / 100 runs)",
            emoji = "💵",
            prefix = "$",
            value = state.maxCost,
            valueRange = 0.01f..10f,
            onChange = {
                onStateChange(state.copy(maxCost = it))
            },
        )

        Spacer(Modifier.requiredHeight(4.dp))

        ProviderChips(
            title = "Model Providers",
            providers = state.providers,
            onToggle = { key ->
                val updatedProviders = state.providers.map { provider ->
                    if (provider.key == key) {
                        provider.copy(isSelected = !provider.isSelected)
                    } else {
                        provider
                    }
                }

                onStateChange(
                    state.copy(providers = updatedProviders),
                )
            },
        )

        Spacer(Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = state.showUnsuitable,
                onCheckedChange = { isChecked ->
                    onStateChange(
                        state.copy(showUnsuitable = isChecked),
                    )
                },
            )

            Text(
                style = typography.body1,
                text = "Show unsuitable models",
            )
        }
    }
}

// TODO: Add toggle, hide slider and units when disabled
// TODO: Move units to subtitle
@Composable
private fun FilterSlider(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    prefix: String? = null,
    emoji: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = themeColors()
    val typography = themeTypography()
    val shapes = themeShapes()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shapes.medium)
            .background(colors.surface)
            .padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (emoji != null) {
                Text(
                    modifier = Modifier
                        .padding(end = 8.dp),
                    text = emoji,
                )
            }

            Text(
                style = typography.subtitle1,
                fontWeight = FontWeight.Medium,
                text = title,
            )
        }

        Slider(
            value = value,
            valueRange = valueRange,
            onValueChange = onChange,
            colors = SliderDefaults.colors(
                thumbColor = colors.primary,
            ),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                style = typography.caption,
                text = formatValue(valueRange.start, prefix),
            )

            Text(
                style = typography.caption,
                text = formatValue(valueRange.endInclusive, prefix),
            )
        }
    }
}

@Composable
private fun ProviderChips(
    title: String,
    providers: List<ProviderFilter>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = themeColors()
    val typography = themeTypography()
    val shapes = themeShapes()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shapes.medium)
            .background(colors.surface)
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                style = typography.subtitle1,
                fontWeight = FontWeight.Medium,
                text = title,
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            horizontalArrangement = spacedBy(8.dp),
        ) {
            providers.forEach { item ->
                SelectableChip(
                    isSelected = item.isSelected,
                    text = item.label,
                    onClick = {
                        onToggle(item.key)
                    },
                )
            }
        }
    }
}

@Composable
private fun SelectableChip(
    isSelected: Boolean,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = themeColors()
    val typography = themeTypography()
    val shapes = themeShapes()

    val bgColor = remember(colors, isSelected) {
        if (isSelected) {
            colors.onBackground
        } else {
            colors.onSurface
        }
    }

    Text(
        modifier = modifier
            .clip(shapes.large)
            .background(bgColor)
            .padding(
                horizontal = 12.dp,
                vertical = 8.dp,
            )
            .clickable {
                onClick()
            },
        style = typography.caption,
        color = colors.onPrimary,
        text = text,
    )
}

private fun formatValue(
    value: Float,
    prefix: String?,
): String {
    val base: String = if (value >= 1000f) {
        "${(value / 1000f).toInt()}k"
    } else {
        toTwoDecimals(value)
    }

    return (prefix ?: "") + base
}

// TODO: Remove after improving `formatValue`
private fun toTwoDecimals(v: Float): String {
    val scaled = round(v * 100f) / 100f
    val text = scaled.toString()

    return if (!text.contains('.')) {
        "$text.00"
    } else {
        val length: Int = text.indexOf('.') + 3
        val padChar = '0'

        text.padEnd(length, padChar)
    }
}
