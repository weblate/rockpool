package io.rebble.libpebblecommon.rockwork

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.util.JvmPaths
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * Settings the rockpool UI expects the daemon to persist (oauth token, weather prefs, health
 * params, profiles, timeline window...) but which have no libpebble3 equivalent yet. Simple
 * synchronized properties file under the daemon's XDG data dir.
 */
internal class RockworkSettings {
    private val logger = Logger.withTag("RockworkSettings")
    private val file = JvmPaths.dataHome.resolve("rockwork.properties")
    private val props = Properties()

    init {
        runCatching {
            if (file.exists()) file.inputStream().use { props.load(it) }
        }.onFailure { logger.w { "failed to load $file: ${it.message}" } }
    }

    @Synchronized
    fun get(key: String, default: String = ""): String =
        props.getProperty(key) ?: default

    @Synchronized
    fun getBool(key: String, default: Boolean = false): Boolean =
        props.getProperty(key)?.toBooleanStrictOrNull() ?: default

    @Synchronized
    fun getInt(key: String, default: Int): Int =
        props.getProperty(key)?.toIntOrNull() ?: default

    @Synchronized
    fun set(key: String, value: String) {
        props.setProperty(key, value)
        runCatching {
            file.outputStream().use { props.store(it, "libpebble3d org.rockwork settings") }
        }.onFailure { logger.w { "failed to save $file: ${it.message}" } }
    }

    fun set(key: String, value: Boolean) = set(key, value.toString())
    fun set(key: String, value: Int) = set(key, value.toString())
}
