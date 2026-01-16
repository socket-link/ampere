package link.socket.ampere.ui.agent.setup

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.BottomSheetScaffold
import androidx.compose.material.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import link.socket.ampere.domain.agent.AgentInput
import link.socket.ampere.domain.agent.KoreAgent
import link.socket.ampere.domain.agent.KoreAgentFactory
import link.socket.ampere.domain.agent.bundled.AgentDefinition
import link.socket.ampere.ui.model.selection.ModelRecommendationSection
import link.socket.ampere.ui.model.selection.ModelSelectionBottomSheet
import link.socket.ampere.ui.model.selection.ModelSelectionViewModel
import link.socket.ampere.ui.widget.header.Header

@Composable
fun AgentSetupScreen(
    agentDefinition: AgentDefinition,
    agentFactory: KoreAgentFactory,
    onAgentCreated: (KoreAgent) -> Unit,
    onBackClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val scaffoldState = rememberBottomSheetScaffoldState()

    val agentSetupViewModel = remember {
        AgentSetupViewModel(
            selectedAgentDefinition = agentDefinition,
        )
    }

    val modelSelectionViewModel = remember {
        ModelSelectionViewModel()
    }

    BottomSheetScaffold(
        modifier = modifier,
        scaffoldState = scaffoldState,
        sheetElevation = 16.dp,
        topBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(),
                elevation = 32.dp,
            ) {
                Column(
                    modifier = Modifier
                        .wrapContentHeight()
                        .fillMaxWidth(),
                ) {
                    Header(
                        title = "Agent Setup",
                        displayBackIcon = true,
                        onBackClicked = onBackClicked,
                        onActionIconClicked = {
                            val aiConfiguration = agentSetupViewModel.stateFlow.value.getSelectedAIConfiguration()

                            val selectedAgentInstance = agentFactory.buildAgent(
                                config = aiConfiguration,
                                definition = agentDefinition,
                                scope = scope,
                            )

                            onAgentCreated(selectedAgentInstance)
                        },
                        actionIcon = {
                            Image(
                                imageVector = Icons
                                    .Default.Check,
                                contentDescription = "Back",
                            )
                        },
                    )
                }
            }
        },
        sheetContent = {
            ModelSelectionBottomSheet(
                modifier = Modifier
                    .fillMaxWidth(),
                viewModel = modelSelectionViewModel,
                onModelSelected = agentSetupViewModel::onModelSelected,
            )
        },
    ) { paddingValues ->
        val state = agentSetupViewModel.stateFlow.value

        val requiredInputs: MutableState<List<AgentInput>> = remember(agentDefinition) {
            mutableStateOf(agentDefinition.requiredInputs)
        }

        val optionalInputs = remember(agentDefinition) {
            mutableStateOf(agentDefinition.optionalInputs)
        }

        Column {
            AgentDetailsSection(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxWidth(),
                agentDefinition = agentDefinition,
            )

            ModelRecommendationSection(
                recommended = state.modelSuggestionDefault,
                others = state.modelSuggestionBackups,
                onModelSelected = agentSetupViewModel::onModelSelected,
            )

            AgentInputsSection(
                requiredInputs = requiredInputs.value,
                optionalInputs = optionalInputs.value,
                onAgentInputsUpdated = { setInputs: Map<String, AgentInput> ->
                    // TODO: call onCreateAgent(partiallySelectedAgent!!)
                },
            )
        }
    }
}
