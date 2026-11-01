package io.github.misdocumeno.betterwarps.commands.others;

import io.github.misdocumeno.betterwarps.BetterWarps;
import io.github.misdocumeno.betterwarps.WorldEditWarner;
import io.github.misdocumeno.betterwarps.database.Database;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ReloadCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        BetterWarps.loadConfig();

        Database.getWarps();
        Database.getSpawn();
        Database.getAllHomes();
        BetterWarps.initializeWarpsInventory();

        for (Player player : Bukkit.getOnlinePlayers()) {
            BetterWarps.homes.remove(player);
            Database.getHomes(player);
            BetterWarps.initializePlayerHomesInventory(player);
        }


        WorldEditWarner.clear();
        BetterWarps.warps.forEach(WorldEditWarner::addWarp);
        BetterWarps.allHomes.forEach((pair) -> WorldEditWarner.addWarp(pair.warp(), pair.player()));

        if (sender instanceof Player player)
            player.sendMessage("[Better Warps] config and database reloaded.");
        else
            System.out.println("[Better Warps] config and database reloaded.");

        return true;
    }
}
