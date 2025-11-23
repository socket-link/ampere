package link.socket.ampere.ui.conversation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.FileCopy
import androidx.compose.material.icons.twotone.ReplayCircleFilled
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aallam.openai.api.core.Role
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import link.socket.ampere.domain.chat.Chat
import link.socket.ampere.ui.theme.iconAlpha
import link.socket.ampere.ui.theme.iconButtonSize
import link.socket.ampere.ui.theme.nonContentCardColor
import link.socket.ampere.ui.theme.themeColors
import link.socket.ampere.ui.theme.themeShapes
import link.socket.ampere.ui.theme.themeTypography

@Composable
fun ChatMessage(
    message: Chat,
    displaySnackbar: (String) -> Unit,
    showRegenerate: Boolean,
    modifier: Modifier = Modifier,
    backgroundColor: Color = themeColors().surface,
    textColor: Color = MaterialTheme.colors.onBackground,
    codeTextColor: Color = MaterialTheme.colors.onBackground,
    linkTextColor: Color = textColor,
    codeBackgroundColor: Color = MaterialTheme.colors.onBackground.copy(alpha = 0.1f),
    inlineCodeBackgroundColor: Color = codeBackgroundColor,
    dividerColor: Color = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
) {
    val surfaceColor = remember(message, nonContentCardColor, backgroundColor) {
        if (message.role != Role.Assistant && message.role != Role.User) {
            nonContentCardColor
        } else {
            backgroundColor
        }
    }

    Surface(
        modifier = modifier
            .wrapContentHeight(),
        shape = themeShapes()
            .small,
        color = surfaceColor,
        elevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .wrapContentHeight()
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 12.dp,
                ),
        ) {
            Row(
                modifier = Modifier
                    .requiredHeight(48.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment
                    .CenterVertically,
            ) {
                if (message.role != Role.User) {
                    Row(
                        modifier = Modifier
                            .padding(bottom = 2.dp),
                        verticalAlignment = Alignment
                            .CenterVertically,
                    ) {
                        Text(
                            style = themeTypography()
                                .h6,
                            text = message.role.role.replaceFirstChar { it.uppercase() },
                        )
                    }
                }
            }

            when (message) {
                is Chat.System,
                is Chat.Text,
                -> {
                    val messageContent = message.chatMessage.content ?: ""

                    if (message.role == Role.User) {
                        Text(
                            modifier = Modifier
                                .fillMaxWidth(),
                            style = themeTypography()
                                .body1,
                            textAlign = TextAlign.End,
                            text = messageContent,
                        )
                    } else {
                        val themeTextLinkStyle = themeTypography()
                            .button.copy(color = linkTextColor).toSpanStyle()

                        Markdown(
                            modifier = Modifier.fillMaxWidth(),
                            content = messageContent,
                            colors = DefaultMarkdownColors(
                                text = textColor,
                                codeText = codeTextColor,
                                inlineCodeText = codeTextColor,
                                linkText = linkTextColor,
                                codeBackground = codeBackgroundColor,
                                inlineCodeBackground = inlineCodeBackgroundColor,
                                dividerColor = dividerColor,
                                tableText = textColor,
                                tableBackground = Color.Transparent,
                            ),
                            typography = DefaultMarkdownTypography(
                                h1 = themeTypography()
                                    .h1,
                                h2 = themeTypography()
                                    .h2,
                                h3 = themeTypography()
                                    .h3,
                                h4 = themeTypography()
                                    .h4,
                                h5 = themeTypography()
                                    .h5,
                                h6 = themeTypography()
                                    .h6,
                                text = themeTypography()
                                    .body1,
                                code = themeTypography()
                                    .body1.copy(
                                        color = codeTextColor,
                                    ),
                                inlineCode = themeTypography()
                                    .subtitle2,
                                quote = themeTypography()
                                    .subtitle1,
                                paragraph = themeTypography()
                                    .body1,
                                ordered = themeTypography()
                                    .caption,
                                bullet = themeTypography()
                                    .caption,
                                list = themeTypography()
                                    .body1,
                                link = themeTypography()
                                    .button,
                                textLink = TextLinkStyles(
                                    style = themeTextLinkStyle,
                                    focusedStyle = themeTextLinkStyle,
                                    hoveredStyle = themeTextLinkStyle,
                                    pressedStyle = themeTextLinkStyle,
                                ),
                                table = themeTypography()
                                    .body2,
                            ),
                        )
                    }
                }
                is Chat.CSV -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(),
                    ) {
                        message.csvContent.forEach { line ->
                            Row {
                                val colWidth = (1f / line.size)
                                for (cell in line) {
                                    Text(
                                        modifier = Modifier
                                            .fillMaxWidth(colWidth)
                                            .border(
                                                border = BorderStroke(
                                                    width = 1.dp,
                                                    color = Color.DarkGray,
                                                ),
                                            )
                                            .padding(2.dp),
                                        text = cell,
                                        overflow = TextOverflow
                                            .Ellipsis,
                                        textAlign = TextAlign
                                            .Center,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (message.role != Role.User) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment
                        .CenterVertically,
                ) {
                    Spacer(modifier = Modifier.weight(1f))

                    if (showRegenerate) {
                        IconButton(
                            modifier = Modifier
                                .requiredSize(iconButtonSize),
                            onClick = {
                                // TODO: Handle click
                            },
                        ) {
                            Image(
                                alpha = iconAlpha,
                                imageVector = Icons
                                    .TwoTone.ReplayCircleFilled,
                                contentDescription = "Regenerate Response",
                            )
                        }
                    }

                    val clipboardManager = LocalClipboardManager.current

                    IconButton(
                        modifier = Modifier
                            .requiredSize(iconButtonSize),
                        onClick = {
                            clipboardManager.setText(
                                AnnotatedString(message.chatMessage.content ?: ""),
                            )
                            displaySnackbar("Copied to clipboard")
                        },
                    ) {
                        Image(
                            alpha = iconAlpha,
                            imageVector = Icons
                                .TwoTone.FileCopy,
                            contentDescription = "Copy Response",
                        )
                    }
                }
            }
        }
    }
}
