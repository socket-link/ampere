package link.socket.ampere.canon

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Ring 2 — Platform. Typed declarations with minimal properties.
 *
 * These reach Ampere through a native framework, not through the assistant
 * vocabulary. They carry enough shape for `PlugManifest` emits/consumes
 * declarations and for provenance to flow end-to-end; richer fields land with
 * each platform integration.
 *
 * `CalendarEvent`, `Reminder`, `Alarm`, and `MediaItem` were Ring 1 candidates.
 * They are here because the shipped Apple catalog has no calendar, reminders,
 * clock, or music/video noun — not because they matter less. A Ring 2 type is
 * fully usable by an Arc; what the ring records is that it does not travel
 * through Apple's cross-app registry.
 */

@Serializable
@SerialName("canon.calendar_event")
data class CanonCalendarEvent(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val title: String,
    val startsAt: Instant,
    val endsAt: Instant? = null,
    val isAllDay: Boolean = false,
    val calendarId: String? = null,
    val place: CanonPlace? = null,
    val attendees: List<CanonPerson> = emptyList(),
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.CALENDAR_EVENT
}

@Serializable
@SerialName("canon.reminder")
data class CanonReminder(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val title: String,
    val dueAt: Instant? = null,
    val isCompleted: Boolean = false,
    val listId: String? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.REMINDER
}

@Serializable
@SerialName("canon.alarm")
data class CanonAlarm(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val label: String? = null,
    val firesAt: Instant? = null,
    val isEnabled: Boolean = true,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.ALARM
}

@Serializable
@SerialName("canon.media_item")
data class CanonMediaItem(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val title: String,
    val artist: String? = null,
    val durationSeconds: Long? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.MEDIA_ITEM
}

@Serializable
@SerialName("canon.health_sample")
data class CanonHealthSample(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val quantityType: String,
    val value: Double,
    val unit: String,
    val recordedAt: Instant,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.HEALTH_SAMPLE
}

@Serializable
@SerialName("canon.home_accessory")
data class CanonHomeAccessory(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val name: String,
    val room: String? = null,
    val isReachable: Boolean = true,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.HOME_ACCESSORY
}

@Serializable
@SerialName("canon.transaction")
data class CanonTransaction(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val amountMinorUnits: Long,
    val currencyCode: String,
    val merchantName: String? = null,
    val postedAt: Instant? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.TRANSACTION
}

@Serializable
@SerialName("canon.pass")
data class CanonPass(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val description: String,
    val organizationName: String? = null,
    val expiresAt: Instant? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.PASS
}

@Serializable
@SerialName("canon.weather_forecast")
data class CanonWeatherForecast(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val place: CanonPlace?,
    val validAt: Instant,
    val temperatureCelsius: Double? = null,
    val conditionSummary: String? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.WEATHER_FORECAST
}

@Serializable
@SerialName("canon.bluetooth_peripheral")
data class CanonBluetoothPeripheral(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val name: String? = null,
    val isConnected: Boolean = false,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.BLUETOOTH_PERIPHERAL
}

@Serializable
@SerialName("canon.motion_sample")
data class CanonMotionSample(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val activity: String,
    val confidence: Double? = null,
    val recordedAt: Instant,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.MOTION_SAMPLE
}
