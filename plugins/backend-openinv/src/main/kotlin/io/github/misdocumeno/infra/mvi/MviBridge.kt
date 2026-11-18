package io.github.misdocumeno.infra.mvi

import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.mvplugins.multiverse.inventories.MultiverseInventoriesApi
import org.mvplugins.multiverse.inventories.profile.data.PlayerProfile
import org.mvplugins.multiverse.inventories.profile.key.ContainerType
import org.mvplugins.multiverse.inventories.profile.key.GlobalProfileKey
import org.mvplugins.multiverse.inventories.profile.key.ProfileKey
import org.mvplugins.multiverse.inventories.profile.key.ProfileTypes
import org.mvplugins.multiverse.inventories.share.Sharables
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Thin, defensive wrapper around the Multiverse-Inventories API.
 *
 * MVI is a soft dependency: if it is missing or disabled, [available] is false
 * and every method returns null/empty instead of throwing. Callers then keep
 * working for players that are online on this backend.
 */
class MviBridge(@Suppress("unused") private val plugin: Plugin) {

    val available: Boolean
        get() = Bukkit.getPluginManager().isPluginEnabled("Multiverse-Inventories")

    private fun api(): MultiverseInventoriesApi = MultiverseInventoriesApi.get()

    fun groups(): List<String> =
        runCatching {
            api().worldGroupManager.groups.map { it.name }.sorted()
        }.getOrDefault(emptyList())

    fun hasGroup(group: String): Boolean = group in groups()

    fun groupForWorld(world: String): String? =
        runCatching {
            api().worldGroupManager.getGroupsForWorld(world).firstOrNull()?.name
                ?: api().worldGroupManager.defaultGroup?.name
        }.getOrNull()

    fun defaultGroup(): String? = runCatching { api().worldGroupManager.defaultGroup?.name }.getOrNull()

    /** The world MVI remembers for this player (used to default the group). */
    fun lastWorld(uuid: UUID, name: String): String? =
        runCatching {
            val key = GlobalProfileKey.of(uuid, name)
            api().profileDataSource.getGlobalProfile(key).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).lastWorld
        }.getOrNull()

    /** Resolves a name to a real player through MVI's own name map. */
    fun findByName(name: String): Pair<UUID, String>? =
        runCatching {
            val option = api().playerNamesMapper.getKey(name)
            if (!option.isDefined) return@runCatching null
            val key = option.get()
            key.playerUUID to (key.playerName ?: name)
        }.getOrNull()

    /** Known names (MVI name mapper), for tab completion. */
    fun names(): List<String> =
        runCatching { api().playerNamesMapper.keys.mapNotNull { it.playerName } }.getOrDefault(emptyList())

    /** Whether a stored profile already exists in this group. Errors count as true (do not nag). */
    fun hasProfile(uuid: UUID, name: String, group: String): Boolean =
        runCatching {
            api().profileDataSource.listPlayerProfileNames(ContainerType.GROUP, group)
                .any { it.equals(name, ignoreCase = true) }
        }.getOrDefault(true)

    fun read(uuid: UUID, name: String, group: String, kind: io.github.misdocumeno.domain.InventoryKind): io.github.misdocumeno.domain.Snapshot {
        val profile = loadProfile(uuid, name, group)
        val items = ArrayList<ItemStack?>(kind.size)
        when (kind) {
            io.github.misdocumeno.domain.InventoryKind.INVENTORY -> {
                val storage = profile.get(Sharables.INVENTORY)
                for (i in 0 until 36) items.add(storage?.getOrNull(i)?.clone())
                val armor = profile.get(Sharables.ARMOR)
                for (i in 0 until 4) items.add(armor?.getOrNull(i)?.clone())
                items.add(profile.get(Sharables.OFF_HAND)?.clone())
            }

            io.github.misdocumeno.domain.InventoryKind.ENDER_CHEST -> {
                val ender = profile.get(Sharables.ENDER_CHEST)
                for (i in 0 until 27) items.add(ender?.getOrNull(i)?.clone())
            }
        }
        return io.github.misdocumeno.domain.Snapshot(kind, items, io.github.misdocumeno.domain.ItemCodec.version())
    }

    /**
     * Writes only the given slots into the freshly loaded profile, so other
     * shares (xp, health, ...) and untouched inventory slots are preserved.
     * Blocking: call from the profile executor, never from the main thread.
     */
    fun apply(
        uuid: UUID,
        name: String,
        group: String,
        kind: io.github.misdocumeno.domain.InventoryKind,
        changed: Map<Int, ItemStack?>,
    ) {
        val profile = loadProfile(uuid, name, group)
        when (kind) {
            io.github.misdocumeno.domain.InventoryKind.INVENTORY -> {
                val storage = MviArrays.copy(profile.get(Sharables.INVENTORY), 36)
                val armor = MviArrays.copy(profile.get(Sharables.ARMOR), 4)
                var offHand = profile.get(Sharables.OFF_HAND)?.clone()
                for ((index, item) in changed) {
                    when {
                        index < 36 -> storage[index] = item?.clone()
                        index < 40 -> armor[index - 36] = item?.clone()
                        index == 40 -> offHand = item?.clone()
                    }
                }
                profile.set(Sharables.INVENTORY, storage)
                profile.set(Sharables.ARMOR, armor)
                profile.set(Sharables.OFF_HAND, offHand)
            }

            io.github.misdocumeno.domain.InventoryKind.ENDER_CHEST -> {
                val ender = MviArrays.copy(profile.get(Sharables.ENDER_CHEST), 27)
                for ((index, item) in changed) {
                    if (index in ender.indices) ender[index] = item?.clone()
                }
                profile.set(Sharables.ENDER_CHEST, ender)
            }
        }
        api().profileDataSource.updatePlayerProfile(profile).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // Next read (refresh, login) must not see the pre-write cached profile.
        api().profileCacheManager.clearPlayerProfileCache { it.playerUUID == uuid }
    }

    private fun loadProfile(uuid: UUID, name: String, group: String): PlayerProfile {
        val key = ProfileKey.of(ContainerType.GROUP, group, ProfileTypes.getDefault(), uuid, name)
        return api().profileDataSource.getPlayerProfile(key).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    companion object {
        private const val TIMEOUT_SECONDS = 5L
    }
}

/** Kotlin array helpers for the MVI `ItemStack[]` shares. */
internal object MviArrays {
    @Suppress("UNCHECKED_CAST")
    fun copy(source: Array<ItemStack?>?, size: Int): Array<ItemStack?> {
        val out = arrayOfNulls<ItemStack>(size)
        for (i in 0 until size) out[i] = source?.getOrNull(i)?.clone()
        return out
    }
}
