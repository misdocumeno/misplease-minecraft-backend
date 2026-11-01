package io.github.misdocumeno.infra.paper

import io.github.misdocumeno.domain.UsernameRepository
import org.bukkit.Bukkit
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

class PaperUsernameRepository : UsernameRepository {

    override fun getUsername(uuid: Uuid): String? {
        return Bukkit.getPlayer(uuid.toJavaUuid())?.name
    }
}