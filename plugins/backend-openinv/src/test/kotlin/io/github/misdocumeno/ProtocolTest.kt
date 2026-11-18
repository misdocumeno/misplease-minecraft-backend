package io.github.misdocumeno

import io.github.misdocumeno.domain.protocol.ApplyMessage
import io.github.misdocumeno.domain.protocol.OpenInvMessage
import io.github.misdocumeno.domain.protocol.OpenInvProtocol
import io.github.misdocumeno.domain.protocol.RequestStateMessage
import io.github.misdocumeno.domain.protocol.StateMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wire format is the only contract between two independently deployed
 * backends: a silent field drop would look like "the other server lost my
 * items", so encode and decode are pinned here.
 */
class ProtocolTest {

    private val json = OpenInvProtocol.json

    @Test
    fun `requests round trip and keep their discriminator`() {
        val request = RequestStateMessage(
            server = "tech",
            sessionId = "abc",
            viewerName = "Mis",
            viewerUuid = "00000000-0000-0000-0000-000000000001",
            targetName = "Friend",
            targetUuid = "00000000-0000-0000-0000-000000000002",
            kind = "INVENTORY",
            group = "survival",
        )
        val encoded = json.encodeToString(OpenInvMessage.serializer(), request)
        assertTrue(encoded.contains("\"type\":\"request-state\""), encoded)
        assertEquals(request, json.decodeFromString(OpenInvMessage.serializer(), encoded))
    }

    @Test
    fun `state keeps empty slots and version`() {
        val state = StateMessage(
            server = "tech",
            sessionId = "abc",
            ok = true,
            group = "survival",
            online = false,
            mcVersion = "26.3",
            items = listOf("aXRlbQ==", null, ""),
        )
        val decoded = json.decodeFromString(
            OpenInvMessage.serializer(),
            json.encodeToString(OpenInvMessage.serializer(), state),
        ) as StateMessage
        assertEquals(state, decoded)
        assertNull(decoded.items[1])
    }

    @Test
    fun `apply maps slot numbers to payloads`() {
        val apply = ApplyMessage(
            server = "tech",
            sessionId = "abc",
            viewerName = "Mis",
            targetName = "Friend",
            targetUuid = "00000000-0000-0000-0000-000000000002",
            kind = "INVENTORY",
            group = "survival",
            mcVersion = "26.3",
            changed = mapOf(0 to "aQ==", 40 to null),
        )
        val decoded = json.decodeFromString(
            OpenInvMessage.serializer(),
            json.encodeToString(OpenInvMessage.serializer(), apply),
        ) as ApplyMessage
        assertEquals(mapOf(0 to "aQ==", 40 to null), decoded.changed)
    }

    @Test
    fun `unknown messages are ignored instead of crashing`() {
        // A future backend may add fields or message types; decoding must not
        // throw on the deployed older side.
        val decoded = json.decodeFromString(
            OpenInvMessage.serializer(),
            """{"type":"state","server":"tech","sessionId":"x","ok":true,"extra":{"n":1}}""",
        ) as StateMessage
        assertEquals("tech", decoded.server)
    }
}
