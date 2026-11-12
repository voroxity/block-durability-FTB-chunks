package top.dreamfall.blockdurability;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Durability settings that apply to one piece of land (claimed, unclaimed, or a specific team's claims).
 * Pure Java on purpose: no Minecraft or FTB types, so it is safe to load without FTB Chunks installed.
 *
 * @param enabled            false = the durability system ignores blocks on this land
 * @param hpMultiplier       scales every block's max HP
 * @param damageMultiplier   scales all incoming durability damage (bullets and explosions); 0 = invulnerable
 * @param healTimeoutSeconds seconds before a damaged block heals; -1 = use the global heal setting, 0 = never heal
 */
public record LandProfile(boolean enabled, float hpMultiplier, float damageMultiplier, int healTimeoutSeconds) {

    /** Changes nothing: used when no profile applies. */
    public static final LandProfile NEUTRAL = new LandProfile(true, 1.0f, 1.0f, -1);

    /** True if durability damage can actually land on this profile's blocks. */
    public boolean canDamage() {
        return enabled && damageMultiplier > 0f;
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
                    healTimeoutSeconds != null ? healTimeoutSeconds : base.healTimeoutSeconds());
        }
    }
}
