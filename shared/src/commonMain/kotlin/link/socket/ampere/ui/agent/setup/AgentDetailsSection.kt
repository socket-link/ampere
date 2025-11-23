package link.socket.ampere.ui.agent.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import link.socket.ampere.domain.agent.bundled.AgentDefinition
import link.socket.ampere.ui.theme.themeTypography

@Composable
fun AgentDetailsSection(
    agentDefinition: AgentDefinition,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .padding(
                    horizontal = 64.dp,
                    vertical = 24.dp,
                ),
        ) {
            Text(
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .fillMaxWidth(),
                style = themeTypography().h6,
                text = "${agentDefinition.name} Agent",
                textAlign = TextAlign.Start,
            )

            Text(
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .fillMaxWidth(),
                style = MaterialTheme.typography.caption,
                text = agentDefinition.description,
                textAlign = TextAlign.Start,
            )

            Text(
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .fillMaxWidth(),
                style = MaterialTheme.typography.subtitle2,
                text = "Required Capabilities",
                textAlign = TextAlign.Start,
                fontWeight = MaterialTheme.typography.subtitle2.fontWeight,
            )
        }
    }
}
