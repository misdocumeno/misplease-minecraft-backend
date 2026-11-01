package io.github.misdocumeno.infra.velocity

import com.velocitypowered.api.proxy.ProxyServer
import io.github.misdocumeno.domain.UsernameRepository
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

class VelocityUsernameRepository(private val proxy: ProxyServer) : UsernameRepository {

    override fun getUsername(uuid: Uuid): String? =
        proxy.getPlayer(uuid.toJavaUuid()).orElse(null)?.username
}