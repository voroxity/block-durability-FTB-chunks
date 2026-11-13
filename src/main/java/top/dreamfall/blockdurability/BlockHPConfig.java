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
                        "Per-block HP overrides. Takes priority over the hardness formula.",
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
                "The defaults change nothing, so existing setups behave exactly as before.")
                .push("claims");
        FTB_CHUNKS_INTEGRATION = builder
                .comment("Master switch. If false, all land is treated as unclaimed.")
                .define("ftbChunksIntegration", true);
        UNCLAIMED = defineLandSection(builder, "unclaimed", "Settings for land that is not claimed by any team.");
        CLAIMED = defineLandSection(builder, "claimed", "Settings for land claimed by any team (the baseline for every team).");
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

    private static LandSection defineLandSection(ModConfigSpec.Builder builder, String name, String comment) {
        LandSection section = new LandSection();
        builder.comment(comment).push(name);
        section.enabled = builder
                .comment("If false, the durability system ignores blocks on this land (no bullet or explosion durability damage).")
                .define("enabled", true);
        section.hpMultiplier = builder
                .comment("Multiplier on every block's max HP on this land (2.0 = twice as tough).")
                .defineInRange("hpMultiplier", 1.0, 0.01, 1000.0);
        section.damageMultiplier = builder
                .comment("Multiplier on all durability damage (bullets and explosions) dealt to blocks on this land. 0 = invulnerable.")
                .defineInRange("damageMultiplier", 1.0, 0.0, 1000.0);
        section.healTimeoutSeconds = builder
                .comment("Seconds before a damaged block on this land heals. -1 = use the global heal setting, 0 = never heal.")
                .defineInRange("healTimeoutSeconds", -1, -1, 86400);
        section.blockHPOverrides = builder
                .comment(
                        "Per-block HP overrides for this land only. Format: \"modid:block_id=hp\", wildcards allowed. First match wins.",
                        "A match sets the block's max HP on this land to exactly hp. It beats [hp_calculation] blockHPOverrides,",
                        "the hardness formula and this section's hpMultiplier (team hpMultiplier values do not scale it either).",
                        "Examples:",
                        "  \"minecraft:obsidian=300\"  - obsidian has 300 HP on this land",
                        "  \"minecraft:*glass*=2\"      - all glass has 2 HP on this land")
                .defineListAllowEmpty("blockHPOverrides", Arrays.asList(),
                        BlockHPConfig::isValidHpOverride);
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

    /** The settings for one kind of land (claimed or unclaimed). */
    public static class LandSection {
        public ModConfigSpec.BooleanValue enabled;
        public ModConfigSpec.DoubleValue hpMultiplier;
        public ModConfigSpec.DoubleValue damageMultiplier;
        public ModConfigSpec.IntValue healTimeoutSeconds;
        public ModConfigSpec.ConfigValue<List<? extends String>> blockHPOverrides;

        public LandProfile toProfile() {
            List<LandProfile.HpOverride> overrides = new ArrayList<>();
            for (String entry : blockHPOverrides.get()) {
                LandProfile.HpOverride.parse(entry).ifPresentOrElse(overrides::add,
                        () -> BlockDurability.LOGGER.warn("Invalid blockHPOverrides entry in [claims]: '{}'. Skipping.", entry));
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
