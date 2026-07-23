package io.rebble.libpebblecommon.sailfish.calls

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.calls.Call
import io.rebble.libpebblecommon.calls.LegacyPhoneReceiver
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.sailfish.contacts.QtContactsDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * Drives the current-call StateFlow from Sailfish's voicecall-manager over the session bus,
 * mirroring rockpoold's VoiceCallManager/VoiceCallHandler: track the manager's `activeVoiceCall`
 * handler id and read the per-call properties whenever something changes.
 */
internal class SailfishPhoneReceiver(
    private val scope: LibPebbleCoroutineScope,
) : LegacyPhoneReceiver {
    private val logger = Logger.withTag("SailfishPhoneReceiver")
    private val initialized = AtomicBoolean(false)
    private val warnedNoService = AtomicBoolean(false)

    @Volatile
    private var connection: DBusConnection? = null

    // Only touched from the single monitor coroutine.
    private var lastSnapshot: Snapshot? = null
    private var cachedLookup: Pair<String, String?>? = null

    private data class Snapshot(
        val type: String,
        val cookie: UInt,
        val number: String,
        val name: String?,
    )

    override fun init(currentCall: MutableStateFlow<Call?>) {
        if (!initialized.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                monitorLoop(currentCall)
            } catch (e: Throwable) {
                logger.e("call monitor died", e)
            }
        }
    }

    private suspend fun monitorLoop(currentCall: MutableStateFlow<Call?>) {
        while (currentCoroutineContext().isActive) {
            val conn = try {
                DBusConnectionBuilder.forSessionBus().build()
            } catch (e: Exception) {
                logger.w { "session bus unavailable (${e.message}); retrying" }
                null
            }
            if (conn == null) {
                delay(RETRY_DELAY)
                continue
            }
            connection = conn
            try {
                watch(conn, currentCall)
            } catch (e: Exception) {
                logger.w { "voicecall watch failed (${e.message}); reconnecting" }
            } finally {
                connection = null
                runCatching { conn.disconnect() }
                update(currentCall, null)
            }
            delay(RETRY_DELAY)
        }
    }

    private suspend fun watch(conn: DBusConnection, currentCall: MutableStateFlow<Call?>) {
        val wake = Channel<Unit>(Channel.CONFLATED)
        val handlers = listOf(
            conn.addSigHandler(VoiceCallManagerIface.activeVoiceCallChanged::class.java) {
                wake.trySend(Unit)
            },
            conn.addSigHandler(VoiceCallManagerIface.voiceCallsChanged::class.java) {
                wake.trySend(Unit)
            },
            conn.addSigHandler(VoiceCallIface.statusChanged::class.java) {
                wake.trySend(Unit)
            },
        )
        logger.i { "watching $VOICECALL_SERVICE" }
        try {
            while (currentCoroutineContext().isActive && conn.isConnected) {
                evaluate(conn, currentCall)
                // Fast poll during a call as a fallback if a statusChanged signal is missed.
                val timeout = if (currentCall.value != null) ACTIVE_POLL else IDLE_POLL
                withTimeoutOrNull(timeout) { wake.receive() }
            }
        } finally {
            handlers.forEach { runCatching { it.close() } }
        }
    }

    private fun evaluate(conn: DBusConnection, currentCall: MutableStateFlow<Call?>) {
        val handlerId = try {
            val raw: Any? = conn
                .getRemoteObject(VOICECALL_SERVICE, VOICECALL_MANAGER_PATH, Properties::class.java)
                .Get<Any>(VOICECALL_MANAGER_IFACE, "activeVoiceCall")
            when (raw) {
                is Variant<*> -> raw.value as? String
                is String -> raw
                else -> null
            }
        } catch (e: Exception) {
            // No voicecall service (e.g. not a phone, or it's not up yet): treat as no call.
            if (warnedNoService.compareAndSet(false, true)) {
                logger.w { "voicecall manager unavailable: ${e.message}" }
            }
            null
        }
        val call = if (handlerId.isNullOrEmpty()) {
            null
        } else {
            try {
                readCall(conn, handlerId)
            } catch (e: Exception) {
                // Call object already gone.
                logger.d { "reading call $handlerId failed: ${e.message}" }
                null
            }
        }
        update(currentCall, call)
    }

    private fun readCall(conn: DBusConnection, handlerId: String): Call? {
        val props = conn
            .getRemoteObject(VOICECALL_SERVICE, voiceCallPath(handlerId), Properties::class.java)
            .GetAll(VOICECALL_IFACE)
        val status = (props["status"]?.value as? Number)?.toInt() ?: return null
        val number = props["lineId"]?.value as? String ?: ""
        val cookie = handlerId.hashCode().toUInt()
        val name = if (number.isNotBlank()) contactNameFor(number) else null
        val onEnd: (Call.EndableCall) -> Unit = { hangup(handlerId) }
        return when (status) {
            STATUS_INCOMING, STATUS_WAITING -> Call.RingingCall(
                contactName = name,
                contactNumber = number,
                cookie = cookie,
                onCallEnd = onEnd,
                onCallAnswer = { answer(handlerId) },
            )

            STATUS_DIALING, STATUS_ALERTING -> Call.DialingCall(name, number, cookie, onEnd)
            STATUS_ACTIVE -> Call.ActiveCall(name, number, cookie, onEnd)
            STATUS_HELD -> Call.HoldingCall(name, number, cookie, onEnd)
            else -> null // null/disconnected
        }
    }

    /** Set the flow only when the mapped state really changed (Call has no equals). */
    private fun update(flow: MutableStateFlow<Call?>, call: Call?) {
        val snapshot = call?.let {
            Snapshot(it::class.simpleName.orEmpty(), it.cookie, it.contactNumber, it.contactName)
        }
        if (snapshot == lastSnapshot) return
        lastSnapshot = snapshot
        logger.d { "call state -> ${snapshot?.type ?: "none"}" }
        flow.value = call
    }

    private fun contactNameFor(number: String): String? {
        cachedLookup?.let { (n, name) -> if (n == number) return name }
        val name = QtContactsDb.lookupNameByNumber(number)
        cachedLookup = number to name
        return name
    }

    private fun answer(handlerId: String) = callMethod(handlerId, "answer") { it.answer() }

    private fun hangup(handlerId: String) = callMethod(handlerId, "hangup") { it.hangup() }

    private fun callMethod(handlerId: String, label: String, block: (VoiceCallIface) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val conn = connection ?: run {
                logger.w { "$label($handlerId) ignored: no bus connection" }
                return@launch
            }
            try {
                block(
                    conn.getRemoteObject(
                        VOICECALL_SERVICE,
                        voiceCallPath(handlerId),
                        VoiceCallIface::class.java,
                    )
                )
            } catch (e: Exception) {
                logger.w { "$label($handlerId) failed: ${e.message}" }
            }
        }
    }

    private companion object {
        private val RETRY_DELAY = 30.seconds
        private val IDLE_POLL = 60.seconds
        private val ACTIVE_POLL = 1.seconds
    }
}
