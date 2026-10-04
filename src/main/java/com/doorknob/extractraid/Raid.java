package com.doorknob.extractraid;

import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** State of one running raid instance. */
final class Raid {

    enum State { LOADING, RUNNING, ENDING }

    final int id;
    final String worldName;

    State state = State.LOADING;
    World world;
    BukkitTask task;

    final Set<UUID> players = new LinkedHashSet<>();
    final Set<UUID> scavs = new HashSet<>();
    /** Ticks each player has spent inside an extraction zone. */
    final Map<UUID, Integer> progress = new HashMap<>();
    /** Spawn protection expiry (epoch millis). */
    final Map<UUID, Long> protectedUntil = new HashMap<>();
    final List<MapData.ExtractPoint> active = new ArrayList<>();

    /** Border phases: {startSecond, newSize, shrinkSeconds}. */
    List<int[]> phases = new ArrayList<>();
    int nextPhase;
    int ticks;
    boolean extractAnnounced;

    Raid(int id, String worldName) {
        this.id = id;
        this.worldName = worldName;
    }
}
