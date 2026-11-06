package com.htorrent.mobile.core

/**
 * Interface translations. English is the default; the files in assets/locales/<code>.json use the
 * same format as the desktop apps ({"language", "status", "strings": {"English": "Translation"}}).
 * Anything without a translation stays in English.
 */
object Translations {
    val languages = listOf("ar", "de", "es", "fr", "hi", "pt", "ur")

    @Volatile var strings: Map<String, String> = emptyMap()
    @Volatile var code = "en"

    fun t(english: String): String = strings[english]?.takeIf { it.isNotEmpty() } ?: english

    /** Loads [code]'s strings with [read] (returns a file's text or null); unknown codes fall back to English. */
    fun use(language: String, read: (String) -> String?) {
        val c = language.lowercase().substringBefore('-').substringBefore('_')
        if (c == code && strings.isNotEmpty()) return
        val text = if (c in languages) runCatching { read("locales/$c.json") }.getOrNull() else null
        strings = text?.let { runCatching { parse(it).second }.getOrNull() } ?: emptyMap()
        code = if (strings.isEmpty()) "en" else c
    }

    /** Parses a locale file: returns its status line and its strings. */
    fun parse(json: String): Pair<String, Map<String, String>> {
        val root = Reader(json).value() as? Map<*, *> ?: error("not an object")
        val strings = (root["strings"] as? Map<*, *>)?.entries?.associate { it.key as String to it.value as String } ?: emptyMap()
        return (root["status"] as? String ?: "") to strings
    }

    /** Small reader for the subset of JSON the locale files use: objects, arrays, strings, numbers, true/false/null. */
    private class Reader(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (s[i]) {
                '{' -> obj(); '[' -> arr(); '"' -> str()
                else -> { val start = i; while (i < s.length && s[i] !in ",}] \n\r\t") i++; s.substring(start, i) }
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws(); check(s[i++] == ':'); m[k] = value(); ws()
                if (s[i++] == '}') return m
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>(); i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) { l += value(); ws(); if (s[i++] == ']') return l }
        }
        fun str(): String {
            check(s[i++] == '"'); val b = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                        'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> b.append(e)
                    }
                    else -> b.append(c)
                }
            }
        }
    }
}

/** Translates an English interface text (unchanged when there is no translation). */
fun tr(english: String): String = Translations.t(english)
