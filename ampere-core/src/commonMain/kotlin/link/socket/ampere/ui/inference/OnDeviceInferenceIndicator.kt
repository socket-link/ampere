package link.socket.ampere.ui.inference

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import link.socket.ampere.agents.domain.routing.local.OnDeviceInferenceState

/**
 * Shows when the on-device model is being used (AMPR-327).
 *
 * A pill with a status dot, a title and a supporting line. While a call is
 * generating on the device the dot pulses and the pill says so; at rest it says
 * whether the device *could* serve, and why not when it cannot.
 *
 * It renders [state] and nothing else — it holds no session, makes no calls,
 * and has no opinion about where the state came from. Collect
 * [OnDeviceInferenceSession.state][link.socket.ampere.llm.OnDeviceInferenceSession.state]
 * or an
 * [OnDeviceInferenceMonitor][link.socket.ampere.agents.domain.routing.local.OnDeviceInferenceMonitor]'s
 * and pass the value in.
 *
 * Drawn for a dark surface: it is designed to sit over the cognition canvas.
 */
@Composable
fun OnDeviceInferenceIndicator(
    state: OnDeviceInferenceState,
    modifier: Modifier = Modifier,
) {
    val model = remember(state) { state.toIndicatorModel() }
    val color = model.tone.color()
    val shape = RoundedCornerShape(percent = 50)

    Row(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(model.title, model.detail).joinToString(". ")
            }
            .background(color = IndicatorColors.surface, shape = shape)
            .border(width = 1.dp, color = color.copy(alpha = 0.55f), shape = shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusDot(
            color = color,
            pulsing = model.tone == OnDeviceIndicatorTone.IN_USE,
        )

        Column {
            Text(
                text = model.title,
                color = IndicatorColors.title,
                style = MaterialTheme.typography.caption,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            model.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    text = detail,
                    color = IndicatorColors.detail,
                    style = MaterialTheme.typography.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The status dot. Pulses by stepping its own alpha on a delay loop rather than
 * through the animation library, which this module does not otherwise depend
 * on — the same approach the cognition surface uses for its frame clock.
 */
@Composable
private fun StatusDot(
    color: Color,
    pulsing: Boolean,
) {
    var phase by remember { mutableStateOf(0f) }

    LaunchedEffect(pulsing) {
        phase = 0f
        while (pulsing && isActive) {
            delay(PULSE_FRAME_MS)
            phase = (phase + PULSE_FRAME_MS / PULSE_PERIOD_MS) % 1f
        }
    }

    val alpha = if (pulsing) {
        PULSE_MIN_ALPHA + (1f - PULSE_MIN_ALPHA) * abs(sin(phase * PI.toFloat()))
    } else {
        1f
    }

    Box(
        modifier = Modifier
            .size(10.dp)
            .alpha(alpha)
            .background(color = color, shape = CircleShape),
    )
}

internal fun OnDeviceIndicatorTone.color(): Color = when (this) {
    OnDeviceIndicatorTone.IN_USE -> IndicatorColors.inUse
    OnDeviceIndicatorTone.READY -> IndicatorColors.ready
    OnDeviceIndicatorTone.UNAVAILABLE -> IndicatorColors.unavailable
    OnDeviceIndicatorTone.UNKNOWN -> IndicatorColors.unknown
}

/** Colours for the on-device surfaces, chosen to read over the black cognition canvas. */
internal object IndicatorColors {
    val surface = Color(0xE6121218)
    val title = Color(0xFFF2F2F7)
    val detail = Color(0xFFAEAEB8)
    val inUse = Color(0xFF34D399)
    val ready = Color(0xFF60A5FA)
    val unavailable = Color(0xFFFBBF24)
    val unknown = Color(0xFF8E8E98)
    val cloud = Color(0xFFC4B5FD)
    val failure = Color(0xFFF87171)
}

private const val PULSE_FRAME_MS = 50L
private const val PULSE_PERIOD_MS = 1_400f
private const val PULSE_MIN_ALPHA = 0.35f
