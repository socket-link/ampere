package link.socket.ampere.ui.conversation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.AddCircleOutline
import androidx.compose.material.icons.twotone.ArrowUpward
import androidx.compose.material.icons.twotone.KeyboardDoubleArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import link.socket.ampere.ui.theme.iconAlpha
import link.socket.ampere.ui.theme.iconButtonSize

@Composable
fun ConversationTextEntry(
    textFieldValue: TextFieldValue,
    onTextChanged: (TextFieldValue) -> Unit,
    onSendClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current

    val onTextInputCompleted: () -> Unit = {
        onSendClicked()
        onTextChanged(textFieldValue.copy(""))
        focusManager.clearFocus()
    }

    Column {
        val isKeyboardOpen: State<Boolean> = keyboardAsState()

        Surface(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp),
            elevation = 24.dp,
        ) {
            Row(
                modifier = Modifier
                    .padding(
                        start = 16.dp,
                        top = 8.dp,
                        end = 16.dp,
                        bottom = 24.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(
                    modifier = Modifier.requiredSize(iconButtonSize),
                    onClick = {
                        if (isKeyboardOpen.value) {
                            focusManager.clearFocus()
                        } else {
                            // TODO: Handle attachments
                        }
                    },
                ) {
                    Image(
                        imageVector = if (isKeyboardOpen.value) {
                            Icons.TwoTone.KeyboardDoubleArrowDown
                        } else {
                            Icons.TwoTone.AddCircleOutline
                        },
                        alpha = iconAlpha,
                        contentDescription = if (isKeyboardOpen.value) {
                            "Closes keyboard"
                        } else {
                            "Expands attachment buttons"
                        },
                    )
                }

                TextField(
                    modifier = Modifier
                        .padding(
                            top = 4.dp,
                            bottom = 8.dp,
                        )
                        .wrapContentHeight()
                        .fillMaxWidth(.9f)
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Enter) {
                                onTextInputCompleted()
                                true
                            } else {
                                false
                            }
                        },
                    colors = TextFieldDefaults.textFieldColors(
                        textColor = Color.Gray,
                        disabledTextColor = Color.Transparent,
                        backgroundColor = Color.White,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    value = textFieldValue,
                    onValueChange = onTextChanged,
                    label = {
                        Text("Your message...")
                    },
                )

                IconButton(
                    modifier = Modifier.requiredSize(iconButtonSize),
                    onClick = onTextInputCompleted,
                ) {
                    Image(
                        imageVector = Icons.TwoTone.ArrowUpward,
                        alpha = iconAlpha,
                        contentDescription = "Sends the current message",
                    )
                }
            }
        }
    }
}

/**
 * A composable function that returns the state of the keyboard visibility.
 *
 * @return State<Boolean> indicating whether the keyboard is visible or not.
 */
@Composable
fun keyboardAsState(): State<Boolean> {
    val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    return rememberUpdatedState(isImeVisible)
}
