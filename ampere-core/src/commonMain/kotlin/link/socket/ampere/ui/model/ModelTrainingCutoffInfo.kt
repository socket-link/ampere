package link.socket.ampere.ui.model

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ktor.util.date.GMTDate

@Composable
fun ModelTrainingCutoffInfo(
    cutoffDate: GMTDate,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            modifier = Modifier.padding(bottom = 4.dp),
            text = "Training Data",
            style = MaterialTheme.typography.subtitle2,
            fontWeight = FontWeight.Bold,
        )

        val text = remember(cutoffDate) {
            "Updated through ${cutoffDate.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${cutoffDate.year}"
        }

        Text(
            text = text,
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
        )
    }
}
