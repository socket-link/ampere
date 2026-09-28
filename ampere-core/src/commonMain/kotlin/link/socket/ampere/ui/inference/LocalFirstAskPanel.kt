package link.socket.ampere.ui.inference

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import link.socket.ampere.agents.domain.routing.local.InferenceLocality
import link.socket.ampere.llm.LocalFirstOutcome

/**
 * A prompt field and the last answer, with the answer's provenance under it
 * (AMPR-327).
 *
 * The provenance line is the point: every answer says where it was produced, in
 * the locality's own colour, so "this one stayed on the device" is something a
 * person reads rather than something they have to trust.
 *
 * Stateless. The caller owns the text, the pending flag and the outcome, and
 * decides what [onAsk] does.
 *
 * @param prompt The text in the field.
 * @param onPromptChanged Called as the person types.
 * @param onAsk Called when the person submits a non-blank prompt.
 * @param outcome The last call's result, or null before the first.
 * @param isAsking Whether a call is in flight. Disables submitting another.
 */
@Composable
fun LocalFirstAskPanel(
    prompt: String,
    onPromptChanged: (String) -> Unit,
    onAsk: () -> Unit,
    outcome: LocalFirstOutcome?,
    isAsking: Boolean,
    modifier: Modifier = Modifier,
) {
    val canAsk = prompt.isNotBlank() && !isAsking

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(color = IndicatorColors.surface, shape = RoundedCornerShape(20.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        outcome?.let { OutcomeCard(it) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                modifier = Modifier.weight(1f),
                value = prompt,
                onValueChange = onPromptChanged,
                enabled = !isAsking,
                singleLine = true,
                placeholder = { Text("Ask the on-device model") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canAsk) onAsk() }),
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.textFieldColors(
                    textColor = IndicatorColors.title,
                    disabledTextColor = IndicatorColors.detail,
                    placeholderColor = IndicatorColors.detail,
                    cursorColor = IndicatorColors.inUse,
                    backgroundColor = FIELD_COLOR,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )

            Button(
                onClick = onAsk,
                enabled = canAsk,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = IndicatorColors.inUse,
                    contentColor = Color.Black,
                    disabledBackgroundColor = FIELD_COLOR,
                    disabledContentColor = IndicatorColors.detail,
                ),
            ) {
                Text(if (isAsking) "Asking" else "Ask")
            }
        }
    }
}

@Composable
private fun OutcomeCard(outcome: LocalFirstOutcome) {
    when (outcome) {
        is LocalFirstOutcome.Answered -> {
            val answer = outcome.answer
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    modifier = Modifier
                        .heightIn(max = ANSWER_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                    text = answer.text,
                    color = IndicatorColors.title,
                    style = MaterialTheme.typography.body2,
                )
                Text(
                    text = answer.describeProvenance(),
                    color = when (answer.locality) {
                        InferenceLocality.ON_DEVICE -> IndicatorColors.inUse
                        InferenceLocality.CLOUD -> IndicatorColors.cloud
                    },
                    style = MaterialTheme.typography.caption,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        is LocalFirstOutcome.Failed -> Text(
            text = outcome.describeFailure(),
            color = IndicatorColors.failure,
            style = MaterialTheme.typography.caption,
        )
    }
}

private val FIELD_COLOR = Color(0xFF24242E)
private val ANSWER_MAX_HEIGHT = 220.dp
