package link.socket.ampere.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import link.socket.phosphor.runtime.SceneSnapshot

/**
 * Mobile-first wrapper surface that renders cognition from scene snapshots.
 *
 * The frame/phase label is a development aid, not user-facing chrome, so it
 * defaults to hidden; callers opt in with [showFrameLabel].
 */
@Composable
fun MobileCognitionWrapperSurface(
    modifier: Modifier = Modifier,
    controller: SceneSnapshotController = remember { SceneSnapshotController() },
    targetFps: Int = 30,
    showFrameLabel: Boolean = false
) {
    var snapshot by remember(controller) { mutableStateOf(controller.snapshot()) }

    LaunchedEffect(controller, targetFps) {
        val fps = targetFps.coerceAtLeast(1)
        val dt = 1f / fps
        val frameDelayMs = (1000f / fps).toLong().coerceAtLeast(1L)

        while (isActive) {
            snapshot = controller.update(dt)
            delay(frameDelayMs)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        CognitiveCanvas(
            substrate = snapshot.substrateState,
            particles = controller.particles,
            agents = controller.agentLayer,
            flow = controller.flow,
            camera = controller.camera,
            waveform = controller.waveform,
            modifier = Modifier.fillMaxSize()
        )

        if (showFrameLabel) {
            BasicText(
                text = frameLabelText(snapshot),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(12.dp),
                style = androidx.compose.ui.text.TextStyle(color = Color.White)
            )
        }
    }
}

internal fun frameLabelText(snapshot: SceneSnapshot): String =
    "Frame ${snapshot.frameIndex} • ${snapshot.choreographyPhase.name}"
