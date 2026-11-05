package com.curato.ui.designsystem

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The kit's `.a-thanks .stamp` — `3 Nov 2026, 10:15`.
 *
 * The backend sends `acceptedAt` as a UTC instant (SPEC.md §4.6) and a customer reads
 * their own clock, so this converts. That conversion is the whole reason
 * `kotlinx-datetime` is a dependency: the Kotlin standard library parses an instant but
 * carries no time zone database, so there is no way to render one in local time without
 * it.
 *
 * The format is fixed rather than locale-derived, deliberately. Every other string in
 * this app is English, so a date rendered from the device's locale would be the one piece
 * of the screen that disagreed with the rest of it.
 */
private val StampFormat = LocalDateTime.Format {
    // Unpadded, so it reads "3 Nov" rather than "03 Nov" — the kit's own rendering.
    day(Padding.NONE)
    char(' ')
    monthName(MonthNames.ENGLISH_ABBREVIATED)
    char(' ')
    year()
    chars(", ")
    // Padded, because a time is read as a pair and "9:05" beside "10:15" in a column
    // would not align.
    hour()
    char(':')
    minute()
}

/**
 * Formats the server's instant, or returns `null` if it cannot.
 *
 * **Null rather than a throw, and never a placeholder string.** The enquiry has already
 * been accepted by the time this runs, so a timestamp that will not parse is a cosmetic
 * problem and must not be allowed to become a visible one. The thank-you simply shows no
 * stamp: the tick and the headline are what confirm the send, and the date was always the
 * smallest part of it.
 *
 * [timeZone] is a parameter so a test asserts one known output rather than whatever the
 * machine running it happens to be set to.
 */
@OptIn(ExperimentalTime::class)
fun formatAcceptedAt(
    acceptedAt: String?,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String? {
    if (acceptedAt.isNullOrBlank()) return null
    return runCatching {
        StampFormat.format(Instant.parse(acceptedAt).toLocalDateTime(timeZone))
    }.getOrNull()
}
