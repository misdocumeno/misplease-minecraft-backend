package io.github.misdocumeno.betterwarps.types;

import org.bukkit.Location;
import org.bukkit.Material;

public class Warp {
    public String type;
    public String name;
    public String rawName;
    public Material material;
    public int enchanted;
    public String description;
    public Location location;
    public String server;

    public Warp(
        String type, String name, Material material, int enchanted, String description, Location location,
        String server
    ) {
        this.type = type;
        this.name = name;
        this.rawName = name.replaceAll("§.?", "");
        this.material = material;
        this.enchanted = enchanted;
        this.description = description;
        this.location = location;
        this.server = server;
    }
}
