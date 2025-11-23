package link.socket.ampere

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import app.cash.sqldelight.db.SqlDriver
import link.socket.ampere.data.createIosDriver
import link.socket.ampere.ui.App
import platform.UIKit.UIViewController

fun mainViewController(): UIViewController =
    ComposeUIViewController {
        val databaseDriver: SqlDriver = remember {
            createIosDriver()
        }

        App(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 64.dp),
            databaseDriver = databaseDriver,
        )
    }
