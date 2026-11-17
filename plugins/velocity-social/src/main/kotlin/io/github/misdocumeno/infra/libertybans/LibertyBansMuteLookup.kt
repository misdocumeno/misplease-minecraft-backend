package io.github.misdocumeno.infra.libertybans

import org.slf4j.Logger
import space.arim.libertybans.api.LibertyBans
import space.arim.libertybans.api.PlayerVictim
import space.arim.libertybans.api.PunishmentType
import space.arim.omnibus.OmnibusProvider
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Asks the LibertyBans instance running on this same proxy for the player's active mute.
 *
 * LibertyBans is `compileOnly` on purpose: the plugin jar it ships already contains
 * these classes, and Velocity's plugin classloader resolves them from there. Bundling
 * a copy would give us a second `Omnibus` registry with no provider registered.
 *
 * Mutes are UUID-only here, so no address is passed to the selection.
 */
class LibertyBansMuteLookup(private val logger: Logger) : MuteLookup {

    @Volatile
    private var instance: LibertyBans? = null

    @Volatile
    private var warnedMissing = false

    override fun activeMute(playerId: UUID): CompletableFuture<MuteState?> {
        val bans = instance() ?: run {
            if (!warnedMissing) {
                warnedMissing = true
                logger.warn("LibertyBans is not available; /msg mutes are not enforced (failing open)")
            }
            return CompletableFuture.completedFuture(null)
        }

        val result = CompletableFuture<MuteState?>()
        try {
            bans.selector
                .selectionBuilder()
                .type(PunishmentType.MUTE)
                .victim(PlayerVictim.of(playerId))
                .selectActiveOnly()
                .build()
                .getFirstSpecificPunishment()
                .whenComplete { punishment, error ->
                    if (error != null) {
                        logger.warn("Mute lookup failed for {}; letting /msg through", playerId, error)
                        result.complete(null)
                        return@whenComplete
                    }
                    val active = punishment?.orElse(null)
                    result.complete(
                        active?.let {
                            MuteState(
                                reason = it.reason,
                                endDate = if (it.isPermanent) null else it.endDate,
                            )
                        },
                    )
                }
        } catch (ex: Throwable) {
            logger.warn("Mute lookup failed for {}; letting /msg through", playerId, ex)
            result.complete(null)
        }
        return result
    }

    private fun instance(): LibertyBans? {
        instance?.let { return it }
        val found = runCatching {
            OmnibusProvider.getOmnibus()
                .getRegistry()
                .getProvider(LibertyBans::class.java)
                .orElse(null)
        }.getOrNull()
        if (found != null) {
            instance = found
        }
        return found
    }
}
