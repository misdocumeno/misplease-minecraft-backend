package io.github.misdocumeno.betterwarps.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.misdocumeno.betterwarps.BetterWarps;
import io.github.misdocumeno.betterwarps.types.HomePair;
import io.github.misdocumeno.betterwarps.types.Warp;
import org.bukkit.*;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class Database {

    private static HikariDataSource dataSource;

    public static void setPostgres(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        String userInfo = uri.getUserInfo();
        String[] parts = userInfo.split(":", 2);
        String username = parts[0];
        String password = parts[1];
        String host = uri.getHost();
        int port = uri.getPort() != -1 ? uri.getPort() : 5432;
        String database = uri.getPath().replaceFirst("^/", "");
        String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s", host, port, database);

        HikariConfig config = new HikariConfig();

        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Couldn't found PostgreSQL driver.", e);
        }
        config.setDriverClassName("org.postgresql.Driver");

        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
//        config.setSchema("warps");
        config.setConnectionInitSql("CREATE SCHEMA IF NOT EXISTS warps; SET search_path TO warps;");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setAutoCommit(true);
        dataSource = new HikariDataSource(config);

        createTables();
    }

    private static Connection connect() throws SQLException {
        if (dataSource == null) throw new IllegalStateException("Database not initialized");
        return dataSource.getConnection();
    }

    private static void createTables() {
        try (Connection connection = connect(); Statement stmt = connection.createStatement()) {

            // warps table
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS warps (
                        name TEXT PRIMARY KEY NOT NULL,
                        material TEXT NOT NULL,
                        enchanted INTEGER NOT NULL,
                        description TEXT NOT NULL,
                        server TEXT NOT NULL,
                        world TEXT NOT NULL,
                        x DOUBLE PRECISION NOT NULL,
                        y DOUBLE PRECISION NOT NULL,
                        z DOUBLE PRECISION NOT NULL,
                        yaw REAL NOT NULL,
                        pitch REAL NOT NULL
                    )
                """);

            // homes table
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS homes (
                        uuid UUID NOT NULL,
                        name TEXT NOT NULL,
                        material TEXT NOT NULL,
                        enchanted INTEGER NOT NULL,
                        description TEXT NOT NULL,
                        server TEXT NOT NULL,
                        world TEXT NOT NULL,
                        x DOUBLE PRECISION NOT NULL,
                        y DOUBLE PRECISION NOT NULL,
                        z DOUBLE PRECISION NOT NULL,
                        yaw REAL NOT NULL,
                        pitch REAL NOT NULL,
                        uses INTEGER NOT NULL DEFAULT 0,
                        last_used TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY(uuid, name)
                    )
                """);

            // spawn table
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS spawn (
                        server TEXT NOT NULL,
                        world TEXT NOT NULL,
                        x DOUBLE PRECISION NOT NULL,
                        y DOUBLE PRECISION NOT NULL,
                        z DOUBLE PRECISION NOT NULL,
                        yaw REAL NOT NULL,
                        pitch REAL NOT NULL
                    )
                """);

            // warp_usages table
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS warp_usages (
                        uuid UUID NOT NULL,
                        warp TEXT NOT NULL,
                        uses INTEGER NOT NULL DEFAULT 0,
                        last_used TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY(uuid, warp)
                    )
                """);

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static void getWarps() {
        BetterWarps.warps.clear();
        String query = "SELECT * FROM warps";
        try (Connection connection = connect(); PreparedStatement stmt = connection.prepareStatement(query)) {
            ResultSet result = stmt.executeQuery();
            while (result.next()) {
                String name = result.getString("name");
                Material material = Material.getMaterial(result.getString("material"));
                int enchanted = result.getInt("enchanted");
                String description = result.getString("description");
                String server = result.getString("server");
                String worldName = result.getString("world");
                World world = Bukkit.getWorld(worldName);
                if (world == null) continue;
                double x = result.getDouble("x");
                double y = result.getDouble("y");
                double z = result.getDouble("z");
                float yaw = result.getFloat("yaw");
                float pitch = result.getFloat("pitch");
                Location location = new Location(world, x, y, z, yaw, pitch);
                Warp warp = new Warp("warp", name, material, enchanted, description, location, server);
                BetterWarps.warps.add(warp);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static void addWarp(Warp warp) {
        String query = """
                INSERT INTO warps(name, material, enchanted, description, server, world, x, y, z, yaw, pitch)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(name) DO UPDATE SET
                    material = EXCLUDED.material,
                    enchanted = EXCLUDED.enchanted,
                    description = EXCLUDED.description,
                    server = EXCLUDED.server,
                    world = EXCLUDED.world,
                    x = EXCLUDED.x,
                    y = EXCLUDED.y,
                    z = EXCLUDED.z,
                    yaw = EXCLUDED.yaw,
                    pitch = EXCLUDED.pitch
            """;

        try (Connection connection = connect(); PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, warp.name);
            stmt.setString(2, warp.material.toString());
            stmt.setInt(3, warp.enchanted);
            stmt.setString(4, warp.description);
            stmt.setString(5, warp.server);
            stmt.setString(6, warp.location.getWorld().getName());
            stmt.setDouble(7, warp.location.getX());
            stmt.setDouble(8, warp.location.getY());
            stmt.setDouble(9, warp.location.getZ());
            stmt.setFloat(10, warp.location.getYaw());
            stmt.setFloat(11, warp.location.getPitch());
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static void delWarp(Warp warp) {
        String query = "DELETE FROM warps WHERE name = ?";
        try (Connection connection = connect(); PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, warp.name);
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static void getHomes(Player player) {
        BetterWarps.homes.put(player, new ArrayList<>());
        String query = "SELECT * FROM homes WHERE uuid = ?";

        try (Connection connection = connect(); PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setObject(1, player.getUniqueId());
            ResultSet result = stmt.executeQuery();
            while (result.next()) {
                String name = result.getString("name");
                Material material = Material.getMaterial(result.getString("material"));
                int enchanted = result.getInt("enchanted");
                String description = result.getString("description");
                String server = result.getString("server");
                String worldName = result.getString("world");
                World world = Bukkit.getWorld(worldName);
                if (world == null) continue;
                double x = result.getDouble("x");
                double y = result.getDouble("y");
                double z = result.getDouble("z");
                float yaw = result.getFloat("yaw");
                float pitch = result.getFloat("pitch");
                Warp home = new Warp(
                    "home",
                    name,
                    material,
                    enchanted,
                    description,
                    new Location(world, x, y, z, yaw, pitch),
                    server
                );
                BetterWarps.homes.get(player).add(home);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static void addHome(Player player, Warp warp) {
        String query = """
                INSERT INTO homes(uuid, name, material, enchanted, description, server, world, x, y, z, yaw, pitch)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid, name) DO UPDATE SET
                    material = EXCLUDED.material,
                    enchanted = EXCLUDED.enchanted,
                    description = EXCLUDED.description,
                    server = EXCLUDED.server,
                    world = EXCLUDED.world,
                    x = EXCLUDED.x,
                    y = EXCLUDED.y,
                    z = EXCLUDED.z,
                    yaw = EXCLUDED.yaw,
                    pitch = EXCLUDED.pitch
            """;

        try (Connection connection = connect(); PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setObject(1, player.getUniqueId());
            stmt.setString(2, warp.name);
            stmt.setString(3, warp.material.toString());
            stmt.setInt(4, warp.enchanted);
            stmt.setString(5, warp.description);
            stmt.setString(6, warp.server);
            stmt.setString(7, warp.location.getWorld().getName());
            stmt.setDouble(8, warp.location.getX());
            stmt.setDouble(9, warp.location.getY());
            stmt.setDouble(10, warp.location.getZ());
            stmt.setFloat(11, warp.location.getYaw());
            stmt.setFloat(12, warp.location.getPitch());
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static void delHome(Player player, Warp home) {
        String query = "DELETE FROM homes WHERE uuid = ? AND name = ?";
        try (Connection connection = connect(); PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setObject(1, player.getUniqueId());
            stmt.setString(2, home.name);
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static void getAllHomes() {
        BetterWarps.allHomes.clear();
        String query = "SELECT * FROM homes";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(query)) {
            ResultSet result = statement.executeQuery();
            while (result.next()) {
                OfflinePlayer player = Bukkit.getOfflinePlayer(UUID.fromString(result.getString(1)));
                String name = result.getString(2);
                Material material = Material.getMaterial(result.getString(3));
                int enchantment = result.getInt(4);
                String description = result.getString(5);
                String server = result.getString(6);
                World world = Bukkit.getWorld(result.getString(7));
                if (world == null) // ignore this warp if the world doesn't exist (the entry can be removed with
                // the /purgewarps command)
                {
                    continue;
                }
                double x = result.getDouble(8);
                double y = result.getDouble(9);
                double z = result.getDouble(10);
                float yaw = result.getFloat(11);
                float pitch = result.getFloat(12);
                Location location = new Location(world, x, y, z, yaw, pitch);
                Warp warp = new Warp("home", name, material, enchantment, description, location, server);
                BetterWarps.allHomes.add(new HomePair(player, warp));
            }
        } catch (SQLException e) {
            System.err.println(e.getMessage());
        }
    }

    public static void getSpawn() {
        String query = "SELECT * FROM spawn LIMIT 1";
        try (Connection connection = connect(); PreparedStatement stmt = connection.prepareStatement(query)) {
            ResultSet result = stmt.executeQuery();
            if (result.next()) {
                String server = result.getString("server");
                String worldName = result.getString("world");
                World world = Bukkit.getWorld(worldName);
                double x = result.getDouble("x");
                double y = result.getDouble("y");
                double z = result.getDouble("z");
                float yaw = result.getFloat("yaw");
                float pitch = result.getFloat("pitch");
                BetterWarps.spawn = new Location(world, x, y, z, yaw, pitch);
                BetterWarps.spawnServer = server;
            } else {
                BetterWarps.spawn = null;
                BetterWarps.spawnServer = null;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }


    public static void setSpawn(Location newSpawn, String server) {
        String delete = "DELETE FROM spawn";
        String insert = "INSERT INTO spawn(server, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?)";

        try (Connection connection = connect()) {
            try (PreparedStatement stmt = connection.prepareStatement(delete)) {
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = connection.prepareStatement(insert)) {
                stmt.setString(1, server);
                stmt.setString(2, newSpawn.getWorld().getName());
                stmt.setDouble(3, newSpawn.getX());
                stmt.setDouble(4, newSpawn.getY());
                stmt.setDouble(5, newSpawn.getZ());
                stmt.setFloat(6, newSpawn.getYaw());
                stmt.setFloat(7, newSpawn.getPitch());
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static void incrementHomeUsage(Player player, Warp home) {
        String query = "UPDATE homes SET uses = uses + 1, last_used = CURRENT_TIMESTAMP WHERE uuid = ? AND name = ?";

        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setObject(1, player.getUniqueId());
            statement.setString(2, home.name);
            statement.executeUpdate();
        } catch (SQLException e) {
            System.err.println(e.getMessage());
        }
    }

    public static void incrementWarpUsage(Player player, Warp warp) {
        String checkQuery = "SELECT * FROM warp_usages WHERE uuid = ? AND warp = ?";
        boolean alreadyUsed = false;

        // check if the user already used this warp before
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(checkQuery)) {
            statement.setObject(1, player.getUniqueId());
            statement.setString(2, warp.name);
            ResultSet result = statement.executeQuery();
            alreadyUsed = result.next();
        } catch (SQLException e) {
            System.err.println(e.getMessage());
            return;
        }
        String query = alreadyUsed
            ? "UPDATE warp_usages SET uses = uses + 1, last_used = CURRENT_TIMESTAMP WHERE uuid = ? AND warp = ?"
            : "INSERT INTO warp_usages(uuid, warp, uses, last_used) VALUES(?, ?, 1, CURRENT_TIMESTAMP)";

        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setObject(1, player.getUniqueId());
            statement.setString(2, warp.name);
            statement.executeUpdate();
        } catch (SQLException e) {
            System.err.println(e.getMessage());
        }
    }

    public static int deleteInvalid() {
        String[] tables = {"warps", "homes", "spawn"};
        Set<String> invalidWorlds = new HashSet<>();
        int total = 0;

        for (String table : tables) {
            String query = "SELECT world FROM " + table;
            try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(query)) {
                ResultSet result = statement.executeQuery();
                while (result.next()) {
                    String world = result.getString(1);
                    if (Bukkit.getWorld(world) == null) {
                        invalidWorlds.add(world);
                        total += 1;
                    }
                }
            } catch (SQLException e) {
                System.err.println(e.getMessage());
            }
        }

        for (String table : tables) {
            for (String world : invalidWorlds) {
                String query = String.format("DELETE FROM %s WHERE world = ?", table);
                try (
                    Connection connection = connect();
                    PreparedStatement statement = connection.prepareStatement(query)
                ) {
                    statement.setString(1, world);
                    statement.executeUpdate();
                } catch (SQLException e) {
                    System.err.println(e.getMessage());
                }
            }
        }

        return total;
    }

    public static void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
