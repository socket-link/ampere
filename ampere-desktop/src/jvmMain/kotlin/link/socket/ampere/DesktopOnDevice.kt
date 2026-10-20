package link.socket.ampere

import java.io.File
import link.socket.ampere.cli.ondevice.OnDeviceHelperEngine
import link.socket.ampere.data.createJvmDriver
import link.socket.ampere.db.Database
import link.socket.ampere.llm.OnDeviceInferenceSession

/**
 * The desktop app's on-device model, or why it has none (AMPR-469).
 *
 * The engine is `ampere-helper`'s, found where the app bundled it, and the session over it is
 * on-device only: it has no cloud transport, so nothing typed into the screen leaves the machine.
 * Rung ZERO serves display text alone (AMPR-372), so the answer goes to the person reading it and
 * nowhere else.
 *
 * Its events are stored in the app's own database, at an absolute path. `createJvmDriver`'s
 * default name is relative to the working directory, which for a packaged app is wherever it was
 * launched from, and the CLI's database is the CLI's.
 *
 * @property session What `OnDeviceCognitionScreen` asks and watches, or null when there is no helper.
 * @property unavailableReason Why there is no [session], for the indicator.
 */
internal class DesktopOnDevice private constructor(
    val session: OnDeviceInferenceSession?,
    val unavailableReason: String?,
    private val resources: List<AutoCloseable> = emptyList(),
) : AutoCloseable {
    /** Ends the session's scope, then the helper's process group, then the database. */
    override fun close() {
        session?.close()
        resources.forEach { resource -> runCatching { resource.close() } }
    }

    companion object {
        /** Where the app's events are stored when it has an on-device model. */
        val DEFAULT_DATABASE: File = File(System.getProperty("user.home"), ".ampere/desktop.db")

        /**
         * Find the helper and open a session over it, or say why there is none. Spawns nothing: the
         * helper starts on the screen's first probe. Opens no database without a helper.
         */
        fun open(database: File = DEFAULT_DATABASE): DesktopOnDevice {
            val found =
                when (val located = OnDeviceHelperEngine.locate()) {
                    is OnDeviceHelperEngine.Absent ->
                        return DesktopOnDevice(session = null, unavailableReason = located.reason)
                    is OnDeviceHelperEngine.Present -> located
                }

            val driver =
                runCatching {
                    database.absoluteFile.parentFile?.mkdirs()
                    createJvmDriver(database.absolutePath)
                }.getOrElse { failure ->
                    found.close()
                    return DesktopOnDevice(
                        session = null,
                        unavailableReason =
                            "The on-device model's event store at '${database.absolutePath}' " +
                                "could not be opened: ${failure.message}",
                    )
                }

            return DesktopOnDevice(
                session = OnDeviceInferenceSession.create(engine = found.engine, database = Database(driver)),
                unavailableReason = null,
                resources = listOf(found, driver),
            )
        }
    }
}
