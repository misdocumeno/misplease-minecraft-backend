package io.github.misdocumeno

import kotlin.test.Test
import kotlin.test.assertEquals

class WakeDecisionTest {

    @Test
    fun `first connect starts the wake`() {
        assertEquals(
            WakeDecision.Action.START,
            WakeDecision.onPreConnect(target = "main", pendingTarget = null),
        )
    }

    @Test
    fun `same target continues one wake`() {
        // Second /warp to the same frozen server: NX keeps one key, one bar.
        assertEquals(
            WakeDecision.Action.CONTINUE,
            WakeDecision.onPreConnect(target = "main", pendingTarget = "main"),
        )
    }

    @Test
    fun `a different target is denied while one is connecting`() {
        // Velocity does not serialize two connection requests; let the first finish.
        assertEquals(
            WakeDecision.Action.DENY,
            WakeDecision.onPreConnect(target = "tech", pendingTarget = "main"),
        )
    }

    @Test
    fun `kick from the target redirects to the previous server`() {
        assertEquals(
            WakeDecision.KickAction.Redirect("tech"),
            WakeDecision.onKick(
                kickedServer = "main",
                pendingTarget = "main",
                previous = "tech",
            ),
        )
    }

    @Test
    fun `kick from the target with no previous server disconnects`() {
        // Initial join from the limbo: nowhere safe to redirect.
        assertEquals(
            WakeDecision.KickAction.Disconnect,
            WakeDecision.onKick(kickedServer = "main", pendingTarget = "main", previous = null),
        )
    }

    @Test
    fun `kick from the target never redirects to itself`() {
        assertEquals(
            WakeDecision.KickAction.Disconnect,
            WakeDecision.onKick(kickedServer = "main", pendingTarget = "main", previous = "main"),
        )
    }

    @Test
    fun `kick from another server does not cancel the wake`() {
        // The previous server may die while the target is still coming up.
        assertEquals(
            WakeDecision.KickAction.Ignore,
            WakeDecision.onKick(kickedServer = "tech", pendingTarget = "main", previous = "tech"),
        )
    }
}
