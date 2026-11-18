package io.github.misdocumeno.domain.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Redis wire messages between a viewer's backend and the backend that owns the
 * target player (the only writer). One channel carries requests, one carries
 * responses; a request names the server it is addressed to and every backend
 * ignores the rest.
 *
 * Item stacks travel as Base64 `serializeAsBytes()` payloads, which are only
 * valid between identical game versions. Every state carries `mcVersion`; a
 * mismatch puts the viewer into read-only mode instead of corrupting items.
 */
@Serializable
sealed interface OpenInvMessage

@Serializable
@SerialName("request-state")
data class RequestStateMessage(
    /** Owner backend this request is addressed to (main / tech). */
    val server: String,
    val sessionId: String,
    val viewerName: String,
    val viewerUuid: String,
    val targetName: String,
    val targetUuid: String,
    /** INVENTORY or ENDER_CHEST. */
    val kind: String,
    /** Null on the first request: the owner resolves it from the target. */
    val group: String? = null,
) : OpenInvMessage

@Serializable
@SerialName("state")
data class StateMessage(
    val server: String,
    val sessionId: String,
    val ok: Boolean,
    val error: String? = null,
    /** Machine-readable reason (`no-profile`, `mvi-missing`); [error] is the human text. */
    val errorCode: String? = null,
    val group: String? = null,
    val online: Boolean = false,
    val mcVersion: String = "",
    /** One entry per slot of [kind]; null means empty. */
    val items: List<String?> = emptyList(),
) : OpenInvMessage

@Serializable
@SerialName("apply")
data class ApplyMessage(
    val server: String,
    val sessionId: String,
    val viewerName: String,
    val targetName: String,
    val targetUuid: String,
    val kind: String,
    val group: String,
    val mcVersion: String,
    val changed: Map<Int, String?>,
) : OpenInvMessage

@Serializable
@SerialName("applied")
data class AppliedMessage(
    val server: String,
    val sessionId: String,
    val ok: Boolean,
    val error: String? = null,
) : OpenInvMessage

object OpenInvProtocol {
    const val REQUEST_CHANNEL = "misplease:openinv:request"
    const val RESPONSE_CHANNEL = "misplease:openinv:response"
    const val PRESENCE_CHANNEL = "misplease:presence"
    const val PRESENCE_KEY = "misplease:presence:proxy"

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }
}
