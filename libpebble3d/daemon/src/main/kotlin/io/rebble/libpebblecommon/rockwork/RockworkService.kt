package io.rebble.libpebblecommon.rockwork

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.ActiveDevice
import io.rebble.libpebblecommon.connection.BleDiscoveredPebbleDevice
import io.rebble.libpebblecommon.connection.bt.ble.bluez.BluezManager
import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.DiscoveredPebbleDevice
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater
import io.rebble.libpebblecommon.connection.KnownPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.PebbleDevice
import io.rebble.libpebblecommon.linux.dbus.RawSessionConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import kotlin.time.Duration.Companion.seconds

/**
 * Claims org.rockwork on the session bus and maintains the Manager object plus one Pebble object
 * per known watch, so the rockpool Silica UI can drive libpebble3d like it drove rockpoold.
 */
internal class RockworkService(
    private val libPebble: LibPebble,
    private val settings: RockworkSettings = RockworkSettings(),
) {
    private val logger = Logger.withTag("RockworkService")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var connection: DBusConnection? = null
    private val exported = LinkedHashMap<String, ExportedWatch>() // address -> object

    private data class ExportedWatch(
        val path: String,
        val obj: RockworkPebbleObject,
        var connected: Boolean,
        var connectionState: Int,
        var upgradingFirmware: Boolean = false,
        var upgradeAvailable: Boolean = false,
    )

    @Volatile
    private var scanning = false

    @Volatile
    private var scanResults: List<Variant<*>> = emptyList()

    // Addresses BlueZ has bonded, refreshed when a scan starts. Discovered watches whose address
    // is in here are already paired and are hidden from the scan results (see updateScanResults).
    @Volatile
    private var bondedAddresses: Set<String> = emptySet()

    // Named class (not an anonymous object): dbus-java invokes exported objects reflectively,
    // so this needs a stable class name in the native-image reflection config.
    internal inner class ManagerObject : RockworkManager {
        override fun getObjectPath() = MANAGER_PATH
        override fun isRemote() = false
        override fun Version(): String = VERSION
        override fun ListWatches(): List<DBusPath> =
            synchronized(exported) { exported.values.map { DBusPath(it.path) } }

        override fun StartScan() {
            // Refresh the bonded set first, so already-paired / dual-mode-misreporting watches
            // stay out of the fresh scan results (see updateScanResults).
            scope.launch(Dispatchers.IO) { bondedAddresses = BluezManager.bondedAddresses() }
            scope.launch { runCatching { libPebble.startBleScan() }.onFailure { logger.e("StartScan", it) } }
        }

        override fun StopScan() {
            scope.launch { runCatching { libPebble.stopBleScan() }.onFailure { logger.e("StopScan", it) } }
        }

        override fun IsScanning(): Boolean = scanning

        override fun ScanResults(): List<Variant<*>> {
            // Test hook: exercise the av/a{sv} marshalling + UI parsing without BT hardware.
            if (System.getenv("LIBPEBBLE3D_FAKE_SCAN") == "1") {
                return listOf(
                    scanResultVariant("Fake Pebble XYZW", "AA:BB:CC:DD:EE:FF", -42)
                )
            }
            return scanResults
        }

        override fun ConnectWatch(address: String) {
            val device = findWatch("ConnectWatch", address) ?: return
            logger.i { "ConnectWatch: connecting to ${device.displayName()} ($address)" }
            // Scanning while connecting starves LE connection attempts on many controllers.
            scope.launch { runCatching { libPebble.stopBleScan() } }
            device.connect()
        }

        override fun DisconnectWatch(address: String) {
            val device = findWatch("DisconnectWatch", address) ?: return
            val active = device as? ActiveDevice
            if (active == null) {
                logger.w {
                    "DisconnectWatch: $address is idle (${device::class.simpleName}); " +
                        "nothing to disconnect"
                }
                return
            }
            logger.i { "DisconnectWatch: disconnecting ${device.displayName()} ($address)" }
            active.disconnect()
        }

        override fun ForgetWatch(address: String) {
            val device = findWatch("ForgetWatch", address) ?: return
            val known = device as? KnownPebbleDevice
            if (known == null) {
                logger.w {
                    "ForgetWatch: $address was never paired (${device::class.simpleName})"
                }
                return
            }
            logger.i { "ForgetWatch: forgetting ${device.displayName()} ($address)" }
            known.forget()
            // forget() only clears libpebble3's own record — nothing in it removes the BlueZ bond.
            // Left behind, the bond makes the next pairing attempt fail: BlueZ still holds keys for
            // a device libpebble3 now treats as new, and the watch rejects the connection. Give
            // forget()'s disconnect a moment first, since removing the device also forces one.
            scope.launch(Dispatchers.IO) {
                delay(FORGET_BOND_SETTLE)
                BluezManager.removeBond(address)
            }
        }

        private fun findWatch(caller: String, address: String): PebbleDevice? {
            val device = libPebble.watches.value.firstOrNull {
                it.identifier.asString.equals(address, ignoreCase = true)
            }
            if (device == null) logger.w { "$caller: $address not found" }
            return device
        }
    }

    private val manager = ManagerObject()

    fun start() {
        scope.launch {
            while (connection == null) {
                try {
                    connectAndExport()
                } catch (e: Exception) {
                    logger.w { "org.rockwork unavailable (${e.message}); retrying in 30s" }
                    delay(30.seconds)
                }
            }
        }
    }

    private suspend fun connectAndExport() = withContext(Dispatchers.IO) {
        val conn = DBusConnectionBuilder.forSessionBus().build()
        try {
            // Fails while rockpoold still owns the name: intentional, rockpoold keeps priority.
            conn.requestBusName(BUS_NAME)
        } catch (e: Exception) {
            conn.disconnect()
            throw e
        }
        conn.exportObject(MANAGER_PATH, manager)
        connection = conn
        logger.i { "org.rockwork exported on the session bus" }
        watchWatches()
        watchLocker()
        watchScanning()
    }

    private fun watchScanning() {
        scope.launch {
            libPebble.isScanningBle.collect { isScanning ->
                if (scanning != isScanning) {
                    scanning = isScanning
                    emitSignal(RockworkManager.ScanningChanged(MANAGER_PATH, isScanning))
                }
            }
        }
    }

    private fun watchWatches() {
        scope.launch {
            libPebble.watches.collect { devices ->
                updateScanResults(devices)
                val known = devices.filterIsInstance<KnownPebbleDevice>()
                    .associateBy { it.identifier.asString.uppercase() }
                var listChanged = false
                synchronized(exported) {
                    (exported.keys - known.keys).toList().forEach { address ->
                        exported.remove(address)?.let { watch ->
                            runCatching { connection?.unExportObject(watch.path) }
                            listChanged = true
                        }
                    }
                    known.forEach { (address, device) ->
                        val existing = exported[address]
                        // CommonConnectedDevice includes recovery-mode watches, which the UI
                        // must see as connected (recovery/firmware UI lives there).
                        val isConnected = device is CommonConnectedDevice
                        val state = connectionStateOf(device)
                        if (existing == null) {
                            val path = "$PATH_PREFIX${address.replace(":", "_")}"
                            val obj = RockworkPebbleObject(
                                address = address,
                                path = path,
                                libPebble = libPebble,
                                settings = settings,
                                scope = scope,
                                emit = ::emitSignal,
                            )
                            runCatching { connection?.exportObject(path, obj) }
                                .onFailure { logger.e("export $path failed", it) }
                            exported[address] = ExportedWatch(path, obj, isConnected, state)
                            listChanged = true
                            if (isConnected) emitSignal(RockworkPebble.Connected(path))
                            if (state != RockworkConnectionState.DISCONNECTED) {
                                emitSignal(RockworkPebble.ConnectionStateChanged(path, state))
                            }
                        } else {
                            if (existing.connected != isConnected) {
                                existing.connected = isConnected
                                emitSignal(
                                    if (isConnected) RockworkPebble.Connected(existing.path)
                                    else RockworkPebble.Disconnected(existing.path)
                                )
                                applyProfile(address, isConnected)
                            }
                            // Emit the finer-grained state too (connecting/negotiating/failed),
                            // which the connected/disconnected pair can't express — this is what
                            // lets PairWatchPage show progress and stop spinning on a failed pair.
                            if (existing.connectionState != state) {
                                existing.connectionState = state
                                emitSignal(RockworkPebble.ConnectionStateChanged(existing.path, state))
                            }
                        }
                        exported[address]?.let { watch ->
                            val common = device as? CommonConnectedDevice
                            val upgrading = (common?.firmwareUpdateState
                                is FirmwareUpdater.FirmwareUpdateStatus.Active)
                            if (watch.upgradingFirmware != upgrading) {
                                watch.upgradingFirmware = upgrading
                                emitSignal(RockworkPebble.UpgradingFirmwareChanged(watch.path))
                            }
                            val available = (common?.firmwareUpdateAvailable?.result
                                is FirmwareUpdateCheckResult.FoundUpdate)
                            if (watch.upgradeAvailable != available) {
                                watch.upgradeAvailable = available
                                emitSignal(
                                    RockworkPebble.FirmwareUpgradeAvailableChanged(watch.path)
                                )
                            }
                        }
                    }
                }
                if (listChanged) emitSignal(RockworkManager.PebblesChanged(MANAGER_PATH))
            }
        }
    }

    private fun updateScanResults(devices: List<io.rebble.libpebblecommon.connection.PebbleDevice>) {
        // Watches we already know (paired via the UI or currently connected) or that BlueZ still
        // has bonded shouldn't reappear as "new" watches to pair. Dual-mode watches like
        // obelix/getafix misreport BR/EDR and linger as bonded BlueZ objects, so without this they
        // show up on every scan despite being set up already; the bonded set covers those and any
        // other already-paired watch.
        val hidden = devices.filterIsInstance<KnownPebbleDevice>()
            .mapTo(HashSet()) { it.identifier.asString.uppercase() }
            .apply { addAll(bondedAddresses) }
        // Every advertisement yields a fresh device instance; dedupe by address (last wins,
        // which carries the freshest rssi).
        val discovered = devices.filterIsInstance<DiscoveredPebbleDevice>()
            .filter { it.identifier.asString.uppercase() !in hidden }
            .associateBy { it.identifier.asString.uppercase() }
            .values.map { device ->
                scanResultVariant(
                    name = device.displayName(),
                    address = device.identifier.asString,
                    rssi = (device as? BleDiscoveredPebbleDevice)?.rssi ?: 0,
                )
            }
        if (discovered != scanResults) {
            scanResults = discovered
            emitSignal(RockworkManager.ScanResultsChanged(MANAGER_PATH))
        }
    }

    private fun watchLocker() {
        scope.launch {
            libPebble.getAllLockerUuids().distinctUntilChanged().drop(1).collect {
                synchronized(exported) { exported.values.map { it.path } }
                    .forEach { emitSignal(RockworkPebble.InstalledAppsChanged(it)) }
            }
        }
    }

    /** rockpool's "profile when connected/disconnected" via com.nokia.profiled. */
    private fun applyProfile(address: String, connected: Boolean) {
        val kind = if (connected) "connected" else "disconnected"
        val profile = settings.get("${address.replace(":", "_")}.profile.$kind")
        if (profile.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            RawSessionConnection.connect()?.use { conn ->
                runCatching {
                    conn.call(
                        "com.nokia.profiled", "/com/nokia/profiled", "com.nokia.profiled",
                        "set_profile", "s", profile,
                    )
                }.onFailure { logger.w { "set_profile failed: ${it.message}" } }
            }
        }
    }

    private fun emitSignal(signal: DBusSignal) {
        runCatching { connection?.sendMessage(signal) }
            .onFailure { logger.w { "signal emit failed: ${it.message}" } }
    }

    companion object {
        private const val BUS_NAME = "org.rockwork"
        private const val MANAGER_PATH = "/org/rockwork/Manager"
        private const val PATH_PREFIX = "/org/rockwork/"
        private const val VERSION = "2.0.0-libpebble3d"
        private val FORGET_BOND_SETTLE = 2.seconds

        private fun scanResultVariant(name: String, address: String, rssi: Int): Variant<*> =
            Variant(
                mapOf(
                    "name" to Variant(name),
                    "address" to Variant(address),
                    "rssi" to Variant(rssi),
                ),
                "a{sv}",
            )
    }
}
