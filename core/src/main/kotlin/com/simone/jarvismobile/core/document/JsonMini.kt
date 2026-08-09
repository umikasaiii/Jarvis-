package com.simone.jarvismobile.core.document

/**
 * A tiny, dependency-free JSON reader — just enough to flatten a document into
 * searchable `path: value` lines. Core stays pure Kotlin (no `org.json`, no
 * Android), so this can be unit-tested off-device. It is not a validating
 * parser: malformed input throws and the caller falls back to raw text.
 */
internal object JsonMini {

    fun parse(text: String): Any? {
        val p = Cursor(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        return v
    }

    /** Turns a parsed value into `a.b.c: value` lines appended to [out]. */
    fun flatten(prefix: String, value: Any?, out: MutableList<String>) {
        when (value) {
            is Map<*, *> -> for ((k, v) in value) {
                val path = if (prefix.isEmpty()) k.toString() else "$prefix.$k"
                flatten(path, v, out)
            }
            is List<*> -> value.forEachIndexed { i, v ->
                flatten("$prefix[$i]", v, out)
            }
            null -> out += "$prefix: null"
            else -> out += "$prefix: $value"
        }
    }

    private class Cursor(val s: String) {
        var i = 0

        fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun readValue(): Any? {
            skipWs()
            return when (val c = s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBool()
                'n' -> readNull()
                else -> if (c == '-' || c.isDigit()) readNumber() else error("json@$i")
            }
        }

        private fun readObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (peek() == '}') { i++; return map }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                expect(':')
                map[key] = readValue()
                skipWs()
                when (peek()) {
                    ',' -> { i++; continue }
                    '}' -> { i++; break }
                    else -> error("json@$i")
                }
            }
            return map
        }

        private fun readArray(): List<Any?> {
            val list = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (peek() == ']') { i++; return list }
            while (true) {
                list += readValue()
                skipWs()
                when (peek()) {
                    ',' -> { i++; continue }
                    ']' -> { i++; break }
                    else -> error("json@$i")
                }
            }
            return list
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (i < s.length) {
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                val hex = s.substring(i, i + 4); i += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            error("json string unterminated")
        }

        private fun readNumber(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val token = s.substring(start, i)
            return token.toLongOrNull() ?: token.toDouble()
        }

        private fun readBool(): Boolean =
            if (s.startsWith("true", i)) { i += 4; true } else { i += 5; false }

        private fun readNull(): Any? { i += 4; return null }

        private fun peek(): Char = if (i < s.length) s[i] else ' '
        private fun expect(c: Char) { if (peek() != c) error("json expected $c @$i"); i++ }
    }
}
