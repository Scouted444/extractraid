# ExtractRaid

Instanced extraction raids for Paper 1.21.x. Players keep a persistent stash in a hub, queue for a raid, loot a temporary copy of a map, and extract alive to keep what they carry. Die, disconnect, or run out the clock and the gear is gone.

**Status: written but not compiled or tested yet.** If the build prints an error, send it to me and I will fix it.

## What it does

- **Hub + stash**: `/stash` opens a 54-slot personal chest (hub only). Hub is protected: no damage, hunger, building, mobs or item dropping.
- **Queue**: `/raid queue` (go in with your inventory), `/raid scav` (empty inventory, get a junk kit, free to lose), `/raid leave`, `/raid status`. Countdown starts at `min-players`, or immediately at `max-players`.
- **Instanced raids**: the `raid_template` world folder is copied to `xr_<n>` for each raid and deleted afterwards. Nothing persists between raids.
- **Loot**: registered chests are refilled from weighted loot tables at raid start.
- **Mobs**: spawned at random surface spots at raid start; natural spawning is off in raids.
- **Shrinking border**: phases in `config.yml`, vanilla border damage outside.
- **Extraction**: a random subset of your registered zones opens after a delay. Stand inside for 10 seconds (taking damage resets it) to return to the hub with your gear.
- **Losing**: dying drops your gear where you died. Logging out inside a raid drops it too. Anyone still inside at the time limit is MIA and loses everything. Ender chests and portals are blocked in raids. Dead players respawn in the hub.

## Install

1. Install JDK 21 and Maven (Windows): `winget install EclipseAdoptium.Temurin.21.JDK` and `winget install Apache.Maven`, then open a new terminal.
2. Run `build.bat` in this folder. Result: `target\ExtractRaid.jar`.
3. Download Paper 1.21.4 (or newer 1.21.x) from papermc.io, rename it `paper.jar`, put it in a server folder with `server\start.bat`.
4. Run `start.bat` once, accept the EULA in `eula.txt`, then put `ExtractRaid.jar` in `plugins\`.
5. Recommended `server.properties`: `spawn-protection=0`, `view-distance=8`, `simulation-distance=6`, `pvp=true`, `difficulty=normal`.
6. Optional but strongly recommended: the Chunky plugin to pregenerate the template map (`/chunky radius 300`, `/chunky start`), otherwise the first raids will lag generating terrain.

## First-time map setup (as op)

1. Build or import your raid map:
   - Existing map: copy its world folder into the server root and name it `raid_template`.
   - From scratch: run `/raidadmin template`. It creates `raid_template` if missing and puts you there in creative.
2. Stand where players should be able to extract and run `/raidadmin addextract Quarry 4` (name, radius in blocks). Add at least 3-5 zones around the map. `active-extract-points` of them are chosen per raid.
3. Place chests or barrels, look at each one and run `/raidadmin addloot common` (or `military` / `rare`). They are cleared and refilled each raid.
4. Optional: `/raidadmin addspawn` where you want players to start. With none set, players spawn at random surface positions.
5. Make sure the map fits inside the border (`raid.border.start-size`, centered on `center-x` / `center-z`).
6. `/raidadmin savetemplate` to save and unload the template. Re-run `/raidadmin template` whenever you want to edit it.
7. In your hub (the main world or a lobby build), stand at the spawn spot and run `/raidadmin sethub`.
8. Test alone: set `queue.min-players: 1` in `config.yml`, `/raidadmin reload`, then `/raid queue`.

## Admin commands

`/raidadmin template`, `savetemplate`, `sethub`, `addextract [name] [radius]`, `delextract [name]`, `addloot [table]`, `delloot`, `addspawn`, `clearspawns`, `info`, `reload`, `start` (force-start the queue), `endall` (end all raids, players keep their gear). Permission: `extractraid.admin` (op by default, grant with LuckPerms).

## Known limits

- The hub should not be a survival world players can leave. Keep the main world as a protected lobby.
- Items dropped on death despawn after Minecraft's normal 5 minutes.
- The stash saves on every click and on close, but a hard server crash while a stash is open can still lose a few seconds of changes.
- Changing `stash.rows` to a smaller value moves overflow items into free slots, so lower it only when stashes are mostly empty.
- Tested target is Paper 1.21.4 API. Newer 1.21.x should load it because `api-version` is 1.21.
- Custom items, matchmaking by gear score, insurance and a shop are not included yet. Good next steps.
