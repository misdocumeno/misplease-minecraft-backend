package io.github.misdocumeno.application

/**
 * Decides whether the proxy reconciler may drop an authenticated session that is
 * not (yet) a proxy player. Pure so it can be tested without Redis or Velocity.
 *
 * The race this guards against: `/login` succeeds in the limbo, the session is in
 * Redis, but LimboAPI has not handed the player to a backend yet, so
 * `proxy.allPlayers` does not contain them. Dropping the session there makes the
 * backend ask for `/login` a second time (2026-11-19).
 */
object OrphanDecision {

    /** 15 s reconcile ticks: 3 misses = 45 s of continuous absence. */
    const val MISSES_BEFORE_INVALIDATE = 3

    /**
     * Never drop a session this young. Covers the limbo hand-off and a cold
     * backend thaw (13-21 s measured, worse under maintenance).
     */
    const val MIN_AGE_SECONDS = 180L

    /**
     * @param ageSeconds seconds since `/login` created the session, or `null` when
     *   Redis has no creation time for it (older session / blip).
     */
    fun shouldInvalidate(misses: Int, ageSeconds: Long?): Boolean {
        if (ageSeconds != null && ageSeconds < MIN_AGE_SECONDS) return false
        return misses >= MISSES_BEFORE_INVALIDATE
    }
}
