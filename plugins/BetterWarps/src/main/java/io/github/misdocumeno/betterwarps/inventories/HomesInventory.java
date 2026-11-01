package io.github.misdocumeno.betterwarps.inventories;

import io.github.misdocumeno.betterwarps.BetterWarps;
import io.github.misdocumeno.betterwarps.types.Warp;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HomesInventory implements Listener {

    public static String homesInvGUITitle;
    public static String homesInvGUITitleSinglePage;
    public static boolean homesShowCoordinates;

    private final Inventory[] inventories;

    public HomesInventory(Player player) {
        int inventorySize = 9 * (int) Math.ceil(BetterWarps.homes.get(player).size() / 9.0);
        int pages = (int) Math.ceil(inventorySize / 54.0);

        if (pages == 0) {
            inventories = new Inventory[1];
            inventories[0] = Bukkit.createInventory(null, 9, homesInvGUITitleSinglePage);
            return;
        }

        inventories = new Inventory[pages];

        int index = 0;

        // iterate over pages
        for (int page = 0; page < pages; page++) {
            inventories[page] = Bukkit.createInventory(null, Math.min(inventorySize, 54),
                    pages > 1 ? homesInvGUITitle.replace("{page}", Integer.toString(page + 1))
                            : homesInvGUITitleSinglePage);
            inventorySize -= 54;
            // iterate over each slot of the page
            for (int slot = 0; slot < 54; slot++) {
                // previous page button
                if (page > 0 && slot == 0) {
                    ItemStack item = new ItemStack(Material.WHITE_BANNER, 1);
                    BannerMeta meta = (BannerMeta) item.getItemMeta();
                    meta.setDisplayName(WarpsInventory.prevPageButtonText);
                    meta.setPatterns(Arrays.asList(new Pattern(DyeColor.RED, PatternType.RHOMBUS_MIDDLE),
                            new Pattern(DyeColor.WHITE, PatternType.HALF_VERTICAL_MIRROR)));
                    meta.addItemFlags(ItemFlag.HIDE_POTION_EFFECTS); // for some reason ???
                    item.setItemMeta(meta);
                    inventories[page].addItem(item);
                    // next page button
                } else if (pages > 1 && slot == 53) {
                    ItemStack item = new ItemStack(Material.WHITE_BANNER, 1);
                    BannerMeta meta = (BannerMeta) item.getItemMeta();
                    meta.setDisplayName(WarpsInventory.nextPageButtonText);
                    meta.setPatterns(Arrays.asList(new Pattern(DyeColor.RED, PatternType.RHOMBUS_MIDDLE),
                            new Pattern(DyeColor.WHITE, PatternType.HALF_VERTICAL)));
                    meta.addItemFlags(ItemFlag.HIDE_POTION_EFFECTS); // for some reason ???
                    item.setItemMeta(meta);
                    inventories[page].addItem(item);
                } else {
                    ItemStack item = new ItemStack(BetterWarps.homes.get(player).get(index).material, 1);
                    ItemMeta meta = item.getItemMeta();
                    meta.setDisplayName(BetterWarps.homes.get(player).get(index).name);
                    if (!BetterWarps.homes.get(player).get(index).description.isEmpty())
                        meta.setLore(
                                Arrays.asList(BetterWarps.homes.get(player).get(index).description.split("\\\\n")));
                    // show location
                    if (homesShowCoordinates) {
                        List<String> lore = meta.getLore();
                        if (lore == null)
                            lore = new ArrayList<>();
                        Location location = BetterWarps.homes.get(player).get(index).location;
                        String worldName = location.getWorld().getName();
                        if (BetterWarps.worldAliases.containsKey(worldName))
                            worldName = BetterWarps.worldAliases.get(worldName);
                        lore.addAll(Arrays.asList(WarpsInventory.coordinatesLore
                                .replace("{world}", worldName)
                                .replace("{x}", Integer.toString(location.getBlockX()))
                                .replace("{y}", Integer.toString(location.getBlockY()))
                                .replace("{z}", Integer.toString(location.getBlockZ()))
                                .split("\\\\n")));
                        meta.setLore(lore);
                    }
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                    meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                    meta.addItemFlags(ItemFlag.HIDE_DYE);
                    if (BetterWarps.homes.get(player).get(index).enchanted == 1)
                        meta.addEnchant(Enchantment.ARROW_DAMAGE, 1, true);
                    item.setItemMeta(meta);
                    inventories[page].addItem(item);
                    if (++index == BetterWarps.homes.get(player).size())
                        break;
                }
            }
        }
    }

    public void openInventory(Player player, int page) {
        player.openInventory(inventories[page]);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        // get the page
        int page = Arrays.asList(inventories).indexOf(e.getInventory());
        // check if the player is inside the warp GUI inventory
        if (page == -1)
            return;
        // check if the click is inside of the GUI inventory
        if (e.getRawSlot() >= inventories[page].getSize())
            return;
        e.setCancelled(true);
        // check if it's a valid item
        ItemStack clickedItem = e.getCurrentItem();
        if (clickedItem == null || clickedItem.getType().isAir())
            return;
        // get the player
        Player player = (Player) e.getWhoClicked();
        // get the warp in this item slot
        if (e.getRawSlot() == 0 && page != 0) {
            // go to the previous page
            openInventory(player, page - 1);
            return;
        } else if (e.getRawSlot() == 53 && page + 1 < inventories.length) {
            // go to the next page
            openInventory(player, page + 1);
            return;
        }
        // go to the specified warp
        int slot = e.getRawSlot() + (54 * page) - (2 * page);
        Warp home = BetterWarps.homes.get(player).get(slot);
        player.closeInventory();
        BetterWarps.teleportPlayer(player, home);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {
        int page = Arrays.asList(inventories).indexOf(e.getInventory());
        if (page != -1) {
            int size = inventories[page].getSize();
            for (int slot : e.getRawSlots()) {
                if (slot < size) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
    }
}
