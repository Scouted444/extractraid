package com.doorknob.extractraid;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/** Queue, raid lifecycle, extraction and instance world handling. */
public final class RaidManager {

    public static final String WORLD_PREFIX = "xr_";

    private final ExtractPlugin plugin;

    /** Queued players in join order; value = joined as scav. */
    private final Map<UUID, Boolean> queue = new LinkedHashMap<>();
    private final Map<UUID, Raid> playerRaid = new HashMap<>();
    private final Map<Integer, Raid> raids = new HashMap<>();

    private BukkitTask countdownTask;
    private int countdown;
    private int nextId = 1;

    public RaidManager(ExtractPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ helpers

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    private String templateName() {
        return cfg().getString("template-world", "raid_template");
    }

    private File templateFolder() {
        return new File(Bukkit.getWorldContainer(), templateName());
    }

    private int max() {
        return Math.max(1, cfg().getInt("queue.max-players", 16));
    }

    private int min() {
        return Math.max(1, Math.min(max(), cfg().getInt("queue.min-players", 2)));
    }

    private static String clock(int seconds) {
        seconds = Math.max(0, seconds);
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    public boolean isRaidWorld(World w) {
        return w != null && w.getName().startsWith(WORLD_PREFIX);
    }

    public boolean isHubWorld(World w) {
        return w != null && w.equals(plugin.mapData().hub().getWorld());
    }

    public boolean isInRaid(Player p) {
        return playerRaid.containsKey(p.getUniqueId());
    }

    public boolean isProtected(Player p) {
        Raid r = playerRaid.get(p.getUniqueId());
        if (r == null) return false;
        return r.protectedUntil.getOrDefault(p.getUniqueId(), 0L) > System.currentTimeMillis();
    }

    private static boolean hasNoItems(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && !it.getType().isAir()) return false;
        }
        return true;
    }

    private void toHub(Player p) {
        p.teleport(plugin.mapData().hub());
    }

    @SuppressWarnings("deprecation")
    private void heal(Player p) {
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        for (PotionEffect eff : new ArrayList<>(p.getActivePotionEffects())) {
            p.removePotionEffect(eff.getType());
        }
    }

