package com.curato.ui.designsystem

/**
 * The one place a price becomes a string (SPEC.md §10.4).
 *
 * Driven by the response's `currency`, never by the device locale. The platform's
 * default currency formatter would render `$55.99` as `₹55.99` on an Indian device —
 * the right symbol for the wrong currency, beside a Send button. Every price on every
 * screen goes through this.
 */
fun formatPrice(price: Double, currency: String): String {
    val symbol = CURRENCY_SYMBOLS[currency.uppercase()]
    val amount = formatTwoDecimals(price)
    // An unknown currency prints its code rather than guessing a symbol: "SEK 55.99" is
    // honest, a wrong glyph is not.
    return if (symbol != null) "$symbol$amount" else "$currency $amount"
}

private val CURRENCY_SYMBOLS = mapOf(
    "USD" to "$",
    "EUR" to "€",
    "GBP" to "£",
    "INR" to "₹",
)

/**
 * Two decimal places without `String.format`, which is not available in `commonMain`.
 * Rounds half-up on the minor unit.
 */
private fun formatTwoDecimals(value: Double): String {
    val negative = value < 0
    val cents = kotlin.math.round(kotlin.math.abs(value) * 100.0).toLong()
    val whole = cents / 100
    val fraction = cents % 100
    val sign = if (negative) "-" else ""
    return "$sign$whole.${fraction.toString().padStart(2, '0')}"
}
