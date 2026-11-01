package io.github.misdocumeno.betterwarps.commands.homes;

import io.github.misdocumeno.betterwarps.BetterWarps;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class HomeCommandCompletion implements TabCompleter {

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String arg, String[] args) {
        if (!(sender instanceof Player player))
            return new ArrayList<>();

        String[] parsedArgs = BetterWarps.parseArgs(args);

        if (parsedArgs.length == 1 && args.length == 1) {
            return BetterWarps.homes.get(player).stream()
                    // get all home names without color
                    .map(home -> home.rawName)
                    // filter all home names that start with the command argument
                    .filter(home -> home.toLowerCase().startsWith(parsedArgs[0].toLowerCase()))
                    // add quotes to home names with spaces
                    .map(home -> home.contains(" ") ? "\"" + home + "\"" : home)
                    // convert it back to a list
                    .collect(Collectors.toList());
        } else
            return new ArrayList<>();
    }
}
