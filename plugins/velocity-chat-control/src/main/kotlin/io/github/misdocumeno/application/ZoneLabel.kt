package io.github.misdocumeno.application

import java.time.ZoneOffset

/**
 * ` (UTC±H[:MM])` label used next to every formatted instant, so a tooltip or a mute
 * notice is unambiguous even when the reader sits in another zone.
 */
internal fun zoneLabel(offset: ZoneOffset): String {
    val seconds = offset.totalSeconds
    if (seconds == 0) return "UTC"
    val abs = kotlin.math.abs(seconds)
    val hours = abs / 3600
    val minutes = (abs % 3600) / 60
    val sign = if (seconds < 0) "-" else "+"
    return if (minutes == 0) {
        "UTC$sign$hours"
    } else {
        "UTC$sign$hours:${minutes.toString().padStart(2, '0')}"
    }
}
