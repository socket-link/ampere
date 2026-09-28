package link.socket.ampere.agents.domain.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import link.socket.ampere.data.DEFAULT_JSON

class ExecutionAssignmentSerializationTest {

    private val json = DEFAULT_JSON

    @Test
    fun `every WorkPhase round-trips under a stable lowercase wire name`() {
        val wireNames = WorkPhase.entries.associateWith { phase ->
            json.encodeToString(WorkPhase.serializer(), phase)
        }

        assertEquals(
            mapOf(
                WorkPhase.RECON to "\"recon\"",
                WorkPhase.IMPLEMENTATION to "\"implementation\"",
            ),
            wireNames,
        )
        wireNames.forEach { (phase, encoded) ->
            assertEquals(phase, json.decodeFromString(WorkPhase.serializer(), encoded))
        }
    }

    @Test
    fun `only the implementation phase permits writes`() {
        assertFalse(WorkPhase.RECON.permitsWrites)
        assertTrue(WorkPhase.IMPLEMENTATION.permitsWrites)
    }

    @Test
    fun `every EffortLevel round-trips under a stable lowercase wire name`() {
        val wireNames = EffortLevel.entries.associateWith { effort ->
            json.encodeToString(EffortLevel.serializer(), effort)
        }

        assertEquals(
            mapOf(
                EffortLevel.LOW to "\"low\"",
                EffortLevel.MEDIUM to "\"medium\"",
                EffortLevel.HIGH to "\"high\"",
            ),
            wireNames,
        )
        wireNames.forEach { (effort, encoded) ->
            assertEquals(effort, json.decodeFromString(EffortLevel.serializer(), encoded))
        }
    }

    @Test
    fun `an effort outside the closed set is rejected`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString(EffortLevel.serializer(), "\"extreme\"")
        }
    }

    @Test
    fun `ExecutionAssignment round-trips with model and effort`() {
        val original = ExecutionAssignment(model = "claude-sonnet-5", effort = EffortLevel.MEDIUM)

        val encoded = json.encodeToString(ExecutionAssignment.serializer(), original)
        val decoded = json.decodeFromString(ExecutionAssignment.serializer(), encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun `ExecutionAssignment round-trips with only one property set`() {
        val modelOnly = ExecutionAssignment(model = "sonnet")
        val effortOnly = ExecutionAssignment(effort = EffortLevel.HIGH)

        listOf(modelOnly, effortOnly).forEach { original ->
            val encoded = json.encodeToString(ExecutionAssignment.serializer(), original)
            assertEquals(original, json.decodeFromString(ExecutionAssignment.serializer(), encoded))
        }
    }

    @Test
    fun `ExecutionAssignment decodes from an empty object as unspecified`() {
        val decoded = json.decodeFromString(ExecutionAssignment.serializer(), "{}")

        assertNull(decoded.model)
        assertNull(decoded.effort)
    }
}
