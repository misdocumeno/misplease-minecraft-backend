package io.github.misdocumeno.application

import io.github.misdocumeno.domain.model.NetworkAnnounce

fun interface NetworkAnnounceHandler {
    fun handle(announce: NetworkAnnounce)
}
