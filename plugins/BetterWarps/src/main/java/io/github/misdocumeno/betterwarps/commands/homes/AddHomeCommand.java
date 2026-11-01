package io.github.misdocumeno.betterwarps.commands.homes;

import io.github.misdocumeno.betterwarps.BetterWarps;
import io.github.misdocumeno.betterwarps.commands.warps.AddWarpCommand;
import io.github.misdocumeno.betterwarps.WorldEditWarner;
import io.github.misdocumeno.betterwarps.database.Database;
import io.github.misdocumeno.betterwarps.types.Warp;
import org.apache.commons.lang.StringUtils;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class AddHomeCommand implements CommandExecutor {

    public static String messageNoNameHome;
    public static String messageAlreadyExistsHome;
    public static String homeDefaultDescription;
    public static String messageHomeAdded;
    public static List<String> homeDefaultItems;

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("[Better Warps] Only a real player can use this command.");
            return true;
        }

        String[] parsedArgs = BetterWarps.parseArgs(args);

        // get the name
        if (parsedArgs.length < 1) {
            player.sendMessage(messageNoNameHome);
            return true;
        }

        String name = parsedArgs[0].replace("&", "§");

        for (Warp home : BetterWarps.homes.get(player)) {
            if (home.rawName.equalsIgnoreCase(name.replaceAll("§.?", ""))) {
                player.sendMessage(messageAlreadyExistsHome);
                return true;
            }
        }

        // get the description
        String description = homeDefaultDescription;

        if (parsedArgs.length >= 2)
            description = parsedArgs[1];

        // get the material
        Material material = Material.getMaterial(getNextHomeDefaultItem(player));

        if (parsedArgs.length >= 3)
            material = Material.getMaterial(parsedArgs[2].toUpperCase());

        if (material == null) {
            player.sendMessage(AddWarpCommand.messageMaterialDoesNotExists);
            return true;
        }

        if (!material.isItem()) {
            player.sendMessage(AddWarpCommand.messageItemCanNotBeUsed);
            return true;
        }

        // get the enchanted effect
        int enchanted = 0;

        if (parsedArgs.length >= 4) {
            if (!StringUtils.isNumeric(parsedArgs[3])) {
                player.sendMessage(AddWarpCommand.messageEnchantmentNaN);
                return true;
            }

            enchanted = Integer.parseInt(parsedArgs[3]);

            if (!(enchanted == 0 || enchanted == 1)) {
                player.sendMessage(AddWarpCommand.messageEnchantmentNotBoolean);
                return true;
            }
        }

        Warp warp = new Warp("home", name, material, enchanted, description, player.getLocation(),
            System.getenv("SERVER_ID"));
        BetterWarps.homes.get(player).add(warp);
        Database.addHome(player, warp);
        WorldEditWarner.addWarp(warp, player);
        BetterWarps.initializePlayerHomesInventory(player);
        player.sendMessage(messageHomeAdded);
        return true;
    }

    private String getNextHomeDefaultItem(Player player) {
        // get all items in homes as strings
        List<String> items = BetterWarps.homes.get(player).stream().map(home -> home.material.toString())
                .collect(Collectors.toList());
        Collections.reverse(items);

        for (String item : items) {
            if (homeDefaultItems.contains(item))
                return homeDefaultItems.get((homeDefaultItems.indexOf(item) + 1) % homeDefaultItems.size());
        }

        return homeDefaultItems.get(0);
    }
}
