# [BD][BDur] Block Durability (v2.0.0)

Adds durability to Minecraft blocks, so firearms and explosions can wear them down step by step.

## Demo

<table>
  <tr>
    <td width="50%">
      <img src="assets/block-durability-explosion.gif" alt="Heavy explosion demo" width="100%">
      <p align="center">Fire!</p>
    </td>
    <td width="50%">
      <img src="assets/block-durability-rpg.gif" alt="RPG explosion demo" width="100%">
      <p align="center">RPG explosion damage</p>
    </td>
  </tr>
  <tr>
    <td width="50%">
      <img src="assets/block-durability-explosion-spray.gif" alt="Continuous explosion demo" width="100%">
      <p align="center">"Explosion Spray"</p>
    </td>
    <td width="50%">
      <img src="assets/block-durability-3x3x3-high-explosive.gif" alt="3x3x3 high-explosive demo" width="100%">
      <p align="center">3x3x3 high-explosive shells blasting obsidian</p>
    </td>
  </tr>
</table>

<p align="center">
  <a href="assets/block-durability-demo.gif">
    <img src="assets/block-durability-demo.gif" alt="Block Durability gunfire demo" width="720">
  </a>
</p>

## Explosion Durability

The v2.0.0 main branch targets Minecraft 1.21.1 NeoForge and focuses on tying explosions into block durability:

- **TaCZ explosives**: RPGs, grenades and similar explosions that normally only deal impact and do not break terrain now apply durability damage to damageable blocks in range, scaled by distance.
- **Vanilla and other explosions**: TNT, creepers, Create cannons and similar explosions still destroy blocks with their original logic; damageable blocks in range that were not destroyed outright still take durability damage that falls off with distance.
- **Cover**: solid collision shapes between the explosion and a block shield it from durability damage, reducing damage through walls.
- **Configurable**: the explosion damage multiplier, processing radius, vanilla-explosion toggle, block HP and block list can all be adjusted in the server config.

> Only blocks on the block list are affected by this durability system (set `blacklistMode = true` to invert the list). The actual result depends on block hardness, HP overrides, the explosion's position and the configured multipliers.

## Obsidian Can Be Blasted Open Too

Obsidian no longer has to rely on vanilla's "cannot be blown up" rule. Its durability comes from the hardness formula, so you don't need to hard-code 150:

```text
block HP = max(minBlockHP, vanilla block hardness × hardnessMultiplier)
```

Obsidian's vanilla hardness is `50`. Add it to the block list and set `hardnessMultiplier` to `3.0`:

```toml
[block_list]
blockList = [
    "minecraft:obsidian"
]

[hp_calculation]
minBlockHP = 1.0
hardnessMultiplier = 3.0
blockHPOverrides = []
```

Plugging that into the formula, obsidian HP is `max(1.0, 50 × 3.0) = 150`. With these durability settings it takes roughly **20-30 high-explosive shells from a Create cannon** to break through obsidian. The exact number depends on where the shells land, the blast radius, the damage multiplier and other settings, so tune it to your server's pacing.

## Compatible Versions

| Branch | Minecraft | Loader | Role |
| :--- | :--- | :--- | :--- |
| `master` | 1.21.1 | NeoForge | Current main development branch |
| `forge-1.20.1` | 1.20.1 | MinecraftForge | Long-term maintenance branch |

