package io.github.misdocumeno.betterwarps.commands.warps;

import io.github.misdocumeno.betterwarps.BetterWarps;
import io.github.misdocumeno.betterwarps.WorldEditWarner;
import io.github.misdocumeno.betterwarps.types.Warp;
import io.github.misdocumeno.betterwarps.database.Database;
import org.apache.commons.lang.StringUtils;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class AddWarpCommand implements CommandExecutor {

    public static String messageNoName;
    public static String messageAlreadyExists;
    public static String messageNoMaterial;
    public static String messageMaterialDoesNotExists;
    public static String messageItemCanNotBeUsed;
    public static String messageNoEnchantment;
    public static String messageEnchantmentNaN;
    public static String messageEnchantmentNotBoolean;
    public static String messageNoDescription;
    public static String messageWarpAdded;

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("[Better Warps] Only a real player can use this command.");
            return true;
        }

        String[] parsedArgs = BetterWarps.parseArgs(args);

        // get the name
        if (parsedArgs.length < 1) {
            player.sendMessage(messageNoName);
            return true;
        }

        String name = parsedArgs[0].replace("&", "§");

        for (Warp warp : BetterWarps.warps) {
            if (warp.rawName.equalsIgnoreCase(name.replaceAll("§.?", ""))) {
                player.sendMessage(messageAlreadyExists);
                return true;
            }
        }

        // get the description
        if (parsedArgs.length < 2) {
            player.sendMessage(messageNoDescription);
            return true;
        }

        String description = parsedArgs[1];

        // get the material
        if (parsedArgs.length < 3) {
            player.sendMessage(messageNoMaterial);
            return true;
        }

        Material material = Material.getMaterial(parsedArgs[2].toUpperCase());

        if (material == null) {
            player.sendMessage(messageMaterialDoesNotExists);
            return true;
        }

        if (!material.isItem()) {
            player.sendMessage(messageItemCanNotBeUsed);
            return true;
        }

        // get the enchanted effect
        if (parsedArgs.length < 4) {
            player.sendMessage(messageNoEnchantment);
            return true;
        }

        if (!StringUtils.isNumeric(parsedArgs[3])) {
            player.sendMessage(messageEnchantmentNaN);
            return true;
        }

        int enchanted = Integer.parseInt(parsedArgs[3]);

        if (!(enchanted == 0 || enchanted == 1)) {
            player.sendMessage(messageEnchantmentNotBoolean);
            return true;
        }

        Warp warp =
            new Warp("warp", name, material, enchanted, description, player.getLocation(), System.getenv("SERVER_ID"));
        BetterWarps.warps.add(warp);
        Database.addWarp(warp);
        WorldEditWarner.addWarp(warp);
        BetterWarps.initializeWarpsInventory();
        player.sendMessage(messageWarpAdded);
        return true;
    }
}
