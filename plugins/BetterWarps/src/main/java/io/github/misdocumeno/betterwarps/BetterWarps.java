package io.github.misdocumeno.betterwarps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sk89q.worldedit.WorldEdit;
import io.github.misdocumeno.betterwarps.commands.homes.*;
import io.github.misdocumeno.betterwarps.commands.others.PurgeCommand;
import io.github.misdocumeno.betterwarps.commands.others.ReloadCommand;
import io.github.misdocumeno.betterwarps.commands.spawn.SetSpawnCommand;
import io.github.misdocumeno.betterwarps.commands.spawn.SpawnCommand;
import io.github.misdocumeno.betterwarps.commands.warps.*;
import io.github.misdocumeno.betterwarps.commands.worldedit.CheckSelectionCommand;
import io.github.misdocumeno.betterwarps.database.Database;
import io.github.misdocumeno.betterwarps.domain.*;
import io.github.misdocumeno.betterwarps.events.BetterWarpsEvents;
import io.github.misdocumeno.betterwarps.events.WorldEditEvents;
import io.github.misdocumeno.betterwarps.inventories.HomesInventory;
import io.github.misdocumeno.betterwarps.inventories.WarpsInventory;
import io.github.misdocumeno.betterwarps.types.HomePair;
import io.github.misdocumeno.betterwarps.types.Warp;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.apache.commons.lang.StringUtils;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.MemorySection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import redis.clients.jedis.Jedis;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class BetterWarps extends JavaPlugin {

    // all existing warps
    public static List<Warp> warps = new ArrayList<>();
    // handles the warps GUI
    public static WarpsInventory warpsInventoryHandler;
    // all existing homes for all players
    public static Map<Player, List<Warp>> homes = new HashMap<>();
    // handles the homes GUI for each player
    public static Map<Player, HomesInventory> homesInventoryHandler = new HashMap<>();
    // the current teleportation in progress for each player
    public static Map<Player, BukkitTask> teleportations = new HashMap<>();
    // world names that will be displayed on the inventories
    public static Map<String, String> worldAliases = new HashMap<>();
    // the server spawn
    public static Location spawn;
    public static String spawnServer;

    public static List<HomePair> allHomes = new ArrayList<>();

    private static String messageTeleportingNoDelay;
    private static String messageTeleportingIn;
    private static String messageTeleportTitle;
    private static String messageTeleportSubTitle;
    private static String messageTeleported;
    private static String messageTeleportedTitle;
    private static String messageTeleportedSubTitle;
    public static String messageWarpDoesNotExistsChat;
    private static String messageWarpDoesNotExistsTitle;
    private static String messageWarpDoesNotExistsSubTitle;
    private static String messageTeleportCancelled;
    private static String messageTeleportCancelledTitle;
    private static String messageTeleportCancelledSubTitle;
    public static String messageHomeDoesNotExistsChat;
    private static String messageHomeDoesNotExistsTitle;
    private static String messageHomeDoesNotExistsSubTitle;
    private static int teleportDelay;

    public static Plugin instance;

    private static RedisManager redis;
    private static ObjectMapper mapper;

    @Override
    public void onEnable() {
        redis = new RedisManager(System.getenv("REDIS_URL"));
        mapper = new ObjectMapper();

        instance = this;
        // register commands
        getCommand("warp").setExecutor(new WarpCommand());
        getCommand("warp").setTabCompleter(new WarpCommandCompletion());
        getCommand("addwarp").setExecutor(new AddWarpCommand());
        getCommand("addwarp").setTabCompleter(new AddWarpCommandCompletion());
        getCommand("delwarp").setExecutor(new DelWarpCommand());
        getCommand("delwarp").setTabCompleter(new WarpCommandCompletion());
        getCommand("home").setExecutor(new HomeCommand());
        getCommand("home").setTabCompleter(new HomeCommandCompletion());
        getCommand("sethome").setExecutor(new AddHomeCommand());
        getCommand("sethome").setTabCompleter(new AddHomeCommandCompletion());
        getCommand("delhome").setExecutor(new DelHomeCommand());
        getCommand("delhome").setTabCompleter(new HomeCommandCompletion());
        getCommand("spawn").setExecutor(new SpawnCommand());
        getCommand("setspawn").setExecutor(new SetSpawnCommand());
        getCommand("purgewarps").setExecutor(new PurgeCommand());
        getCommand("warpsreload").setExecutor(new ReloadCommand());
        getCommand("checkselection").setExecutor(new CheckSelectionCommand());
        // register events
        getServer().getPluginManager().registerEvents(new BetterWarpsEvents(), this);
        // read the config
        loadConfig();
        // read database
//        Database.setUrl(getDataFolder());
        Database.setPostgres(System.getenv("DATABASE_URL"));
        new BukkitRunnable() {
            @Override
            public void run() {
                // read warps from the database after all worlds (multiverse) are loaded
                Database.getWarps();
                Database.getSpawn();
                Database.getAllHomes();
                warps.forEach(WorldEditWarner::addWarp);
                allHomes.forEach((pair) -> WorldEditWarner.addWarp(pair.warp(), pair.player()));
                initializeWarpsInventory();
            }
        }.runTask(this);

        WorldEdit.getInstance().getEventBus().register(new WorldEditEvents());
    }

    @SuppressWarnings("unchecked")
    public static void loadConfig() {
        instance.saveDefaultConfig();
        instance.reloadConfig();
        FileConfiguration config = instance.getConfig();
        // read messages
        teleportDelay = config.getInt("teleport-delay");
        messageTeleportingNoDelay = config.getString("teleporting-no-delay");
        messageTeleportingIn = config.getString("teleporting-in");
        messageTeleportTitle = config.getString("teleport-title");
        messageTeleportSubTitle = config.getString("teleport-subtitle");
        messageTeleported = config.getString("teleported");
        messageTeleportedTitle = config.getString("teleported-title");
        messageTeleportedSubTitle = config.getString("teleported-subtitle");
        messageWarpDoesNotExistsChat = config.getString("warp-does-not-exists-chat");
        messageWarpDoesNotExistsTitle = config.getString("warp-does-not-exists-title");
        messageWarpDoesNotExistsSubTitle = config.getString("warp-does-not-exists-subtitle");
        messageTeleportCancelled = config.getString("teleport-cancelled");
        messageTeleportCancelledTitle = config.getString("teleport-cancelled-title");
        messageTeleportCancelledSubTitle = config.getString("teleport-cancelled-subtitle");
        messageHomeDoesNotExistsChat = config.getString("home-does-not-exists-chat");
        messageHomeDoesNotExistsTitle = config.getString("home-does-not-exists-title");
        messageHomeDoesNotExistsSubTitle = config.getString("home-does-not-exists-subtitle");
        AddWarpCommand.messageNoName = config.getString("no-name-specified");
        AddWarpCommand.messageAlreadyExists = config.getString("warp-already-exists");
        AddWarpCommand.messageNoMaterial = config.getString("no-item-specified");
        AddWarpCommand.messageMaterialDoesNotExists = config.getString("material-does-not-exists");
        AddWarpCommand.messageItemCanNotBeUsed = config.getString("material-cant-be-used");
        AddWarpCommand.messageNoEnchantment = config.getString("no-enchantment-specified");
        AddWarpCommand.messageEnchantmentNaN = config.getString("enchantment-not-a-number");
        AddWarpCommand.messageEnchantmentNotBoolean = config.getString("enchantment-not-one-or-zero");
        AddWarpCommand.messageNoDescription = config.getString("no-description-specified");
        AddWarpCommand.messageWarpAdded = config.getString("warp-added");
        AddHomeCommand.messageNoNameHome = config.getString("no-name-specified-home");
        AddHomeCommand.messageAlreadyExistsHome = config.getString("home-already-exists");
        AddHomeCommand.homeDefaultDescription = config.getString("home-default-description");
        AddHomeCommand.messageHomeAdded = config.getString("home-added");
        AddHomeCommand.homeDefaultItems = (List<String>) config.getList("default-home-items");
        DelHomeCommand.messageNoNameDelHome = config.getString("no-name-specified-del-home");
        DelHomeCommand.messageHomeDeleted = config.getString("home-deleted");
        DelWarpCommand.messageNoNameDelWarp = config.getString("no-name-specified-del-warp");
        DelWarpCommand.messageWarpDeleted = config.getString("warp-deleted");
        SetSpawnCommand.messageSpawnSet = config.getString("spawn-set");
        WarpsInventory.nextPageButtonText = config.getString("next-page");
        WarpsInventory.prevPageButtonText = config.getString("previous-page");
        WarpsInventory.warpsInvGUITitle = config.getString("warps-gui-title");
        WarpsInventory.warpsInvGUITitleSinglePage = config.getString("warps-gui-title-single-page");
        WarpsInventory.warpsShowCoordinates = config.getBoolean("warps-show-coordinates");
        WarpsInventory.coordinatesLore = config.getString("coordinates-lore");
        HomesInventory.homesInvGUITitle = config.getString("homes-gui-title");
        HomesInventory.homesInvGUITitleSinglePage = config.getString("homes-gui-title-single-page");
        HomesInventory.homesShowCoordinates = config.getBoolean("homes-show-coordinates");
        WarpCommand.messageWarpsList = config.getString("warp-list");

        worldAliases = new HashMap<>();

        MemorySection section = (MemorySection) config.get("world-names");
        for (String key : section.getKeys(false)) {
            worldAliases.put(key, section.getString(key));
        }
    }

    public static void initializeWarpsInventory() {
        HandlerList.unregisterAll(warpsInventoryHandler);
        warpsInventoryHandler = new WarpsInventory();
        instance.getServer().getPluginManager().registerEvents(warpsInventoryHandler, instance);
    }

    public static void initializePlayerHomesInventory(Player player) {
        HomesInventory homeInventoryHandler = homesInventoryHandler.get(player);
        if (homeInventoryHandler != null) {
            HandlerList.unregisterAll(homeInventoryHandler);
        }
        homeInventoryHandler = new HomesInventory(player);
        homesInventoryHandler.put(player, homeInventoryHandler);
        instance.getServer().getPluginManager().registerEvents(homeInventoryHandler, instance);
    }

    public static void teleportPlayer(Player player, Warp destination) {
        int delay = getPlayerTeleportDelay(player);

        if (delay == 0) {

//            player.teleport(destination.location);
//            player.sendMessage(messageTeleportingNoDelay.replace("{destination}", destination.name));

            GlobalLocation location = new GlobalLocation(
                destination.server,
                destination.location.getWorld().getName(),
                destination.location.getX(),
                destination.location.getY(),
                destination.location.getZ(),
                destination.location.getYaw(),
                destination.location.getPitch()
            );
            Component component = LegacyComponentSerializer.legacySection()
                .deserialize(messageTeleportingNoDelay.replace("{destination}", destination.name));
            String chat = MiniMessage.miniMessage().serialize(component);
            TeleportFeedback feedback = new TeleportFeedback(chat, null, null);
            TeleportRequest request = new TeleportRequest(player.getName(), location, feedback);
            String message;
            try {
                message = mapper.writeValueAsString(request);
            } catch (Exception e) {
                System.err.println("Error serializing teleport request");
                return;
            }
            redis.publish("global_teleport:execute", message);

            if (destination.type.equals("home")) {
                Database.incrementHomeUsage(player, destination);
            } else if (destination.type.equals("warp")) {
                Database.incrementWarpUsage(player, destination);
            }

            return;
        }

        player.sendMessage(messageTeleportingIn.replace("{destination}", destination.name)
            .replace("{seconds}", Integer.toString(delay)));
        // start loading the chunk
        // location.getChunk().addPluginChunkTicket(instance);
        // get the current location of the player (with just the precision of a block)
        Location playerLocation = player.getLocation();
        Location currentLocation = new Location(
            playerLocation.getWorld(),
            playerLocation.getBlockX(),
            playerLocation.getBlockY(),
            playerLocation.getBlockZ()
        );
        // get the current teleportation in progress and cancel it
        BukkitTask teleportation = teleportations.get(player);
        if (teleportation != null) {
            teleportation.cancel();
        }
        // add the new teleportation
        AtomicInteger seconds = new AtomicInteger(delay);
        teleportations.put(
            player, new BukkitRunnable() {
                @Override
                public void run() {
                    // get the current location of the player (with just the precision of a block)
                    Location newPlayerLocation = player.getLocation();
                    Location newCurrentLocation = new Location(
                        newPlayerLocation.getWorld(),
                        newPlayerLocation.getBlockX(),
                        newPlayerLocation.getBlockY(),
                        newPlayerLocation.getBlockZ()
                    );
                    // check if the player has moved
                    if (!currentLocation.equals(newCurrentLocation)) {
                        this.cancel();
                        // location.getChunk().removePluginChunkTicket(instance);
                        player.sendTitle(
                            messageTeleportCancelledTitle.replace("{seconds}", Integer.toString(seconds.get()))
                                .replace("{destination}", destination.name),
                            messageTeleportCancelledSubTitle.replace("{seconds}", Integer.toString(seconds.get()))
                                .replace("{destination}", destination.name),
                            5,
                            60,
                            20
                        );
                        player.sendMessage(messageTeleportCancelled.replace(
                            "{seconds}",
                            Integer.toString(seconds.get())
                        ).replace("{destination}", destination.name));
                        player.playSound(player.getLocation(), Sound.ENTITY_WITHER_BREAK_BLOCK, 1f, 2f);
                        teleportations.put(player, null);
                    }
                    // the timer has not finished yet
                    else if (seconds.get() != 0) {
                        player.sendTitle(
                            messageTeleportTitle.replace("{seconds}", Integer.toString(seconds.get())),
                            messageTeleportSubTitle.replace("{destination}", destination.name),
                            5,
                            10,
                            5
                        );
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
                    }
                    // the timer finished, teleport the player
                    else {
                        this.cancel();
//                    player.teleport(destination.location);
                        // location.getChunk().removePluginChunkTicket(instance);
//                    player.sendTitle(messageTeleportedTitle.replace("{destination}", destination.name),
//                            messageTeleportedSubTitle.replace("{destination}", destination.name), 5, 60, 20);
//                    player.playSound(player.getLocation(), Sound.BLOCK_PORTAL_TRAVEL, 0.1f, 2f);
//                    player.sendMessage(messageTeleported.replace("{destination}", destination.name));

                        GlobalLocation location = new GlobalLocation(
                            destination.server,
                            destination.location.getWorld().getName(),
                            destination.location.getX(),
                            destination.location.getY(),
                            destination.location.getZ(),
                            destination.location.getYaw(),
                            destination.location.getPitch()
                        );
                        Component chatComponent = LegacyComponentSerializer.legacySection()
                            .deserialize(messageTeleported.replace("{destination}", destination.name));
                        String chat = MiniMessage.miniMessage().serialize(chatComponent);
                        Component titleComponent = LegacyComponentSerializer.legacySection()
                            .deserialize(messageTeleportedTitle.replace("{destination}", destination.name));
                        Component subtitleComponent = LegacyComponentSerializer.legacySection()
                            .deserialize(messageTeleportedSubTitle.replace("{destination}", destination.name));
                        TitleFeedback title = new TitleFeedback(
                            MiniMessage.miniMessage().serialize(titleComponent),
                            MiniMessage.miniMessage().serialize(subtitleComponent),
                            5, 60, 20
                        );
                        SoundFeedback sound = new SoundFeedback(Sound.BLOCK_PORTAL_TRAVEL.name(), 0.1f, 2f);
                        TeleportFeedback feedback = new TeleportFeedback(chat, title, sound);
                        TeleportRequest request = new TeleportRequest(player.getName(), location, feedback);
                        String message;
                        try {
                            message = mapper.writeValueAsString(request);
                        } catch (Exception e) {
                            System.err.println("Error serializing teleport request");
                            return;
                        }
                        redis.publish("global_teleport:execute", message);

                        teleportations.put(player, null);

                        if (destination.type.equals("home")) {
                            Database.incrementHomeUsage(player, destination);
                        } else if (destination.type.equals("warp")) {
                            Database.incrementWarpUsage(player, destination);
                        }
                    }
                    seconds.decrementAndGet();
                }
            }.runTaskTimer(BetterWarps.instance, 20L, 20L)
        );

    }

    private static int getPlayerTeleportDelay(Player player) {
        int delay = teleportDelay;

        List<Integer> levels = new ArrayList<>();

        for (PermissionAttachmentInfo permission : player.getEffectivePermissions()) {
            String perm = permission.getPermission();
            if (perm.startsWith("better.warps.teleport.delay.")) {
                levels.add(Integer.parseInt(perm.substring(perm.lastIndexOf(".") + 1)));
            }
        }

        if (!levels.isEmpty()) {
            delay = Collections.min(levels);
        }

        return delay;
    }

    public static void warpDoesNotExists(Player player, String name) {
        player.sendMessage(messageWarpDoesNotExistsChat.replace("{destination}", name));
        player.sendTitle(
            messageWarpDoesNotExistsTitle.replace("{destination}", name),
            messageWarpDoesNotExistsSubTitle.replace("{destination}", name),
            20,
            60,
            20
        );
        player.playSound(player.getLocation(), Sound.ENTITY_WITHER_BREAK_BLOCK, 1f, 2f);
    }

    public static void homeDoesNotExists(Player player, String name) {
        player.sendMessage(messageHomeDoesNotExistsChat.replace("{destination}", name));
        player.sendTitle(
            messageHomeDoesNotExistsTitle.replace("{destination}", name),
            messageHomeDoesNotExistsSubTitle.replace("{destination}", name),
            20,
            60,
            20
        );
        player.playSound(player.getLocation(), Sound.ENTITY_WITHER_BREAK_BLOCK, 1f, 2f);
    }

    public static String[] parseArgs(String[] args) {
        List<String> parsedArgs = new ArrayList<>();
        StringBuilder builder = new StringBuilder();
        for (String arg : args) {
            if (builder.isEmpty()) {
                if (arg.startsWith("\"")) {
                    if (arg.endsWith("\"")) {
                        parsedArgs.add(StringUtils.strip(arg, "\""));
                    } else {
                        builder.append(arg);
                    }
                } else {
                    parsedArgs.add(StringUtils.strip(arg, "\""));
                }
            } else {
                builder.append(" ").append(arg);
                if (arg.endsWith("\"")) {
                    parsedArgs.add(StringUtils.strip(builder.toString(), "\""));
                    builder.setLength(0);
                }
            }
        }

        if (!builder.isEmpty()) {
            parsedArgs.add(StringUtils.strip(builder.toString(), "\""));
        }

        return parsedArgs.toArray(new String[0]);
    }

    @Override
    public void onDisable() {
        Database.close();
        redis.close();
    }
}
