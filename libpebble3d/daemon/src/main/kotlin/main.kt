// Distinct facade name: commonMain/main.kt (the getPlatform/performPlatformSpecificInit expects)
// already compiles to io.rebble.libpebblecommon.MainKt in libpebble3's jvm jar, so the daemon
// entrypoint must not also be MainKt or the two collide on the classpath.
@file:JvmName("Daemon")

package io.rebble.libpebblecommon

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.DiscoveredPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.LibPebble3
import io.rebble.libpebblecommon.rockwork.RockworkService
import io.rebble.libpebblecommon.rockwork.RockworkSettings
import io.rebble.libpebblecommon.sailfish.sailfishModule
import io.rebble.libpebblecommon.linux.web.RebbleBootConfigProvider
import io.rebble.libpebblecommon.linux.web.RebbleTokenProvider
import io.rebble.libpebblecommon.linux.web.RebbleWebServices
import io.rebble.libpebblecommon.linux.voice.RebbleTranscriptionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Headless Sailfish daemon entrypoint. Assembles libpebble3 (the generic Linux library) into a
 * Sailfish daemon: generic freedesktop platform defaults, the [sailfishModule] overrides, and the
 * org.rockwork D-Bus control surface, then idles so BLE scanning and connection can run.
 */
fun main() {
    // Info by default: Debug logs the whole watch list on every state change, which
    // floods journald on a phone. Raise with LIBPEBBLE3D_DEBUG/VERBOSE or setLogLevel.
    Logger.setMinSeverity(
        when {
            System.getenv("LIBPEBBLE3D_VERBOSE") == "1" -> Severity.Verbose
            System.getenv("LIBPEBBLE3D_DEBUG") == "1" -> Severity.Debug
            else -> Severity.Info
        }
    )
    Logger.i { "libpebble3 (Linux desktop) starting" }

    // Shared with RockworkService: setOAuthToken (UI's boot.rebble.io login) lands here and
    // authenticates all Rebble web services.
    val settings = RockworkSettings()
    val oauthToken = { settings.get("account.oauthToken").ifEmpty { null } }

    // One boot-config fetch shared by every Rebble-backed feature: firmware/locker (web services),
    // the account dev token (getAccountToken), and the ASR voice endpoints.
    val bootConfig = RebbleBootConfigProvider(oauthToken)
    val webServices = RebbleWebServices(oauthToken, bootConfig)
    // Tracing hook: record ktor/TLS reflection in the native-image metadata (build-native.sh).
    if (System.getenv("LIBPEBBLE3D_TRACE_HTTP") == "1") {
        runBlocking { runCatching { webServices.selfTest() }.onFailure { it.printStackTrace() } }
    }

    val libPebble = LibPebble3.create(
        defaultConfig = LibPebbleConfig(),
        webServices = webServices,
        appContext = AppContext(),
        // Backs Pebble.getAccountToken(): the Rebble account's user id (from users/me).
        tokenProvider = RebbleTokenProvider(oauthToken, bootConfig),
        proxyTokenProvider = MutableStateFlow(null),
        // Voice dictation via Rebble ASR (subscribers only).
        transcriptionProvider = RebbleTranscriptionProvider(bootConfig),
        // libpebble3's jvm defaults are plain freedesktop; this is what makes it a Sailfish daemon.
        platformOverrides = listOf(sailfishModule),
    )
    libPebble.init()
    // LIBPEBBLE3D_PPOG_VERBOSE=1 logs every PPoGATT packet (inbound acks/data, outbound data)
    // for transport-level diagnosis. Applied AFTER init because the stored config overrides
    // the default; gated separately from the Kermit log level. Set explicitly either way so a
    // previous debug run doesn't leave it persisted on.
    run {
        val wantPpogVerbose = System.getenv("LIBPEBBLE3D_PPOG_VERBOSE") == "1"
        val current = libPebble.config.value
        if (current.bleConfig.verbosePpogLogging != wantPpogVerbose) {
            libPebble.updateConfig(
                current.copy(bleConfig = current.bleConfig.copy(verbosePpogLogging = wantPpogVerbose))
            )
        }
        if (wantPpogVerbose) Logger.i { "PPoGATT verbose logging enabled" }
    }
    Logger.i { "libpebble3 init() complete; idling" }

    // The rockpool UI's control surface (org.rockwork on the session bus).
    RockworkService(libPebble, settings).start()

    runBlocking {
        runAutoConnectHookIfEnabled(libPebble)
        awaitCancellation()
    }
}

/**
 * Device-testing hook until a real control surface (D-Bus API) exists:
 * LIBPEBBLE3D_AUTOCONNECT=1 starts a BLE scan after init, logs every watch the scan
 * surfaces, and connects to the first discovered Pebble — or only to the one whose BLE
 * address matches LIBPEBBLE3D_WATCH=AA:BB:CC:DD:EE:FF if that is set. Put the watch in
 * pairing mode (Settings > Bluetooth on the watch); bonding is handled by the JustWorks
 * agent.
 */
private fun CoroutineScope.runAutoConnectHookIfEnabled(libPebble: LibPebble) {
    if (System.getenv("LIBPEBBLE3D_AUTOCONNECT") != "1") return
    val wantedAddress = System.getenv("LIBPEBBLE3D_WATCH")?.uppercase()
    val logger = Logger.withTag("AutoConnect")

    launch {
        libPebble.connectionEvents.collect { logger.i { "connection event: $it" } }
    }
    launch {
        logger.i { "starting BLE scan (filter: ${wantedAddress ?: "first Pebble found"})" }
        libPebble.startBleScan()
        val attempted = mutableSetOf<String>()
        val logged = mutableSetOf<String>()
        libPebble.watches.collect { devices ->
            devices.forEach { device ->
                val address = device.identifier.asString.uppercase()
                if (logged.add("$address/${device::class.simpleName}")) {
                    logger.i { "watch: ${device.displayName()} $address (${device::class.simpleName})" }
                }
            }
            val candidate = devices.filterIsInstance<DiscoveredPebbleDevice>()
                .firstOrNull {
                    wantedAddress == null || it.identifier.asString.uppercase() == wantedAddress
                }
            if (candidate != null && attempted.add(candidate.identifier.asString)) {
                logger.i { "connecting to ${candidate.displayName()} (${candidate.identifier.asString})" }
                candidate.connect()
            }
        }
    }
}
