package io.github.misdocumeno.infra.paper

import io.github.misdocumeno.domain.model.TeleportRequest
import java.time.Duration
import java.time.Instant

/**
 * A cross-server teleport that arrived before the player did.
 *
 * The proxy can hold the connect while a backend wakes, so "requested" and
 * "player is here" are no longer milliseconds apart. Without a deadline a
 * stale entry would fire on any later join to the same server and move the
 * player to coordinates from minutes ago.
 */
data class PendingTeleport(
    val request: TeleportRequest,
    val createdAt: Instant = Instant.now(),
) {
    fun isExpired(now: Instant = Instant.now()): Boolean =
        Duration.between(createdAt, now) > TTL

    companion object {
        /** Long enough for a cold boot (main is ~67 s); not longer. */
        val TTL: Duration = Duration.ofSeconds(120)
    }
}
