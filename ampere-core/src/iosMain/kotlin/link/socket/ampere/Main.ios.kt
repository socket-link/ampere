package link.socket.ampere

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import link.socket.ampere.compose.MobileCognitionWrapperSurface
import platform.UIKit.UIViewController

fun mainViewController(): UIViewController =
    ComposeUIViewController {
        MobileCognitionWrapperSurface(
            modifier = Modifier.fillMaxSize(),
        )
    }
