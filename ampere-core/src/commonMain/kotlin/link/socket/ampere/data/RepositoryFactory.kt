package link.socket.ampere.data

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.messages.MessageRepository
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.agents.events.subscription.MessageSubscription
import link.socket.ampere.agents.events.subscription.Subscription
import link.socket.ampere.agents.tools.registry.ToolRegistryRepository
import link.socket.ampere.db.Database

/**
 * The JSON every repository encodes with.
 *
 * `NotificationEvent.ToAgent<S : Subscription>` is generic, so the serialization plugin encodes
 * its `subscription` through `PolymorphicSerializer(Subscription)` rather than the sealed
 * serializer, and looks the concrete subclass up here. Every [Subscription] implementor is
 * registered so the routers' notifications can go through the door (F1, AMPR-338).
 */
val DEFAULT_JSON = Json {
    prettyPrint = false
    encodeDefaults = true
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    serializersModule = SerializersModule {
        polymorphic(Subscription::class) {
            subclass(EventSubscription.ByEventClassType::class)
            subclass(MessageSubscription.ByType::class)
            subclass(MessageSubscription.ByChannels::class)
            subclass(MessageSubscription.ByThreads::class)
        }
    }
}

class RepositoryFactory(
    val scope: CoroutineScope,
    val driver: SqlDriver,
    val json: Json = DEFAULT_JSON,
) {
    val database: Database by lazy {
        Database(driver)
    }

    inline fun <reified T : Repository<*, *>> createRepository(): T = when (T::class) {
        EventRepository::class -> {
            EventRepository(json, scope, database) as T
        }
        MessageRepository::class -> {
            MessageRepository(json, scope, database) as T
        }
        ToolRegistryRepository::class -> {
            ToolRegistryRepository(json, scope, database) as T
        }
        UserConversationRepository::class -> {
            UserConversationRepository(json, scope) as T
        }
        else -> {
            throw IllegalArgumentException("No repository found for type ${T::class}")
        }
    }
}
