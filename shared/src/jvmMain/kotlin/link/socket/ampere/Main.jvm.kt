package link.socket.ampere

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.cash.sqldelight.db.SqlDriver
import link.socket.ampere.data.createJvmDriver
import link.socket.ampere.ui.App

@Composable
fun MainView() {
    val databaseDriver: SqlDriver = remember {
        createJvmDriver()
    }

    App(
        modifier = Modifier
            .fillMaxSize(),
        databaseDriver = databaseDriver,
    )
}

@Preview
@Composable
fun MainViewPreview() {
    MainView()
}
