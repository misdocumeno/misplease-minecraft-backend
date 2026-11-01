package io.github.misdocumeno.betterwarps.commands.homes;

import io.github.misdocumeno.betterwarps.BetterWarps;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AddHomeCommandCompletion implements TabCompleter {

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String arg, String[] args) {
        String[] parsedArgs = BetterWarps.parseArgs(args);
        if (parsedArgs[parsedArgs.length - 1].length() == 0) {
            return switch (parsedArgs.length) {
                case 1 -> Collections.singletonList("<name>");
                case 2 -> Collections.singletonList("<description>");
                case 3 -> Collections.singletonList("<material>");
                case 4 -> Collections.singletonList("<enchanted> (1|0)");
                default -> new ArrayList<>();
            };
        }
        return new ArrayList<>();
    }
}
