package io.github.misdocumeno.domain

import io.github.misdocumeno.domain.model.MuteState
import java.util.concurrent.CompletableFuture
import kotlin.uuid.Uuid

/**
 * Reads the active mute for a player, if any.
 *
 * Contract: never fails. A lookup error completes with `null` (fail open), because
 * losing chat for the whole network is worse than letting a muted player talk while
 * the punishment backend is unavailable. Implementations must not block the caller.
 */
interface MuteLookup {

    fun activeMute(playerId: Uuid): CompletableFuture<MuteState?>
}
