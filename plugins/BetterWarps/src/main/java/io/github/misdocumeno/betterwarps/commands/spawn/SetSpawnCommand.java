package io.github.misdocumeno.betterwarps.commands.spawn;

import io.github.misdocumeno.betterwarps.BetterWarps;
import io.github.misdocumeno.betterwarps.database.Database;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class SetSpawnCommand implements CommandExecutor {

    public static String messageSpawnSet;

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        Location spawn = player.getLocation();
        Database.setSpawn(spawn, System.getenv("SERVER_ID"));
        BetterWarps.spawn = spawn;
        BetterWarps.spawnServer = System.getenv("SERVER_ID");
        player.sendMessage(messageSpawnSet);
        return true;
    }
}
