package io.github.misdocumeno.infra.libertybans

import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Reads the active mute for a player, if any.
 *
 * Contract: never fails. A lookup error completes with `null` (fail open), because
 * losing /msg for the whole network is worse than letting a muted player whisper while
 * the punishment backend is unavailable. Implementations must not block the caller.
 */
interface MuteLookup {

    fun activeMute(playerId: UUID): CompletableFuture<MuteState?>
}
