package link.socket.kore

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import java.awt.Dimension

fun main() =
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Ampere",
            state =
                WindowState(
                    width = 800.dp,
                    height = 1200.dp,
                ),
        ) {
            window.minimumSize = Dimension(600, 800)
            MainView()
        }
    }
