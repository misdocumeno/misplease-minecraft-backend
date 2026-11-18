package io.github.misdocumeno.session

import io.github.misdocumeno.OpenInvPlugin
import io.github.misdocumeno.domain.GuiMapping
import io.github.misdocumeno.domain.InventoryKind
import io.github.misdocumeno.domain.ItemCodec
import io.github.misdocumeno.domain.Snapshot
import io.github.misdocumeno.infra.paper.GuiPads
import io.github.misdocumeno.source.InventorySource
import io.github.misdocumeno.source.LiveSource
import io.github.misdocumeno.source.ProfileSource
import io.github.misdocumeno.source.RemoteSource
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
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
 *
 * A session is either local (this backend owns the target) or [remote]: then
 * every read/write is a Redis RPC and the owner backend remains the single
 * writer.
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
    private val remote: RemoteSource? = null,
    /** Set when [initial] came from a world profile instead of the group cache. */
    private val seedProfile: Snapshot? = null,
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
    private val writeQueue = ConcurrentLinkedQueue<Pair<InventorySource, Map<Int, ItemStack?>>>()
    private val writeBusy = AtomicBoolean(false)
    private val readBusy = AtomicBoolean(false)

    private var taskId = -1
    private var closed = false
    private var lastState: String? = null
    private var lastOnlineState = initiallyOnline
    private var settleUntilMillis = 0L

    @Volatile
    private var viewOnly: Boolean = initial.mcVersion.isNotBlank() && initial.mcVersion != ItemCodec.version()

    fun start() {
        for (index in 0 until kind.size) {
            val slot = GuiMapping.toGui(kind, index) ?: continue
            gui.setItem(slot, initial.items[index]?.clone())
        }
        GuiPads.stamp(gui, kind)
        val player = Bukkit.getPlayer(viewer) ?: return
        player.openInventory(gui)
        lastState = stateMarker(initiallyOnline)
        taskId = Bukkit.getScheduler()
            .runTaskTimer(plugin, Runnable { tick() }, plugin.messages.refreshTicks(), plugin.messages.refreshTicks())
            .taskId
    }

    fun isOpenFor(player: Player): Boolean =
        player.isOnline && player.openInventory.topInventory === gui

    /** True right after an online/offline switch: interactions are paused. */
    fun isSettling(): Boolean = System.currentTimeMillis() < settleUntilMillis

    /** True when the owner runs another game version; the GUI is not editable. */
    fun isViewOnly(): Boolean = viewOnly

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
                    if (closed || !isOpenFor(player)) return@Runnable
                    val wasViewOnly = viewOnly
                    viewOnly = remote?.viewOnly ?: (snapshot.mcVersion.isNotBlank() && snapshot.mcVersion != ItemCodec.version())
                    if (viewOnly != wasViewOnly) notifyState(player, force = true)
                    merge(snapshot)
                })
            }.onFailure { error ->
                plugin.logger.fine("source read failed for $targetName: ${error.message}")
                if (remote != null && remote.consecutiveFailures >= REMOTE_FAILURES_BEFORE_CLOSE) {
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        closeWith(plugin.messages.component("remote-lost", "server" to server))
                    })
                } else if (remote != null && remote.consecutiveFailures == 1) {
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        if (!closed) {
                            val viewerPlayer = Bukkit.getPlayer(viewer) ?: return@Runnable
                            viewerPlayer.sendActionBar(
                                plugin.messages.component("remote-timeout", "server" to server),
                            )
                        }
                    })
                }
            }
        }
    }

    /** Applies source values to slots the viewer has not touched. */
    private fun merge(snapshot: Snapshot) {
        for (index in 0 until kind.size) {
            val slot = GuiMapping.toGui(kind, index) ?: continue
            if (index in dirty) continue
            val current = gui.getItem(slot)?.takeIf { !it.type.isAir }
            if (!same(current, acked[index])) continue
            val incoming = snapshot.items[index]?.clone()?.takeIf { !it.type.isAir }
            if (!same(current, incoming)) gui.setItem(slot, incoming)
            acked[index] = incoming
        }
    }

    /** Diffs the GUI against the last acked state and writes the difference. */
    fun syncNow() {
        if (closed || isSettling() || viewOnly) return
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
                changed.keys.forEach { dirty.remove(it) }
                plugin.server.scheduler.runTask(plugin, Runnable { reportFailure(error) })
            }
        } else {
            enqueueWrite(source, changed)
        }
    }

    private fun enqueueWrite(source: InventorySource, changed: Map<Int, ItemStack?>) {
        writeQueue.add(source to changed)
        if (writeBusy.compareAndSet(false, true)) plugin.profileExecutor.execute { flush() }
    }

    private fun flush() {
        try {
            while (true) {
                val (source, batch) = writeQueue.poll() ?: break
                try {
                    source.apply(batch)
                } catch (error: Exception) {
                    plugin.logger.warning("profile write failed for $targetName: ${error.message}")
                    Bukkit.getScheduler().runTask(plugin, Runnable { reportFailure(error) })
                } finally {
                    batch.keys.forEach { dirty.remove(it) }
                }
            }
        } finally {
            writeBusy.set(false)
            if (writeQueue.isNotEmpty() && writeBusy.compareAndSet(false, true)) {
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

    /**
     * Live while the target is online *and* in this group; otherwise the
     * MVI profile of the pinned group. Re-evaluated before every read/write so
     * a join/quit/world change never needs to close the GUI.
     */
    private fun resolveSource(): InventorySource {
        remote?.let { return it }
        val player = Bukkit.getPlayer(targetUuid)
        if (player != null && player.isOnline) {
            val currentGroup = plugin.mvi.groupForWorld(player.world.name)
            if (currentGroup == null || currentGroup == group) {
                return LiveSource(player, kind, server, group)
            }
        }
        return ProfileSource(plugin.mvi, targetUuid, targetName, kind, server, group, seedProfile)
    }

    private fun onlineInGroup(): Boolean {
        remote?.let { return it.online }
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

    private fun stateMarker(online: Boolean): String = "$online|$group|$server|$viewOnly"

    /**
     * The window title is created once (Component titles cannot be updated
     * through InventoryView), so a source switch is announced in the action bar.
     */
    private fun notifyState(player: Player, force: Boolean = false) {
        val online = onlineInGroup()
        val marker = stateMarker(online)
        if (marker == lastState && !force) return
        lastState = marker
        if (viewOnly) {
            player.sendActionBar(
                plugin.messages.component(
                    "view-only",
                    "player" to targetName,
                    "server" to server,
                    "version" to (initial.mcVersion.ifBlank { "?" }),
                    "local" to ItemCodec.version(),
                ),
            )
            return
        }
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

    /** Closes the GUI with an explanation (target moved servers, owner lost). */
    fun closeWith(message: net.kyori.adventure.text.Component) {
        if (closed) return
        Bukkit.getPlayer(viewer)?.sendMessage(message)
        finish()
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
        if (writeQueue.isNotEmpty() && writeBusy.compareAndSet(false, true)) {
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

        /** Three missed reads roughly a minute apart: the owner is gone. */
        const val REMOTE_FAILURES_BEFORE_CLOSE = 3
    }
}
