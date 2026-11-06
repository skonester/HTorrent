// Minimal JSON reader for search provider APIs (replaces kotlinx.serialization used upstream).
package com.htorrent.search

internal object Json {
    fun parse(text: String): Any? = Parser(text).run { val value = value(); ws(); require(i == s.length) { "Trailing JSON data at $i" }; value }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws(); require(i < s.length) { "Unexpected end of JSON" }
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> num()
            }
        }
        fun obj(): Map<String, Any?> {
            i++; val map = LinkedHashMap<String, Any?>(); ws()
            if (take('}')) return map
            while (true) { ws(); val key = str(); ws(); expect(':'); map[key] = value(); ws(); if (take('}')) return map; expect(',') }
        }
        fun arr(): List<Any?> {
            i++; val list = ArrayList<Any?>(); ws()
            if (take(']')) return list
            while (true) { list += value(); ws(); if (take(']')) return list; expect(',') }
        }
        fun str(): String {
            expect('"'); val out = StringBuilder()
            while (true) {
                require(i < s.length) { "Unterminated JSON string" }
                when (val c = s[i++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        require(i < s.length) { "Unterminated JSON escape" }
                        when (val e = s[i++]) {
                            'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r')
                            'b' -> out.append('\b'); 'f' -> out.append('\u000C')
                            'u' -> { require(i + 4 <= s.length) { "Bad JSON unicode escape" }; out.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> out.append(e)
                        }
                    }
                    else -> out.append(c)
                }
            }
        }
        fun num(): Any {
            val start = i
            while (i < s.length && s[i] in "+-0123456789.eE") i++
            val text = s.substring(start, i)
            return text.toLongOrNull() ?: text.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid JSON value at $start")
        }
        fun literal(word: String, value: Any?): Any? { require(s.startsWith(word, i)) { "Invalid JSON literal at $i" }; i += word.length; return value }
        fun take(c: Char) = (i < s.length && s[i] == c).also { if (it) i++ }
        fun expect(c: Char) = require(take(c)) { "Expected '$c' at $i" }
    }
}

// Json.parse only builds Map<String, Any?>; the typed cast lets Map.get win over this extension (a Map<*, *> would recurse).
@Suppress("UNCHECKED_CAST")
internal operator fun Any?.get(key: String): Any? = (this as? Map<String, Any?>)?.get(key)
internal fun Any?.text(): String? = when (this) { is String -> this; is Number, is Boolean -> toString(); else -> null }
internal fun Any?.long(): Long? = when (this) { is Number -> toLong(); is String -> trim().toLongOrNull() ?: trim().toDoubleOrNull()?.toLong(); else -> null }
internal fun Any?.int(): Int? = long()?.toInt()
internal fun Any?.items(): List<Any?> = this as? List<*> ?: emptyList()
