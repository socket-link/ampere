package link.socket.ampere

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import link.socket.ampere.agents.domain.routing.local.LocalUnavailableReason
import link.socket.ampere.agents.domain.routing.local.SwiftLocalInferenceEngine
import link.socket.ampere.agents.domain.routing.local.toLocalInferenceEngine
import link.socket.ampere.compose.MobileCognitionWrapperSurface
import link.socket.ampere.data.createIosDriver
import link.socket.ampere.db.Database
import link.socket.ampere.llm.OnDeviceInferenceSession
import link.socket.ampere.ui.inference.OnDeviceCognitionScreen
import platform.UIKit.UIViewController

/**
 * The app's root view controller on a system with no on-device model framework
 * (before iOS 26): the cognition surface, with the indicator explaining why
 * nothing can run on the device.
 */
fun mainViewController(): UIViewController = mainViewController(localInferenceEngine = null)

/**
 * The app's root view controller, running on [localInferenceEngine] (AMPR-327).
 *
 * The engine is Swift's to build — `FoundationModels` has no C or Objective-C
 * headers Kotlin/Native could import — and Kotlin's to use: it is adapted to
 * [LocalInferenceEngine][link.socket.ampere.agents.domain.routing.local.LocalInferenceEngine]
 * and bound into an on-device-only [OnDeviceInferenceSession], whose state
 * drives the indicator. The session persists its events to the app's own
 * database, so what the indicator showed is also what the store recorded.
 *
 * ```swift
 * if #available(iOS 26.0, *) {
 *     Main_iosKt.mainViewController(localInferenceEngine: FoundationModelsLocalInferenceEngine())
 * } else {
 *     Main_iosKt.mainViewController()
 * }
 * ```
 *
 * @param localInferenceEngine The engine to run on, or null where the system
 *   has none.
 */
fun mainViewController(localInferenceEngine: SwiftLocalInferenceEngine?): UIViewController =
    ComposeUIViewController {
        val session = remember(localInferenceEngine) {
            localInferenceEngine?.let { engine ->
                OnDeviceInferenceSession.create(
                    engine = engine.toLocalInferenceEngine(),
                    database = Database(createIosDriver()),
                )
            }
        }

        DisposableEffect(session) {
            onDispose { session?.close() }
        }

        OnDeviceCognitionScreen(
            session = session,
            modifier = Modifier.fillMaxSize(),
            unavailableReason = LocalUnavailableReason.OS_TOO_OLD,
        ) {
            MobileCognitionWrapperSurface(
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
