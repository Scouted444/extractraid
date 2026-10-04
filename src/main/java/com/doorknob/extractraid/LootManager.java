package com.doorknob.extractraid;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Weighted loot tables read from config.yml. */
public final class LootManager {

    private record Entry(Material material, int min, int max, int weight) {}

    private record Table(int minRolls, int maxRolls, List<Entry> entries, int totalWeight) {}

    private final ExtractPlugin plugin;
    private final Map<String, Table> tables = new HashMap<>();

    public LootManager(ExtractPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        tables.clear();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("loot-tables");
        if (root == null) return;

        for (String name : root.getKeys(false)) {
            ConfigurationSection t = root.getConfigurationSection(name);
            if (t == null) continue;

            int[] rolls = range(t.getString("rolls", "2-4"));
            List<Entry> entries = new ArrayList<>();
            int total = 0;

            for (String line : t.getStringList("items")) {
                String[] parts = line.split(":");
                Material m = Material.matchMaterial(parts[0].trim());
                if (m == null || !m.isItem()) {
                    plugin.getLogger().warning("Loot table '" + name + "': unknown item '" + parts[0] + "'");
                    continue;
                }
                int[] amount = range(parts.length > 1 ? parts[1].trim() : "1");
                int weight = 10;
                if (parts.length > 2) {
                    try {
                        weight = Math.max(1, Integer.parseInt(parts[2].trim()));
                    } catch (NumberFormatException ignored) {
                        // keep default weight
                    }
                }
                entries.add(new Entry(m, amount[0], amount[1], weight));
                total += weight;
            }

            if (!entries.isEmpty()) {
                tables.put(name.toLowerCase(), new Table(rolls[0], rolls[1], entries, total));
            }
        }
        plugin.getLogger().info("Loaded " + tables.size() + " loot table(s).");
    }

    public boolean has(String name) {
        return tables.containsKey(name.toLowerCase());
    }

    public Set<String> names() {
        return tables.keySet();
    }

    /** Clears the inventory and fills it with a fresh roll from the named table. */
    public void fill(Inventory inv, String tableName) {
        Table t = tables.get(tableName.toLowerCase());
        if (t == null) return;

        inv.clear();
        ThreadLocalRandom r = ThreadLocalRandom.current();

        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) slots.add(i);
        Collections.shuffle(slots);

        int rolls = r.nextInt(t.minRolls(), t.maxRolls() + 1);
        for (int i = 0; i < rolls && i < slots.size(); i++) {
            Entry e = pick(t, r);
            int amount = r.nextInt(e.min(), e.max() + 1);
            amount = Math.max(1, Math.min(amount, e.material().getMaxStackSize()));
            inv.setItem(slots.get(i), new ItemStack(e.material(), amount));
        }
    }

    private static Entry pick(Table t, ThreadLocalRandom r) {
        int roll = r.nextInt(t.totalWeight());
        for (Entry e : t.entries()) {
            roll -= e.weight();
            if (roll < 0) return e;
        }
        return t.entries().get(0);
    }

    /** Parses "3-6" or "4" into {min, max}. */
    private static int[] range(String s) {
        try {
            String[] p = s.trim().split("-");
            int a = Integer.parseInt(p[0].trim());
            int b = p.length > 1 ? Integer.parseInt(p[1].trim()) : a;
            if (b < a) b = a;
            return new int[]{Math.max(1, a), Math.max(1, b)};
        } catch (NumberFormatException ex) {
            return new int[]{1, 1};
        }
    }
}
