package io.github.misdocumeno.infra.paper

import io.github.misdocumeno.domain.model.GlobalLocation
import org.bukkit.entity.Player

val Player.globalLocation: GlobalLocation
    get() = GlobalLocation(
        System.getenv("SERVER_ID"),
        world = this.location.world.name,
        x = this.location.x,
        y = this.location.y,
        z = this.location.z,
        yaw = this.location.yaw,
        pitch = this.location.pitch,
    )