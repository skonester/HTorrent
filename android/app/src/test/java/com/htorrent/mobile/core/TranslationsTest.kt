package com.htorrent.mobile.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TranslationsTest {
    private val dir = File("src/main/assets/locales")
    private fun read(path: String) = File(dir.parentFile, path).takeIf { it.exists() }?.readText()

    @After fun english() = Translations.use("en", ::read)

    @Test fun everyLanguageHasTheEnglishKeys() {
        val english = Translations.parse(File(dir, "en.json").readText()).second.keys
        assertTrue(english.size > 100)
        for (code in Translations.languages) {
            val (status, strings) = Translations.parse(File(dir, "$code.json").readText())
            assertEquals(code, english, strings.keys)
            assertTrue(code, strings.values.all { it.isNotEmpty() })
            assertTrue("$code must stay marked as a machine translation until reviewed", status.contains("review"))
            assertTrue(code, strings.getValue("Downloads/HTorrent on this phone").contains("Downloads/HTorrent"))
        }
    }

    @Test fun picksTheLanguageAndFallsBackToEnglish() {
        Translations.use("de-DE", ::read)
        assertEquals("de", Translations.code)
        assertEquals("Später", tr("Later"))
        assertEquals("Not translated", tr("Not translated"))
        Translations.use("xx", ::read)
        assertEquals("en", Translations.code)
        assertEquals("Later", tr("Later"))
    }

    @Test fun readsEscapes() {
        val (_, s) = Translations.parse("""{"status":"x","n":1,"a":[true,null],"strings":{"Say \"hi\"":"Sag \"hallo\" é\n"}}""")
        assertEquals("Sag \"hallo\" é\n", s["Say \"hi\""])
    }
}
