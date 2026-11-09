package io.github.misdocumeno.infra.redis

import io.github.misdocumeno.domain.model.TeleportAskType
import io.lettuce.core.api.sync.RedisCommands
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap

/** Redis tpa keys + a short tab-complete cache so the main thread never blocks on SMEMBERS. */
class TpaRequestQueries(
    private val redis: RedisCommands<String, String>,
    private val plugin: Plugin,
) {
    private data class Cache(val names: List<String>, val atMs: Long)

    private val incomingTab = ConcurrentHashMap<String, Cache>()
    private val outgoingTab = ConcurrentHashMap<String, Cache>()

    fun incoming(target: String): List<Pair<String, TeleportAskType>> {
        return redis.smembers("tpa:requests:$target").mapNotNull { requester ->
            redis.get("tpa:request:$requester:$target")?.let {
                requester to TeleportAskType.valueOf(it)
            } ?: run {
                redis.srem("tpa:requests:$target", requester)
                redis.srem("tpa:outgoing_requests:$requester", target)
                null
            }
        }.also { incomingTab[target] = Cache(it.map { (name) -> name }, System.currentTimeMillis()) }
    }

    fun outgoing(requester: String): List<Pair<String, TeleportAskType>> {
        return redis.smembers("tpa:outgoing_requests:$requester").mapNotNull { target ->
            redis.get("tpa:request:$requester:$target")?.let {
                target to TeleportAskType.valueOf(it)
            } ?: run {
                redis.srem("tpa:requests:$target", requester)
                redis.srem("tpa:outgoing_requests:$requester", target)
                null
            }
        }.also { outgoingTab[requester] = Cache(it.map { (name) -> name }, System.currentTimeMillis()) }
    }

    fun clear(requester: String, target: String) {
        redis.del("tpa:request:$requester:$target")
        redis.srem("tpa:requests:$target", requester)
        redis.srem("tpa:outgoing_requests:$requester", target)
        incomingTab.remove(target)
        outgoingTab.remove(requester)
    }

    fun suggestIncoming(target: String, partial: String): List<String> {
        refreshIncomingAsync(target)
        return prefix(incomingTab[target]?.names.orEmpty(), partial)
    }

    fun suggestOutgoing(requester: String, partial: String): List<String> {
        refreshOutgoingAsync(requester)
        return prefix(outgoingTab[requester]?.names.orEmpty(), partial)
    }

    private fun refreshIncomingAsync(target: String) {
        val cached = incomingTab[target]
        if (cached != null && System.currentTimeMillis() - cached.atMs < TAB_TTL_MS) return
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable { incoming(target) })
    }

    private fun refreshOutgoingAsync(requester: String) {
        val cached = outgoingTab[requester]
        if (cached != null && System.currentTimeMillis() - cached.atMs < TAB_TTL_MS) return
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable { outgoing(requester) })
    }

    private fun prefix(names: List<String>, partial: String): List<String> =
        names.filter { it.startsWith(partial, ignoreCase = true) }.sortedBy { it.lowercase() }

    companion object {
        private const val TAB_TTL_MS = 1_500L
    }
}
