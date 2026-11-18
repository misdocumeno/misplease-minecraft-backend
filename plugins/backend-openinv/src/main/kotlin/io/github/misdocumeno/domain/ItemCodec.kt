package io.github.misdocumeno.domain

import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack
import java.util.Base64

/**
 * Slot codec shared by the Redis transport (v0.3) and the version handshake.
 *
 * `serializeAsBytes()` is lossless only between identical game versions, which
 * is why every snapshot carries [version] and callers must compare it before
 * accepting remote writes.
 */
object ItemCodec {

    fun version(): String =
        runCatching { Bukkit.getMinecraftVersion() }.getOrNull() ?: "unknown"

    fun encode(item: ItemStack?): String? {
        val stack = item?.takeIf { !it.type.isAir } ?: return null
        return Base64.getEncoder().encodeToString(stack.serializeAsBytes())
    }

    /** Returns null for an empty slot; throws on undecodable data. */
    fun decode(encoded: String?): ItemStack? {
        if (encoded == null) return null
        val bytes = Base64.getDecoder().decode(encoded)
        return ItemStack.deserializeBytes(bytes).takeIf { !it.type.isAir }
    }
}
