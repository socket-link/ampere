package link.socket.kore.ui.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import link.socket.kore.domain.ai.model.AIModelFeatures

// TODO: Potentially remove
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun ModelFeaturesSection(
    features: AIModelFeatures,
    modifier: Modifier = Modifier,
) {
    val cardExpanded: MutableState<Boolean> = remember {
        mutableStateOf(true)
    }

    Card(
        modifier = modifier,
        onClick = {
            cardExpanded.value = !cardExpanded.value
        },
        shape = RoundedCornerShape(12.dp),
        elevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp),
            verticalArrangement = Arrangement
                .spacedBy(16.dp),
        ) {
            Text(
                modifier = Modifier
                    .padding(bottom = 4.dp),
                style = MaterialTheme
                    .typography.h6,
                text = "Model Capabilities",
            )

            if (cardExpanded.value) {
                ModelOverviewSection(
                    modifier = Modifier
                        .fillMaxWidth(),
                    features = features,
                )

                ModelSupportedInputsSection(
                    modifier = Modifier
                        .fillMaxWidth(),
                    supportedInputs = features.supportedInputs,
                )
            }
        }
    }
}
