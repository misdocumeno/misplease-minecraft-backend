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

    /** Whether a stored profile already exists in this group. Errors count as missing. */
    fun hasProfile(uuid: UUID, name: String, group: String): Boolean =
        runCatching {
            api().profileDataSource.listPlayerProfileNames(ContainerType.GROUP, group)
                .any { it.equals(name, ignoreCase = true) }
        }.getOrDefault(false)

    /** Whether MVI already migrated this player's per-world profile (pre-group-cache installs). */
    fun hasWorldProfile(uuid: UUID, name: String, world: String): Boolean =
        runCatching {
            api().profileDataSource.listPlayerProfileNames(ContainerType.WORLD, world)
                .any { it.equals(name, ignoreCase = true) }
        }.getOrDefault(false)

    fun read(uuid: UUID, name: String, group: String, kind: io.github.misdocumeno.domain.InventoryKind): io.github.misdocumeno.domain.Snapshot =
        readContainer(ContainerType.GROUP, group, uuid, name, kind)

    /**
     * Fallback for a player who has not logged in since MVI was installed: the
     * group cache does not exist yet, but the per-world profile (written by MVI)
     * may. Reading it is safe; writing still goes to the group profile.
     */
    fun readWorld(uuid: UUID, name: String, world: String, kind: io.github.misdocumeno.domain.InventoryKind): io.github.misdocumeno.domain.Snapshot =
        readContainer(ContainerType.WORLD, world, uuid, name, kind)

    private fun readContainer(
        container: ContainerType,
        dataName: String,
        uuid: UUID,
        name: String,
        kind: io.github.misdocumeno.domain.InventoryKind,
    ): io.github.misdocumeno.domain.Snapshot {
        val profile = loadProfile(container, dataName, uuid, name)
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
        seed: io.github.misdocumeno.domain.Snapshot? = null,
    ) {
        // A player who has not joined since MVI was installed has no group cache.
        // If the session was read from their world profile, copy that whole profile
        // first; otherwise applying only the viewer's deltas to an empty profile
        // would erase the rest of the inventory at the next login.
        val groupExists = hasProfile(uuid, name, group)
        val profile = loadProfile(ContainerType.GROUP, group, uuid, name)
        if (!groupExists && seed != null) seedProfile(profile, kind, seed)
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
        // save_load_on_log_in_out is false: Paper loads vanilla playerdata on join and
        // MVI only applies a group profile when loadOnLogin is set. Without this flag an
        // offline edit would be overwritten by the stale .dat at the next login.
        api().profileDataSource
            .modifyGlobalProfile(GlobalProfileKey.of(uuid, name)) { it.setLoadOnLogin(true) }
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // Next read (refresh, login) must not see the pre-write cached profile.
        api().profileCacheManager.clearPlayerProfileCache { it.playerUUID == uuid }
    }

    private fun seedProfile(
        profile: PlayerProfile,
        kind: io.github.misdocumeno.domain.InventoryKind,
        seed: io.github.misdocumeno.domain.Snapshot,
    ) {
        when (kind) {
            io.github.misdocumeno.domain.InventoryKind.INVENTORY -> {
                val storage = arrayOfNulls<ItemStack>(36)
                for (i in 0 until 36) storage[i] = seed.items.getOrNull(i)?.clone()
                val armor = arrayOfNulls<ItemStack>(4)
                for (i in 0 until 4) armor[i] = seed.items.getOrNull(36 + i)?.clone()
                profile.set(Sharables.INVENTORY, storage)
                profile.set(Sharables.ARMOR, armor)
                profile.set(Sharables.OFF_HAND, seed.items.getOrNull(40)?.clone())
            }

            io.github.misdocumeno.domain.InventoryKind.ENDER_CHEST -> {
                val ender = arrayOfNulls<ItemStack>(27)
                for (i in 0 until 27) ender[i] = seed.items.getOrNull(i)?.clone()
                profile.set(Sharables.ENDER_CHEST, ender)
            }
        }
    }

    private fun loadProfile(container: ContainerType, dataName: String, uuid: UUID, name: String): PlayerProfile {
        val key = ProfileKey.of(container, dataName, ProfileTypes.getDefault(), uuid, name)
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
