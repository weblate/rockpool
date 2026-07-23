package io.rebble.libpebblecommon.sailfish

import io.rebble.libpebblecommon.calendar.SystemCalendar
import io.rebble.libpebblecommon.calls.LegacyPhoneReceiver
import io.rebble.libpebblecommon.contacts.SystemContacts
import io.rebble.libpebblecommon.linux.music.VolumeControl
import io.rebble.libpebblecommon.linux.notifications.NotificationHintMapper
import io.rebble.libpebblecommon.sailfish.calendar.SailfishSystemCalendar
import io.rebble.libpebblecommon.sailfish.calls.SailfishPhoneReceiver
import io.rebble.libpebblecommon.sailfish.contacts.SailfishSystemContacts
import io.rebble.libpebblecommon.sailfish.location.SailfishSystemGeolocation
import io.rebble.libpebblecommon.sailfish.music.PulseAudioVolume
import io.rebble.libpebblecommon.sailfish.notifications.NemoHintMapper
import io.rebble.libpebblecommon.util.SystemGeolocation
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Sailfish OS bindings, replacing the generic Linux defaults in
 * [io.rebble.libpebblecommon.di.platformModule]. Pass to
 * [io.rebble.libpebblecommon.connection.LibPebble3.create]'s `platformOverrides`.
 *
 * Only what Sailfish actually improves on is bound — notifications keep the generic listener and
 * action handler and swap the hint mapper; music keeps MPRIS and swaps only volume. The rest
 * (calendar, contacts, calls, geolocation) has no portable freedesktop equivalent and is replaced
 * wholesale: mkcal, qtcontacts-sqlite, org.nemomobile.voicecall and geoclue 0.12 respectively.
 */
val sailfishModule: Module = module {
    // Notifications: lipstick's x-nemo-* hints carry the sender and the remote actions.
    singleOf(::NemoHintMapper) bind NotificationHintMapper::class

    // Volume: MPRIS only moves the player; MainVolume2 moves the device.
    singleOf(::PulseAudioVolume) bind VolumeControl::class

    // Read directly from the system's own stores — rockpoold used the Qt libs for these.
    singleOf(::SailfishSystemCalendar) bind SystemCalendar::class
    singleOf(::SailfishSystemContacts) bind SystemContacts::class
    singleOf(::SailfishPhoneReceiver) bind LegacyPhoneReceiver::class
    singleOf(::SailfishSystemGeolocation) bind SystemGeolocation::class
}
