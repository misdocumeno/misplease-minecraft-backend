package io.github.misdocumeno.application

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrphanDecisionTest {

    @Test
    fun `fresh session is never an orphan, no matter how many misses`() {
        // The limbo hand-off: authenticated 1 s ago, not in proxy.allPlayers.
        assertFalse(OrphanDecision.shouldInvalidate(misses = 1, ageSeconds = 1))
        assertFalse(OrphanDecision.shouldInvalidate(misses = 3, ageSeconds = 30))
        assertFalse(OrphanDecision.shouldInvalidate(misses = 10, ageSeconds = 179))
    }

    @Test
    fun `old session needs consecutive misses`() {
        assertFalse(OrphanDecision.shouldInvalidate(misses = 1, ageSeconds = 600))
        assertFalse(OrphanDecision.shouldInvalidate(misses = 2, ageSeconds = 600))
        assertTrue(OrphanDecision.shouldInvalidate(misses = 3, ageSeconds = 600))
        assertTrue(OrphanDecision.shouldInvalidate(misses = 5, ageSeconds = 600))
    }

    @Test
    fun `unknown age falls back to the miss counter`() {
        // A session created before this feature (no created-at key) still gets
        // cleaned up after a proxy crash, but not on the first tick.
        assertFalse(OrphanDecision.shouldInvalidate(misses = 1, ageSeconds = null))
        assertTrue(OrphanDecision.shouldInvalidate(misses = 3, ageSeconds = null))
    }

    @Test
    fun `age exactly at the floor follows the miss counter`() {
        assertFalse(OrphanDecision.shouldInvalidate(misses = 2, ageSeconds = 180))
        assertTrue(OrphanDecision.shouldInvalidate(misses = 3, ageSeconds = 180))
    }
}
