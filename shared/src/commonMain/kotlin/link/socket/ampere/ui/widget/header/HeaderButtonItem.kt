package link.socket.ampere.ui.widget.header

import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import link.socket.ampere.ui.theme.themeTypography

@Composable
fun HeaderButtonItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        modifier = modifier
            .padding(end = 8.dp),
        onClick = onClick,
    ) {
        Text(
            style = themeTypography().button,
            text = text,
        )
    }
}
