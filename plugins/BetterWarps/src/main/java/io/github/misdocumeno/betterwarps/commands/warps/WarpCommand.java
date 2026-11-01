package io.github.misdocumeno.betterwarps.commands.warps;

import io.github.misdocumeno.betterwarps.BetterWarps;
import io.github.misdocumeno.betterwarps.types.Warp;
import org.apache.commons.lang.StringUtils;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class WarpCommand implements CommandExecutor {

    public static String messageWarpsList;

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!(sender instanceof Player player))
            return false;
        if (args.length == 0) {
            if (!BetterWarps.warps.isEmpty()) {
                String warps = "§6"
                        + StringUtils.join(BetterWarps.warps.stream().map(warp -> warp.rawName).toArray(), "§2, §6");
                player.sendMessage(messageWarpsList.replace("{warps}", warps));
            }
            BetterWarps.warpsInventoryHandler.openInventory(player, 0);
        } else {
            String warpName = String.join(" ", args);
            for (Warp warp : BetterWarps.warps) {
                if (warp.rawName.equalsIgnoreCase(StringUtils.strip(warpName, "\""))) {
                    BetterWarps.teleportPlayer(player, warp);
                    return true;
                }
            }
            BetterWarps.warpDoesNotExists(player, warpName);
        }
        return true;
    }
}
