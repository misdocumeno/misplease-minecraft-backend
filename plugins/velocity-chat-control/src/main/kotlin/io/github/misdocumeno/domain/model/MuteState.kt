package io.github.misdocumeno.domain.model

import java.time.Instant

/**
 * A mute currently applying to a player.
 *
 * [endDate] is `null` for a permanent mute: LibertyBans models permanence with
 * [Instant.MAX], which would print as a nonsense date in the notice.
 */
data class MuteState(
    val reason: String?,
    val endDate: Instant?,
)
