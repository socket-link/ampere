package link.socket.ampere.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import link.socket.ampere.ui.widget.header.Header

@Composable
fun HomeHeader(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        elevation = 16.dp,
    ) {
        Column(
            modifier = Modifier
                .wrapContentHeight()
                .fillMaxWidth(),
        ) {
            Header(
                title = "Agent Conversations",
                displayBackIcon = false,
                onBackClicked = {},
            )
        }
    }
}
