package io.github.misdocumeno.betterwarps.commands.warps;

import io.github.misdocumeno.betterwarps.BetterWarps;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class WarpCommandCompletion implements TabCompleter {

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String arg, String[] args) {
        if (!(sender instanceof Player))
            return new ArrayList<>();

        String[] parsedArgs = BetterWarps.parseArgs(args);

        if (parsedArgs.length == 1 && args.length == 1) {
            return BetterWarps.warps.stream()
                    // get all warp names without color
                    .map(warp -> warp.rawName)
                    // filter all warp names that start with the command argument
                    .filter(warp -> warp.toLowerCase().startsWith(parsedArgs[0].toLowerCase()))
                    // add quotes to warp names with spaces
                    .map(warp -> warp.contains(" ") ? "\"" + warp + "\"" : warp)
                    // convert it back to a list
                    .collect(Collectors.toList());
        } else
            return new ArrayList<>();
    }
}
