package link.socket.ampere.ui.model

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.util.date.GMTDate
import link.socket.ampere.domain.limits.ModelLimits

@Composable
fun ModelLimitsSection(
    limits: ModelLimits,
    trainingCutoffDate: GMTDate,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment
                .CenterVertically,
        ) {
            ModelTokenInfo(
                modifier = Modifier
                    .weight(1f),
                limits = limits.token,
            )

            Spacer(modifier = Modifier.width(16.dp))

            ModelRateLimitsSection(
                modifier = Modifier
                    .weight(1f),
                rateLimits = limits.rate,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        ModelTrainingCutoffInfo(trainingCutoffDate)
    }
}
