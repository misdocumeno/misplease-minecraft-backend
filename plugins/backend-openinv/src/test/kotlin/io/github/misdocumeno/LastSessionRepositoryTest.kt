package io.github.misdocumeno

import io.github.misdocumeno.infra.database.LastSessionRepository
import kotlin.test.Test
import kotlin.test.assertEquals

class LastSessionRepositoryTest {

    @Test
    fun `compose url is converted to jdbc`() {
        val (url, user, password) = LastSessionRepository.toJdbc("postgres://misplease:secret@db:5432/misplease")
        assertEquals("jdbc:postgresql://db:5432/misplease", url)
        assertEquals("misplease", user)
        assertEquals("secret", password)
    }

    @Test
    fun `password may contain a colon`() {
        val (_, user, password) = LastSessionRepository.toJdbc("postgres://mis:se:cret@db:5432/misplease")
        assertEquals("mis", user)
        assertEquals("se:cret", password)
    }

    @Test
    fun `default port and already jdbc urls are preserved`() {
        assertEquals(
            "jdbc:postgresql://db:5432/misplease",
            LastSessionRepository.toJdbc("postgres://mis:secret@db/misplease").first,
        )
        val jdbc = "jdbc:postgresql://db:5432/misplease"
        assertEquals(jdbc, LastSessionRepository.toJdbc(jdbc).first)
    }
}
