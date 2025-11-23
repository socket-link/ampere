package link.socket.ampere.ui.widget

import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.Snackbar
import androidx.compose.material.SnackbarDuration
import androidx.compose.material.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import kotlinx.coroutines.delay

@Composable
fun SmallSnackbarHost(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val currentSnackbarData = snackbarHostState.currentSnackbarData
    val accessibilityManager = LocalAccessibilityManager.current

    LaunchedEffect(currentSnackbarData) {
        if (currentSnackbarData != null) {
            val duration = currentSnackbarData.duration.toMillis(
                hasAction = currentSnackbarData.actionLabel != null,
                accessibilityManager = accessibilityManager,
            )
            delay(duration)
            currentSnackbarData.dismiss()
        }
    }

    currentSnackbarData?.let { data ->
        Snackbar(
            modifier = modifier.wrapContentWidth(),
            snackbarData = data,
        )
    }
}

private fun SnackbarDuration.toMillis(
    hasAction: Boolean,
    accessibilityManager: AccessibilityManager?,
): Long {
    val original = when (this) {
        SnackbarDuration.Indefinite -> Long.MAX_VALUE
        SnackbarDuration.Long -> 10000L
        SnackbarDuration.Short -> 4000L
    }

    if (accessibilityManager == null) {
        return original
    }

    return accessibilityManager.calculateRecommendedTimeoutMillis(
        originalTimeoutMillis = original,
        containsIcons = true,
        containsText = true,
        containsControls = hasAction,
    )
}
