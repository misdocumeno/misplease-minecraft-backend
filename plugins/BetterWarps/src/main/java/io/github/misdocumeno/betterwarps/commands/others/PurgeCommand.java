package io.github.misdocumeno.betterwarps.commands.others;

import io.github.misdocumeno.betterwarps.database.Database;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class PurgeCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        int total = Database.deleteInvalid();

        if (sender instanceof Player player)
            player.sendMessage("[Better Warps] Removed " + total + " invalid entries.");
        else
            System.out.println("[Better Warps] Removed " + total + " invalid entries.");

        return true;
    }
}
