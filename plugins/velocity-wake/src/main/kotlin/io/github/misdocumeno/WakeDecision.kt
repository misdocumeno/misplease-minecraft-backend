package io.github.misdocumeno

/**
 * Pure decisions for the wake gate.
 *
 * The plugin only supplies the observed values (target, current pending target,
 * previous server) and executes the answer; keeping the rules here means the
 * nasty cases can be tested without Velocity or Redis.
 */
object WakeDecision {

    enum class Action {
        /** No wake is pending: request one and start the progress bar. */
        START,

        /** The same target is already waking: request the key (NX) and keep the bar. */
        CONTINUE,

        /** A different target is already connecting: refuse this second connect. */
        DENY,
    }

    /**
     * One backend at a time: Velocity does not serialize two
     * `createConnectionRequest` calls, so a second target would race the first.
     */
    fun onPreConnect(target: String, pendingTarget: String?): Action =
        when (pendingTarget) {
            null -> Action.START
            target -> Action.CONTINUE
            else -> Action.DENY
        }

    sealed interface KickAction {
        /** Not the server we were waking; the wake may still be in progress. */
        data object Ignore : KickAction

        /** The target failed and there is nowhere safe to put the player. */
        data object Disconnect : KickAction

        /** Put the player back on the server they came from. */
        data class Redirect(val server: String) : KickAction
    }

    /**
     * What to do when the target dies mid-wake.
     *
     * [previous] is where the player was before the warp. If it is absent (the
     * first join came from the limbo) or is the same server that just failed,
     * redirecting would loop, so let the proxy disconnect.
     */
    fun onKick(kickedServer: String, pendingTarget: String, previous: String?): KickAction =
        when {
            kickedServer != pendingTarget -> KickAction.Ignore
            previous == null || previous == pendingTarget -> KickAction.Disconnect
            else -> KickAction.Redirect(previous)
        }
}
