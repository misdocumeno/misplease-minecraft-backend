package io.github.misdocumeno

import io.github.misdocumeno.command.OpenInvCommand
import io.github.misdocumeno.domain.InventoryKind
import io.github.misdocumeno.infra.database.LastSessionRepository
import io.github.misdocumeno.infra.mvi.MviBridge
import io.github.misdocumeno.infra.paper.Messages
import io.github.misdocumeno.infra.paper.OpenInvListener
import io.github.misdocumeno.session.OpenInvSession
import io.github.misdocumeno.session.SessionRegistry
import io.github.misdocumeno.source.LiveSource
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OpenInvPlugin : JavaPlugin() {

    lateinit var messages: Messages
        private set
    lateinit var mvi: MviBridge
        private set
    lateinit var lastSessions: LastSessionRepository
        private set
    lateinit var sessions: SessionRegistry
        private set

    /** Single writer thread: blocking MVI profile reads/writes never touch the server thread. */
    val profileExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "OpenInv-profiles").apply { isDaemon = true }
        }

    val serverId: String
        get() = System.getenv("SERVER_ID")?.takeIf { it.isNotBlank() } ?: "unknown"

    private val lastOpen = ConcurrentHashMap<UUID, Long>()

    override fun onEnable() {
        saveDefaultConfig()
        reloadConfig()
        messages = Messages(this)
        mvi = MviBridge(this)
        lastSessions = LastSessionRepository.fromEnv(logger)
        sessions = SessionRegistry()

        server.pluginManager.registerEvents(OpenInvListener(this), this)
        register("openinv", InventoryKind.INVENTORY)
        register("openender", InventoryKind.ENDER_CHEST)

        if (!mvi.available) {
            logger.warning("Multiverse-Inventories not found: only players online on this backend can be opened")
        }
        logger.info("OpenInv ready (server=$serverId, mvi=${mvi.available}, groups=${mvi.groups()})")
    }

    override fun onDisable() {
        sessions.closeAll()
        profileExecutor.shutdown()
        runCatching { profileExecutor.awaitTermination(5, TimeUnit.SECONDS) }
    }

    private fun register(name: String, kind: InventoryKind) {
        val command = OpenInvCommand(this, kind)
        getCommand(name)?.let {
            it.setExecutor(command)
            it.tabCompleter = command
        }
    }

    fun reloadAllConfig() {
        reloadConfig()
    }

    /**
     * Opens [targetUuid]'s inventory/ender chest for [viewer].
     *
     * The group defaults to the target's current world group (live) or the
     * group of the world MVI remembers (offline); an explicit group overrides
     * it and is then edited as an MVI profile even for online players.
     */
    fun openSession(
        viewer: Player,
        targetUuid: UUID,
        targetName: String,
        kind: InventoryKind,
        explicitGroup: String?,
        server: String,
    ) {
        if (!throttled(viewer)) return

        val online = Bukkit.getPlayer(targetUuid)?.takeIf { it.isOnline }
        if (online != null) {
            // Online: group lookups are in-memory; only a profile read goes async.
            val groups = mvi.groups()
            if (explicitGroup != null && groups.isNotEmpty() && explicitGroup !in groups) {
                viewer.sendMessage(
                    messages.component("group-unknown", "group" to explicitGroup, "groups" to messages.groupList(groups)),
                )
                return
            }
            // An explicit group means "edit that MVI profile", which needs MVI even
            // for an online player. Only the plain live path can run degraded.
            if (explicitGroup != null && !mvi.available) {
                viewer.sendMessage(messages.component("mvi-missing"))
                return
            }
            val group = explicitGroup
                ?: mvi.groupForWorld(online.world.name)
                ?: mvi.defaultGroup()
                ?: "default"

            if ((mvi.groupForWorld(online.world.name) ?: group) == group) {
                val source = LiveSource(online, kind, server, group)
                val snapshot = runCatching { source.read() }.getOrElse { error ->
                    logger.warning("live read failed for $targetName: ${error.message}")
                    viewer.sendMessage(messages.component("write-failed", "reason" to (error.message ?: "read error")))
                    return
                }
                start(viewer, targetUuid, targetName, kind, group, server, snapshot)
                return
            }
            if (!mvi.available) {
                viewer.sendMessage(messages.component("mvi-missing"))
                return
            }
            readProfile(viewer, targetUuid, targetName, kind, group, server)
            return
        }

        // Offline: MVI/profile work (including lastWorld) must stay off the server thread.
        profileExecutor.execute {
            val groups = mvi.groups()
            if (explicitGroup != null && groups.isNotEmpty() && explicitGroup !in groups) {
                onMain { if (viewer.isOnline) groupUnknown(viewer, groups) }
                return@execute
            }
            if (!mvi.available) {
                onMain { if (viewer.isOnline) viewer.sendMessage(messages.component("mvi-missing")) }
                return@execute
            }
            val group = explicitGroup
                ?: mvi.lastWorld(targetUuid, targetName)?.let { mvi.groupForWorld(it) }
                ?: mvi.defaultGroup()
            if (group == null) {
                onMain { if (viewer.isOnline) groupUnknown(viewer, groups) }
                return@execute
            }
            val profileExists = mvi.hasProfile(targetUuid, targetName, group)
            val snapshot = runCatching { mvi.read(targetUuid, targetName, group, kind) }
            onMain {
                if (!viewer.isOnline) return@onMain
                if (!profileExists) {
                    viewer.sendMessage(messages.component("empty-profile", "player" to targetName, "group" to group))
                }
                snapshot.onSuccess {
                    start(viewer, targetUuid, targetName, kind, group, server, it)
                }.onFailure { error ->
                    logger.warning("profile read failed for $targetName: ${error.message}")
                    viewer.sendMessage(messages.component("write-failed", "reason" to (error.message ?: "read error")))
                }
            }
        }
    }

    private fun readProfile(
        viewer: Player,
        targetUuid: UUID,
        targetName: String,
        kind: InventoryKind,
        group: String,
        server: String,
    ) {
        profileExecutor.execute {
            val snapshot = runCatching { mvi.read(targetUuid, targetName, group, kind) }
            onMain {
                if (!viewer.isOnline) return@onMain
                snapshot.onSuccess {
                    start(viewer, targetUuid, targetName, kind, group, server, it)
                }.onFailure { error ->
                    logger.warning("profile read failed for $targetName: ${error.message}")
                    viewer.sendMessage(messages.component("write-failed", "reason" to (error.message ?: "read error")))
                }
            }
        }
    }

    private fun groupUnknown(viewer: Player, groups: List<String>) {
        viewer.sendMessage(messages.component("group-unknown", "group" to "?", "groups" to messages.groupList(groups)))
    }

    private fun onMain(block: () -> Unit) {
        Bukkit.getScheduler().runTask(this, Runnable(block))
    }

    private fun start(
        viewer: Player,
        targetUuid: UUID,
        targetName: String,
        kind: InventoryKind,
        group: String,
        server: String,
        snapshot: io.github.misdocumeno.domain.Snapshot,
    ) {
        val session = OpenInvSession(this, viewer.uniqueId, targetUuid, targetName, kind, group, server, snapshot)
        sessions.add(session)
        session.start()
        viewer.sendMessage(
            messages.component(
                "opened",
                "player" to targetName,
                "server" to server,
                "group" to group,
                "offline" to if (Bukkit.getPlayer(targetUuid)?.isOnline == true) "" else messages.offlineSuffix(),
            ),
        )
    }

    private fun throttled(viewer: Player): Boolean {
        val now = System.currentTimeMillis()
        val previous = lastOpen.put(viewer.uniqueId, now)
        return previous != null && now - previous < 500L
    }
}
