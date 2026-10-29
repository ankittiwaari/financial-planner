package com.algopraxis.dhanniti.domain

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.math.absoluteValue

/**
 * An amount of money in paise. The API sends and receives integer paise; never use floating point for money.
 * Serialises as the bare number of paise.
 */
@Serializable
@JvmInline
value class Money(val paise: Long) : Comparable<Money> {
    operator fun plus(other: Money) = Money(paise + other.paise)
    operator fun minus(other: Money) = Money(paise - other.paise)
    operator fun unaryMinus() = Money(-paise)
    override fun compareTo(other: Money) = paise.compareTo(other.paise)

    val isNegative: Boolean get() = paise < 0

    companion object {
        val ZERO = Money(0)
        fun rupees(rupees: Long) = Money(rupees * 100)

        // A trailing "." is allowed: it's what the field holds mid-typing ("45250.").
        private val AMOUNT = Regex("""^(\d+)(?:\.(\d{0,2}))?$""")
        private const val MAX_RUPEES = 1_000_000_000L // ₹100 crore: well past any personal amount

        /**
         * Parses what a person types: "45250", "45,250.5", "₹ 1,23,456.78". Returns null for anything that
         * isn't a positive-or-zero amount with at most two decimals.
         */
        fun parse(input: String): Money? {
            val cleaned = input.trim().removePrefix("₹").replace(",", "").replace(" ", "")
            val match = AMOUNT.matchEntire(cleaned) ?: return null
            val rupees = match.groupValues[1].toLongOrNull()?.takeIf { it <= MAX_RUPEES } ?: return null
            val paise = match.groupValues[2].padEnd(2, '0').ifEmpty { "00" }.toLong()
            return Money(rupees * 100 + paise)
        }
    }
}

/** Plain editable form: 4525050 → "45250.5", 100 → "1". Round-trips through [Money.parse]. */
fun Money.toInputString(): String {
    val rupees = paise / 100
    val fraction = paise % 100
    return when {
        fraction == 0L -> rupees.toString()
        fraction % 10 == 0L -> "$rupees.${fraction / 10}"
        else -> "$rupees.${fraction.toString().padStart(2, '0')}"
    }
}

/**
 * Formats with Indian digit grouping: ₹1,23,45,678.50. Paise are dropped when zero unless [alwaysShowPaise].
 */
fun Money.formatInr(alwaysShowPaise: Boolean = false): String {
    val abs = paise.absoluteValue
    val rupees = abs / 100
    val fraction = abs % 100
    val grouped = groupIndian(rupees.toString())
    val sign = if (paise < 0) "-" else ""
    val decimals = if (fraction != 0L || alwaysShowPaise) "." + fraction.toString().padStart(2, '0') else ""
    return "$sign₹$grouped$decimals"
}

private fun groupIndian(digits: String): String {
    if (digits.length <= 3) return digits
    val lastThree = digits.takeLast(3)
    val rest = digits.dropLast(3)
    val restGrouped = rest.reversed().chunked(2).joinToString(",").reversed()
    return "$restGrouped,$lastThree"
}
