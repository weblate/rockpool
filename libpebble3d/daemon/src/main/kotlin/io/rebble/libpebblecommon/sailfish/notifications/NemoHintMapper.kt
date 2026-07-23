package io.rebble.libpebblecommon.sailfish.notifications

import io.rebble.libpebblecommon.linux.notifications.DBusNotification
import io.rebble.libpebblecommon.linux.notifications.MappedNotification
import io.rebble.libpebblecommon.linux.notifications.NotificationHintMapper
import io.rebble.libpebblecommon.linux.notifications.RemoteAction
import io.rebble.libpebblecommon.linux.notifications.themeIconName

/**
 * Maps lipstick's `x-nemo-*` hints onto the watch-facing fields.
 *
 * The important one is the title. Lipstick groups notifications, and a grouped notification's
 * `summary` argument is the *app* ("Messages"), while `x-nemo-preview-summary` — what the banner
 * shows — is the sender ("Alice"). We prefer the preview hint, which lines up with Android putting
 * EXTRA_TITLE (the person) in the title; the app name reaches the watch separately, via the
 * NotificationApp BlobDB entry.
 */
class NemoHintMapper : NotificationHintMapper {
    override fun map(n: DBusNotification): MappedNotification? {
        val title = n.hintString("x-nemo-preview-summary")?.takeIf { it.isNotEmpty() }
            ?: n.summary
        val body = n.hintString("x-nemo-preview-body")?.takeIf { it.isNotEmpty() }
            ?: n.body
        if (title.isEmpty() && body.isEmpty()) return null
        if (n.hintBool("x-nemo-hidden") || n.hintBool("transient")) return null

        // x-nemo-origin-package first: for anything bridged out of AppSupport, x-nemo-owner is the
        // bridge itself ("apkd-bridge") rather than the app, so keying on it would collapse every
        // Android app into one entry sharing a single name and mute state. Native apps send no
        // origin-package and fall back to owner ("commhistoryd").
        val androidPackage = n.hintString("x-nemo-origin-package")
        val packageName = androidPackage
            ?: n.hintString("x-nemo-owner")
            ?: n.appName.ifEmpty { "unknown" }.lowercase().replace(' ', '-')

        return MappedNotification(
            title = title,
            body = body,
            packageName = packageName,
            appName = n.appName.ifEmpty { packageName },
            androidPackageName = androidPackage,
            category = n.hintString("category"),
            iconName = themeIconName(n.appIcon),
            // AppSupport forwards the Android notification's accent colour here; this is the
            // equivalent of Android's Notification.color.
            colorArgb = parseArgb(n.hintString("x-nemo-color")),
            remoteActions = remoteActions(n),
        )
    }

    /** "#ff5865f2" / "#5865f2" -> ARGB int, opaque if no alpha given. */
    private fun parseArgb(value: String?): Int? {
        val hex = value?.removePrefix("#")?.takeIf { it.length == 6 || it.length == 8 } ?: return null
        val rgb = hex.toLongOrNull(16) ?: return null
        return if (hex.length == 8) rgb.toInt() else (rgb or 0xFF000000L).toInt()
    }

    private fun remoteActions(n: DBusNotification): Map<String, RemoteAction> {
        val keys = n.actions.filterIndexed { index, _ -> index % 2 == 0 }
        return keys.mapNotNull { key ->
            RemoteAction.parse(n.hintString("x-nemo-remote-action-$key"))?.let { key to it }
        }.toMap()
    }
}