The main branch requires NeoForge 21.1.80 or newer. [TaCZ](https://modrinth.com/mod/tacz-1.21.1) is an optional dependency; without it the mod still handles explosion durability for vanilla and other mods.

## Basic Configuration

The config file is the server-side `config/blockdurability-common.toml`. Common options:

```toml
[general]
enabled = true
damagePerHit = 1.0
useBulletDamage = false
bulletDamageMultiplier = 1.0

[explosion]
enabled = true
damageMultiplier = 1.0
maxRadius = 16.0
handleVanillaExplosions = true

[healing]
healTimeoutSeconds = 120
```

- `damageMultiplier`: multiplier for explosion durability damage.
- `maxRadius`: the largest radius scanned for a single explosion, which caps the extra cost of very large explosions.
- `handleVanillaExplosions`: whether to handle non-TaCZ explosions such as TNT and creepers.
- `healTimeoutSeconds`: how long after the last hit a damaged block recovers; set to `0` to disable healing.

The block list supports block tags, exact IDs and `*` wildcards:

```toml
[block_list]
blockList = [
    "tag:minecraft:planks",
    "tag:minecraft:logs",
    "minecraft:obsidian",
    "minecraft:*glass"
]
```

A block's max HP is computed from vanilla hardness by default. `blockHPOverrides` is only for giving individual blocks a fixed HP value and can normally stay empty. These overrides are **global**: they apply on all land, and `hpMultiplier` values from the `[claims]` section do not scale them:

```toml
[hp_calculation]
hardnessMultiplier = 1.5
minBlockHP = 1.0
blockHPOverrides = []
```

If you really need to override one block's computed result, add an entry in the form `"minecraft:block_id=value"`. With FTB Chunks installed, `[claims.claimed]` has its own `blockHPOverrides` that replace these global values on claimed land (see below).

## Per-Gun Overrides (TaCZ)

By default every bullet hit deals `damagePerHit` (or the bullet's own damage × `bulletDamageMultiplier` when `useBulletDamage` is on). `gunOverrides` in `[general]` lets you change the block damage and enable penetration for specific guns:

```toml
[general]
gunOverrides = [
    "tacz:ak47|damage=2.0",
    "tacz:m700|damage=10|penetration=true",
    "tacz:m4a1|penetration=true",
    "tacz:*|damage=0.5"
]
```

- Format: `"modid:gun_id|damage=value|penetration=true/false"`. Wildcards work in the gun id. Both keys are optional.
- The first matching entry wins, so put specific guns above wildcards. Keys an entry leaves out use the normal behavior; they do not fall through to later entries.
- `damage` is the exact block damage per hit for that gun. `damagePerHit`, `useBulletDamage` and `bulletDamageMultiplier` are ignored for it. `0` means the gun never damages blocks.
- `penetration` (default `false`): when a hit does more damage than the block's remaining HP, the block is destroyed and the leftover damage carries on to the next block the bullet would hit, and so on until it runs out. A block that cannot be damaged (not damageable per the block list, or durability disabled or invulnerable on that land) stops the bullet.
- The `damageMultiplier` of the land (see below) still applies to every block hit, so claimed land can stay tougher. Leftover damage is tracked before that multiplier, so each block applies its own land's value.
- Only affects bullet hits. Explosions (RPGs, grenades, TNT) use the `[explosion]` settings.

## Land Claim Compatibility (FTB Chunks, optional)

With [FTB Chunks](https://modrinth.com/mod/ftb-chunks) (1.21.1 NeoForge) installed, block durability distinguishes **claimed** from **unclaimed** land. Each has its own settings, and the claimed values can be overridden per **team**. Without FTB Chunks, all land is treated as unclaimed and the mod behaves exactly as before.

```toml
[claims]
ftbChunksIntegration = true

[claims.unclaimed]          # land not claimed by any team
enabled = true              # false = block durability is off here
hpMultiplier = 1.0          # multiplier on block max HP
damageMultiplier = 1.0      # durability damage multiplier (bullets and explosions), 0 = invulnerable
healTimeoutSeconds = -1     # -1 = use the global heal setting, 0 = never heal

[claims.claimed]            # land inside any team's claim (the baseline for all teams)
enabled = true
hpMultiplier = 1.0
damageMultiplier = 1.0
healTimeoutSeconds = -1
blockHPOverrides = []       # exact HP per block on claimed land only, e.g. "minecraft:obsidian=300"

[claims.teams]
overrides = [
    "Dragons|hpMultiplier=3.0|damageMultiplier=0.5",
    "Builders|enabled=false",
    "Raiders|healTimeoutSeconds=600"
]
```

- Multipliers stack on top of the existing values in `hp_calculation`, `general` and `explosion`; the defaults (all 1.0) change nothing.
- Each `overrides` entry has the form `"<team>|key=value|key=value"`. `<team>` can be the FTB team's UUID, short name or display name (case-insensitive). Only the listed keys are replaced; the rest come from `[claims.claimed]`.
- Available keys: `enabled`, `hpMultiplier`, `damageMultiplier`, `healTimeoutSeconds`.
- Block HP is looked up in this order: `[claims.claimed] blockHPOverrides` (claimed land only), then the global `[hp_calculation] blockHPOverrides` (all land), then the hardness formula multiplied by the land's `hpMultiplier`. Overrides are exact values and are never scaled by `hpMultiplier`. Only claimed land has its own override list; unclaimed land just uses the global one.
- When land is claimed, unclaimed or changes owner, existing damage progress is reset.

## In-Game Rule

Admins can adjust the recovery time of damaged blocks on the fly:

```text
/gamerule blockDurabilityHealSeconds <seconds>
```

For example, `/gamerule blockDurabilityHealSeconds 60` makes blocks recover after 60 seconds, and `0` disables healing.

## Building

Requires JDK 21:

```bash
./gradlew build
./gradlew test
```

The build output is `build/libs/blockdurability-2.0.0-neoforge.jar`.

## License

This project is open source under the [MIT License](LICENSE).
