package io.github.misdocumeno.infra.paper

import io.github.misdocumeno.domain.InventoryKind
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.Inventory
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.Locale

/** MiniMessage strings from config.yml; reloadable with `/openinv reload`. */
class Messages(private val plugin: Plugin) {

    private val miniMessage = MiniMessage.miniMessage()

    fun component(key: String, vararg placeholders: Pair<String, String>): Component {
        val raw = plugin.config.getString("messages.$key") ?: key
        return render(raw, placeholders)
    }

    fun title(key: String, vararg placeholders: Pair<String, String>): Component {
        val raw = plugin.config.getString("titles.$key") ?: key
        return render(raw, placeholders)
    }

    private fun render(raw: String, placeholders: Array<out Pair<String, String>>): Component {
        val resolvers = placeholders.map { (name, value) -> Placeholder.unparsed(name, value) }
        return runCatching {
            miniMessage.deserialize(raw, *resolvers.toTypedArray())
        }.getOrElse { Component.text(raw) }
    }

    fun refreshTicks(): Long = plugin.config.getLong("settings.refresh-ticks", 10L).coerceIn(5L, 200L)

    fun offlineSuffix(): String = plugin.config.getString("messages.offline-suffix") ?: ", offline"

    fun groupList(groups: Collection<String>): String = groups.joinToString(", ").lowercase(Locale.ROOT)
}

