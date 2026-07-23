package io.rebble.libpebblecommon.sailfish.calendar

import co.touchlab.kermit.Logger
import java.io.File

internal data class NemoCalendarConfig(
    val excluded: Set<String>,
    val colorOverrides: Map<String, Int>,
) {
    companion object {
        val EMPTY = NemoCalendarConfig(emptySet(), emptyMap())
    }
}

/**
 * Per-notebook UI settings written by jolla-calendar (QSettings INI):
 * exclude/<uid>=true hides a notebook, colors/<uid>=#rrggbb overrides its color.
 */
internal object NemoCalendarSettings {
    private val logger = Logger.withTag("NemoCalendarSettings")

    fun defaultFile(): File = File(
        System.getProperty("user.home") ?: "/home/defaultuser",
        ".config/nemo/nemo-qml-plugin-calendar.conf",
    )

    fun load(file: File = defaultFile()): NemoCalendarConfig = try {
        if (file.isFile && file.canRead()) parse(file.readLines()) else NemoCalendarConfig.EMPTY
    } catch (e: Exception) {
        logger.d(e) { "failed to read ${file.path}" }
        NemoCalendarConfig.EMPTY
    }

    fun parse(lines: List<String>): NemoCalendarConfig {
        val excluded = mutableSetOf<String>()
        val colors = mutableMapOf<String, Int>()
        var section = ""
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim()
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val fullKey = buildString {
                if (section.isNotEmpty()) append(section).append('/')
                append(line.substring(0, eq).trim())
            }
            val value = line.substring(eq + 1).trim().removeSurrounding("\"")
            val slash = fullKey.indexOf('/')
            if (slash <= 0 || slash == fullKey.length - 1) continue
            val uid = decodeQSettingsKey(fullKey.substring(slash + 1))
            when (fullKey.substring(0, slash)) {
                "exclude" -> if (value.equals("true", ignoreCase = true)) excluded.add(uid)
                "colors" -> parseCalendarColor(value)?.let { colors[uid] = it }
            }
        }
        return NemoCalendarConfig(excluded, colors)
    }

    // QSettings %-encodes characters outside [A-Za-z0-9_.-] in ini keys.
    private fun decodeQSettingsKey(key: String): String {
        if ('%' !in key) return key
        val sb = StringBuilder(key.length)
        var i = 0
        while (i < key.length) {
            val ch = key[i]
            if (ch == '%' && i + 2 < key.length) {
                val code = key.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) {
                    sb.append(code.toChar())
                    i += 3
                    continue
                }
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
    }
}

/** "#rrggbb" / "#aarrggbb" -> ARGB int as CalendarEntity.color expects. */
internal fun parseCalendarColor(value: String?): Int? {
    val hex = value?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() } ?: return null
    return try {
        when (hex.length) {
            6 -> (0xFF000000L or hex.toLong(16)).toInt()
            8 -> hex.toLong(16).toInt()
            else -> null
        }
    } catch (e: NumberFormatException) {
        null
    }
}
