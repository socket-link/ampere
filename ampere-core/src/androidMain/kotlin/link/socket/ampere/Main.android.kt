package link.socket.ampere

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import link.socket.ampere.compose.MobileCognitionWrapperSurface

@Composable
fun MainView() {
    MobileCognitionWrapperSurface(
        modifier = Modifier.fillMaxSize(),
    )
}

@Preview
@Composable
fun MainViewPreview() {
    MainView()
}
