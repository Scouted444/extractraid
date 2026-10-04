package com.doorknob.extractraid;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class Commands implements TabExecutor {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,24}");

    private final ExtractPlugin plugin;

    public Commands(ExtractPlugin plugin) {
        this.plugin = plugin;
    }

    private RaidManager rm() {
        return plugin.raids();
    }

    private String templateName() {
        return plugin.getConfig().getString("template-world", "raid_template");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "raid" -> raid(sender, args);
            case "stash" -> stash(sender);
            case "raidadmin" -> admin(sender, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ /raid and /stash

    private void raid(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Msg.send(sender, "Players only.");
            return;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "queue", "join" -> rm().queueJoin(p, false);
            case "scav" -> rm().queueJoin(p, true);
            case "leave" -> rm().queueLeave(p);
            case "status" -> rm().status(p);
            default -> {
                Msg.send(p, "<white>/raid queue</white> - enter the queue with your current loadout");
                Msg.send(p, "<white>/raid scav</white> - free junk-gear run (needs an empty inventory)");
                Msg.send(p, "<white>/raid leave</white> - leave the queue");
                Msg.send(p, "<white>/raid status</white> - queue and raid info");
                Msg.send(p, "<white>/stash</white> - open your stash (hub only)");
            }
        }
    }

    private void stash(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            Msg.send(sender, "Players only.");
            return;
        }
        if (rm().isInRaid(p) || !rm().isHubWorld(p.getWorld())) {
            Msg.send(p, "<red>Your stash can only be opened in the hub.");
            return;
        }
        plugin.stash().open(p);
    }

    // ------------------------------------------------------------------ /raidadmin

    private void admin(CommandSender sender, String[] args) {
        if (args.length == 0) {
            help(sender);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        MapData map = plugin.mapData();

        switch (sub) {
            case "reload" -> {
                plugin.reloadAll();
                Msg.send(sender, "Config, loot tables and map data reloaded.");
            }
            case "info" -> {
                Msg.send(sender, "Template world: <white>" + templateName() + "</white>");
                Msg.send(sender, "Extraction zones: <white>" + map.extracts().size() + "</white>  (" +
                        String.join(", ", map.extractNames()) + ")");
                Msg.send(sender, "Loot containers: <white>" + map.loot().size() + "</white>");
                Msg.send(sender, "Spawn points: <white>" + map.spawns().size() + "</white>"
                        + (map.spawns().isEmpty() ? " (random surface spawns are used)" : ""));
                Msg.send(sender, "Loot tables: <white>" + String.join(", ", plugin.loot().names()) + "</white>");
            }
            case "start" -> {
                int n = rm().forceStart();
                Msg.send(sender, n == 0 ? "Nobody is queued." : "Starting a raid with <white>" + n + "</white> queued player(s).");
            }
            case "endall" -> {
                int n = rm().endAll();
                Msg.send(sender, "Ended <white>" + n + "</white> raid(s). Players were returned to the hub with their gear.");
            }
            case "sethub" -> {
                Player p = needPlayer(sender);
                if (p == null) return;
                map.setHub(p.getLocation());
                Msg.send(p, "Hub set to your position.");
            }
            case "template" -> {
                Player p = needPlayer(sender);
                if (p == null) return;
                World w = Bukkit.getWorld(templateName());
                if (w == null) {
                    Msg.send(p, "Loading template world <white>" + templateName() + "</white> (it is created if the folder does not exist)...");
                    w = new WorldCreator(templateName()).createWorld();
                }
                if (w == null) {
                    Msg.send(p, "<red>Could not load the template world. Check the console.");
                    return;
                }
                p.setGameMode(GameMode.CREATIVE);
                p.teleport(w.getSpawnLocation());
                Msg.send(p, "You are in the template world. Build, then register things with /raidadmin, then <white>/raidadmin savetemplate</white>.");
            }
            case "savetemplate" -> {
                World w = Bukkit.getWorld(templateName());
                if (w == null) {
                    Msg.send(sender, "The template world is not loaded.");
                    return;
                }
                for (Player pl : new ArrayList<>(w.getPlayers())) {
                    pl.teleport(map.hub());
                    pl.setGameMode(GameMode.SURVIVAL);
                }
                w.save();
                Bukkit.unloadWorld(w, true);
                Msg.send(sender, "Template saved and unloaded. New raids will use this version.");
            }
            case "addextract" -> {
                Player p = needTemplatePlayer(sender);
                if (p == null) return;
                if (args.length < 3 || !NAME.matcher(args[1]).matches()) {
                    Msg.send(p, "Usage: <white>/raidadmin addextract [name] [radius]</white> (name: letters, digits, _ or -)");
                    return;
                }
                double radius;
                try {
                    radius = Double.parseDouble(args[2]);
                } catch (NumberFormatException ex) {
                    Msg.send(p, "<red>Radius must be a number.");
                    return;
                }
                if (radius < 1 || radius > 30) {
                    Msg.send(p, "<red>Radius must be between 1 and 30.");
                    return;
                }
                map.addExtract(args[1], p.getLocation(), radius);
                Msg.send(p, "Extraction zone <white>" + args[1] + "</white> saved at your position.");
            }
            case "delextract" -> {
                if (args.length < 2) {
                    Msg.send(sender, "Usage: <white>/raidadmin delextract [name]</white>");
                    return;
                }
                Msg.send(sender, map.removeExtract(args[1]) ? "Zone removed." : "<red>No zone with that name.");
            }
            case "addspawn" -> {
                Player p = needTemplatePlayer(sender);
                if (p == null) return;
                map.addSpawn(p.getLocation());
                Msg.send(p, "Player spawn point added (" + map.spawns().size() + " total).");
            }
            case "clearspawns" -> {
                map.clearSpawns();
                Msg.send(sender, "All spawn points cleared. Random surface spawns will be used.");
            }
            case "addloot" -> {
                Player p = needTemplatePlayer(sender);
                if (p == null) return;
                if (args.length < 2 || !plugin.loot().has(args[1])) {
                    Msg.send(p, "Usage: <white>/raidadmin addloot [table]</white>. Tables: <white>"
                            + String.join(", ", plugin.loot().names()) + "</white>");
                    return;
                }
                Block b = p.getTargetBlockExact(6);
                if (b == null || !(b.getState() instanceof Container)) {
                    Msg.send(p, "<red>Look at a chest, barrel or other container (within 6 blocks).");
                    return;
                }
                map.addLoot(b.getX(), b.getY(), b.getZ(), args[1].toLowerCase(Locale.ROOT));
                Msg.send(p, "Container registered with loot table <white>" + args[1].toLowerCase(Locale.ROOT)
                        + "</white> (" + map.loot().size() + " total).");
            }
            case "delloot" -> {
                Player p = needTemplatePlayer(sender);
                if (p == null) return;
                Block b = p.getTargetBlockExact(6);
                if (b == null) {
                    Msg.send(p, "<red>Look at a registered container.");
                    return;
                }
                Msg.send(p, map.removeLoot(b.getX(), b.getY(), b.getZ()) ? "Loot container unregistered."
                        : "<red>That block is not a registered loot container.");
            }
            default -> help(sender);
        }
    }

    private void help(CommandSender s) {
        Msg.send(s, "<gold>ExtractRaid admin</gold>");
        String[] lines = {
                "/raidadmin template - load the template world and teleport there",
                "/raidadmin savetemplate - save and unload the template world",
                "/raidadmin sethub - set the hub spawn to your position",
                "/raidadmin addextract [name] [radius] - extraction zone at your position",
                "/raidadmin delextract [name]",
                "/raidadmin addloot [table] - register the container you look at",
                "/raidadmin delloot - unregister the container you look at",
                "/raidadmin addspawn - player spawn point (optional)",
                "/raidadmin clearspawns",
                "/raidadmin info | reload | start | endall"
        };
        for (String l : lines) Msg.send(s, "<white>" + l);
    }

    private Player needPlayer(CommandSender s) {
        if (s instanceof Player p) return p;
        Msg.send(s, "Players only.");
        return null;
    }

    /** Placement commands only make sense while standing in the template world. */
    private Player needTemplatePlayer(CommandSender s) {
        Player p = needPlayer(s);
        if (p == null) return null;
        Location l = p.getLocation();
        if (!l.getWorld().getName().equals(templateName())) {
            Msg.send(p, "<red>Run this inside the template world (use /raidadmin template).");
            return null;
        }
        return p;
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            List<String> options = switch (name) {
                case "raid" -> List.of("queue", "scav", "leave", "status");
                case "raidadmin" -> List.of("template", "savetemplate", "sethub", "addextract", "delextract",
                        "addloot", "delloot", "addspawn", "clearspawns", "info", "reload", "start", "endall");
                default -> List.of();
            };
            return filter(options, args[0]);
        }
        if (name.equals("raidadmin") && args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("addloot")) return filter(new ArrayList<>(plugin.loot().names()), args[1]);
            if (sub.equals("delextract")) return filter(new ArrayList<>(plugin.mapData().extractNames()), args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) out.add(o);
        }
        return out;
    }
}
