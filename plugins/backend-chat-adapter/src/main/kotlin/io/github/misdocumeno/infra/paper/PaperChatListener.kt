package io.github.misdocumeno.infra.paper

import io.github.misdocumeno.application.ChatIntentPublisher
import io.github.misdocumeno.domain.ChatIntent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventPriority
import java.util.UUID
import kotlin.uuid.Uuid
import kotlin.uuid.toKotlinUuid

class PaperChatListener(private val publisher: ChatIntentPublisher) : Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onChat(event: AsyncChatEvent) {
        event.isCancelled = true
        publisher.publish(
            ChatIntent(
                messageId = Uuid.random(),
                senderUUID = event.player.uniqueId.toKotlinUuid(),
                rawMessage = PlainTextComponentSerializer.plainText().serialize(event.message()),
                timestamp = System.currentTimeMillis(),
            )
        )
    }
}