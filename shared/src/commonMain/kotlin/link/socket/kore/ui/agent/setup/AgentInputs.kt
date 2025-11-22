package link.socket.kore.ui.agent.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import link.socket.kore.domain.agent.AgentInput
import link.socket.kore.ui.theme.themeTypography

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun EnumInput(
    input: AgentInput.EnumArgs,
    possibleValues: List<String>,
    onValueChanged: (AgentInput.EnumArgs) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
    ) {
        val showDropdown: MutableState<Boolean> = remember { mutableStateOf(false) }

        Text(
            modifier = Modifier.fillMaxWidth(),
            text = input.name,
        )

        Card(
            modifier = Modifier.padding(12.dp),
            onClick = { showDropdown.value = true },
        ) {
            Text(
                text = input.value,
            )
        }

        DropdownMenu(
            expanded = showDropdown.value,
            onDismissRequest = { showDropdown.value = false },
        ) {
            possibleValues.forEach { enumValue ->
                DropdownMenuItem(
                    onClick = {
                        onValueChanged(
                            input.copy(
                                value = enumValue,
                            ),
                        )
                        showDropdown.value = false
                    },
                ) {
                    Text(enumValue)
                }
            }
        }
    }
}

@Composable
fun StringInput(
    input: AgentInput.StringArg,
    onValueChanged: (AgentInput.StringArg) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textFieldValue: MutableState<TextFieldValue> = remember { mutableStateOf(TextFieldValue()) }

    TextField(
        modifier = modifier,
        value = textFieldValue.value,
        onValueChange = { value ->
            textFieldValue.value = value
            onValueChanged(
                input.copy(value = value.text),
            )
        },
        label = { Text(input.name) },
    )

    Spacer(modifier = Modifier.requiredHeight(8.dp))
}

@Composable
fun ListInput(
    input: AgentInput.ListArg,
    onValueChanged: (AgentInput.ListArg) -> Unit,
    modifier: Modifier = Modifier,
) {
    val itemCount: MutableState<Int> = remember { mutableStateOf(1) }
    val inputs: MutableState<MutableList<String>> = remember { mutableStateOf(mutableListOf("")) }

    Column(
        modifier = modifier.wrapContentHeight(),
    ) {
        for (index in 0..<itemCount.value) {
            val textFieldValue: MutableState<TextFieldValue> = remember { mutableStateOf(TextFieldValue()) }

            TextField(
                value = textFieldValue.value,
                onValueChange = { value ->
                    textFieldValue.value = value
                    if (inputs.value.getOrNull(index) == null) {
                        inputs.value.add(index, value.text)
                    } else {
                        inputs.value[index] = value.text
                    }
                    onValueChanged(
                        input.copy(listValue = inputs.value),
                    )
                },
                label = { Text(input.name) },
            )

            Spacer(modifier = Modifier.requiredHeight(8.dp))
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(),
            onClick = { itemCount.value++ },
        ) {
            Text(
                modifier = Modifier.fillMaxWidth(),
                style = themeTypography().button,
                text = "Add List Item",
                textAlign = TextAlign.Center,
            )
        }
    }
}
