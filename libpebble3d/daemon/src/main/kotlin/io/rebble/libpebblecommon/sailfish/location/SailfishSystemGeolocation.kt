package io.rebble.libpebblecommon.sailfish.location

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.util.GeolocationPositionResult
import io.rebble.libpebblecommon.util.SystemGeolocation
import io.rebble.libpebblecommon.util.SystemGeolocation.Companion.DEFAULT_MAX_AGE
import io.rebble.libpebblecommon.util.SystemGeolocation.Companion.DEFAULT_TIMEOUT
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.freedesktop.dbus.DBusMatchRule
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.messages.DBusSignal
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * PKJS geolocation via Sailfish's geoclue 0.12 providers, addressed directly (the Master's
 * connectivity gating falsely reports "no usable Position providers"). The cheap network provider
 * is kept referenced so it stays warm and pushes PositionChanged; GPS is only polled on demand.
 * The freshest fix by timestamp wins.
 */
internal class SailfishSystemGeolocation : SystemGeolocation {
    private val logger = Logger.withTag("SailfishSystemGeolocation")
    private val mutex = Mutex()
    private var connection: DBusConnection? = null
    private val lastFix = MutableStateFlow<GeolocationPositionResult.Success?>(null)

    // Both PositionChanged and GetPosition carry iiddd(idd): fields, timestamp, lat, lon, alt,
    // (level, hAcc, vAcc). getParameters() demarshals the (idd) struct to an Array/List.
    private fun parseParams(params: Array<Any?>?): GeolocationPositionResult.Success? {
        if (params == null || params.size < 5) return null
        val fields = (params[0] as? Number)?.toInt() ?: return null
        if (fields and GeoclueConstants.FIELD_LATITUDE == 0 ||
            fields and GeoclueConstants.FIELD_LONGITUDE == 0
        ) {
            return null
        }
        val latitude = (params[2] as? Number)?.toDouble() ?: return null
        val longitude = (params[3] as? Number)?.toDouble() ?: return null
        val altitude = (params[4] as? Number)?.toDouble()
        val horizontal = when (val acc = params.getOrNull(5)) {
            is Array<*> -> (acc.getOrNull(1) as? Number)?.toDouble()
            is List<*> -> (acc.getOrNull(1) as? Number)?.toDouble()
            else -> null
        }
        return GeolocationPositionResult.Success(
            timestamp = Instant.fromEpochSeconds((params[1] as? Number)?.toLong() ?: 0L),
            latitude = latitude,
            longitude = longitude,
            accuracy = horizontal?.takeIf { it > 0 && it.isFinite() },
            altitude = altitude?.takeIf {
                fields and GeoclueConstants.FIELD_ALTITUDE != 0 && it.isFinite()
            },
            heading = null,
            speed = null,
        )
    }

    // Keep only the freshest fix — a stale GPS push must not clobber a newer network fix.
    private fun offer(fix: GeolocationPositionResult.Success) {
        val cur = lastFix.value
        if (cur == null || fix.timestamp >= cur.timestamp) lastFix.value = fix
    }

    private fun handleSignal(signal: DBusSignal) {
        @Suppress("UNCHECKED_CAST")
        val params = runCatching { signal.parameters }.getOrNull() as? Array<Any?>
        parseParams(params)?.let { offer(it) }
    }

    // Read each provider's last known fix directly. Raw reply params sidestep demarshalling the
    // (idd) accuracy struct; the newest fix across providers is returned.
    private fun queryPositionOnce(): GeolocationPositionResult.Success? {
        val conn = connection ?: return null
        var best: GeolocationPositionResult.Success? = null
        for ((service, path) in GeoclueConstants.PROVIDERS) {
            val fix = try {
                val position = conn.getRemoteObject(service, path, GeocluePosition::class.java)
                val reply = conn.callMethodAsync(position, "GetPosition")
                    .call.getReply(GeoclueConstants.GET_POSITION_TIMEOUT_MS)
                @Suppress("UNCHECKED_CAST")
                parseParams(reply?.parameters as? Array<Any?>)
            } catch (e: Exception) {
                logger.d { "GetPosition($service) failed: ${e::class.simpleName}: ${e.message}" }
                null
            }
            if (fix != null) {
                offer(fix)
                if (best == null || fix.timestamp > best.timestamp) best = fix
            }
        }
        return best
    }

    private suspend fun ensureStarted(): Boolean = mutex.withLock {
        if (connection != null) return true
        try {
            val conn = DBusConnectionBuilder.forSessionBus().build()
            // Catch PositionChanged from any provider (same interface+member); filter by fields.
            conn.addGenericSigHandler(
                DBusMatchRule("signal", GeoclueConstants.POSITION_INTERFACE, GeoclueConstants.POSITION_CHANGED),
                DBusSigHandler<DBusSignal> { signal ->
                    runCatching { handleSignal(signal) }
                        .onFailure { logger.w { "bad position signal: ${it.message}" } }
                },
            )
            // Keep the network provider active (cheap) so it keeps a fresh fix and pushes updates.
            runCatching {
                conn.getRemoteObject(
                    GeoclueConstants.NETWORK_SERVICE, GeoclueConstants.NETWORK_PATH,
                    GeoclueProvider::class.java,
                ).AddReference()
            }.onFailure { logger.d { "AddReference(network) failed: ${it.message}" } }
            connection = conn
            logger.i { "geoclue providers ready" }
            true
        } catch (e: Exception) {
            logger.w { "geoclue unavailable: ${e.message}" }
            runCatching { connection?.disconnect() }
            connection = null
            false
        }
    }

    override suspend fun getCurrentPosition(
        maximumAge: Duration?,
        timeout: Duration?,
        highAccuracy: Boolean,
    ): GeolocationPositionResult {
        if (!ensureStarted()) return GeolocationPositionResult.Error("Positioning unavailable")
        val maxAge = maximumAge ?: DEFAULT_MAX_AGE
        val now = Clock.System.now()
        lastFix.value?.takeIf { now - it.timestamp <= maxAge }?.let { return it }
        // Providers usually have a cached fix ready; poll in case one is still acquiring.
        val fix = withTimeoutOrNull(timeout ?: DEFAULT_TIMEOUT) {
            while (true) {
                queryPositionOnce()?.let { return@withTimeoutOrNull it }
                delay(POLL_INTERVAL_MS)
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: lastFix.value
        if (fix == null) return GeolocationPositionResult.Error("Timed out waiting for a fix")
        logger.i { "position: ${fix.latitude}, ${fix.longitude} (±${fix.accuracy}m)" }
        return fix
    }

    override suspend fun watchPosition(
        interval: Duration,
        highAccuracy: Boolean,
    ): Flow<GeolocationPositionResult> {
        if (!ensureStarted()) {
            return MutableStateFlow(GeolocationPositionResult.Error("Positioning unavailable"))
        }
        return lastFix.filterNotNull().sample(interval)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1500L
    }
}
