package io.github.misdocumeno.betterwarps.commands.homes;

import io.github.misdocumeno.betterwarps.BetterWarps;
import io.github.misdocumeno.betterwarps.WorldEditWarner;
import io.github.misdocumeno.betterwarps.database.Database;
import io.github.misdocumeno.betterwarps.types.Warp;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class DelHomeCommand implements CommandExecutor {

    public static String messageNoNameDelHome;
    public static String messageHomeDeleted;

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("[Better Warps] Only a real player can use this command.");
            return true;
        }

        String[] parsedArgs = BetterWarps.parseArgs(args);

        // get the name
        if (parsedArgs.length < 1) {
            player.sendMessage(messageNoNameDelHome);
            return true;
        }

        String name = parsedArgs[0];

        for (int i = 0; i < BetterWarps.homes.get(player).size(); i++) {
            if (BetterWarps.homes.get(player).get(i).rawName.equalsIgnoreCase(name.replaceAll("§.?", ""))) {
                Warp home = BetterWarps.homes.get(player).remove(i);
                Database.delHome(player, home);
                WorldEditWarner.remove(home);
                BetterWarps.initializePlayerHomesInventory(player);
                player.sendMessage(messageHomeDeleted);
                return true;
            }
        }

        player.sendMessage(BetterWarps.messageHomeDoesNotExistsChat);

        return true;
    }
}
