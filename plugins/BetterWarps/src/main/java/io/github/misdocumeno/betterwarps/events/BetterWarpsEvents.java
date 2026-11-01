package io.github.misdocumeno.betterwarps.events;

import io.github.misdocumeno.betterwarps.BetterWarps;
import io.github.misdocumeno.betterwarps.database.Database;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class BetterWarpsEvents implements Listener {

    @EventHandler
    public static void onPlayerJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        Database.getHomes(player);
        BetterWarps.initializePlayerHomesInventory(player);
    }

    @EventHandler
    public static void onPlayerQuit(PlayerQuitEvent e) {
        BetterWarps.homes.remove(e.getPlayer());
    }
}
