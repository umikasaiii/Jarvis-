package com.simone.jarvismobile.core.regex

import java.util.regex.PatternSyntaxException

/**
 * BETA 8 hotfix — regular expressions that compile on the JVM AND on Android.
 *
 * The inline flag `U` (UNICODE_CHARACTER_CLASS) is accepted by the JVM's `java.util.regex` but **rejected by Android's
 * ICU-based engine**: `Regex("(?iU)ciao")` throws `PatternSyntaxException: Invalid value for match mode flags` there
 * (reproduced on an Android 14 emulator). Several `object`s kept such expressions in `private val`s, so the object's
 * static initializer failed with `ExceptionInInitializerError` and every later use threw
 * `NoClassDefFoundError: <the object's name>` — which is exactly the message the chat showed for ordinary questions
 * ("errore tecnico: …OfflineHubParser"), because the chat consults these parsers before the language model.
 *
 * Android's engine already treats `\w`, `\b`, `\s`, `\p{L}` and case folding as Unicode-aware by default (verified on the
 * same emulator), so on Android the safe equivalent is the same expression without `U`. [of] therefore keeps the exact
 * JVM behaviour (including `U`) and, only if the engine rejects the expression, retries it with `U` mapped to the
 * supported `u` (UNICODE_CASE). JVM unit tests keep exercising the original semantics.
 */
object PortableRegex {
    fun of(pattern: String): Regex = try {
        Regex(pattern)
    } catch (e: PatternSyntaxException) {
        val safe = androidSafe(pattern)
        if (safe == pattern) throw e
        Regex(safe)
    }

    /** `(?iU)…` → `(?iu)…` — only the leading inline-flag group is rewritten; the rest of the expression is untouched. */
    internal fun androidSafe(pattern: String): String {
        val m = LEADING_FLAGS.find(pattern) ?: return pattern
        val flags = m.groupValues[1]
        if ('U' !in flags) return pattern
        val mapped = flags.replace('U', 'u').toList().distinct().joinToString("")
        return "(?$mapped)" + pattern.substring(m.value.length)
    }

    private val LEADING_FLAGS = Regex("""^\(\?([a-zA-Z]+)\)""")
}
