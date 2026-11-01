package io.github.misdocumeno.infra.paper

import io.github.misdocumeno.application.ChatBroadcastHandler
import io.github.misdocumeno.domain.ChatBroadcast

class PaperChatBroadcastHandler(private val renderer: PaperChatRenderer) : ChatBroadcastHandler {

    override fun handle(broadcast: ChatBroadcast) {
        renderer.render(broadcast)
    }
}