package top.dreamfall.blockdurability;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One "modid:gun_id=damage" entry (wildcards allowed in the id): the exact block damage that gun deals per hit.
 * Pure Java on purpose (no Minecraft or TaCZ types) so it can be unit tested and is safe to load without TaCZ.
 */
public record GunDamageOverride(Pattern pattern, float damage) {

    /** Parses an entry; empty if the format is wrong or the damage is not a finite number >= 0 (0 = never damages blocks). */
    public static Optional<GunDamageOverride> parse(String entry) {
        if (entry == null) return Optional.empty();
        String[] parts = entry.split("=", 2);
        if (parts.length != 2) return Optional.empty();
        String key = parts[0].trim();
        if (key.isEmpty()) return Optional.empty();
        try {
            float damage = Float.parseFloat(parts[1].trim());
            if (!Float.isFinite(damage) || damage < 0f) return Optional.empty();
            return Optional.of(new GunDamageOverride(LandProfile.globToPattern(key), damage));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Config validator. */
    public static boolean isValid(Object obj) {
        return obj instanceof String s && parse(s).isPresent();
    }

    public boolean matches(String gunId) {
        return pattern.matcher(gunId).matches();
    }

    /** The damage of the first entry matching this gun id, if any. */
    public static Optional<Float> find(List<GunDamageOverride> overrides, String gunId) {
        for (GunDamageOverride override : overrides) {
            if (override.matches(gunId)) return Optional.of(override.damage());
        }
        return Optional.empty();
    }
}
