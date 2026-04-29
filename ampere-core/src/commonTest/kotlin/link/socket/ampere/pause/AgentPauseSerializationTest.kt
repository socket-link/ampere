package link.socket.ampere.pause

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class AgentPauseSerializationTest {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
    }

    @Test
    fun `AgentPause round-trips through JSON with all fields populated`() {
        val original = AgentPause(
            correlationId = "pause-1",
            reason = "Confirm production deploy",
            urgency = PauseUrgency.Critical,
            suggestedChannels = listOf(
                EscalationChannel.Voice(
                    prompt = "Approve the production deploy?",
                    expectedResponseSeconds = 20,
                    voiceProfile = "default",
                ),
                EscalationChannel.Push(
                    notificationCategory = "deploy",
                    title = "Approve production deploy",
                    body = "Hold on the v0.5.0 ship while we wait.",
                    deeplink = "ampere://pause/pause-1",
                ),
                EscalationChannel.InAppCard(
                    cardKind = EscalationChannel.InAppCard.CardKind.Modal,
                    title = "Production deploy",
                    body = "Approve to ship.",
                ),
                EscalationChannel.PublicLink(
                    url = "https://ampere.example/pause/pause-1",
                    displayLabel = "Open approval",
                ),
            ),
            timeoutMillis = 60_000,
            fallbackUrl = "https://ampere.example/pause/pause-1",
        )

        val encoded = json.encodeToString(AgentPause.serializer(), original)
        val decoded = json.decodeFromString(AgentPause.serializer(), encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun `AgentPause round-trips with null fallbackUrl`() {
        val original = AgentPause(
            correlationId = "pause-no-link",
            reason = "Internal-only escalation",
            urgency = PauseUrgency.Routine,
            suggestedChannels = listOf(
                EscalationChannel.InAppCard(
                    cardKind = EscalationChannel.InAppCard.CardKind.Banner,
                    title = "Heads up",
                    body = "Want to keep going?",
                ),
            ),
            timeoutMillis = 5_000,
            fallbackUrl = null,
        )

        val encoded = json.encodeToString(AgentPause.serializer(), original)
        val decoded = json.decodeFromString(AgentPause.serializer(), encoded)

        assertEquals(original, decoded)
        assertNull(decoded.fallbackUrl)
    }

    @Test
    fun `every EscalationChannel variant round-trips`() {
        val channels: List<EscalationChannel> = listOf(
            EscalationChannel.Push(
                notificationCategory = "auth",
                title = "Approve sign-in",
                body = "From 192.0.2.1",
            ),
            EscalationChannel.Voice(prompt = "Approve the sign-in?"),
            EscalationChannel.InAppCard(
                cardKind = EscalationChannel.InAppCard.CardKind.ArcPinned,
                title = "Continue?",
                body = "Long-running search waiting on you.",
            ),
            EscalationChannel.PublicLink(url = "https://example.com/p/x"),
        )

        for (channel in channels) {
            val encoded = json.encodeToString(EscalationChannel.serializer(), channel)
            val decoded = json.decodeFromString(EscalationChannel.serializer(), encoded)
            assertEquals(channel, decoded, "Channel $channel did not round-trip cleanly")
        }
    }

    @Test
    fun `every AgentPauseResponse variant round-trips`() {
        val responses: List<AgentPauseResponse> = listOf(
            AgentPauseResponse.Approved(correlationId = "p-1", payload = "lgtm"),
            AgentPauseResponse.Approved(correlationId = "p-2"),
            AgentPauseResponse.Rejected(correlationId = "p-3", reason = "wrong env"),
            AgentPauseResponse.Rejected(correlationId = "p-4"),
            AgentPauseResponse.TimedOut(correlationId = "p-5"),
        )

        for (response in responses) {
            val encoded = json.encodeToString(AgentPauseResponse.serializer(), response)
            val decoded = json.decodeFromString(AgentPauseResponse.serializer(), encoded)
            assertEquals(response, decoded)
        }
    }

    @Test
    fun `every PauseUrgency value round-trips`() {
        for (urgency in PauseUrgency.entries) {
            val encoded = json.encodeToString(PauseUrgency.serializer(), urgency)
            val decoded = json.decodeFromString(PauseUrgency.serializer(), encoded)
            assertEquals(urgency, decoded)
        }
    }

    @Test
    fun `AgentPauseResponse correlationId is exhaustive across variants`() {
        val responses: List<AgentPauseResponse> = listOf(
            AgentPauseResponse.Approved(correlationId = "a"),
            AgentPauseResponse.Rejected(correlationId = "b"),
            AgentPauseResponse.TimedOut(correlationId = "c"),
        )
        for (response in responses) {
            // Exhaustive when ensures every variant is covered at compile time.
            val id: PauseCorrelationId = when (response) {
                is AgentPauseResponse.Approved -> response.correlationId
                is AgentPauseResponse.Rejected -> response.correlationId
                is AgentPauseResponse.TimedOut -> response.correlationId
            }
            assertTrue(id.isNotEmpty())
        }
    }

    @Test
    fun `EscalationChannel sealed hierarchy is exhaustive at compile time`() {
        val channels: List<EscalationChannel> = listOf(
            EscalationChannel.Push("c", "t", "b"),
            EscalationChannel.Voice(prompt = "p"),
            EscalationChannel.InAppCard(
                cardKind = EscalationChannel.InAppCard.CardKind.Modal,
                title = "t",
                body = "b",
            ),
            EscalationChannel.PublicLink(url = "https://example.com"),
        )
        for (channel in channels) {
            val description: String = when (channel) {
                is EscalationChannel.Push -> "push:${channel.notificationCategory}"
                is EscalationChannel.Voice -> "voice:${channel.prompt}"
                is EscalationChannel.InAppCard -> "card:${channel.cardKind}"
                is EscalationChannel.PublicLink -> "link:${channel.url}"
            }
            assertTrue(description.isNotEmpty())
        }
    }

    @Test
    fun `AgentPause preserves channel ordering through serialization`() {
        val original = AgentPause(
            correlationId = "pause-order",
            reason = "verify ordering",
            urgency = PauseUrgency.Important,
            suggestedChannels = listOf(
                EscalationChannel.Push("c1", "t1", "b1"),
                EscalationChannel.InAppCard(
                    cardKind = EscalationChannel.InAppCard.CardKind.Banner,
                    title = "t",
                    body = "b",
                ),
                EscalationChannel.PublicLink(url = "https://example.com"),
            ),
            timeoutMillis = 30_000,
        )

        val encoded = json.encodeToString(AgentPause.serializer(), original)
        val decoded = json.decodeFromString(AgentPause.serializer(), encoded)

        assertEquals(original.suggestedChannels, decoded.suggestedChannels)
        assertIs<EscalationChannel.Push>(decoded.suggestedChannels[0])
        assertIs<EscalationChannel.InAppCard>(decoded.suggestedChannels[1])
        assertIs<EscalationChannel.PublicLink>(decoded.suggestedChannels[2])
    }

    @Test
    fun `Approved payload is optional and round-trips when absent`() {
        val original: AgentPauseResponse = AgentPauseResponse.Approved(correlationId = "p-x")
        val encoded = json.encodeToString(AgentPauseResponse.serializer(), original)
        val decoded = json.decodeFromString(AgentPauseResponse.serializer(), encoded)
        assertEquals(original, decoded)
        val approved = decoded as AgentPauseResponse.Approved
        assertNull(approved.payload)
    }

    @Test
    fun `Approved payload is preserved when supplied`() {
        val original: AgentPauseResponse = AgentPauseResponse.Approved(
            correlationId = "p-y",
            payload = "ship-it-v0.5.0",
        )
        val encoded = json.encodeToString(AgentPauseResponse.serializer(), original)
        val decoded = json.decodeFromString(AgentPauseResponse.serializer(), encoded)
        val approved = decoded as AgentPauseResponse.Approved
        assertNotNull(approved.payload)
        assertEquals("ship-it-v0.5.0", approved.payload)
    }
}
