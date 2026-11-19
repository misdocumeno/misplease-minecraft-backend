package io.github.misdocumeno.infra.limbo

import com.velocitypowered.api.network.ProtocolVersion
import com.velocitypowered.proxy.connection.MinecraftSessionHandler
import com.velocitypowered.proxy.protocol.MinecraftPacket
import com.velocitypowered.proxy.protocol.ProtocolUtils
import io.netty.buffer.ByteBuf

/**
 * Clientbound "Set Entity Metadata" carrying only the local player's displayed
 * skin parts.
 *
 * Paper sends a player's own metadata right after Join Game. The limbo does not,
 * and 1.21.11+ clients then keep the defaults - which means the outer skin layer
 * (jacket, hood, hat) is missing while the player waits in the login limbo.
 * Same fix as Elytrium/LimboAPI#277, scoped to the one entry we need.
 *
 * Wire format, one entry: `[varint entity id][index byte][varint serializer][value]`
 * terminated by `0xFF`. Entity id 1 is what LimboAPI puts in the limbo's
 * `JoinGamePacket`; index 16 / serializer 0 (byte) / 0x7F (all layers) is the
 * player metadata layout of 1.21.11 and 26.3 (see the protocol wiki, "Player ...
 * extends Avatar").
 */
class SelfSkinPacket : MinecraftPacket {

    override fun encode(buf: ByteBuf, direction: ProtocolUtils.Direction, version: ProtocolVersion) {
        ProtocolUtils.writeVarInt(buf, SELF_ENTITY_ID)
        buf.writeByte(SKIN_PARTS_INDEX)
        ProtocolUtils.writeVarInt(buf, BYTE_SERIALIZER)
        buf.writeByte(SKIN_PARTS_ALL_LAYERS)
        buf.writeByte(0xFF) // end of the metadata array
    }

    override fun decode(buf: ByteBuf, direction: ProtocolUtils.Direction, version: ProtocolVersion) {
        throw UnsupportedOperationException("server-to-client only")
    }

    override fun handle(handler: MinecraftSessionHandler): Boolean {
        throw UnsupportedOperationException("server-to-client only")
    }

    private companion object {
        const val SELF_ENTITY_ID = 1
        const val SKIN_PARTS_INDEX = 16
        const val BYTE_SERIALIZER = 0

        /**
         * Cape, jacket, both sleeves, both pant legs and hat. We do not know the
         * client's own choices here, and showing the whole skin is the friendlier
         * default; the backend sends the real value as soon as they are connected.
         */
        const val SKIN_PARTS_ALL_LAYERS = 0x7F
    }
}
