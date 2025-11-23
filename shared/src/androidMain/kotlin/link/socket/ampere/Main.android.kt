package link.socket.ampere

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import app.cash.sqldelight.db.SqlDriver
import link.socket.ampere.data.createAndroidDriver
import link.socket.ampere.ui.App

@Composable
fun MainView() {
    val context = LocalContext.current

    val databaseDriver: SqlDriver = remember {
        createAndroidDriver(context)
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
