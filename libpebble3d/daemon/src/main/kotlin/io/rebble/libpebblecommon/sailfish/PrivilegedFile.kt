package io.rebble.libpebblecommon.sailfish

import java.io.File
import java.io.FileInputStream

/**
 * Whether this file can actually be opened for reading.
 *
 * Deliberately not [File.canRead], which is access(2) — and access(2) answers for the process's
 * *real* uid/gid, by design, so that setuid programs can ask "could the invoking user do this?".
 * That is the opposite of what we want here.
 *
 * Sailfish keeps the calendar and contacts databases under `.../system/privileged/`, a directory
 * with no permissions for "other" that is only reachable through the `privileged` group.
 * libpebble3d gets in via its setgid bit (see the rpm spec), and setgid raises only the *effective*
 * gid — `defaultuser` itself is not in the group. So access(2) resolves the path as the real gid,
 * is denied at `privileged/`, and reports false for databases that open(2) opens without complaint.
 *
 * Opening is the only check that asks the same question the subsequent read will.
 */
internal fun File.isReadableWithEffectiveIds(): Boolean = try {
    FileInputStream(this).close()
    true
} catch (e: Exception) {
    false
}
