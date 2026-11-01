package io.github.misdocumeno.betterwarps;

import com.sk89q.worldedit.math.BlockVector3;
import io.github.misdocumeno.betterwarps.types.Warp;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;

import javax.annotation.Nullable;
import java.util.*;

public class WorldEditWarner {

    public record WarpOrHome(Warp warp, @Nullable OfflinePlayer owner) {
    }

    private static final Map<BlockVector3, List<WarpOrHome>> allWarpsAndHomes = new HashMap<>();

    public static void addWarp(Warp warp) {
        addWarp(warp, null);
    }

    public static void addWarp(Warp warp, OfflinePlayer player) {
        BlockVector3 position = locationToBlockVector3(warp.location);

        if (!allWarpsAndHomes.containsKey(position)) {
            allWarpsAndHomes.put(position, new LinkedList<>());
        }

        allWarpsAndHomes.get(position).add(new WarpOrHome(warp, player));
    }

    public static void remove(Warp warp) {
        BlockVector3 position = locationToBlockVector3(warp.location);
        List<WarpOrHome> warpsAtPosition = allWarpsAndHomes.get(position);

        if (warpsAtPosition != null) {
            warpsAtPosition.removeIf(warpOrHome -> warpOrHome.warp == warp);
        }
    }

    public static void clear() {
        allWarpsAndHomes.clear();
    }

    public static List<WarpOrHome> getWarpsOrHomesAt(World world, BlockVector3 position) {
        return allWarpsAndHomes.containsKey(position)
                ? allWarpsAndHomes.get(position).stream().filter((warp) -> warp.warp.location.getWorld() == world).toList() :
                new ArrayList<>();
    }

    private static BlockVector3 locationToBlockVector3(Location location) {
        return BlockVector3.at(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }
}
