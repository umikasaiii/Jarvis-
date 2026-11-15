package com.simone.jarvismobile.core.reliability

import java.math.BigDecimal
import java.math.MathContext

/** A number with its optional unit, read with explicit Italian notation rules (1.234,5 = 1234.5; 3,5 milioni = 3 500 000). */
data class Quantity(val value: BigDecimal, val unit: String?, val raw: String, val start: Int, val end: Int) {
    val dimension: String? get() = Quantities.UNITS[unit]?.first
    /** The value in the dimension's base unit, or the plain value when the unit is unknown/absent. */
    val base: BigDecimal get() = Quantities.UNITS[unit]?.let { value.multiply(it.second, MathContext.DECIMAL64) } ?: value
}

object Quantities {
    /** unit → (dimension, factor to the base unit of that dimension). Explicit table: the only conversions the verifier performs. */
    internal val UNITS: Map<String, Pair<String, BigDecimal>> = mapOf(
        "km" to ("len" to BigDecimal("1000")), "m" to ("len" to BigDecimal.ONE), "cm" to ("len" to BigDecimal("0.01")), "mm" to ("len" to BigDecimal("0.001")),
        "km²" to ("area" to BigDecimal("1000000")), "m²" to ("area" to BigDecimal.ONE), "ha" to ("area" to BigDecimal("10000")),
        "kg" to ("mass" to BigDecimal("1000")), "g" to ("mass" to BigDecimal.ONE), "mg" to ("mass" to BigDecimal("0.001")), "t" to ("mass" to BigDecimal("1000000")),
        "l" to ("vol" to BigDecimal.ONE), "ml" to ("vol" to BigDecimal("0.001")),
        "h" to ("time" to BigDecimal("3600")), "min" to ("time" to BigDecimal("60")), "s" to ("time" to BigDecimal.ONE),
        "%" to ("pct" to BigDecimal.ONE), "°C" to ("tempC" to BigDecimal.ONE), "°F" to ("tempF" to BigDecimal.ONE),
        "anni" to ("years" to BigDecimal.ONE), "secoli" to ("years" to BigDecimal("100")),
        "eur" to ("eur" to BigDecimal.ONE), "ab" to ("pop" to BigDecimal.ONE), "mmHg" to ("mmHg" to BigDecimal.ONE),
        "bpm" to ("bpm" to BigDecimal.ONE), "mg/dl" to ("mgdl" to BigDecimal.ONE),
    )

    private val ALIAS = mapOf(
        "km" to "km", "chilometri" to "km", "chilometro" to "km", "m" to "m", "metri" to "m", "metro" to "m", "cm" to "cm", "centimetri" to "cm",
        "mm" to "mm", "millimetri" to "mm", "kg" to "kg", "chili" to "kg", "chilogrammi" to "kg", "chilo" to "kg", "g" to "g", "grammi" to "g",
        "mg" to "mg", "milligrammi" to "mg", "t" to "t", "tonnellate" to "t", "l" to "l", "litri" to "l", "litro" to "l", "ml" to "ml", "millilitri" to "ml",
        "h" to "h", "ore" to "h", "ora" to "h", "min" to "min", "minuti" to "min", "minuto" to "min", "s" to "s", "secondi" to "s", "secondo" to "s",
        "anni" to "anni", "anno" to "anni", "secoli" to "secoli", "secolo" to "secoli", "euro" to "eur", "€" to "eur", "abitanti" to "ab",
        "mmhg" to "mmHg", "bpm" to "bpm", "ha" to "ha", "ettari" to "ha", "km2" to "km²", "km²" to "km²", "m2" to "m²", "m²" to "m²",
    )
    private val SCALE = mapOf(
        "milione" to BigDecimal("1000000"), "milioni" to BigDecimal("1000000"), "miliardo" to BigDecimal("1000000000"),
        "miliardi" to BigDecimal("1000000000"), "mila" to BigDecimal("1000"), "mille" to BigDecimal("1000"),
    )
    private val NUMBER = Regex("(?<![\\p{L}\\d.,])(\\d{1,3}(?:\\.\\d{3})+(?:,\\d+)?|\\d+(?:[.,]\\d+)?)(?:\\s*(%|°\\s?[CcFf]|€)|\\s+([\\p{L}²]+)(?:\\s+(quadrat[io]))?)?")

    private val SCALE_UNIT = Regex("\\s+di\\s+([\\p{L}²\\d]+)")

    fun parse(raw: String): BigDecimal? = try {
        val s = if (Regex("^\\d{1,3}(\\.\\d{3})+(,\\d+)?$").matches(raw)) raw.replace(".", "").replace(',', '.') else raw.replace(',', '.')
        BigDecimal(s)
    } catch (_: NumberFormatException) { null }

    fun extract(text: String): List<Quantity> {
        val out = ArrayList<Quantity>()
        for (m in NUMBER.findAll(text)) {
            var v = parse(m.groupValues[1]) ?: continue
            var unit: String? = null
            val sym = m.groupValues[2]
            val word = m.groupValues[3].lowercase()
            val sq = m.groupValues[4].isNotEmpty()
            var end = m.range.last + 1
            when {
                sym.isNotEmpty() -> unit = when { sym == "%" -> "%"; sym == "€" -> "eur"; sym.last().lowercaseChar() == 'c' -> "°C"; else -> "°F" }
                word in SCALE -> {
                    v = v.multiply(SCALE.getValue(word)); end = m.range.last + 1
                    SCALE_UNIT.matchAt(text, end)?.let { u -> ALIAS[u.groupValues[1].lowercase()]?.let { c -> unit = c; end = u.range.last + 1 } }
                }
                word.isNotEmpty() -> {
                    val canon = ALIAS[word]
                    if (canon != null) {
                        unit = if (sq && (canon == "km" || canon == "m")) "$canon²" else canon
                    } else end = m.groupValues[1].length + m.range.first // the following word is not a unit: leave it out of the quantity
                }
            }
            if (unit == null && word !in SCALE) end = m.range.first + m.groupValues[1].length
            out += Quantity(v, unit, text.substring(m.range.first, end), m.range.first, end)
        }
        return out
    }

    /** Same number and unit (EXACT), or the same physical value through the explicit table (CONVERTED), or different. */
    enum class Match { EXACT, CONVERTED, DIFFERENT }

    fun compare(a: Quantity, b: Quantity): Match {
        if (a.unit == b.unit) return if (a.value.compareTo(b.value) == 0) Match.EXACT else Match.DIFFERENT
        val da = a.dimension; val db = b.dimension
        if (da != null && da == db) {
            val x = a.base; val y = b.base
            val tol = x.abs().max(y.abs()).multiply(BigDecimal("0.000001"))
            return if (x.subtract(y).abs() <= tol) Match.CONVERTED else Match.DIFFERENT
        }
        // a bare number against a number with a unit is NOT a match: "5" and "5 km" are not the same claim
        return Match.DIFFERENT
    }
}
