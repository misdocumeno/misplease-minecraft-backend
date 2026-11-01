package io.github.misdocumeno.infra.paper

import io.github.misdocumeno.application.LocationHandler
import io.github.misdocumeno.domain.model.GlobalLocation
import io.github.misdocumeno.domain.model.LocationRequest
import io.github.misdocumeno.domain.model.LocationResponse
import org.bukkit.Bukkit

class PaperLocationHandler : LocationHandler {

    override fun handle(request: LocationRequest): LocationResponse? {
        val player = Bukkit.getPlayer(request.username) ?: return null
        return LocationResponse(
            request.requestId,
            GlobalLocation(
                server = System.getenv("SERVER_ID"),
                world = player.location.world.name,
                x = player.location.x,
                y = player.location.y,
                z = player.location.z,
                yaw = player.location.yaw,
                pitch = player.location.pitch,
            )
        )

    }
}