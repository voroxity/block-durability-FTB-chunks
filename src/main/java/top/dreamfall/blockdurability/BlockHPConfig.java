package top.dreamfall.blockdurability;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BlockHPConfig {
    public static final ModConfigSpec SPEC;
    public static final Config CONFIG;

    // ========================
    // General
    // ========================
    public static ModConfigSpec.BooleanValue ENABLED;
    public static ModConfigSpec.DoubleValue DAMAGE_PER_HIT;
    public static ModConfigSpec.BooleanValue USE_BULLET_DAMAGE;
    public static ModConfigSpec.DoubleValue BULLET_DAMAGE_MULTIPLIER;
    public static ModConfigSpec.ConfigValue<List<? extends String>> GUN_OVERRIDES;
    public static ModConfigSpec.IntValue HEAL_TIMEOUT_SECONDS;

    // ========================
    // Block list: by default only matching blocks can be damaged (blacklistMode inverts this)
    // ========================
    public static ModConfigSpec.BooleanValue BLACKLIST_MODE;
    public static ModConfigSpec.ConfigValue<List<? extends String>> BLOCK_LIST;

    // ========================
    // HP calculation: based on block hardness (correlates with explosion resistance)
    // ========================
    public static ModConfigSpec.DoubleValue HARDNESS_MULTIPLIER;
    public static ModConfigSpec.DoubleValue MIN_BLOCK_HP;

    // ========================
    // Optional per-block HP overrides
    // ========================
    public static ModConfigSpec.ConfigValue<List<? extends String>> BLOCK_HP_OVERRIDES;

    // ========================
    // Explosion durability (TaCZ explosions, vanilla TNT, creepers, modded explosions)
    // ========================
    public static ModConfigSpec.BooleanValue EXPLOSION_ENABLED;
    public static ModConfigSpec.DoubleValue EXPLOSION_DAMAGE_MULTIPLIER;
    public static ModConfigSpec.DoubleValue EXPLOSION_MAX_RADIUS;
    public static ModConfigSpec.BooleanValue HANDLE_VANILLA_EXPLOSIONS;

    // ========================
    // Land claims (FTB Chunks, optional): claimed / unclaimed configured separately, with per-team overrides
    // ========================
    public static ModConfigSpec.BooleanValue FTB_CHUNKS_INTEGRATION;
    public static LandSection UNCLAIMED;
    public static LandSection CLAIMED;
    public static ModConfigSpec.ConfigValue<List<? extends String>> TEAM_OVERRIDES;

    // ========================
    // Effects
    // ========================
    public static ModConfigSpec.BooleanValue SPAWN_BREAK_PARTICLES;
    public static ModConfigSpec.BooleanValue PLAY_BREAK_SOUND;
    public static ModConfigSpec.BooleanValue DROP_ITEMS;

    static {
        Pair<Config, ModConfigSpec> pair = new ModConfigSpec.Builder()
                .configure(BlockHPConfig::build);
        SPEC = pair.getRight();
        CONFIG = pair.getLeft();
    }

    private static Config build(ModConfigSpec.Builder builder) {
        builder.push("general");
        ENABLED = builder
                .comment("Enable the block durability system")
                .define("enabled", true);
        DAMAGE_PER_HIT = builder
                .comment("Damage dealt to a block per bullet hit (used when useBulletDamage is false)")
                .defineInRange("damagePerHit", 1.0, 0.1, 100.0);
        USE_BULLET_DAMAGE = builder
                .comment("If true, use the bullet's entity damage * bulletDamageMultiplier as block damage")
                .define("useBulletDamage", false);
        BULLET_DAMAGE_MULTIPLIER = builder
                .comment("Multiplier when useBulletDamage is true")
                .defineInRange("bulletDamageMultiplier", 1.0, 0.0, 100.0);
        GUN_OVERRIDES = builder
                .comment(
                        "Per-gun overrides (TaCZ). Format: \"modid:gun_id|damage=value|penetration=true\", wildcards allowed in the gun id.",
                        "Both keys are optional. The first matching entry wins; keys you leave out use the normal behavior",
                        "(they do not fall through to later entries).",
                        "  damage      = exact block damage per hit for this gun. Replaces damagePerHit / useBulletDamage /",
                        "                bulletDamageMultiplier for it. 0 = this gun never damages blocks.",
                        "  penetration = true/false (default false). When a hit does more damage than the block's remaining HP,",
                        "                the block is destroyed and the leftover damage carries on to the next block the bullet would hit,",
                        "                and so on until it runs out. A block that cannot be damaged (not damageable per the block list,",
                        "                or durability disabled / invulnerable on that land) stops the bullet.",
                        "[claims] damageMultiplier values still apply to every block hit, so claimed land can stay tougher.",
                        "Examples:",
                        "  \"tacz:ak47|damage=2.0\"                   - every AK-47 hit deals 2 damage to blocks",
                        "  \"tacz:m700|damage=10|penetration=true\"   - M700 hits deal 10 and punch through weak blocks",
                        "  \"tacz:m4a1|penetration=true\"             - normal damage, but penetrates",
                        "  \"tacz:*|damage=0.5\"                     - every other TaCZ gun deals 0.5 (put specific entries above)")
                .defineListAllowEmpty("gunOverrides", Arrays.asList(),
                        GunOverride::isValid);
        HEAL_TIMEOUT_SECONDS = builder
                .comment("Seconds of inactivity before a damaged block heals and clears cracks (0 to disable auto-healing)")
                .defineInRange("healTimeoutSeconds", 120, 0, 86400);
        builder.pop();

        builder.push("block_list");
        BLACKLIST_MODE = builder
                .comment(
                        "If true (default), blockList below is treated as a BLACKLIST (deny list): every block EXCEPT the ones listed can be damaged.",
                        "Air, fluids and unbreakable blocks (bedrock, barriers, command blocks, ...) are never damageable in either mode.",
                        "Remember to replace the default list when you turn this on.",
                        "Performance: with this on, far more blocks per explosion take part in the damage calculation. Consider lowering [explosion] maxRadius.")
                .define("blacklistMode", true);
        BLOCK_LIST = builder
                .comment(
                        "Block list:",
                        "Two formats supported:",
                        "  \"tag:namespace:path\"  → Block tag matching (auto-compatible with all mods!)",
                        "  \"modid:block_id\"     → Exact name match",
                        "  \"modid:*pattern*\"    → Wildcard name match",
                        ""
                )
                .defineListAllowEmpty("blockList",
                        Arrays.asList(),
                        BlockHPConfig::isValidBlockListEntry);
        builder.pop();

        builder.push("hp_calculation");
        HARDNESS_MULTIPLIER = builder
                .comment(
                        "Block HP = max(minBlockHP, blockHardness * hardnessMultiplier).",
                        "Block hardness correlates strongly with explosion resistance.",
                        "Examples with multiplier=1.5:",
                        "  Glass (hardness 0.3) → HP = max(1, 0.3*1.5) = 1",
                        "  Oak Planks (2.0) → HP = max(1, 2.0*1.5) = 3",
                        "  Oak Door (3.0)   → HP = max(1, 3.0*1.5) = 4.5",
                        "  Stone (1.5)      → HP = max(1, 1.5*1.5) = 2.25",
                        "  Obsidian (50.0)  → HP = max(1, 50*1.5) = 75"
                )
                .defineInRange("hardnessMultiplier", 1.5, 0.1, 100.0);
        MIN_BLOCK_HP = builder
                .comment("Minimum HP for any breakable block (prevents zero-HP blocks)")
                .defineInRange("minBlockHP", 1.0, 0.5, Double.MAX_VALUE);
        BLOCK_HP_OVERRIDES = builder
                .comment(
                        "Global per-block HP overrides. They apply on ALL land (claimed and unclaimed) and take priority over the hardness formula.",
                        "The value is the block's exact max HP: the [claims] hpMultiplier values do not scale it.",
                        "[claims.claimed] blockHPOverrides replaces these values for matching blocks on claimed land.",
                        "Format: \"modid:block_id=hp\", supports wildcards.",
                        "Examples:",
                        "  \"minecraft:obsidian=20\"  - reduce obsidian to 20 HP",
                        "  \"minecraft:*glass*=1\"    - force all glass to 1 HP"
                )
                .defineListAllowEmpty("blockHPOverrides", Arrays.asList(),
                        BlockHPConfig::isValidHpOverride);
        builder.pop();

        builder.push("explosion");
        EXPLOSION_ENABLED = builder
                .comment("Enable durability damage from explosions (TaCZ, TNT, Creeper, etc.)")
                .define("enabled", true);
        EXPLOSION_DAMAGE_MULTIPLIER = builder
                .comment("Multiplier for durability damage caused by explosions")
                .defineInRange("damageMultiplier", 1.0, 0.0, 100.0);
        EXPLOSION_MAX_RADIUS = builder
                .comment("Maximum explosion radius to scan for durability damage (performance safety clamp)")
                .defineInRange("maxRadius", 16.0, 1.0, 64.0);
        HANDLE_VANILLA_EXPLOSIONS = builder
                .comment("Whether to apply residual durability damage to vanilla/mod explosions (TNT, Creeper, etc.)")
                .define("handleVanillaExplosions", true);
        builder.pop();

        builder.comment(
                "Optional FTB Chunks integration. Does nothing unless FTB Chunks is installed.",
                "Land is split into two kinds, each with its own values:",
                "  unclaimed - land nobody has claimed (and ALL land if FTB Chunks is not installed)",
                "  claimed   - land inside any FTB Chunks claim",
                "Per-team overrides for claimed land go in [claims.teams].",
                "Multipliers stack on top of the values above (hp_calculation, general, explosion).",
                "HP lookup order: [claims.claimed] blockHPOverrides (claimed land only) > [hp_calculation] blockHPOverrides (all land) > hardness formula x hpMultiplier.",
                "The defaults change nothing, so existing setups behave exactly as before.")
                .push("claims");
        FTB_CHUNKS_INTEGRATION = builder
                .comment("Master switch. If false, all land is treated as unclaimed.")
                .define("ftbChunksIntegration", true);
        UNCLAIMED = defineLandSection(builder, "unclaimed", "Settings for land that is not claimed by any team.", false);
        CLAIMED = defineLandSection(builder, "claimed", "Settings for land claimed by any team (the baseline for every team).", true);
        builder.push("teams");
        TEAM_OVERRIDES = builder
                .comment(
                        "Per-team overrides of the [claims.claimed] values. Only the keys you list are replaced.",
                        "Format: \"<team>|key=value|key=value...\"",
                        "  <team> = the FTB team's UUID, short name or display name (case-insensitive)",
                        "  keys   = enabled (true/false), hpMultiplier (>0), damageMultiplier (>=0),",
                        "           healTimeoutSeconds (-1 = global, 0 = never heal, else seconds)",
                        "Examples:",
                        "  \"Dragons|hpMultiplier=3.0|damageMultiplier=0.5\"   - tougher, takes half damage",
                        "  \"Builders|enabled=false\"                        - durability never applies to this team's claims",
                        "  \"Raiders|healTimeoutSeconds=600\"                 - damaged blocks take 10 minutes to heal")
                .defineListAllowEmpty("overrides", Arrays.asList(),
                        LandProfile.TeamOverride::isValid);
        builder.pop(); // teams
        builder.pop(); // claims

        builder.push("effects");
        SPAWN_BREAK_PARTICLES = builder
                .comment("Spawn block break particles when damaged")
                .define("spawnBreakParticles", true);
        PLAY_BREAK_SOUND = builder
                .comment("Play block hit sound when damaged")
                .define("playBreakSound", true);
        DROP_ITEMS = builder
                .comment("Whether broken blocks drop items")
                .define("dropItems", false);
        builder.pop();

        return new Config();
    }

    private static LandSection defineLandSection(ModConfigSpec.Builder builder, String name, String comment,
                                                  boolean withHpOverrides) {
        LandSection section = new LandSection();
        builder.comment(comment).push(name);
        section.enabled = builder
                .comment("If false, the durability system ignores blocks on this land (no bullet or explosion durability damage).")
                .define("enabled", true);
        section.hpMultiplier = builder
                .comment("Multiplier on the max HP of every block on this land (2.0 = twice as tough). Blocks with an exact blockHPOverrides value are not scaled.")
                .defineInRange("hpMultiplier", 1.0, 0.01, 1000.0);
        section.damageMultiplier = builder
                .comment("Multiplier on all durability damage (bullets and explosions) dealt to blocks on this land. 0 = invulnerable.")
                .defineInRange("damageMultiplier", 1.0, 0.0, 1000.0);
        section.healTimeoutSeconds = builder
                .comment("Seconds before a damaged block on this land heals. -1 = use the global heal setting, 0 = never heal.")
                .defineInRange("healTimeoutSeconds", -1, -1, 86400);
        if (withHpOverrides) {
            section.blockHPOverrides = builder
                    .comment(
                            "Per-block HP overrides for claimed land only. Format: \"modid:block_id=hp\", wildcards allowed. First match wins.",
                            "A match sets the block's exact max HP on claimed land. It replaces the matching [hp_calculation] blockHPOverrides",
                            "value (or the hardness formula) and is not scaled by hpMultiplier (team hpMultiplier values do not scale it either).",
                            "Blocks with no match here fall back to the global [hp_calculation] overrides.",
                            "Examples:",
                            "  \"minecraft:obsidian=300\"  - obsidian has 300 HP on claimed land",
                            "  \"minecraft:*glass*=2\"      - all glass has 2 HP on claimed land")
                    .defineListAllowEmpty("blockHPOverrides", Arrays.asList(),
                            BlockHPConfig::isValidHpOverride);
        }
        builder.pop();
        return section;
    }

    public static boolean isValidBlockListEntry(Object obj) {
        if (!(obj instanceof String str)) return false;
        String trimmed = str.trim();
        if (trimmed.isEmpty()) return false;
        if (trimmed.startsWith("tag:")) {
            String tagId = trimmed.substring(4).trim();
            if (tagId.isEmpty()) return false;
            ResourceLocation rl = ResourceLocation.tryParse(tagId);
            return rl != null && !rl.getPath().isEmpty();
        }
        return true;
    }

    public static boolean isValidHpOverride(Object obj) {
        if (!(obj instanceof String str)) return false;
        String[] parts = str.split("=", 2);
        if (parts.length != 2) return false;
        String key = parts[0].trim();
        if (key.isEmpty()) return false;
        try {
            float hp = Float.parseFloat(parts[1].trim());
            return Float.isFinite(hp) && hp > 0f;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static class Config {}

    /** The settings for one kind of land (claimed or unclaimed). Only the claimed section has blockHPOverrides. */
    public static class LandSection {
        public ModConfigSpec.BooleanValue enabled;
        public ModConfigSpec.DoubleValue hpMultiplier;
        public ModConfigSpec.DoubleValue damageMultiplier;
        public ModConfigSpec.IntValue healTimeoutSeconds;
        public ModConfigSpec.ConfigValue<List<? extends String>> blockHPOverrides;

        public LandProfile toProfile() {
            List<LandProfile.HpOverride> overrides = new ArrayList<>();
            if (blockHPOverrides != null) {
                for (String entry : blockHPOverrides.get()) {
                    LandProfile.HpOverride.parse(entry).ifPresentOrElse(overrides::add,
                            () -> BlockDurability.LOGGER.warn("Invalid blockHPOverrides entry in [claims.claimed]: '{}'. Skipping.", entry));
                }
            }
            return new LandProfile(
                    enabled.get(),
                    hpMultiplier.get().floatValue(),
                    damageMultiplier.get().floatValue(),
                    healTimeoutSeconds.get(),
                    overrides);
        }
    }
}
