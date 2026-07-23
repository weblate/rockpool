package io.rebble.libpebblecommon.sailfish.location

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface

/**
 * Sailfish ships geoclue 0.12 with two providers (Hybris GPS, Mlsdb network). We talk to them
 * DIRECTLY rather than through org.freedesktop.Geoclue.Master: the Master gates providers on
 * ConnMan connectivity and returns "no usable Position providers" even when the providers
 * themselves hold a valid fix. AddReference activates a provider and keeps it pushing updates.
 */
@DBusInterfaceName("org.freedesktop.Geoclue")
internal interface GeoclueProvider : DBusInterface {
    fun AddReference()
    fun RemoveReference()
}

/**
 * A provider's Position interface. GetPosition's reply and the PositionChanged signal are both
 * iiddd(idd) (fields, timestamp, lat, lon, alt, (level, hAcc, vAcc)); we read the raw reply/signal
 * params via getParameters() to sidestep demarshalling the (idd) accuracy struct — a typed
 * DBusSignal subclass can't round-trip it (dbus-java rejects the re-serialized Object[]). The
 * declared return type here only serves to resolve the method by name.
 */
@DBusInterfaceName("org.freedesktop.Geoclue.Position")
internal interface GeocluePosition : DBusInterface {
    fun GetPosition(): Any
}

internal object GeoclueConstants {
    const val POSITION_INTERFACE = "org.freedesktop.Geoclue.Position"
    const val POSITION_CHANGED = "PositionChanged"

    // GPS first (Detailed accuracy, has altitude), then network (works indoors). Ordering only
    // breaks ties — the freshest fix by timestamp wins.
    val PROVIDERS = listOf(
        "org.freedesktop.Geoclue.Providers.Hybris" to "/org/freedesktop/Geoclue/Providers/Hybris",
        "org.freedesktop.Geoclue.Providers.Mlsdb" to "/org/freedesktop/Geoclue/Providers/Mlsdb",
    )
    // Network provider is cheap to keep referenced; GPS is not, so we only poll it opportunistically.
    const val NETWORK_SERVICE = "org.freedesktop.Geoclue.Providers.Mlsdb"
    const val NETWORK_PATH = "/org/freedesktop/Geoclue/Providers/Mlsdb"

    // PositionChanged fields bitmask
    const val FIELD_LATITUDE = 1 shl 0
    const val FIELD_LONGITUDE = 1 shl 1
    const val FIELD_ALTITUDE = 1 shl 2

    // How long to block for a GetPosition reply.
    const val GET_POSITION_TIMEOUT_MS = 4000L
}
