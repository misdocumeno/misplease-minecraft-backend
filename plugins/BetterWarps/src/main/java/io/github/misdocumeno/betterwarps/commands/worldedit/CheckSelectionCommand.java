package io.github.misdocumeno.betterwarps.commands.worldedit;

import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.SessionManager;
import io.github.misdocumeno.betterwarps.BetterWarps;
import io.github.misdocumeno.betterwarps.WarningExtent;
import io.github.misdocumeno.betterwarps.WorldEditWarner;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class CheckSelectionCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only a real player can use this command.");
            return true;
        }

        Bukkit.getScheduler().runTaskAsynchronously(BetterWarps.instance, () -> {

            var actor = BukkitAdapter.adapt(player);
            SessionManager manager = WorldEdit.getInstance().getSessionManager();
            LocalSession session = manager.get(actor);
            var world = session.getSelectionWorld();

            if (world == null) {
                player.sendMessage("You must select a region first.");
                return;
            }

            Region selection = session.getSelection(world);
            int found = 0;

            for (BlockVector3 position : selection) {
                for (var warpOrHome : WorldEditWarner.getWarpsOrHomesAt(player.getWorld(), position)) {
                    String message = String.format(
                            "There is a %s at %s called '%s'",
                            warpOrHome.owner() != null ? warpOrHome.owner().getName() + "'s home" : "warp",
                            position,
                            warpOrHome.warp().name
                    );
                    player.spigot().sendMessage(WarningExtent.getTextComponent(player, warpOrHome, message));
                    found++;
                }
            }

            if (found == 0) {
                player.sendMessage("There is no homes or warps in your current selection.");
            } else {
                player.sendMessage("There are " + found + " homes or warps in your current selection.");
            }
        });

        return true;
    }
}
