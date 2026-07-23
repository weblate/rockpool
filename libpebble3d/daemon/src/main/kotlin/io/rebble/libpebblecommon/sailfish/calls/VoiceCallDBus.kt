package io.rebble.libpebblecommon.sailfish.calls

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal

internal const val VOICECALL_SERVICE = "org.nemomobile.voicecall"
internal const val VOICECALL_MANAGER_PATH = "/"
internal const val VOICECALL_MANAGER_IFACE = "org.nemomobile.voicecall.VoiceCallManager"
internal const val VOICECALL_IFACE = "org.nemomobile.voicecall.VoiceCall"

internal fun voiceCallPath(handlerId: String) = "/calls/$handlerId"

/**
 * Minimal declarations for Sailfish's voicecall-manager D-Bus API (session bus). Class names are
 * lowercase because dbus-java matches signals by the class's simple name, and voicecall-manager
 * exports Qt-style lowercase signal members.
 */
@DBusInterfaceName(VOICECALL_MANAGER_IFACE)
@Suppress("ClassName", "unused")
internal interface VoiceCallManagerIface : DBusInterface {
    class activeVoiceCallChanged(path: String) : DBusSignal(path)
    class voiceCallsChanged(path: String) : DBusSignal(path)
}

@DBusInterfaceName(VOICECALL_IFACE)
@Suppress("ClassName", "unused")
internal interface VoiceCallIface : DBusInterface {
    fun answer()
    fun hangup()

    class statusChanged(path: String, val status: Int, val statusText: String) :
        DBusSignal(path, status, statusText)
}

// voicecallhandler.h VoiceCallStatus values.
internal const val STATUS_ACTIVE = 1
internal const val STATUS_HELD = 2
internal const val STATUS_DIALING = 3
internal const val STATUS_ALERTING = 4
internal const val STATUS_INCOMING = 5
internal const val STATUS_WAITING = 6
