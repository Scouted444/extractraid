# ExtractRaid

Instanced extraction raids for Paper 1.21.x. Players keep a persistent stash in a hub, queue for a raid, loot a temporary copy of a map, and extract alive to keep what they carry. Die, disconnect, or run out the clock and the gear is gone.

**Status: written but not compiled or tested yet.** If the build prints an error, send it to me and I will fix it.

## What it does

- **Lobby with pads**: `/raidadmin lobby create` builds a finished lobby in its own void world. Players walk onto colored pads: green = queue, orange = scav run, blue = stash, red = leave queue. Commands (`/raid queue`, `/raid scav`, `/stash`, `/raid leave`) work too.
- **Instanced raids**: the `raid_template` world folder is copied to `xr_<n>` for each raid and deleted afterwards. Nothing persists between raids.
- **Rarity loot**: six rarity pools (junk, common, uncommon, rare, epic, legendary) plus themed pools, and mixed tables (`loose`, `standard`, `supply`, `military_crate`, `vault`, `jackpot`) that roll a rarity per item. Items can carry enchantments and a colored rarity line in their lore.
- **Mobs**, **shrinking border**, **extraction zones** (hold 10 seconds, damage cancels), **spawn protection**, **scav runs**.
- **Losing**: dying drops your gear where you died. Logging out inside a raid drops it too. Anyone still inside at the time limit is MIA and loses everything. Ender chests and portals are blocked in raids. Dead players respawn in the hub.

## Install (exaroton)

1. Build the jar on your PC: install JDK 21 and Maven, then run `build.bat`. The jar is `target\ExtractRaid.jar`.
2. In exaroton, set the software to **Paper 1.21.4**, upload the jar to the `plugins` folder, start the server and `op` yourself.
3. `spawn-protection=0`, `view-distance=8`, `simulation-distance=6` in server.properties are recommended.

Local servers: use `server\start.bat` with a Paper jar named `paper.jar`.

## Making the map (fast way)

1. `/raidadmin lobby create` for the hub. Done: it sets the hub, builds the platform and places the pads.
2. `/raidadmin template` (or `template flat` / `template void` for a builder-friendly blank map). If you already have a map, copy its world folder to the server root as `raid_template` before running this.
3. Build or import your map. Make sure it fits the border (`raid.border.start-size`, default 500, centered on 0,0).
4. `/raidadmin autoloot 150 standard` registers every chest and barrel within 150 blocks of you as the `standard` table. Run it again with a different table near special buildings, e.g. `/raidadmin autoloot 20 vault` next to a bunker. The later command wins for containers it covers.
5. `/raidadmin wand` gives you the Setup Wand for fine-tuning (see below). `/raidadmin show` previews everything with particles so you can see what is registered.
6. Add extraction zones with the wand (EXTRACT mode, right-click the ground) or `/raidadmin addextract`. Put 4-6 around the map; 3 are chosen per raid.
7. `/raidadmin pregen 300` generates the chunks so raids do not lag on first load, then `/raidadmin savetemplate`.
8. `/raidadmin info` shows a checklist of what is still missing. To test alone: `queue.min-players: 1` in `config.yml`, `/raidadmin reload`, then step on the green pad.

### The Setup Wand (blaze rod, template world only)

- Left-click: next mode (LOOT, EXTRACT, SPAWN)
- Shift + left-click: LOOT = next loot table, EXTRACT = next radius
- Right-click a block: LOOT registers that container with the selected table, EXTRACT puts a zone on top of the block, SPAWN adds a player spawn point
- Shift + right-click: removes that loot container, the nearest zone (within 15 blocks), or the nearest spawn point

## Rarities and loot tables

Everything is in `config.yml` under `loot-tables`.

- **Item pools**: `"MATERIAL:amount:weight"` or `"MATERIAL:amount:weight:ENCHANT=level,ENCHANT=level"`, e.g. `"DIAMOND_SWORD:1:4:SHARPNESS=4,UNBREAKING=3"` or `"ENCHANTED_BOOK:1:3:MENDING=1"`. `label` is the rarity line in the item lore, `color` is the preview particle color.
- **Mixed tables**: a `mix:` map of pool name to weight. Each roll picks a pool by weight, then an item from it. Example: `vault` is 40% rare, 40% epic, 10% legendary, 10% valuables. Add your own tiers or tables freely and use them in `autoloot` and the wand.
- Set `rarity-lore: false` if you do not want rarity lore (items of different rarities then stack).

## Commands

Players: `/raid queue|scav|leave|status`, `/stash`.

Admins (`extractraid.admin`, op by default): `/raidadmin info`, `lobby create|build [radius]`, `template [normal|flat|void]`, `savetemplate`, `wand`, `show`, `autoloot [radius] [table]`, `pregen [radius]`, `addextract [name] [radius]` (both optional), `delextract`, `addloot [table]`, `delloot`, `clearloot`, `addspawn`, `clearspawns`, `pad [queue|scav|stash|leave]`, `delpad`, `sethub`, `reload`, `start`, `endall`.

## Known limits

- `lobby build` clears 5 blocks of space above the platform where you stand. Prefer `lobby create`, which uses its own empty world.
- Double chests count as two registered containers; both halves are refilled, which is harmless.
- Items dropped on death despawn after Minecraft's normal 5 minutes.
- The stash saves on every click and on close, but a hard crash while a stash is open can lose a few seconds of changes.
- Lowering `stash.rows` moves overflow items into free slots, so only do it when stashes are mostly empty.
- Built against the Paper 1.21.4 API. Newer 1.21.x should load it, but rebuild and test before putting players on it.
- Not included yet: shop, insurance, custom item models, gear-based matchmaking, multiple maps or lobbies.
