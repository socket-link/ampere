package link.socket.ampere.ui.inference

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import link.socket.ampere.agents.domain.routing.local.OnDeviceAvailability
import link.socket.ampere.agents.domain.routing.local.OnDeviceInferenceState
import link.socket.ampere.llm.LocalFirstOutcome
import link.socket.ampere.llm.OnDeviceInferenceSession

/**
 * The cognition surface with on-device inference on top of it (AMPR-327): the
 * indicator at the top, the ask panel at the bottom, and whatever [background]
 * draws behind both.
 *
 * [background] is a slot rather than a fixed surface because the canvas lives
 * in `:ampere-compose`, which this module's common code does not depend on;
 * each platform entry point passes its own.
 *
 * @param session The session to ask and to watch, or null on a platform with
 *   no on-device engine. With none, the indicator says so — and why, from
 *   [unavailableReason] — and there is no ask panel: a field that can only fail
 *   is worse than no field.
 * @param unavailableReason The reason code to show when [session] is null.
 * @param availabilityRefreshMillis How often to re-probe the engine while the
 *   screen is up, so a model that finishes downloading turns the indicator to
 *   ready without a relaunch.
 */
@Composable
fun OnDeviceCognitionScreen(
    session: OnDeviceInferenceSession?,
    modifier: Modifier = Modifier,
    unavailableReason: String? = null,
    availabilityRefreshMillis: Long = DEFAULT_AVAILABILITY_REFRESH_MILLIS,
    background: @Composable () -> Unit,
) {
    val state: OnDeviceInferenceState = if (session != null) {
        val live by session.state.collectAsState()

        LaunchedEffect(session, availabilityRefreshMillis) {
            while (isActive) {
                session.refreshAvailability()
                delay(availabilityRefreshMillis.coerceAtLeast(MIN_AVAILABILITY_REFRESH_MILLIS))
            }
        }

        live
    } else {
        remember(unavailableReason) {
            OnDeviceInferenceState(
                availability = OnDeviceAvailability.Unavailable(reason = unavailableReason),
            )
        }
    }

    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<LocalFirstOutcome?>(null) }
    var isAsking by remember { mutableStateOf(false) }

    MaterialTheme {
        Box(modifier = modifier.fillMaxSize()) {
            background()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(12.dp),
            ) {
                OnDeviceInferenceIndicator(
                    state = state,
                    modifier = Modifier.align(Alignment.TopEnd),
                )

                if (session != null) {
                    LocalFirstAskPanel(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .widthIn(max = MAX_PANEL_WIDTH),
                        prompt = prompt,
                        onPromptChanged = { prompt = it },
                        outcome = outcome,
                        isAsking = isAsking,
                        onAsk = {
                            val asked = prompt
                            isAsking = true
                            scope.launch {
                                try {
                                    outcome = session.ask(asked)
                                    if (outcome is LocalFirstOutcome.Answered) prompt = ""
                                } finally {
                                    isAsking = false
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Default interval between availability probes while the screen is visible. */
const val DEFAULT_AVAILABILITY_REFRESH_MILLIS: Long = 15_000L

private const val MIN_AVAILABILITY_REFRESH_MILLIS = 1_000L
private val MAX_PANEL_WIDTH = 640.dp
