package io.github.misdocumeno

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RestarterDecisionTest {

    private val day = 24L * 3600

    @Test
    fun `not due before the awake threshold`() {
        assertFalse(RestarterDecision.isDue(0, day))
        assertFalse(RestarterDecision.isDue(day - 1, day))
    }

    @Test
    fun `due at and after the awake threshold`() {
        assertTrue(RestarterDecision.isDue(day, day))
        assertTrue(RestarterDecision.isDue(day * 3, day))
    }

    @Test
    fun `never stops with players online, even when due`() {
        assertFalse(
            RestarterDecision.shouldStop(
                due = true,
                onlinePlayers = 1,
                emptySeconds = 60,
                emptyForSeconds = 600,
            ),
        )
    }

    @Test
    fun `waits for the empty gate`() {
        assertFalse(
            RestarterDecision.shouldStop(
                due = true,
                onlinePlayers = 0,
                emptySeconds = 60,
                emptyForSeconds = 59,
            ),
        )
        assertTrue(
            RestarterDecision.shouldStop(
                due = true,
                onlinePlayers = 0,
                emptySeconds = 60,
                emptyForSeconds = 60,
            ),
        )
    }

    @Test
    fun `not due means no stop even when empty`() {
        assertFalse(
            RestarterDecision.shouldStop(
                due = false,
                onlinePlayers = 0,
                emptySeconds = 60,
                emptyForSeconds = 60,
            ),
        )
    }
}
