package io.github.misdocumeno.betterwarps.commands.warps;

import io.github.misdocumeno.betterwarps.BetterWarps;
import io.github.misdocumeno.betterwarps.WorldEditWarner;
import io.github.misdocumeno.betterwarps.database.Database;
import io.github.misdocumeno.betterwarps.types.Warp;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class DelWarpCommand implements CommandExecutor {

    public static String messageNoNameDelWarp;
    public static String messageWarpDeleted;

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("[Better Warps] Only a real player can use this command.");
            return true;
        }

        String[] parsedArgs = BetterWarps.parseArgs(args);

        // get the name
        if (parsedArgs.length < 1) {
            player.sendMessage(messageNoNameDelWarp);
            return true;
        }

        String name = parsedArgs[0];

        for (int i = 0 ; i < BetterWarps.warps.size(); i++) {
            if (BetterWarps.warps.get(i).rawName.equalsIgnoreCase(name.replaceAll("§.?", ""))) {
                Warp warp = BetterWarps.warps.remove(i);
                Database.delWarp(warp);
                WorldEditWarner.remove(warp);
                BetterWarps.initializeWarpsInventory();
                player.sendMessage(messageWarpDeleted);
                return true;
            }
        }

        player.sendMessage(BetterWarps.messageWarpDoesNotExistsChat);

        return true;
    }
}
