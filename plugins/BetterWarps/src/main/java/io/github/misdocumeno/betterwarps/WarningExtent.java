package io.github.misdocumeno.betterwarps;

import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WarningExtent extends AbstractDelegateExtent {

    private final Player player;
    private final Set<BlockVector3> alreadyWarned;

    public WarningExtent(Extent extent, Player player) {
        super(extent);
        this.player = player;
        this.alreadyWarned = Collections.newSetFromMap(new ConcurrentHashMap<>());
    }

    @Override
    public BlockState getBlock(BlockVector3 position) {
        checkBlock(position);
        return super.getBlock(position);
    }

    @Override
    public BlockState getBlock(int x, int y, int z) {
        checkBlock(BlockVector3.at(x, y, z));
        return super.getBlock(x, y, z);
    }

    @Override
    public BaseBlock getFullBlock(BlockVector3 position) {
        checkBlock(position);
        return super.getFullBlock(position);
    }

    @Override
    public BaseBlock getFullBlock(int x, int y, int z) {
        checkBlock(BlockVector3.at(x, y, z));
        return super.getFullBlock(x, y, z);
    }

    public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 location, T block) {
        checkBlock(location);
        return super.setBlock(location, block);
    }

    public <T extends BlockStateHolder<T>> boolean setBlock(int x, int y, int z, T block) {
        checkBlock(BlockVector3.at(x, y, z));
        return super.setBlock(x, y, z, block);
    }

    private void checkBlock(BlockVector3 position) {
        for (var warpOrHome : WorldEditWarner.getWarpsOrHomesAt(player.getWorld(), position)) {
            if (alreadyWarned.contains(position)) {
                continue;
            } else {
                alreadyWarned.add(position);
            }

            String message = String.format(
                    "There is a %s at %s called '%s'",
                    warpOrHome.owner() != null ? warpOrHome.owner().getName() + "'s home" : "warp",
                    position,
                    warpOrHome.warp().name
            );

            player.spigot().sendMessage(getTextComponent(player, warpOrHome, message));
            player.playSound(player.getLocation(), Sound.ENTITY_WITHER_BREAK_BLOCK, 1f, 2f);
            player.sendTitle("§4WATCH OUT", "There is a home or warp in the region you modified.", 5, 60, 20);

            if (warpOrHome.owner() == null) {
                BetterWarps.instance.getLogger().warning(
                        "A world edit operation was made over '" +
                                warpOrHome.warp().name + "' warp at " +
                                warpOrHome.warp().location
                );
            } else {
                BetterWarps.instance.getLogger().warning(
                        "A world edit operation was made over " +
                                warpOrHome.owner().getName() + "'s '" +
                                warpOrHome.warp().name + "' home at " + warpOrHome.warp().location
                );
            }
        }
    }

    public static TextComponent getTextComponent(Player player, WorldEditWarner.WarpOrHome warpOrHome, String message) {
        Location location = warpOrHome.warp().location;
        String command = String.format(
                "/minecraft:tp %s %f %f %f %f %f",
                player.getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch());

        TextComponent component = new TextComponent(message);
        component.setColor(ChatColor.RED);
        component.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
        return component;
    }
}
