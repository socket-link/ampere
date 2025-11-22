package link.socket.kore.ui.agent.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import link.socket.kore.domain.agent.AgentInput
import link.socket.kore.ui.theme.themeTypography

@Composable
fun AgentInputsSection(
    requiredInputs: List<AgentInput>,
    optionalInputs: List<AgentInput>,
    onAgentInputsUpdated: (setInputs: Map<String, AgentInput>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val inputValues: MutableState<MutableMap<String, AgentInput>> = remember {
        mutableStateOf(mutableMapOf())
    }

    LaunchedEffect(inputValues.value) {
        onAgentInputsUpdated(inputValues.value)
    }

    val inputComposable: @Composable (AgentInput) -> Unit = remember {
        @Composable { input ->
            when (input) {
                is AgentInput.EnumArgs -> {
                    EnumInput(
                        modifier = Modifier
                            .fillMaxWidth(),
                        input = input,
                        possibleValues = input.possibleValues,
                        onValueChanged = { value ->
                            inputValues.value[input.key] = value
                        },
                    )
                }
                is AgentInput.StringArg -> {
                    StringInput(
                        modifier = Modifier
                            .fillMaxWidth(),
                        input = input,
                        onValueChanged = { value ->
                            inputValues.value[input.key] = value
                        },
                    )
                }
                is AgentInput.ListArg -> {
                    ListInput(
                        modifier = Modifier
                            .fillMaxWidth(),
                        input = input,
                        onValueChanged = { value ->
                            inputValues.value[input.key] = value
                        },
                    )
                }
            }
        }
    }

    Column(
        modifier = modifier
            .wrapContentHeight(),
    ) {
        val listState = rememberLazyListState()

        LazyColumn(
            modifier = Modifier
                .wrapContentHeight()
                .fillMaxWidth()
                .padding(
                    horizontal = 16.dp,
                ),
            state = listState,
        ) {
            if (requiredInputs.isNotEmpty()) {
                item {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                top = 24.dp,
                                bottom = 12.dp,
                            ),
                        style = themeTypography()
                            .h6,
                        text = "Required inputs",
                    )
                }
            }

            items(requiredInputs) { input ->
                Column(
                    modifier = Modifier
                        .padding(
                            bottom = 12.dp,
                        ),
                ) {
                    inputComposable(input)
                }
            }

            if (optionalInputs.isNotEmpty()) {
                item {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                top = 24.dp,
                                bottom = 12.dp,
                            ),
                        style = themeTypography()
                            .h6,
                        text = "Optional inputs",
                    )
                }
            }

            items(optionalInputs) { input ->
                Column(
                    modifier = Modifier
                        .padding(
                            bottom = 12.dp,
                        ),
                ) {
                    inputComposable(input)
                }
            }
        }
    }
}
