package io.github.misdocumeno.domain.model

import kotlinx.serialization.Serializable

/** One backend's display name for a player. `name == null` means "forget it". */
@Serializable
data class TabListNameUpdate(
    val uuid: String,
    val name: String? = null,
)
