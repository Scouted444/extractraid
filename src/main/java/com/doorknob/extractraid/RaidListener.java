package com.doorknob.extractraid;

import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.EnumSet;
import java.util.Set;

public final class RaidListener implements Listener {

    /** Spawn reasons that are blocked in the hub and in raids. Mobs placed by the plugin use CUSTOM. */
    private static final Set<SpawnReason> BLOCKED_SPAWNS = EnumSet.of(
            SpawnReason.NATURAL, SpawnReason.CHUNK_GEN, SpawnReason.JOCKEY, SpawnReason.MOUNT,
            SpawnReason.PATROL, SpawnReason.REINFORCEMENTS, SpawnReason.TRAP, SpawnReason.VILLAGE_DEFENSE,
            SpawnReason.VILLAGE_INVASION, SpawnReason.SPAWNER, SpawnReason.NETHER_PORTAL);

    private final ExtractPlugin plugin;

    public RaidListener(ExtractPlugin plugin) {
        this.plugin = plugin;
    }

    private RaidManager rm() {
        return plugin.raids();
    }

    /** Admins in creative mode may build in the hub and edit the template. */
    private boolean builder(Player p) {
        return p.hasPermission("extractraid.admin") && p.getGameMode() == GameMode.CREATIVE;
    }

    private boolean buildLocked(World w) {
        if (rm().isHubWorld(w)) return true;
        return rm().isRaidWorld(w) && !plugin.getConfig().getBoolean("raid.allow-building", false);
    }

    // ------------------------------------------------------------------ life cycle

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        rm().handleDeath(e.getEntity());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        e.setRespawnLocation(plugin.mapData().hub());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        rm().handleQuit(e.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (rm().isInRaid(p)) return;

        World w = p.getWorld();
        if (rm().isHubWorld(w)) return;

        String template = plugin.getConfig().getString("template-world", "raid_template");
        if (w.getName().equals(template) && p.hasPermission("extractraid.admin")) return;

        p.teleport(plugin.mapData().hub());
    }

    // ------------------------------------------------------------------ damage and hunger

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;

        if (rm().isHubWorld(p.getWorld())) {
            if (e.getCause() == EntityDamageEvent.DamageCause.VOID) {
                p.teleport(plugin.mapData().hub());
            }
            e.setCancelled(true);
            return;
        }
        if (rm().isInRaid(p)) {
            if (rm().isProtected(p)) {
                e.setCancelled(true);
                return;
            }
            rm().onDamaged(p);
        }
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && rm().isHubWorld(p.getWorld())) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ world protection

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!builder(e.getPlayer()) && buildLocked(e.getBlock().getWorld())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!builder(e.getPlayer()) && buildLocked(e.getBlock().getWorld())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (buildLocked(e.getLocation().getWorld())) e.blockList().clear();
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (buildLocked(e.getBlock().getWorld())) e.blockList().clear();
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        if (rm().isHubWorld(p.getWorld()) && !builder(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        World w = e.getLocation().getWorld();
        if ((rm().isRaidWorld(w) || rm().isHubWorld(w)) && BLOCKED_SPAWNS.contains(e.getSpawnReason())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        if (rm().isInRaid(e.getPlayer())) e.setCancelled(true);
    }

    /** Ender chests would be a safe stash inside raids. */
    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (e.getInventory().getType() == InventoryType.ENDER_CHEST
                && e.getPlayer() instanceof Player p && rm().isInRaid(p)) {
            e.setCancelled(true);
            Msg.send(p, "<red>Ender chests are disabled in raids.");
        }
    }

    // ------------------------------------------------------------------ stash saving

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof StashManager.StashHolder h) {
            plugin.stash().scheduleSave(h);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof StashManager.StashHolder h) {
            plugin.stash().scheduleSave(h);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof StashManager.StashHolder h) {
            plugin.stash().save(h);
        }
    }
}
