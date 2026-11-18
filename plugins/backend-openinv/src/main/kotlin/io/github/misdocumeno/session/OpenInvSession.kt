package io.github.misdocumeno.session

import io.github.misdocumeno.OpenInvPlugin
import io.github.misdocumeno.domain.InventoryKind
import io.github.misdocumeno.domain.Snapshot
import io.github.misdocumeno.domain.GuiMapping
import io.github.misdocumeno.infra.paper.GuiPads
import io.github.misdocumeno.source.InventorySource
import io.github.misdocumeno.source.LiveSource
import io.github.misdocumeno.source.ProfileSource
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class OpenInvHolder(val sessionId: UUID) : InventoryHolder {
    private var attached: Inventory? = null

    fun attach(inventory: Inventory) {
        attached = inventory
    }

    override fun getInventory(): Inventory = attached ?: error("inventory not attached")
}

/**
 * One viewer editing one inventory/ender chest.
 *
 * The GUI is a mirror. Every click is reconciled slot by slot against [acked]
 * (last known source value); only differing slots are written, so a live
 * player's unrelated slots are never overwritten.
 */
class OpenInvSession(
    private val plugin: OpenInvPlugin,
    val viewer: UUID,
    val targetUuid: UUID,
    val targetName: String,
    val kind: InventoryKind,
    val group: String,
    val server: String,
    private val initial: Snapshot,
) {

    val id: UUID = UUID.randomUUID()
    val holder = OpenInvHolder(id)

    private val initiallyOnline = onlineInGroup()
    val gui: Inventory = Bukkit.createInventory(
        holder,
        GuiMapping.size(kind),
        titleComponent(initiallyOnline),
    ).also { holder.attach(it) }

    private val acked: MutableList<ItemStack?> = initial.items.map { it?.clone() }.toMutableList()
    private val dirty: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val pending: MutableMap<Int, ItemStack?> = ConcurrentHashMap()
    private val writeBusy = AtomicBoolean(false)
    private val readBusy = AtomicBoolean(false)

    private var taskId = -1
    private var closed = false
    private var lastTitle: String? = null
    private var lastOnlineState = initiallyOnline
    private var settleUntilMillis = 0L

    fun start() {
        for (index in 0 until kind.size) {
            val slot = GuiMapping.toGui(kind, index) ?: continue
            gui.setItem(slot, initial.items[index]?.clone())
        }
        GuiPads.stamp(gui, kind)
        val player = Bukkit.getPlayer(viewer) ?: return
        player.openInventory(gui)
        lastTitle = stateMarker(initiallyOnline)
        taskId = Bukkit.getScheduler()
            .runTaskTimer(plugin, Runnable { tick() }, plugin.messages.refreshTicks(), plugin.messages.refreshTicks())
            .taskId
    }

    fun isOpenFor(player: Player): Boolean =
        player.isOnline && player.openInventory.topInventory === gui

    private fun tick() {
        if (closed) return
        val player = Bukkit.getPlayer(viewer)
        if (player == null || !isOpenFor(player)) return finish(closeView = false)
        val online = onlineInGroup()
        if (online != lastOnlineState) {
            // MVI applies the group profile on the join tick; pause the mirror
            // for 2s so a stale/empty .dat can never be mirrored and duplicated.
            lastOnlineState = online
            settleUntilMillis = System.currentTimeMillis() + SETTLE_MILLIS
        }
        notifyState(player)
        refresh(player)
    }

    /** True right after an online/offline switch: interactions are paused. */
    fun isSettling(): Boolean = System.currentTimeMillis() < settleUntilMillis

    /** Reconciles the mirror with its source. Never writes by itself. */
    private fun refresh(player: Player) {
        if (isSettling()) return
        val source = resolveSource()
        if (source.live) {
            runCatching { source.read() }.onSuccess { merge(it) }
                .onFailure { plugin.logger.warning("live read failed for $targetName: ${it.message}") }
            return
        }
        if (!readBusy.compareAndSet(false, true)) return
        plugin.profileExecutor.execute {
            val result = runCatching { source.read() }
            readBusy.set(false)
            result.onSuccess { snapshot ->
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    if (!closed && isOpenFor(player)) merge(snapshot)
                })
            }.onFailure {
                plugin.logger.fine("profile read failed for $targetName: ${it.message}")
            }
        }
    }

    /** Applies source values to slots the viewer has not touched. */
    private fun merge(snapshot: Snapshot) {
        for (index in 0 until kind.size) {
            val slot = GuiMapping.toGui(kind, index) ?: continue
            if (index in dirty || pending.containsKey(index)) continue
            val current = gui.getItem(slot)?.takeIf { !it.type.isAir }
            if (!same(current, acked[index])) continue
            val incoming = snapshot.items[index]?.clone()?.takeIf { !it.type.isAir }
            if (!same(current, incoming)) gui.setItem(slot, incoming)
            acked[index] = incoming
        }
    }

    /** Diffs the GUI against the last acked state and writes the difference. */
    fun syncNow() {
        if (closed || isSettling()) return
        val changed = LinkedHashMap<Int, ItemStack?>()
        for (index in 0 until kind.size) {
            val slot = GuiMapping.toGui(kind, index) ?: continue
            val now = gui.getItem(slot)?.takeIf { !it.type.isAir }?.clone()
            if (same(now, acked[index])) continue
            changed[index] = now
            acked[index] = now
            dirty.add(index)
        }
        GuiPads.sanitize(gui, kind)
        if (changed.isEmpty()) return

        val source = resolveSource()
        if (source.live) {
            try {
                source.apply(changed)
                changed.keys.forEach { dirty.remove(it) }
            } catch (error: Exception) {
                plugin.logger.warning("live write failed for $targetName: ${error.message}")
                plugin.server.scheduler.runTask(plugin, Runnable { reportFailure(error) })
            }
        } else {
            enqueueProfileWrite(changed)
        }
    }

    private fun enqueueProfileWrite(changed: Map<Int, ItemStack?>) {
        pending.putAll(changed)
        dirty.addAll(changed.keys)
        if (writeBusy.compareAndSet(false, true)) plugin.profileExecutor.execute { flush() }
    }

    private fun flush() {
        try {
            while (pending.isNotEmpty()) {
                val batch = HashMap(pending)
                pending.clear()
                try {
                    profileSource().apply(batch)
                    for (index in batch.keys) if (!pending.containsKey(index)) dirty.remove(index)
                } catch (error: Exception) {
                    plugin.logger.warning("profile write failed for $targetName: ${error.message}")
                    for (index in batch.keys) dirty.remove(index)
                    Bukkit.getScheduler().runTask(plugin, Runnable { reportFailure(error) })
                }
            }
        } finally {
            writeBusy.set(false)
            if (pending.isNotEmpty() && writeBusy.compareAndSet(false, true)) {
                plugin.profileExecutor.execute { flush() }
            }
        }
    }

    private fun reportFailure(error: Exception) {
        val player = Bukkit.getPlayer(viewer) ?: return
        player.sendMessage(
            plugin.messages.component("write-failed", "reason" to (error.message ?: error.javaClass.simpleName)),
        )
    }

    private fun profileSource(): InventorySource =
        ProfileSource(plugin.mvi, targetUuid, targetName, kind, server, group)

    /**
     * Live while the target is online *and* in this group; otherwise the
     * MVI profile of the pinned group. Re-evaluated before every read/write so
     * a join/quit/world change never needs to close the GUI.
     */
    private fun resolveSource(): InventorySource {
        val player = Bukkit.getPlayer(targetUuid)
        if (player != null && player.isOnline) {
            val currentGroup = plugin.mvi.groupForWorld(player.world.name)
            if (currentGroup == null || currentGroup == group) {
                return LiveSource(player, kind, server, group)
            }
        }
        return profileSource()
    }

    private fun onlineInGroup(): Boolean {
        val player = Bukkit.getPlayer(targetUuid) ?: return false
        if (!player.isOnline) return false
        val currentGroup = plugin.mvi.groupForWorld(player.world.name)
        return currentGroup == null || currentGroup == group
    }

    private fun titleComponent(online: Boolean): net.kyori.adventure.text.Component {
        val offlineMarker = if (online) "" else plugin.messages.offlineSuffix()
        val key = if (kind == InventoryKind.INVENTORY) "inventory" else "ender"
        return plugin.messages.title(
            key,
            "player" to targetName,
            "server" to server,
            "group" to group,
            "offline" to offlineMarker,
        )
    }

    private fun stateMarker(online: Boolean): String = "$online|$group|$server"

    /**
     * The window title is created once (Component titles cannot be updated
     * through InventoryView), so a source switch is announced in the action bar.
     */
    private fun notifyState(player: Player) {
        val online = onlineInGroup()
        val marker = stateMarker(online)
        if (marker == lastTitle) return
        lastTitle = marker
        player.sendActionBar(
            plugin.messages.component(
                "state",
                "player" to targetName,
                "server" to server,
                "group" to group,
                "offline" to if (online) "" else plugin.messages.offlineSuffix(),
            ),
        )
    }

    fun finish(closeView: Boolean = true) {
        if (closed) return
        syncNow()
        closed = true
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId)
        plugin.sessions.remove(this)
        if (closeView) {
            val player = Bukkit.getPlayer(viewer)
            if (player != null && isOpenFor(player)) player.closeInventory()
        }
        if (pending.isNotEmpty() && writeBusy.compareAndSet(false, true)) {
            plugin.profileExecutor.execute { flush() }
        }
    }

    private fun same(a: ItemStack?, b: ItemStack?): Boolean {
        if (a == null || a.type.isAir) return b == null || b.type.isAir
        if (b == null || b.type.isAir) return false
        return a.amount == b.amount && a.isSimilar(b)
    }

    private companion object {
        /** MVI applies the group profile right after join; stay read-only briefly. */
        const val SETTLE_MILLIS = 2000L
    }
}
