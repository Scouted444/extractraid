package com.doorknob.extractraid;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Per-player persistent storage, saved to plugins/ExtractRaid/stashes/[uuid].yml. */
public final class StashManager {

    /** Marks an inventory as somebody's stash. */
    public static final class StashHolder implements InventoryHolder {
        private final UUID owner;
        private Inventory inventory;

        StashHolder(UUID owner) {
            this.owner = owner;
        }

        public UUID owner() {
            return owner;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final ExtractPlugin plugin;
    private final File dir;

    public StashManager(ExtractPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "stashes");
        this.dir.mkdirs();
    }

    public void open(Player p) {
        int rows = Math.max(1, Math.min(6, plugin.getConfig().getInt("stash.rows", 6)));
        StashHolder holder = new StashHolder(p.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, rows * 9, Component.text("Stash"));
        holder.inventory = inv;
        load(holder);
        p.openInventory(inv);
    }

    private File fileOf(UUID id) {
        return new File(dir, id + ".yml");
    }

    private void load(StashHolder holder) {
        File f = fileOf(holder.owner);
        if (!f.exists()) return;

        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection slots = y.getConfigurationSection("slots");
        if (slots == null) return;

        for (String key : slots.getKeys(false)) {
            int slot;
            try {
                slot = Integer.parseInt(key);
            } catch (NumberFormatException ex) {
                continue;
            }
            ItemStack item = slots.getItemStack(key);
            if (item == null || slot < 0) continue;
            if (slot < holder.inventory.getSize()) {
                holder.inventory.setItem(slot, item);
            } else {
                // stash was shrunk in config: never delete items, put them anywhere they fit
                holder.inventory.addItem(item);
            }
        }
    }

    public void save(StashHolder holder) {
        YamlConfiguration y = new YamlConfiguration();
        ItemStack[] contents = holder.inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it != null && !it.getType().isAir()) {
                y.set("slots." + i, it);
            }
        }

        File target = fileOf(holder.owner);
        File tmp = new File(dir, holder.owner + ".yml.tmp");
        try {
            dir.mkdirs();
            y.save(tmp);
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save stash for " + holder.owner + ": " + ex.getMessage());
        }
    }

    /** Saves on the next tick, after the click/drag that triggered it has been applied. */
    public void scheduleSave(StashHolder holder) {
        Bukkit.getScheduler().runTask(plugin, () -> save(holder));
    }

    /** Called on shutdown: saves every stash that is currently open. */
    public void saveAllOpen() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof StashHolder h) {
                save(h);
            }
        }
    }
}