    private void broadcast(Raid raid, String mini) {
        for (UUID u : raid.players) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) Msg.send(p, mini);
        }
    }

    private void broadcastQueue(String mini) {
        for (UUID u : queue.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) Msg.send(p, mini);
        }
    }

    private void removeFromRaid(UUID u) {
        Raid r = playerRaid.remove(u);
        if (r != null) {
            r.players.remove(u);
            r.progress.remove(u);
        }
    }

    // ------------------------------------------------------------------ queue

    public void queueJoin(Player p, boolean scav) {
        UUID id = p.getUniqueId();
        if (isInRaid(p)) {
            Msg.send(p, "<red>You are already in a raid.");
            return;
        }
        if (!isHubWorld(p.getWorld())) {
            Msg.send(p, "<red>You can only queue from the hub.");
            return;
        }
        if (queue.containsKey(id)) {
            Msg.send(p, "You are already queued. Use <white>/raid leave</white> to cancel.");
            return;
        }
        if (!templateFolder().isDirectory() || plugin.mapData().extracts().isEmpty()) {
            Msg.send(p, "<red>No raid map is set up yet. Ask an admin.");
            return;
        }
        if (scav && !hasNoItems(p)) {
            Msg.send(p, "<red>Scav runs need an empty inventory. Put your gear in <white>/stash</white> first.");
            return;
        }

        queue.put(id, scav);
        Msg.send(p, "Queued " + (scav ? "as a <yellow>scav</yellow>" : "with your current loadout")
                + ". <white>" + queue.size() + "</white> in queue, <white>" + min() + "</white> needed.");
        updateCountdown();
    }

    public void queueLeave(Player p) {
        if (queue.remove(p.getUniqueId()) != null) {
            Msg.send(p, "You left the queue.");
            updateCountdown();
        } else if (isInRaid(p)) {
            Msg.send(p, "<red>You cannot leave a raid. Find an extraction point!");
        } else {
            Msg.send(p, "You are not queued.");
        }
    }

    public void status(Player p) {
        Msg.send(p, "Queue: <white>" + queue.size() + "</white> player(s), <white>" + min()
                + "</white> needed. Active raids: <white>" + raids.size() + "</white>.");
        Raid r = playerRaid.get(p.getUniqueId());
        if (r != null && r.state == Raid.State.RUNNING) {
            int left = cfg().getInt("raid.duration-seconds", 1200) - r.ticks / 20;
            Msg.send(p, "Raid time left: <white>" + clock(left) + "</white>.");
        }
    }

    private void updateCountdown() {
        int size = queue.size();
        if (size >= max()) {
            cancelCountdown();
            startFromQueue(false);
            return;
        }
        if (size >= min()) {
            if (countdownTask == null) {
                countdown = cfg().getInt("queue.countdown-seconds", 30);
                broadcastQueue("Enough players! Raid starts in <white>" + countdown + "s</white>.");
                countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                    countdown--;
                    if (countdown <= 0) {
                        cancelCountdown();
                        startFromQueue(false);
                        return;
                    }
                    if (countdown <= 5 || countdown == 10 || countdown == 20 || countdown == 30) {
                        broadcastQueue("Raid starts in <white>" + countdown + "s</white>.");
                    }
                }, 20L, 20L);
            }
        } else if (countdownTask != null) {
            cancelCountdown();
            broadcastQueue("Not enough players. Countdown cancelled.");
        }
    }

    private void cancelCountdown() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
    }

    /** Admin command: start a raid with whoever is queued, ignoring the minimum. */
    public int forceStart() {
        int n = queue.size();
        if (n == 0) return 0;
        cancelCountdown();
        startFromQueue(true);
        return n;
    }

    private void startFromQueue(boolean force) {
        List<UUID> ready = new ArrayList<>();
        Set<UUID> scavs = new HashSet<>();

        Iterator<Map.Entry<UUID, Boolean>> it = queue.entrySet().iterator();
        while (it.hasNext() && ready.size() < max()) {
            Map.Entry<UUID, Boolean> e = it.next();
            it.remove();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline() || isInRaid(p)) continue;
            if (e.getValue() && !hasNoItems(p)) {
                Msg.send(p, "<red>You picked up items while queued as a scav, so you were removed from the queue.");
                continue;
            }
            ready.add(e.getKey());
            if (e.getValue()) scavs.add(e.getKey());
        }

        if (ready.isEmpty()) {
            updateCountdown();
            return;
        }
        if (!force && ready.size() < min()) {
            for (UUID id : ready) queue.put(id, scavs.contains(id));
            updateCountdown();
            return;
        }

        startRaid(ready, scavs);
        updateCountdown();
    }

    // ------------------------------------------------------------------ raid start

    private void startRaid(List<UUID> ids, Set<UUID> scavs) {
        int id = nextId++;
        Raid raid = new Raid(id, WORLD_PREFIX + id);
        raids.put(id, raid);
        for (UUID u : ids) {
            raid.players.add(u);
            playerRaid.put(u, raid);
            if (scavs.contains(u)) raid.scavs.add(u);
        }
        broadcast(raid, "<gold>Preparing the raid map...");

        World loadedTemplate = Bukkit.getWorld(templateName());
        if (loadedTemplate != null) loadedTemplate.save();

        Path src = templateFolder().toPath();
        Path dst = new File(Bukkit.getWorldContainer(), raid.worldName).toPath();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                copyWorld(src, dst);
            } catch (IOException ex) {
                plugin.getLogger().severe("Could not copy raid map: " + ex);
                Bukkit.getScheduler().runTask(plugin, () -> abort(raid));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> launch(raid));
        });
    }

    private void abort(Raid raid) {
        broadcast(raid, "<red>The raid could not be started. Check the console.");
        for (UUID u : new ArrayList<>(raid.players)) removeFromRaid(u);
        raids.remove(raid.id);
        deleteAsync(new File(Bukkit.getWorldContainer(), raid.worldName));
    }

    private void launch(Raid raid) {
        if (raid.state != Raid.State.LOADING) return;

        raid.players.removeIf(u -> {
            Player p = Bukkit.getPlayer(u);
            if (p == null || !p.isOnline()) {
                playerRaid.remove(u);
                return true;
            }
            return false;
        });
        if (raid.players.isEmpty()) {
            raids.remove(raid.id);
            deleteAsync(new File(Bukkit.getWorldContainer(), raid.worldName));
            return;
        }

        World w = new WorldCreator(raid.worldName).createWorld();
        if (w == null) {
            plugin.getLogger().severe("Could not load raid world " + raid.worldName);
            abort(raid);
            return;
        }
        raid.world = w;
        w.setAutoSave(false);
        w.setTime(cfg().getLong("raid.start-time", 0L));
        try {
            w.setDifficulty(Difficulty.valueOf(cfg().getString("raid.difficulty", "NORMAL").toUpperCase()));
        } catch (IllegalArgumentException ignored) {
            // keep the template's difficulty
        }

        setupBorder(w);
        fillLoot(w);
        spawnMobs(w);
        pickExtracts(raid);
        raid.phases = parsePhases();
        teleportPlayers(raid);

        raid.state = Raid.State.RUNNING;
        raid.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(raid), 5L, 5L);
        plugin.getLogger().info("Raid " + raid.id + " started with " + raid.players.size() + " player(s).");
    }

    private void setupBorder(World w) {
        WorldBorder b = w.getWorldBorder();
        b.setCenter(cfg().getDouble("raid.border.center-x", 0), cfg().getDouble("raid.border.center-z", 0));
        b.setSize(cfg().getDouble("raid.border.start-size", 500));
        b.setDamageAmount(cfg().getDouble("raid.border.damage-per-block", 2.0));
        b.setDamageBuffer(0);
        b.setWarningDistance(20);
    }

    private void fillLoot(World w) {
        for (MapData.LootPoint lp : plugin.mapData().loot()) {
            Block b = w.getBlockAt(lp.x(), lp.y(), lp.z());
            if (!(b.getState() instanceof Container)) {
                b.setType(Material.CHEST);
            }
            if (b.getState() instanceof Container c) {
                plugin.loot().fill(c.getInventory(), lp.table());
            }
        }
    }

    private void spawnMobs(World w) {
        for (String entry : cfg().getStringList("raid.mobs")) {
            String[] parts = entry.split(":");
            if (parts.length != 2) continue;
            EntityType type;
            int count;
            try {
                type = EntityType.valueOf(parts[0].trim().toUpperCase());
                count = Integer.parseInt(parts[1].trim());
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Bad raid.mobs entry: " + entry);
                continue;
            }
            for (int i = 0; i < count; i++) {
                Location l = randomSurface(w, 0.9);
                if (l == null) continue;
                try {
                    Entity e = w.spawnEntity(l, type);
                    if (e instanceof LivingEntity le) le.setRemoveWhenFarAway(false);
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Cannot spawn " + type + " in raids.");
                    break;
                }
            }
        }
    }

    private void pickExtracts(Raid raid) {
        List<MapData.ExtractPoint> all = new ArrayList<>(plugin.mapData().extracts());
        Collections.shuffle(all);
        int n = Math.max(1, cfg().getInt("raid.active-extract-points", 3));
        raid.active.addAll(all.subList(0, Math.min(n, all.size())));
    }

    private List<int[]> parsePhases() {
        List<int[]> out = new ArrayList<>();
        for (String s : cfg().getStringList("raid.border.phases")) {
            String[] p = s.split(":");
            if (p.length != 3) continue;
            try {
                out.add(new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
                        Integer.parseInt(p[2].trim())});
            } catch (NumberFormatException ex) {
                plugin.getLogger().warning("Bad border phase: " + s);
            }
        }
        out.sort(Comparator.comparingInt(a -> a[0]));
        return out;
    }

    private void teleportPlayers(Raid raid) {
        List<MapData.SpawnPoint> spawns = new ArrayList<>(plugin.mapData().spawns());
        Collections.shuffle(spawns);
        long protectMs = cfg().getLong("raid.spawn-protection-seconds", 10L) * 1000L;
        int i = 0;

        for (UUID u : raid.players) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;

            Location loc;
            if (!spawns.isEmpty()) {
                MapData.SpawnPoint s = spawns.get(i++ % spawns.size());
                loc = new Location(raid.world, s.x(), s.y(), s.z(), s.yaw(), 0f);
            } else {
                loc = randomSurface(raid.world, 0.8);
                if (loc == null) loc = raid.world.getSpawnLocation();
            }

            p.closeInventory(); // also saves an open stash so it can't be used inside the raid
            p.teleport(loc);
            p.setGameMode(GameMode.SURVIVAL);
            heal(p);
            if (raid.scavs.contains(u)) giveScavKit(p);
            raid.protectedUntil.put(u, System.currentTimeMillis() + protectMs);

            Msg.title(p, "<gold><bold>RAID STARTED", "<gray>Loot up, then reach an extraction point");
            Msg.send(p, "Extraction opens in <white>" + clock(cfg().getInt("raid.extract-open-after-seconds", 120))
                    + "</white>. The border will shrink. Good luck.");
        }
    }

    private void giveScavKit(Player p) {
        PlayerInventory inv = p.getInventory();
        for (String line : cfg().getStringList("raid.scav-kit")) {
            String[] parts = line.split(":");
            Material m = Material.matchMaterial(parts[0].trim());
            if (m == null || !m.isItem()) continue;
            int amount = 1;
            if (parts.length > 1) {
                try {
                    amount = Math.max(1, Integer.parseInt(parts[1].trim()));
                } catch (NumberFormatException ignored) {
                    // keep 1
                }
            }
            ItemStack stack = new ItemStack(m, amount);
            String n = m.name();
            if (n.endsWith("_HELMET") && inv.getHelmet() == null) inv.setHelmet(stack);
            else if (n.endsWith("_CHESTPLATE") && inv.getChestplate() == null) inv.setChestplate(stack);
            else if (n.endsWith("_LEGGINGS") && inv.getLeggings() == null) inv.setLeggings(stack);
            else if (n.endsWith("_BOOTS") && inv.getBoots() == null) inv.setBoots(stack);
            else inv.addItem(stack);
        }
    }

    /** A random solid, dry surface position inside the (start) border, or null if none was found. */
    private Location randomSurface(World w, double factor) {
        WorldBorder b = w.getWorldBorder();
        double half = b.getSize() / 2.0 * factor;
        Location c = b.getCenter();
        ThreadLocalRandom r = ThreadLocalRandom.current();

        for (int attempt = 0; attempt < 30; attempt++) {
            int x = (int) Math.floor(c.getX() + r.nextDouble(-half, half));
            int z = (int) Math.floor(c.getZ() + r.nextDouble(-half, half));
            Block top = w.getHighestBlockAt(x, z);
            Material m = top.getType();
            if (top.isLiquid() || !m.isSolid() || m.name().endsWith("LEAVES")) continue;
            return new Location(w, x + 0.5, top.getY() + 1.0, z + 0.5);
        }
        return null;
    }

    // ------------------------------------------------------------------ raid tick

    private void tick(Raid raid) {
        if (raid.state != Raid.State.RUNNING) return;

        raid.ticks += 5;
        int sec = raid.ticks / 20;
        int duration = cfg().getInt("raid.duration-seconds", 1200);
        int openAt = cfg().getInt("raid.extract-open-after-seconds", 120);
        int holdTicks = cfg().getInt("raid.extract-hold-seconds", 10) * 20;
        boolean open = sec >= openAt;
        boolean onSecond = raid.ticks % 20 == 0;

        // shrinking border
        while (raid.nextPhase < raid.phases.size() && sec >= raid.phases.get(raid.nextPhase)[0]) {
            int[] ph = raid.phases.get(raid.nextPhase++);
            raid.world.getWorldBorder().setSize(ph[1], ph[2]);
            broadcast(raid, "<red>The border is closing in! Shrinking to <white>" + ph[1] + "</white> blocks.");
        }

        // extraction opens
        if (open && !raid.extractAnnounced) {
            raid.extractAnnounced = true;
            broadcast(raid, "<green><bold>Extraction points are now open!");
            if (cfg().getBoolean("raid.reveal-extract-coords", true)) {
                for (MapData.ExtractPoint e : raid.active) {
                    broadcast(raid, "<gray> - <white>" + e.name() + "</white> at <white>"
                            + (int) e.x() + ", " + (int) e.y() + ", " + (int) e.z());
                }
            }
        }
        if (open && onSecond) drawZones(raid);

        for (UUID u : new ArrayList<>(raid.players)) {
            Player p = Bukkit.getPlayer(u);
            if (p == null || !p.isOnline()) {
                removeFromRaid(u);
                continue;
            }
            if (p.isDead()) continue;

            MapData.ExtractPoint zone = open ? zoneAt(raid, p) : null;
            if (zone != null) {
                int prog = raid.progress.merge(u, 5, Integer::sum);
                if (prog >= holdTicks) {
                    extract(p);
                    continue;
                }
                Msg.bar(p, "<green>Extracting at <white>" + zone.name() + "</white> - hold position: <white>"
                        + ((holdTicks - prog + 19) / 20) + "s");
            } else if (raid.progress.remove(u) != null) {
                Msg.bar(p, "<red>Extraction cancelled");
            } else if (onSecond) {
                Msg.bar(p, "<yellow>Time left: <white>" + clock(duration - sec)
                        + (open ? "" : " <gray>| extraction opens in <white>" + clock(openAt - sec)));
            }
        }

        if (sec >= duration) {
            endByTime(raid);
        } else if (raid.players.isEmpty()) {
            endRaid(raid);
        }
    }

    private MapData.ExtractPoint zoneAt(Raid raid, Player p) {
        if (!p.getWorld().equals(raid.world)) return null;
        Location l = p.getLocation();
        for (MapData.ExtractPoint e : raid.active) {
            double dx = l.getX() - e.x();
            double dz = l.getZ() - e.z();
            if (dx * dx + dz * dz <= e.radius() * e.radius() && Math.abs(l.getY() - e.y()) <= 4.0) {
                return e;
            }
        }
        return null;
    }

    private void drawZones(Raid raid) {
        for (MapData.ExtractPoint e : raid.active) {
            int points = 24;
            for (int i = 0; i < points; i++) {
                double a = 2 * Math.PI * i / points;
                raid.world.spawnParticle(Particle.END_ROD,
                        e.x() + Math.cos(a) * e.radius(), e.y() + 0.5, e.z() + Math.sin(a) * e.radius(),
                        1, 0, 0, 0, 0);
            }
            for (int h = 0; h < 8; h++) {
                raid.world.spawnParticle(Particle.END_ROD, e.x(), e.y() + h, e.z(), 1, 0.1, 0, 0.1, 0);
            }
        }
    }

    private void extract(Player p) {
        removeFromRaid(p.getUniqueId());
        toHub(p);
        heal(p);
        Msg.title(p, "<green><bold>EXTRACTED", "<gray>You made it out alive");
        Msg.send(p, "You extracted with everything you were carrying.");
    }

    private void endByTime(Raid raid) {
        for (UUID u : new ArrayList<>(raid.players)) {
            Player p = Bukkit.getPlayer(u);
            removeFromRaid(u);
            if (p == null) continue;
            p.getInventory().clear();
            toHub(p);
            heal(p);
            Msg.title(p, "<red><bold>MISSING IN ACTION", "<gray>You did not extract in time. Gear lost.");
        }
        endRaid(raid);
    }

    private void endRaid(Raid raid) {
        if (raid.state == Raid.State.ENDING) return;
        raid.state = Raid.State.ENDING;
        if (raid.task != null) raid.task.cancel();
        for (UUID u : new ArrayList<>(raid.players)) removeFromRaid(u);
        raids.remove(raid.id);
        plugin.getLogger().info("Raid " + raid.id + " ended.");
        if (raid.world != null) cleanupWorld(raid.world, raid.worldName);
    }

    /** Waits until the world is empty (dead players are respawned to the hub), then unloads and deletes it. */
    private void cleanupWorld(World w, String name) {
        new BukkitRunnable() {
            int tries = 0;

            @Override
            public void run() {
                tries++;
                for (Player p : new ArrayList<>(w.getPlayers())) {
                    if (p.isDead()) {
                        if (tries >= 3) p.spigot().respawn();
                    } else {
                        toHub(p);
                    }
                }
                if (!w.getPlayers().isEmpty()) return;

                if (!Bukkit.unloadWorld(w, false)) {
                    if (tries < 30) return;
                    plugin.getLogger().warning("Could not unload " + name + "; it will be deleted on next start.");
                    cancel();
                    return;
                }
                cancel();
                deleteAsync(new File(Bukkit.getWorldContainer(), name));
            }
        }.runTaskTimer(plugin, 40L, 40L);
    }

    // ------------------------------------------------------------------ events from listeners

    public void handleDeath(Player p) {
        Raid r = playerRaid.get(p.getUniqueId());
        if (r == null) return;
        removeFromRaid(p.getUniqueId());
        if (r.state == Raid.State.RUNNING) {
            broadcast(r, "<red>A raider has fallen. <white>" + r.players.size() + "</white> remaining.");
            if (r.players.isEmpty()) endRaid(r);
        }
    }

    public void handleQuit(Player p) {
        UUID id = p.getUniqueId();
        boolean wasQueued = queue.remove(id) != null;

        Raid r = playerRaid.get(id);
        if (r != null) {
            if (r.state == Raid.State.RUNNING && r.world != null && p.getWorld().equals(r.world)) {
                // disconnecting in a raid counts as dying: gear drops where you stood
                Location loc = p.getLocation();
                for (ItemStack it : p.getInventory().getContents()) {
                    if (it != null && !it.getType().isAir()) {
                        p.getWorld().dropItemNaturally(loc, it);
                    }
                }
                p.getInventory().clear();
            }
            removeFromRaid(id);
            if (r.state == Raid.State.RUNNING) {
                broadcast(r, "<red>A raider disconnected. <white>" + r.players.size() + "</white> remaining.");
                if (r.players.isEmpty()) endRaid(r);
            }
        }
        if (wasQueued) updateCountdown();
    }

    /** Taking damage cancels an extraction in progress. */
    public void onDamaged(Player p) {
        Raid r = playerRaid.get(p.getUniqueId());
        if (r != null && r.progress.remove(p.getUniqueId()) != null) {
            Msg.bar(p, "<red>Extraction interrupted!");
        }
    }

    // ------------------------------------------------------------------ admin / lifecycle

    /** Admin command: send everyone in raids back to the hub with their gear and end all raids. */
    public int endAll() {
        int n = raids.size();
        for (Raid r : new ArrayList<>(raids.values())) {
            for (UUID u : new ArrayList<>(r.players)) {
                Player p = Bukkit.getPlayer(u);
                removeFromRaid(u);
                if (p != null) {
                    toHub(p);
                    Msg.send(p, "The raid was ended by an admin.");
                }
            }
            if (r.state == Raid.State.RUNNING) {
                endRaid(r);
            } else {
                r.state = Raid.State.ENDING;
                raids.remove(r.id);
            }
        }
        return n;
    }

    /** Server shutdown: return everyone to the hub with their gear. Raid worlds are deleted on next start. */
    public void shutdown() {
        cancelCountdown();
        for (Raid r : new ArrayList<>(raids.values())) {
            if (r.task != null) r.task.cancel();
            for (UUID u : new ArrayList<>(r.players)) {
                Player p = Bukkit.getPlayer(u);
                removeFromRaid(u);
                if (p != null) toHub(p);
            }
        }
        raids.clear();
        queue.clear();
    }

    public void deleteLeftoverWorlds() {
        File[] dirs = Bukkit.getWorldContainer().listFiles(File::isDirectory);
        if (dirs == null) return;
        for (File d : dirs) {
            if (d.getName().startsWith(WORLD_PREFIX) && !d.getName().equals(templateName())) {
                plugin.getLogger().info("Deleting leftover raid world " + d.getName());
                deleteRecursive(d);
            }
        }
    }

    // ------------------------------------------------------------------ file utils

    private static void copyWorld(Path src, Path dst) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path fileName = p.getFileName();
                String name = fileName == null ? "" : fileName.toString();
                // uid.dat must not be copied or Bukkit sees two worlds with the same UUID
                if (name.equals("uid.dat") || name.equals("session.lock")) continue;

                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Path parent = target.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void deleteAsync(File dir) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> deleteRecursive(dir));
    }

    private static void deleteRecursive(File f) {
        if (!f.exists()) return;
        try (Stream<Path> walk = Files.walk(f.toPath())) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
