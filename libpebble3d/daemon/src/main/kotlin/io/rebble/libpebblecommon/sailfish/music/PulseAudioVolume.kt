package io.rebble.libpebblecommon.sailfish.music

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.linux.music.VolumeControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.connections.impl.DirectConnection
import org.freedesktop.dbus.connections.impl.DirectConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32

/**
 * System volume stepping via PulseAudio's Meego MainVolume2 extension (MPRIS has no volume
 * stepping). PulseAudio publishes a peer D-Bus address on the session bus; we connect to that
 * peer bus lazily and step CurrentStep within [0, StepCount-1] — same mechanism rockpool used.
 * All methods block and must be called on Dispatchers.IO; failures degrade to a no-op.
 */
class PulseAudioVolume : VolumeControl {
    private val logger = Logger.withTag("PulseAudioVolume")

    private val _volumePercent = MutableStateFlow<Int?>(null)
    /** Last known main volume in percent, null until PulseAudio was reachable once. */
    override val volumePercent: StateFlow<Int?> = _volumePercent.asStateFlow()

    private var peer: DirectConnection? = null
    private var stepCount = 0
    private var lastFailureAt = 0L

    @Synchronized
    override fun refresh() {
        val props = mainVolume() ?: return
        try {
            _volumePercent.value = toPercent(currentStep(props))
        } catch (e: Exception) {
            logger.w { "failed to read volume: ${e.message}" }
            dropConnection()
        }
    }

    @Synchronized
    override fun step(delta: Int) {
        val props = mainVolume() ?: return
        try {
            val current = currentStep(props)
            val target = (current + delta).coerceIn(0, stepCount - 1)
            if (target != current) {
                props.Set(VOLUME_IFACE, "CurrentStep", UInt32(target.toLong()))
            }
            _volumePercent.value = toPercent(target)
        } catch (e: Exception) {
            logger.w { "failed to change volume: ${e.message}" }
            dropConnection()
        }
    }

    private fun currentStep(props: Properties): Int =
        props.Get<UInt32>(VOLUME_IFACE, "CurrentStep").toInt()

    private fun toPercent(step: Int): Int =
        if (stepCount > 1) step * 100 / (stepCount - 1) else 100

    private fun mainVolume(): Properties? {
        val conn = ensureConnected() ?: return null
        return try {
            conn.getRemoteObject(VOLUME_PATH, Properties::class.java)
        } catch (e: Exception) {
            logger.w { "failed to create MainVolume2 proxy: ${e.message}" }
            null
        }
    }

    private fun ensureConnected(): DirectConnection? {
        peer?.let {
            if (it.isConnected) return it
            dropConnection()
        }
        // Don't hammer PulseAudio while it's unreachable (e.g. build container).
        val now = System.currentTimeMillis()
        if (now - lastFailureAt < RETRY_MS) return null
        return try {
            val conn = DirectConnectionBuilder.forAddress(lookupPeerAddress()).build()
            stepCount = conn.getRemoteObject(VOLUME_PATH, Properties::class.java)
                .Get<UInt32>(VOLUME_IFACE, "StepCount").toInt()
            logger.d { "connected to PulseAudio ($stepCount volume steps)" }
            peer = conn
            conn
        } catch (e: Exception) {
            lastFailureAt = now
            logger.w { "PulseAudio unreachable, volume control disabled: ${e.message}" }
            null
        }
    }

    private fun lookupPeerAddress(): String {
        val session = DBusConnectionBuilder.forSessionBus().withShared(false).build()
        try {
            return session
                .getRemoteObject(PA_BUS_NAME, PA_LOOKUP_PATH, Properties::class.java)
                .Get(PA_LOOKUP_IFACE, "Address")
        } finally {
            runCatching { session.disconnect() }
        }
    }

    private fun dropConnection() {
        peer?.let { runCatching { it.disconnect() } }
        peer = null
        stepCount = 0
    }

    companion object {
        private const val PA_BUS_NAME = "org.PulseAudio1"
        private const val PA_LOOKUP_PATH = "/org/pulseaudio/server_lookup1"
        private const val PA_LOOKUP_IFACE = "org.PulseAudio.ServerLookup1"
        private const val VOLUME_PATH = "/com/meego/mainvolume2"
        private const val VOLUME_IFACE = "com.Meego.MainVolume2"
        private const val RETRY_MS = 30_000L
    }
}
