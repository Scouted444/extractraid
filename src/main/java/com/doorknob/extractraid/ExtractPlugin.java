package com.doorknob.extractraid;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class ExtractPlugin extends JavaPlugin {

    private MapData mapData;
    private StashManager stash;
    private LootManager loot;
    private RaidManager raids;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        mapData = new MapData(this);
        stash = new StashManager(this);
        loot = new LootManager(this);
        raids = new RaidManager(this);
        raids.deleteLeftoverWorlds();

        getServer().getPluginManager().registerEvents(new RaidListener(this), this);

        Commands commands = new Commands(this);
        for (String name : new String[]{"raid", "stash", "raidadmin"}) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(commands);
                cmd.setTabCompleter(commands);
            }
        }

        getLogger().info("ExtractRaid enabled.");
    }

    @Override
    public void onDisable() {
        if (raids != null) raids.shutdown();
        if (stash != null) stash.saveAllOpen();
    }

    public void reloadAll() {
        reloadConfig();
        loot.reload();
        mapData.load();
    }

    public MapData mapData() {
        return mapData;
    }

    public StashManager stash() {
        return stash;
    }

    public LootManager loot() {
        return loot;
    }

    public RaidManager raids() {
        return raids;
    }
}
