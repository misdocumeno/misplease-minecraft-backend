package io.github.misdocumeno.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Nickname normalisation decides what ends up in LuckPerms meta and therefore in
 * every chat line and tab-list entry. A bug here is visible network-wide, so the
 * stripping/limit rules are pinned down.
 */
class NicknamesTest {

    @Test
    fun `plain name is kept`() {
        assertEquals("Master_1", Nicknames.normalize("Master_1", allowColour = false))
    }

    @Test
    fun `colour codes are stripped without permission`() {
        assertEquals("Angel", Nicknames.normalize("&bAngel", allowColour = false))
        assertEquals("Angel", Nicknames.normalize("§bAngel", allowColour = false))
    }

    @Test
    fun `colour codes are kept with permission and section signs normalise`() {
        assertEquals("&bAngel", Nicknames.normalize("&bAngel", allowColour = true))
        assertEquals("&bAngel", Nicknames.normalize("§bAngel", allowColour = true))
    }

    @Test
    fun `stray ampersand is dropped with colour permission`() {
        assertEquals("Angel", Nicknames.normalize("Angel&", allowColour = true))
    }

    @Test
    fun `visible length ignores colour codes`() {
        val fifteen = "abcdefghijklmno"
        assertEquals(fifteen, Nicknames.normalize(fifteen, allowColour = false))
        assertEquals("&b$fifteen", Nicknames.normalize("&b$fifteen", allowColour = true))
        assertNull(Nicknames.normalize("${fifteen}x", allowColour = false))
    }

    @Test
    fun `spaces and other characters are rejected`() {
        assertNull(Nicknames.normalize("bad name", allowColour = false))
        assertNull(Nicknames.normalize("bad-name", allowColour = false))
        assertNull(Nicknames.normalize("<red>Angel", allowColour = false))
    }

    @Test
    fun `empty and colour-only names are rejected`() {
        assertNull(Nicknames.normalize("", allowColour = false))
        assertNull(Nicknames.normalize("   ", allowColour = false))
        assertNull(Nicknames.normalize("&b", allowColour = true))
    }

    @Test
    fun `clear words are recognised case-insensitively`() {
        assertTrue(Nicknames.isClear("OFF"))
        assertTrue(Nicknames.isClear("reset"))
        assertFalse(Nicknames.isClear("Angel"))
    }

    @Test
    fun `display adds the nickname marker`() {
        assertEquals("~Angel", Nicknames.display("Angel"))
    }
}
