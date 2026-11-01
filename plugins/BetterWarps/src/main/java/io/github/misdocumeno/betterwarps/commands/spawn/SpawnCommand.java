package io.github.misdocumeno.betterwarps.commands.spawn;

import io.github.misdocumeno.betterwarps.BetterWarps;
import io.github.misdocumeno.betterwarps.types.Warp;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class SpawnCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (sender instanceof Player player) {
            Warp spawn = new Warp("spawn", "spawn", null, 0, "", BetterWarps.spawn, BetterWarps.spawnServer);
            BetterWarps.teleportPlayer(player, spawn);
        }
        return true;
    }
}
