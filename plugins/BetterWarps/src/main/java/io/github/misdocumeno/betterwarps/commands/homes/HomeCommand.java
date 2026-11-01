package io.github.misdocumeno.betterwarps.commands.homes;

import io.github.misdocumeno.betterwarps.BetterWarps;
import io.github.misdocumeno.betterwarps.commands.warps.WarpCommand;
import io.github.misdocumeno.betterwarps.types.Warp;
import org.apache.commons.lang.StringUtils;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class HomeCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player))
            return false;
        if (args.length == 0) {
            if (!BetterWarps.homes.get(player).isEmpty()) {
                String warps = "§6" + StringUtils
                        .join(BetterWarps.homes.get(player).stream().map(warp -> warp.rawName).toArray(), "§2, §6");
                player.sendMessage(WarpCommand.messageWarpsList.replace("{warps}", warps));
            }
            BetterWarps.homesInventoryHandler.get(player).openInventory(player, 0);
        } else {
            String homeName = String.join(" ", args);
            for (Warp home : BetterWarps.homes.get(player)) {
                if (home.rawName.equalsIgnoreCase(StringUtils.strip(homeName, "\""))) {
                    BetterWarps.teleportPlayer(player, home);
                    return true;
                }
            }
            BetterWarps.homeDoesNotExists(player, homeName);
        }
        return true;
    }
}
