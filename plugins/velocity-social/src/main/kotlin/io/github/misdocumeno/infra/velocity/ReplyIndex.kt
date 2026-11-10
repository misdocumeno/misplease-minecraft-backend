package io.github.misdocumeno.infra.velocity

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Last private-message partner per player (for /reply). */
class ReplyIndex {
    private val last = ConcurrentHashMap<UUID, UUID>()

    fun remember(a: UUID, b: UUID) {
        last[a] = b
        last[b] = a
    }

    fun partnerOf(player: UUID): UUID? = last[player]
}
