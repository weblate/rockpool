package io.rebble.libpebblecommon.rockwork

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.ConnectedPebbleDeviceInRecovery
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater
import io.rebble.libpebblecommon.connection.KnownPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.PebbleDevice
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.js.PKJSApp
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.locker.LockerWrapper
import io.rebble.libpebblecommon.linux.web.RebbleAppstore
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.timeline.TimelineColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt16
import org.freedesktop.dbus.types.Variant
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.uuid.Uuid

/**
 * One exported /org/rockwork/<ADDRESS> object per known watch. Backed by the LibPebble facade;
 * settings rockpool persisted itself (weather keys, health params, profiles...) go to
 * [RockworkSettings] so the UI round-trips even where no libpebble3 feature exists yet.
 */
internal class RockworkPebbleObject(
    private val address: String,
    private val path: String,
    private val libPebble: LibPebble,
    private val settings: RockworkSettings,
    private val scope: CoroutineScope,
    private val emit: (DBusSignal) -> Unit,
) : RockworkPebble {
    private val logger = Logger.withTag("RockworkPebble")
    private val keyPrefix = address.replace(":", "_")

    override fun getObjectPath(): String = path
    override fun isRemote(): Boolean = false

    private fun device(): PebbleDevice? = libPebble.watches.value.firstOrNull {
        it.identifier.asString.equals(address, ignoreCase = true)
    }

    private fun known(): KnownPebbleDevice? = device() as? KnownPebbleDevice

    // A recovery-mode watch is CommonConnectedDevice but NOT ConnectedPebbleDevice; it still
    // has watchInfo/battery/firmware/logs. connected() is only for what genuinely needs full
    // firmware (screenshots, language packs, dev connection).
    private fun commonConnected(): CommonConnectedDevice? = device() as? CommonConnectedDevice
    private fun connected(): ConnectedPebbleDevice? = device() as? ConnectedPebbleDevice

    private fun key(name: String) = "$keyPrefix.$name"

    // The UI passes Qt.resolvedUrl() results ("file:///home/..."); rockworkd stripped the
    // scheme, so mirror that (URI also percent-decodes).
    private fun localPath(fileOrUrl: String): String = runCatching {
        if (fileOrUrl.startsWith("file:")) java.net.URI(fileOrUrl).path else fileOrUrl
    }.getOrDefault(fileOrUrl)

    // ---- Identity / hardware ----
    override fun Address(): String = address
    override fun Name(): String = device()?.displayName() ?: address
    override fun SerialNumber(): String = known()?.serial ?: ""
    override fun PlatformString(): String =
        known()?.watchType?.watchType?.codename ?: "unknown"

    override fun HardwarePlatform(): String = known()?.watchType?.revision ?: "unknown"
    override fun SoftwareVersion(): String = known()?.runningFwVersion ?: ""
    override fun LanguageVersion(): String = commonConnected()?.watchInfo?.language ?: ""
    override fun Model(): Int = commonConnected()?.watchInfo?.color?.protocolNumber ?: 0
    override fun IsConnected(): Boolean = commonConnected() != null
    override fun ConnectionState(): Int = connectionStateOf(device())
    override fun LastError(): String = device()?.connectionFailureInfo?.reason?.name ?: ""
    override fun Recovery(): Boolean = device() is ConnectedPebbleDeviceInRecovery

    // ---- Firmware / language packs ----
    private fun foundUpdate(): FirmwareUpdateCheckResult.FoundUpdate? =
        commonConnected()?.firmwareUpdateAvailable?.result
            as? FirmwareUpdateCheckResult.FoundUpdate

    override fun FirmwareUpgradeAvailable(): Boolean = foundUpdate() != null
    override fun CandidateFirmwareVersion(): String =
        foundUpdate()?.version?.stringVersion ?: ""

    override fun FirmwareReleaseNotes(): String = foundUpdate()?.notes ?: ""
    override fun PerformFirmwareUpgrade() {
        val update = foundUpdate() ?: return
        commonConnected()?.updateFirmware(update)
        emit(RockworkPebble.UpgradingFirmwareChanged(path))
    }

    override fun UpgradingFirmware(): Boolean =
        commonConnected()?.firmwareUpdateState is FirmwareUpdater.FirmwareUpdateStatus.Active
    override fun LoadLanguagePack(pblFile: String) {
        val local = localPath(pblFile)
        connected()?.installLanguagePack(Path(local), File(local).nameWithoutExtension)
    }

    // ---- Account / cloud sync (no Rebble web services wired up yet) ----
    override fun accountName(): String = settings.get("account.name")
    override fun accountEmail(): String = settings.get("account.email")
    override fun oauthToken(): String = settings.get("account.oauthToken")
    override fun setOAuthToken(token: String) {
        settings.set("account.oauthToken", token)
        emit(RockworkPebble.oauthTokenChanged(path, token))
        // A fresh token can unlock the firmware update check (cohorts needs auth); force it so a
        // previous auth-less "no update" result doesn't suppress the re-check.
        commonConnected()?.checkforFirmwareUpdate(force = true)
    }

    override fun syncAppsFromCloud(): Boolean = settings.getBool("account.syncAppsFromCloud")
    override fun setSyncAppsFromCloud(enable: Boolean) {
        settings.set("account.syncAppsFromCloud", enable)
        if (enable) libPebble.requestLockerSync()
    }

    override fun resetTimeline() {
        logger.i { "resetTimeline: not supported" }
    }

    // ---- Timeline window (persisted only; libpebble3 manages its own window) ----
    override fun setTimelineWindow(start: Int, fade: Int, end: Int) {
        settings.set(key("timeline.start"), start)
        settings.set(key("timeline.fade"), fade)
        settings.set(key("timeline.end"), end)
    }

    override fun timelineWindowStart(): Int = settings.getInt(key("timeline.start"), -2)
    override fun timelineWindowFade(): Int = settings.getInt(key("timeline.fade"), 5)
    override fun timelineWindowEnd(): Int = settings.getInt(key("timeline.end"), 7)
    override fun insertTimelinePin(jsonPin: String) {
        logger.i { "insertTimelinePin: not supported" }
    }

    // ---- Notification filter ----
    override fun NotificationsFilter(): Map<String, Variant<*>> = runBlocking {
        libPebble.notificationApps().first().associate { appWithCount ->
            val app = appWithCount.app
            val enabled = if (app.muteState == MuteState.Always) 0 else 2
            // Explicit a{sv} signature: dbus-java can't infer the D-Bus type of a raw Map
            // wrapped in a Variant ("Can't wrap LinkedHashMap in an unqualified Variant").
            app.packageName to Variant(
                mapOf(
                    "name" to Variant(app.name),
                    "icon" to Variant(""),
                    "enabled" to Variant(enabled),
                    // Current per-app overrides so the UI can show/edit them (empty = using the
                    // resolved default). A TimelineIcon.code and a TimelineColor.name respectively.
                    "iconCode" to Variant(app.iconCode ?: ""),
                    "colorName" to Variant(app.colorName ?: ""),
                ),
                "a{sv}",
            )
        }
    }

    override fun SetNotificationFilter(sourceId: String, enabled: Int) {
        val muteState = if (enabled == 0) MuteState.Always else MuteState.Never
        libPebble.updateNotificationAppMuteState(sourceId, muteState)
        emit(RockworkPebble.NotificationFilterChanged(path, sourceId, sourceId, "", enabled))
    }

    // Per-app notification appearance overrides. updateNotificationAppState replaces vibe/colour/
    // icon together, so read the current entry and change only the one field. Empty clears the
    // override (falls back to the resolved default). No signal — this isn't a mute-filter change;
    // the UI re-reads NotificationsFilter to pick up the new value.
    override fun SetNotificationAppColor(sourceId: String, colorName: String) {
        scope.launch {
            val app = notificationAppItem(sourceId) ?: return@launch
            libPebble.updateNotificationAppState(
                sourceId, app.vibePatternName, colorName.ifEmpty { null }, app.iconCode,
            )
        }
    }

    override fun SetNotificationAppIcon(sourceId: String, iconCode: String) {
        scope.launch {
            val app = notificationAppItem(sourceId) ?: return@launch
            libPebble.updateNotificationAppState(
                sourceId, app.vibePatternName, app.colorName, iconCode.ifEmpty { null },
            )
        }
    }

    private suspend fun notificationAppItem(packageName: String) =
        libPebble.notificationApps().first().find { it.app.packageName == packageName }?.app

    // rgb as "#RRGGBB" so QML can use it as a colour directly; name is the value the setters take.
    override fun TimelineColors(): List<Variant<*>> = TimelineColor.entries.map { c ->
        Variant(
            mapOf(
                "name" to Variant(c.name),
                "displayName" to Variant(c.displayName),
                "rgb" to Variant("#%06X".format(c.color and 0xFFFFFF)),
            ),
            "a{sv}",
        )
    }

    override fun TimelineIcons(): List<Variant<*>> = TimelineIcon.entries.map { i ->
        Variant(
            mapOf(
                "code" to Variant(i.code),
                "name" to Variant(i.name),
            ),
            "a{sv}",
        )
    }

    override fun ForgetNotificationFilter(sourceId: String) {
        logger.d { "ForgetNotificationFilter: not supported" }
    }

    // ---- Canned responses / favorite contacts (persisted for the UI round-trip) ----
    override fun cannedResponses(): Map<String, Variant<*>> = storedList("canned")
    override fun setCannedResponses(cans: Map<String, Variant<*>>) = storeList("canned", cans)
    override fun getCannedResponses(groups: List<String>): Map<String, Variant<*>> =
        storedList("canned").filterKeys { groups.isEmpty() || it in groups }

    override fun setFavoriteContacts(cans: Map<String, Variant<*>>) = storeList("contacts", cans)
    override fun getFavoriteContacts(names: List<String>): Map<String, Variant<*>> =
        storedList("contacts").filterKeys { names.isEmpty() || it in names }

    private fun storeList(kind: String, values: Map<String, Variant<*>>) {
        val keys = values.keys.joinToString(SEP)
        settings.set("$kind.keys", keys)
        values.forEach { (name, variant) ->
            val list = (variant.value as? Collection<*>)
                ?.joinToString(SEP) { (it as? Variant<*>)?.value?.toString() ?: it.toString() }
                ?: variant.value?.toString().orEmpty()
            settings.set("$kind.$name", list)
        }
    }

    private fun storedList(kind: String): Map<String, Variant<*>> =
        settings.get("$kind.keys").split(SEP).filter { it.isNotEmpty() }.associateWith { name ->
            // Explicit "as" signature: a raw List can't be wrapped in an unqualified Variant.
            Variant(settings.get("$kind.$name").split(SEP).filter { it.isNotEmpty() }, "as")
        }

    // ---- Voice ----
    override fun voiceSessionResult(dumpFile: String, sentences: List<Variant<*>>) {
        logger.d { "voiceSessionResult: not supported" }
    }

    // ---- Developer connection / logging ----
    override fun DevConnectionEnabled(): Boolean = settings.getBool(key("devconn.enabled"))
    override fun DevConnListenPort(): UInt16 = UInt16(9000)
    override fun DevConnectionState(): Boolean =
        connected()?.devConnectionActive?.value ?: false

    override fun DevConnCloudEnabled(): Boolean = false
    override fun DevConnCloudState(): Boolean = false
    override fun SetDevConnEnabled(enabled: Boolean) {
        settings.set(key("devconn.enabled"), enabled)
        scope.launch {
            try {
                if (enabled) connected()?.startDevConnection() else connected()?.stopDevConnection()
                emit(RockworkPebble.DevConnectionChanged(path, enabled))
            } catch (e: Exception) {
                logger.e("dev connection toggle failed", e)
            }
        }
    }

    override fun SetDevConnCloudEnabled(enabled: Boolean) {
        logger.d { "SetDevConnCloudEnabled: not supported" }
    }

    override fun SetDevConnListenPort(port: UInt16) {
        logger.d { "SetDevConnListenPort: not supported" }
    }

    override fun startLogDump(): String = ""
    override fun stopLogDump(): String = ""
    override fun getLogDump(): String = ""
    override fun isLogDumping(): Boolean = false
    override fun setLogLevel(level: Int) {
        settings.set("logLevel", level)
        Logger.setMinSeverity(
            when {
                level <= 0 -> Severity.Error
                level == 1 -> Severity.Warn
                level == 2 -> Severity.Info
                level == 3 -> Severity.Debug
                else -> Severity.Verbose
            }
        )
    }

    override fun getLogLevel(): Int = settings.getInt("logLevel", 3)
    override fun DumpLogs(fileName: String) {
        scope.launch {
            val ok = try {
                val logs = commonConnected()?.gatherLogs()
                if (logs != null) {
                    File(logs.toString()).copyTo(File(fileName), overwrite = true)
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                logger.e("DumpLogs failed", e)
                false
            }
            emit(RockworkPebble.LogsDumped(path, ok))
        }
    }

    // ---- Apps / watchfaces ----
    private fun locker(): List<LockerWrapper> = runBlocking {
        val faces = libPebble.getLocker(AppType.Watchface, null, LOCKER_LIMIT).first()
        val apps = libPebble.getLocker(AppType.Watchapp, null, LOCKER_LIMIT).first()
        (faces + apps).sortedBy { it.properties.order }
    }

    override fun InstallApp(id: String) {
        scope.launch {
            val ok = try {
                val app = RebbleAppstore.downloadPbw(id)
                val installed = app != null && libPebble.sideloadApp(app.path)
                val uuid = app?.uuid
                if (installed && uuid != null) {
                    // Also add it to the Rebble locker so it becomes a real locker member with a
                    // timeline user_token, then refresh so getTimelineToken sees it. Best-effort:
                    // the sandbox-token path still covers apps that don't make it into the locker.
                    val token = settings.get("account.oauthToken").ifEmpty { null }
                    if (RebbleAppstore.addToLocker(uuid, token)) {
                        libPebble.requestLockerSync()
                    }
                }
                installed
            } catch (e: Exception) {
                logger.e("InstallApp failed", e)
                false
            }
            logger.i { "InstallApp($id): $ok" }
            emit(RockworkPebble.InstalledAppsChanged(path))
        }
    }

    override fun SideloadApp(packageFile: String) {
        scope.launch {
            val ok = try {
                libPebble.sideloadApp(Path(localPath(packageFile)))
            } catch (e: Exception) {
                logger.e("sideload failed", e)
                false
            }
            logger.i { "SideloadApp($packageFile): $ok" }
            emit(RockworkPebble.InstalledAppsChanged(path))
        }
    }

    override fun InstalledAppIds(): List<String> = locker().map { it.properties.id.toString() }

    // 'av' with variant-wrapped maps, matching rockworkd's QVariantList (the UI's
    // parser depends on that wire shape — plain aa{sv} demarshals differently in Qt).
    override fun InstalledApps(): List<Variant<*>> = locker().map { app ->
        val props = app.properties
        Variant(
            mapOf(
                "uuid" to Variant(props.id.toString()),
                "storeId" to Variant(props.storeId ?: ""),
                "name" to Variant(props.title),
                "vendor" to Variant(props.developerName),
                "watchface" to Variant(props.type == AppType.Watchface),
                "version" to Variant(props.version ?: ""),
                "hasSettings" to Variant((app as? LockerWrapper.NormalApp)?.configurable ?: false),
                "icon" to Variant(props.platforms.firstNotNullOfOrNull { it.iconImageUrl } ?: ""),
                "systemApp" to Variant(app is LockerWrapper.SystemApp),
            ),
            "a{sv}",
        )
    }

    override fun RemoveApp(id: String) {
        scope.launch {
            val uuid = runCatching { Uuid.parse(id) }.getOrNull()
                ?: locker().firstOrNull { it.properties.storeId == id }?.properties?.id
            if (uuid == null) {
                logger.w { "RemoveApp: unknown id $id" }
                return@launch
            }
            libPebble.removeApp(uuid)
            emit(RockworkPebble.InstalledAppsChanged(path))
        }
    }

    // A PKJS session exists while its app runs on the watch. The UI's normalised UUID has
    // braces + lowercase; libpebble3's is bare — compare on the bare hex.
    @Suppress("DEPRECATION")
    private fun pkjsSession(uuid: String): PKJSApp? {
        val wanted = uuid.trim('{', '}').lowercase()
        return connected()?.currentPKJSSession?.value
            ?.takeIf { it.uuid.toString().lowercase() == wanted }
    }

    // The UI ignores this method's return value — it opens the page from the OpenURL signal.
    // Launch the app if its JS isn't already running (config needs a live PKJS session), get
    // the URL the app hands back, and emit it. Non-blocking so the D-Bus thread stays free.
    override fun ConfigurationURL(uuid: String): String {
        scope.launch {
            var session = pkjsSession(uuid)
            if (session == null) {
                runCatching { libPebble.launchApp(Uuid.parse(uuid.trim('{', '}'))) }
                session = withTimeoutOrNull(8.seconds) {
                    while (true) {
                        pkjsSession(uuid)?.let { return@withTimeoutOrNull it }
                        delay(250)
                    }
                    @Suppress("UNREACHABLE_CODE") null
                }
            }
            if (session == null) {
                logger.w { "ConfigurationURL($uuid): no PKJS session (app has no JS?)" }
                return@launch
            }
            val url = withTimeoutOrNull(15.seconds) {
                runCatching { session.requestConfigurationUrl() }.getOrNull()
            }
            if (url.isNullOrEmpty()) {
                logger.w { "ConfigurationURL($uuid): app returned no config URL" }
                return@launch
            }
            emit(RockworkPebble.OpenURL(path, uuid, url))
        }
        return ""
    }

    override fun ConfigurationClosed(uuid: String, result: String) {
        pkjsSession(uuid)?.triggerOnWebviewClosed(result)
            ?: logger.w { "ConfigurationClosed($uuid): no running PKJS session" }
    }

    override fun SetAppOrder(newList: List<String>) {
        scope.launch {
            newList.forEachIndexed { index, id ->
                runCatching { Uuid.parse(id) }.getOrNull()?.let {
                    libPebble.setAppOrder(it, index)
                }
            }
        }
    }

    override fun SendAppData(uuid: String, data: Map<String, Variant<*>>) {
        logger.w { "SendAppData($uuid): not supported over org.rockwork yet" }
    }

    override fun CloseApp(uuid: String) {
        scope.launch { runCatching { libPebble.stopApp(Uuid.parse(uuid)) } }
    }

    override fun LaunchApp(uuid: String) {
        scope.launch { runCatching { libPebble.launchApp(Uuid.parse(uuid)) } }
    }

    // ---- Screenshots ----
    private fun screenshotDir(): File =
        File(System.getProperty("user.home"), "Pictures/Screenshots/Pebble").apply { mkdirs() }

    override fun RequestScreenshot() {
        scope.launch {
            try {
                val bitmap = connected()?.takeScreenshot() ?: run {
                    logger.w { "RequestScreenshot: not connected or unsupported" }
                    return@launch
                }
                val w = bitmap.width
                val h = bitmap.height
                if (w <= 0 || h <= 0) {
                    logger.e { "screenshot has invalid dimensions ${w}x$h" }
                    return@launch
                }
                val pixels = IntArray(w * h)
                bitmap.readPixels(pixels)
                val png = encodePngRgba(w, h, pixels)
                val file = File(screenshotDir(), "pebble-${System.currentTimeMillis()}.png")
                file.writeBytes(png)
                emit(RockworkPebble.ScreenshotAdded(path, file.absolutePath))
            } catch (e: Exception) {
                logger.e("screenshot failed", e)
            }
        }
    }

    override fun Screenshots(): List<String> =
        screenshotDir().listFiles { f -> f.extension == "png" }
            ?.sortedBy { it.name }?.map { it.absolutePath } ?: emptyList()

    override fun RemoveScreenshot(filename: String) {
        val file = File(filename)
        if (file.parentFile?.absolutePath == screenshotDir().absolutePath && file.delete()) {
            emit(RockworkPebble.ScreenshotRemoved(path, filename))
        }
    }

    // ---- Weather (persisted only; watch-side weather is a later milestone) ----
    override fun setWeatherApiKey(key: String) = settings.set("weather.apiKey", key)
    override fun WeatherUnits(): String = settings.get("weather.units", "m")
    override fun setWeatherUnits(units: String) = settings.set("weather.units", units)
    override fun WeatherLanguage(): String = settings.get("weather.language", "en")
    override fun setWeatherLanguage(lang: String) = settings.set("weather.language", lang)
    override fun WeatherAltKey(): String = settings.get("weather.altKey")
    override fun setWeatherAltKey(key: String) = settings.set("weather.altKey", key)
    override fun WeatherLocations(): List<Variant<*>> = emptyList()
    override fun SetWeatherLocations(locations: List<Variant<*>>) {
        logger.w { "SetWeatherLocations: weather sync not implemented yet" }
    }

    override fun InjectWeatherData(locationName: String, conditions: Map<String, Variant<*>>) {
        logger.w { "InjectWeatherData: weather sync not implemented yet" }
    }

    // ---- Health / units / profiles / calendar ----
    override fun HealthParams(): Map<String, Variant<*>> = mapOf(
        "enabled" to Variant(settings.getBool("health.enabled")),
        "age" to Variant(settings.getInt("health.age", 30)),
        "gender" to Variant(settings.getInt("health.gender", 0)),
        "height" to Variant(settings.getInt("health.height", 170)),
        "weight" to Variant(settings.getInt("health.weight", 70)),
        "moreActive" to Variant(settings.getBool("health.moreActive")),
        "sleepMore" to Variant(settings.getBool("health.sleepMore")),
    )

    override fun SetHealthParams(params: Map<String, Variant<*>>) {
        params.forEach { (k, v) -> settings.set("health.$k", v.value?.toString().orEmpty()) }
        emit(RockworkPebble.HealthParamsChanged(path))
    }

    override fun ImperialUnits(): Boolean = settings.getBool("imperialUnits")
    override fun SetImperialUnits(imperial: Boolean) {
        settings.set("imperialUnits", imperial)
        emit(RockworkPebble.ImperialUnitsChanged(path))
    }

    override fun ProfileWhenConnected(): String = settings.get(key("profile.connected"))
    override fun SetProfileWhenConnected(profile: String) {
        settings.set(key("profile.connected"), profile)
        emit(RockworkPebble.ProfileWhenConnectedChanged(path))
    }

    override fun ProfileWhenDisconnected(): String = settings.get(key("profile.disconnected"))
    override fun SetProfileWhenDisconnected(profile: String) {
        settings.set(key("profile.disconnected"), profile)
        emit(RockworkPebble.ProfileWhenDisconnectedChanged(path))
    }

    override fun CalendarSyncEnabled(): Boolean =
        libPebble.config.value.watchConfig.calendarPins

    override fun SetCalendarSyncEnabled(enabled: Boolean) {
        val config = libPebble.config.value
        libPebble.updateConfig(
            config.copy(watchConfig = config.watchConfig.copy(calendarPins = enabled))
        )
        emit(RockworkPebble.CalendarSyncEnabledChanged(path))
    }

    companion object {
        private const val LOCKER_LIMIT = 500

        /** Unit separator for persisted string lists. */
        private const val SEP = "\u001f"
    }
}

/**
 * Encode ARGB_8888 pixels to a PNG (8-bit RGBA, no interlace) using only java.util.zip — no skiko,
 * no AWT, so it survives in the GraalVM native image where both of those native stacks are absent.
 */
private fun encodePngRgba(width: Int, height: Int, argb: IntArray): ByteArray {
    // Raw scanlines: each row is [filter=0] followed by width * (R,G,B,A).
    val raw = ByteArray(height * (1 + width * 4))
    var p = 0
    for (y in 0 until height) {
        raw[p++] = 0 // filter type: None
        val rowStart = y * width
        for (x in 0 until width) {
            val c = argb[rowStart + x]
            raw[p++] = ((c shr 16) and 0xFF).toByte() // R
            raw[p++] = ((c shr 8) and 0xFF).toByte()  // G
            raw[p++] = (c and 0xFF).toByte()          // B
            raw[p++] = ((c ushr 24) and 0xFF).toByte() // A
        }
    }

    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) // PNG signature

    val ihdr = ByteArrayOutputStream().apply {
        writeIntBE(width)
        writeIntBE(height)
        write(8) // bit depth
        write(6) // colour type: RGBA
        write(0) // compression
        write(0) // filter
        write(0) // interlace
    }.toByteArray()
    out.writeChunk("IHDR", ihdr)

    val deflater = Deflater(Deflater.BEST_COMPRESSION).apply {
        setInput(raw)
        finish()
    }
    val idat = ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (!deflater.finished()) {
        val n = deflater.deflate(buf)
        idat.write(buf, 0, n)
    }
    deflater.end()
    out.writeChunk("IDAT", idat.toByteArray())

    out.writeChunk("IEND", ByteArray(0))
    return out.toByteArray()
}

private fun ByteArrayOutputStream.writeIntBE(v: Int) {
    write((v ushr 24) and 0xFF)
    write((v ushr 16) and 0xFF)
    write((v ushr 8) and 0xFF)
    write(v and 0xFF)
}

private fun ByteArrayOutputStream.writeChunk(type: String, data: ByteArray) {
    writeIntBE(data.size)
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    write(typeBytes)
    write(data)
    val crc = CRC32().apply {
        update(typeBytes)
        update(data)
    }
    writeIntBE(crc.value.toInt())
}
