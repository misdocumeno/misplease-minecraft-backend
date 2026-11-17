package io.github.misdocumeno.infra.libertybans

import java.time.Instant

/**
 * A mute currently applying to a player.
 *
 * [endDate] is `null` for a permanent mute: LibertyBans models permanence with
 * [Instant.MAX], which would print as a nonsense date in the feedback.
 *
 * Duplicated on purpose from velocity-chat-control: the repo has no shared module for
 * proxy plugins (RedisAuthSet and DatabaseFactory are duplicated the same way).
 */
data class MuteState(
    val reason: String?,
    val endDate: Instant?,
)
