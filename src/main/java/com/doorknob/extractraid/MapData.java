package com.doorknob.extractraid;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Everything the admin sets up with /raidadmin, saved in plugins/ExtractRaid/map.yml. */
public final class MapData {

    public record ExtractPoint(String name, double x, double y, double z, double radius) {}

    public record LootPoint(int x, int y, int z, String table) {}

    public record SpawnPoint(double x, double y, double z, float yaw) {}

    private final ExtractPlugin plugin;
    private final File file;

    private final Map<String, ExtractPoint> extracts = new LinkedHashMap<>();
    private final List<LootPoint> loot = new ArrayList<>();
    private final List<SpawnPoint> spawns = new ArrayList<>();

    private boolean hubSet;
    private String hubWorld;
    private double hx, hy, hz;
    private float hyaw, hpitch;

    public MapData(ExtractPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "map.yml");
        load();
    }

    public void load() {
        extracts.clear();
        loot.clear();
        spawns.clear();
        hubSet = false;
        if (!file.exists()) return;

        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);

        if (y.isConfigurationSection("hub")) {
            hubSet = true;
            hubWorld = y.getString("hub.world");
            hx = y.getDouble("hub.x");
            hy = y.getDouble("hub.y");
            hz = y.getDouble("hub.z");
            hyaw = (float) y.getDouble("hub.yaw");
            hpitch = (float) y.getDouble("hub.pitch");
        }

        ConfigurationSection ex = y.getConfigurationSection("extracts");
        if (ex != null) {
            for (String key : ex.getKeys(false)) {
                ConfigurationSection s = ex.getConfigurationSection(key);
                if (s == null) continue;
                extracts.put(key, new ExtractPoint(key,
                        s.getDouble("x"), s.getDouble("y"), s.getDouble("z"), s.getDouble("radius", 4)));
            }
        }

        for (String line : y.getStringList("loot")) {
            String[] p = line.split(",");
            if (p.length < 4) continue;
            try {
                loot.add(new LootPoint(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
                        Integer.parseInt(p[2].trim()), p[3].trim()));
            } catch (NumberFormatException ignored) {
                plugin.getLogger().warning("Bad loot line in map.yml: " + line);
            }
        }

        for (String line : y.getStringList("spawns")) {
            String[] p = line.split(",");
            if (p.length < 4) continue;
            try {
                spawns.add(new SpawnPoint(Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim()),
                        Double.parseDouble(p[2].trim()), Float.parseFloat(p[3].trim())));
            } catch (NumberFormatException ignored) {
                plugin.getLogger().warning("Bad spawn line in map.yml: " + line);
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();

        if (hubSet) {
            y.set("hub.world", hubWorld);
            y.set("hub.x", hx);
            y.set("hub.y", hy);
            y.set("hub.z", hz);
            y.set("hub.yaw", (double) hyaw);
            y.set("hub.pitch", (double) hpitch);
        }

        for (ExtractPoint e : extracts.values()) {
            String base = "extracts." + e.name();
            y.set(base + ".x", e.x());
            y.set(base + ".y", e.y());
            y.set(base + ".z", e.z());
            y.set(base + ".radius", e.radius());
        }

        List<String> lootLines = new ArrayList<>();
        for (LootPoint l : loot) lootLines.add(l.x() + "," + l.y() + "," + l.z() + "," + l.table());
        y.set("loot", lootLines);

        List<String> spawnLines = new ArrayList<>();
        for (SpawnPoint s : spawns) spawnLines.add(s.x() + "," + s.y() + "," + s.z() + "," + s.yaw());
        y.set("spawns", spawnLines);

        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save map.yml: " + ex.getMessage());
        }
    }

    // ---- hub

    public void setHub(Location l) {
        hubSet = true;
        hubWorld = l.getWorld().getName();
        hx = l.getX();
        hy = l.getY();
        hz = l.getZ();
        hyaw = l.getYaw();
        hpitch = l.getPitch();
        save();
    }

    /** The hub location. Falls back to the main world's spawn if no hub has been set. */
    public Location hub() {
        World w = hubSet && hubWorld != null ? Bukkit.getWorld(hubWorld) : null;
        if (w == null) {
            return Bukkit.getWorlds().get(0).getSpawnLocation();
        }
        return new Location(w, hx, hy, hz, hyaw, hpitch);
    }

    // ---- extraction zones

    public Collection<ExtractPoint> extracts() {
        return extracts.values();
    }

    public Set<String> extractNames() {
        return extracts.keySet();
    }

    public void addExtract(String name, Location l, double radius) {
        extracts.put(name, new ExtractPoint(name, l.getX(), l.getY(), l.getZ(), radius));
        save();
    }

    public boolean removeExtract(String name) {
        boolean removed = extracts.remove(name) != null;
        if (removed) save();
        return removed;
    }

    // ---- loot containers

    public List<LootPoint> loot() {
        return loot;
    }

    public void addLoot(int x, int y, int z, String table) {
        loot.removeIf(l -> l.x() == x && l.y() == y && l.z() == z);
        loot.add(new LootPoint(x, y, z, table));
        save();
    }

    public boolean removeLoot(int x, int y, int z) {
        boolean removed = loot.removeIf(l -> l.x() == x && l.y() == y && l.z() == z);
        if (removed) save();
        return removed;
    }

    // ---- player spawn points

    public List<SpawnPoint> spawns() {
        return spawns;
    }

    public void addSpawn(Location l) {
        spawns.add(new SpawnPoint(l.getX(), l.getY(), l.getZ(), l.getYaw()));
        save();
    }

    public void clearSpawns() {
        spawns.clear();
        save();
    }
}
