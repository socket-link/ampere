package link.socket.ampere.ui.agent

import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Card
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import link.socket.ampere.domain.agent.bundled.AgentDefinition
import link.socket.ampere.domain.agent.bundled.codeAgents
import link.socket.ampere.domain.agent.bundled.generalAgents
import link.socket.ampere.domain.agent.bundled.promptAgents
import link.socket.ampere.domain.agent.bundled.reasoningAgents

@Composable
fun AgentSelectionSection(
    onAgentPartiallySelected: (AgentDefinition) -> Unit,
    modifier: Modifier = Modifier,
) {
    val agents = remember {
        listOf(
            "Code Agents" to codeAgents,
            "General Agents" to generalAgents,
            "Prompt Agents" to promptAgents,
            "Reasoning Agents" to reasoningAgents,
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth(),
        verticalArrangement = spacedBy(16.dp),
    ) {
        items(agents) { (header, row) ->
            AgentRow(
                category = header,
                agents = row,
                onAgentSelected = onAgentPartiallySelected,
            )
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun AgentRow(
    category: String,
    agents: List<AgentDefinition>,
    onAgentSelected: (AgentDefinition) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isExpanded: MutableState<Boolean> = remember { mutableStateOf(true) }

    Column(modifier = modifier) {
        Surface(
            modifier = Modifier
                .padding(
                    horizontal = 16.dp,
                    vertical = 4.dp,
                ),
            onClick = { isExpanded.value = !isExpanded.value },
        ) {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                text = "$category ${if (isExpanded.value) "▼" else "▶"}",
            )
        }

        if (isExpanded.value) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 16.dp,
                    ),
                verticalArrangement = spacedBy(8.dp),
            ) {
                agents.forEach { agent ->
                    AgentCard(
                        agent = agent,
                        onAgentSelected = { newAgent ->
                            onAgentSelected(newAgent)
                            isExpanded.value = false
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun AgentCard(
    agent: AgentDefinition,
    onAgentSelected: (AgentDefinition) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        elevation = 4.dp,
        onClick = {
            onAgentSelected(agent)
        },
    ) {
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            text = agent.name,
            textAlign = TextAlign.Start,
        )
    }
}
