package io.github.misdocumeno.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class TeleportAskType {
    TPA,
    TPA_HERE,
}