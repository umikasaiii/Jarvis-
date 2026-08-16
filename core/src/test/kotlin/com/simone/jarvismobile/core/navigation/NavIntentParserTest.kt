package com.simone.jarvismobile.core.navigation

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertEquals

class NavIntentParserTest {

    @Test
    fun `imposta navigazione per is not the offline naviga trigger`() {
        // Regression: "naviga" used to match as a raw substring of "navigazione",
        // so this phrase (Modalità Guida's online-Maps command) was wrongly
        // captured here with the whole unstripped sentence as the "destination"
        // and always failed against the offline place index.
        assertNull(NavIntentParser.parse("imposta navigazione per via Ardeatina 850"))
    }

    @Test
    fun `genuine offline trigger words still work`() {
        val intent = NavIntentParser.parse("portami a Piazza Navona")
        assertIs<NavIntent.Navigate>(intent)
        assertEquals("piazza navona", intent.query)
    }

    @Test
    fun `naviga verso still triggers offline navigation`() {
        val intent = NavIntentParser.parse("naviga verso il Colosseo")
        assertIs<NavIntent.Navigate>(intent)
        assertEquals("il colosseo", intent.query)
    }

    @Test
    fun `stop control phrase still works`() {
        assertIs<NavIntent.Stop>(NavIntentParser.parse("ferma navigazione"))
    }
}
