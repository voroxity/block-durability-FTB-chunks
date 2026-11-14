package top.dreamfall.blockdurability;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Durability settings that apply to one piece of land (claimed, unclaimed, or a specific team's claims).
 * Pure Java on purpose: no Minecraft or FTB types, so it is safe to load without FTB Chunks installed.
 *
 * @param enabled            false = the durability system ignores blocks on this land
 * @param hpMultiplier       scales hardness-formula HP (never applied to an HP override, global or claimed)
 * @param damageMultiplier   scales all incoming durability damage (bullets and explosions); 0 = invulnerable
 * @param healTimeoutSeconds seconds before a damaged block heals; -1 = use the global heal setting, 0 = never heal
 * @param hpOverrides        per-block max HP for this land (only the claimed section has any); the first match wins and is used as-is
 */
public record LandProfile(boolean enabled, float hpMultiplier, float damageMultiplier,
                          int healTimeoutSeconds, List<HpOverride> hpOverrides) {

    /** Changes nothing: used when no profile applies. */
    public static final LandProfile NEUTRAL = new LandProfile(true, 1.0f, 1.0f, -1);

    public LandProfile {
        hpOverrides = List.copyOf(hpOverrides);
    }

    /** Profile with no per-block HP overrides. */
    public LandProfile(boolean enabled, float hpMultiplier, float damageMultiplier, int healTimeoutSeconds) {
        this(enabled, hpMultiplier, damageMultiplier, healTimeoutSeconds, List.of());
    }

    /** True if durability damage can actually land on this profile's blocks. */
    public boolean canDamage() {
        return enabled && damageMultiplier > 0f;
    }

    /** The land-specific max HP for this block id, if any entry matches (first match wins). */
    public Optional<Float> hpOverrideFor(String blockId) {
        for (HpOverride override : hpOverrides) {
            if (override.matches(blockId)) return Optional.of(override.hp());
        }
        return Optional.empty();
    }

    /** One "modid:block_id=hp" entry (wildcards allowed in the id). */
    public record HpOverride(Pattern pattern, float hp) {

        public static Optional<HpOverride> parse(String entry) {
            if (entry == null) return Optional.empty();
            String[] parts = entry.split("=", 2);
            if (parts.length != 2) return Optional.empty();
            String key = parts[0].trim();
            if (key.isEmpty()) return Optional.empty();
            try {
                float hp = Float.parseFloat(parts[1].trim());
                if (!Float.isFinite(hp) || hp <= 0f) return Optional.empty();
                return Optional.of(new HpOverride(globToPattern(key), hp));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }

        public boolean matches(String blockId) {
            return pattern.matcher(blockId).matches();
        }
    }

    /** Same conversion as BlockHPManager.globToPattern, duplicated here to keep this class free of Minecraft types. Also used by GunOverride. */
    static Pattern globToPattern(String glob) {
        String regex = "^" + Pattern.quote(glob).replace("*", "\\E.*\\Q") + "$";
        return Pattern.compile(regex);
    }

    /**
     * A per-team override of the "claimed" profile. Only the keys present are replaced.
     * <p>
     * Config format: {@code "<team>|enabled=false|hpMultiplier=2.0|damageMultiplier=0.5|healTimeoutSeconds=300"}
     * where {@code <team>} is the FTB team's UUID, short name, or display name (case-insensitive).
     */
    public record TeamOverride(String selector, Boolean enabled, Float hpMultiplier,
                               Float damageMultiplier, Integer healTimeoutSeconds) {

        public static Optional<TeamOverride> parse(String raw) {
            if (raw == null) return Optional.empty();
            String[] parts = raw.split("\\|");
            String selector = parts[0].trim();
            if (selector.isEmpty()) return Optional.empty();

            Boolean enabled = null;
            Float hp = null;
            Float dmg = null;
            Integer heal = null;

            for (int i = 1; i < parts.length; i++) {
                String[] kv = parts[i].split("=", 2);
                if (kv.length != 2) return Optional.empty();
                String key = kv[0].trim();
                String value = kv[1].trim();
                try {
                    switch (key) {
                        case "enabled" -> {
                            if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                                return Optional.empty();
                            }
                            enabled = Boolean.parseBoolean(value);
                        }
                        case "hpMultiplier" -> {
                            float f = Float.parseFloat(value);
                            if (!Float.isFinite(f) || f <= 0f) return Optional.empty();
                            hp = f;
                        }
                        case "damageMultiplier" -> {
                            float f = Float.parseFloat(value);
                            if (!Float.isFinite(f) || f < 0f) return Optional.empty();
                            dmg = f;
                        }
                        case "healTimeoutSeconds" -> {
                            int n = Integer.parseInt(value);
                            if (n < -1) return Optional.empty();
                            heal = n;
                        }
                        default -> {
                            return Optional.empty();
                        }
                    }
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
            }
            return Optional.of(new TeamOverride(selector, enabled, hp, dmg, heal));
        }

        public static boolean isValid(Object obj) {
            return obj instanceof String s && parse(s).isPresent();
        }

        public boolean matches(UUID teamId, String shortName, String displayName) {
            if (teamId != null && selector.equalsIgnoreCase(teamId.toString())) return true;
            if (shortName != null && selector.equalsIgnoreCase(shortName)) return true;
            return displayName != null && selector.toLowerCase(Locale.ROOT).equals(displayName.toLowerCase(Locale.ROOT));
        }

        public LandProfile applyTo(LandProfile base) {
            return new LandProfile(
                    enabled != null ? enabled : base.enabled(),
                    hpMultiplier != null ? hpMultiplier : base.hpMultiplier(),
                    damageMultiplier != null ? damageMultiplier : base.damageMultiplier(),
                    healTimeoutSeconds != null ? healTimeoutSeconds : base.healTimeoutSeconds(),
                    base.hpOverrides());
        }
    }
}
