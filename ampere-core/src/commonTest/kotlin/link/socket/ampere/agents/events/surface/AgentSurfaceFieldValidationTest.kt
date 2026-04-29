package link.socket.ampere.agents.events.surface

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.datetime.Instant

class AgentSurfaceFieldValidationTest {

    @Test
    fun `Text field flags missing required value`() {
        val field = AgentSurfaceField.Text(id = "name", label = "Name", required = true)
        val result = field.validate(null)
        assertIs<FieldValidationResult.Invalid>(result)
        assertTrue(result.errors.any { it.contains("required", ignoreCase = true) })
    }

    @Test
    fun `Text field enforces minLength and pattern`() {
        val field = AgentSurfaceField.Text(
            id = "branch",
            label = "Branch",
            required = true,
            minLength = 3,
            maxLength = 10,
            pattern = "^[a-z-]+$",
        )

        val tooShort = field.validate(AgentSurfaceFieldValue.TextValue("ab"))
        assertIs<FieldValidationResult.Invalid>(tooShort)
        assertTrue(tooShort.errors.any { it.contains("at least 3") })

        val tooLong = field.validate(AgentSurfaceFieldValue.TextValue("abcdefghijklmnop"))
        assertIs<FieldValidationResult.Invalid>(tooLong)
        assertTrue(tooLong.errors.any { it.contains("at most 10") })

        val badPattern = field.validate(AgentSurfaceFieldValue.TextValue("Abc"))
        assertIs<FieldValidationResult.Invalid>(badPattern)
        assertTrue(badPattern.errors.any { it.contains("expected format") })

        val ok = field.validate(AgentSurfaceFieldValue.TextValue("feature"))
        assertEquals(FieldValidationResult.Valid, ok)
    }

    @Test
    fun `Number field enforces bounds and integer-only`() {
        val field = AgentSurfaceField.Number(
            id = "count",
            label = "Count",
            required = true,
            min = 1.0,
            max = 10.0,
            integerOnly = true,
        )

        assertIs<FieldValidationResult.Invalid>(field.validate(null))
        assertIs<FieldValidationResult.Invalid>(field.validate(AgentSurfaceFieldValue.NumberValue(0.0)))
        assertIs<FieldValidationResult.Invalid>(field.validate(AgentSurfaceFieldValue.NumberValue(11.0)))
        assertIs<FieldValidationResult.Invalid>(field.validate(AgentSurfaceFieldValue.NumberValue(2.5)))
        assertEquals(FieldValidationResult.Valid, field.validate(AgentSurfaceFieldValue.NumberValue(3.0)))
    }

    @Test
    fun `Toggle field flags missing acceptance when required`() {
        val field = AgentSurfaceField.Toggle(id = "tos", label = "Accept ToS", required = true)
        assertIs<FieldValidationResult.Invalid>(field.validate(AgentSurfaceFieldValue.ToggleValue(false)))
        assertEquals(
            FieldValidationResult.Valid,
            field.validate(AgentSurfaceFieldValue.ToggleValue(true)),
        )
    }

    @Test
    fun `DateTime field enforces notBefore and notAfter`() {
        val low = Instant.fromEpochMilliseconds(1_000)
        val high = Instant.fromEpochMilliseconds(10_000)
        val field = AgentSurfaceField.DateTime(
            id = "when",
            label = "When",
            required = true,
            notBefore = low,
            notAfter = high,
        )

        assertIs<FieldValidationResult.Invalid>(
            field.validate(AgentSurfaceFieldValue.DateTimeValue(Instant.fromEpochMilliseconds(0))),
        )
        assertIs<FieldValidationResult.Invalid>(
            field.validate(AgentSurfaceFieldValue.DateTimeValue(Instant.fromEpochMilliseconds(20_000))),
        )
        assertEquals(
            FieldValidationResult.Valid,
            field.validate(AgentSurfaceFieldValue.DateTimeValue(Instant.fromEpochMilliseconds(5_000))),
        )
    }

    @Test
    fun `Selection field enforces option ids and selection counts`() {
        val field = AgentSurfaceField.Selection(
            id = "branches",
            label = "Branches",
            required = true,
            options = listOf(
                AgentSurface.Choice.Option(id = "main", label = "main"),
                AgentSurface.Choice.Option(id = "develop", label = "develop"),
            ),
            multiSelect = true,
            minSelections = 1,
            maxSelections = 1,
        )

        assertIs<FieldValidationResult.Invalid>(
            field.validate(AgentSurfaceFieldValue.SelectionValue(emptyList())),
        )
        val tooMany = field.validate(AgentSurfaceFieldValue.SelectionValue(listOf("main", "develop")))
        assertIs<FieldValidationResult.Invalid>(tooMany)
        assertTrue(tooMany.errors.any { it.contains("at most 1") })

        val unknown = field.validate(AgentSurfaceFieldValue.SelectionValue(listOf("trunk")))
        assertIs<FieldValidationResult.Invalid>(unknown)
        assertTrue(unknown.errors.any { it.contains("unknown options") })

        assertEquals(
            FieldValidationResult.Valid,
            field.validate(AgentSurfaceFieldValue.SelectionValue(listOf("main"))),
        )
    }

    @Test
    fun `Secret field reuses Text-style length checks`() {
        val field = AgentSurfaceField.Secret(
            id = "token",
            label = "Token",
            required = true,
            minLength = 8,
            maxLength = 32,
        )

        assertIs<FieldValidationResult.Invalid>(field.validate(null))
        assertIs<FieldValidationResult.Invalid>(field.validate(AgentSurfaceFieldValue.SecretValue("short")))
        assertEquals(
            FieldValidationResult.Valid,
            field.validate(AgentSurfaceFieldValue.SecretValue("a-real-token")),
        )
    }

    @Test
    fun `mismatched value variant is treated as missing`() {
        val field = AgentSurfaceField.Text(id = "x", label = "X", required = true)
        val result = field.validate(AgentSurfaceFieldValue.NumberValue(1.0))
        assertIs<FieldValidationResult.Invalid>(result)
        assertTrue(result.errors.any { it.contains("required", ignoreCase = true) })
    }
}
