package link.socket.ampere.ui.widget.header

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import link.socket.ampere.ui.theme.headerHeight
import link.socket.ampere.ui.theme.themeTypography

@Composable
fun Header(
    title: String,
    displayBackIcon: Boolean,
    onBackClicked: () -> Unit,
    modifier: Modifier = Modifier,
    onActionIconClicked: () -> Unit = {},
    actionIcon: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .requiredHeight(headerHeight)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (displayBackIcon) {
            IconButton(
                modifier = Modifier
                    .requiredSize(64.dp),
                onClick = onBackClicked,
            ) {
                Image(
                    imageVector = Icons.AutoMirrored.TwoTone.ArrowBack,
                    contentDescription = "Back",
                )
            }
        } else {
            Spacer(modifier = Modifier.requiredWidth(64.dp))
        }

        Text(
            modifier = Modifier
                .fillMaxWidth(.8f),
            style = themeTypography().h6,
            text = title,
            textAlign = TextAlign.Center,
        )

        if (actionIcon != null) {
            IconButton(
                modifier = Modifier
                    .requiredSize(64.dp),
                onClick = onActionIconClicked,
            ) {
                actionIcon()
            }
        } else {
            Spacer(modifier = Modifier.requiredWidth(64.dp))
        }
    }
}
