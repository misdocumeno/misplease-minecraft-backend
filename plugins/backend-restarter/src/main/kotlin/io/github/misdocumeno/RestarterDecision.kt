package io.github.misdocumeno

/**
 * Pure rules for the backend restarter.
 *
 * The point of the restart is to refresh a JVM that has been *running* for a
 * while. Hibernation pauses the process, so frozen time must not count: a
 * server asleep for a week should not be rebooted just because it is old.
 */
object RestarterDecision {

    /** True once the JVM has accumulated [maxAwakeSeconds] of running time. */
    fun isDue(awakeSeconds: Long, maxAwakeSeconds: Long): Boolean =
        awakeSeconds >= maxAwakeSeconds

    /** Never restart with players online, and always wait out the empty gate. */
    fun shouldStop(
        due: Boolean,
        onlinePlayers: Int,
        emptySeconds: Long,
        emptyForSeconds: Long,
    ): Boolean =
        due && onlinePlayers == 0 && emptyForSeconds >= emptySeconds
}
