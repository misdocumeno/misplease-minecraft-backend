package io.github.misdocumeno.betterwarps.events;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import io.github.misdocumeno.betterwarps.WarningExtent;
import org.bukkit.Bukkit;

public class WorldEditEvents {
    @Subscribe
    public void onEditSessionEvent(EditSessionEvent event) {
        Actor actor = event.getActor();

        if (
                actor == null || event.getStage() != EditSession.Stage.BEFORE_HISTORY
                        || !actor.isPlayer() || !Bukkit.getPlayer(actor.getName()).isOp()
        ) {
            return;
        }

        event.setExtent(new WarningExtent(event.getExtent(), Bukkit.getPlayer(actor.getName())));
    }
}
