package top.dreamfall.blockdurability;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A per-gun override. Config format: {@code "modid:gun|damage=value|penetration=true"} (spaces around the pipes are tolerated).
 * The gun id may contain wildcards; both keys are optional.
 * Pure Java on purpose (no Minecraft or TaCZ types) so it can be unit tested and is safe to load without TaCZ.
 *
 * @param pattern     matches the gun id (e.g. tacz:ak47)
 * @param damage      exact block damage per hit, or null = use the normal damage calculation; 0 = never damages blocks
 * @param penetration whether leftover damage carries on to the next block when a block is destroyed
 */
public record GunOverride(Pattern pattern, Float damage, boolean penetration) {

    public static Optional<GunOverride> parse(String raw) {
        if (raw == null) return Optional.empty();
        String[] parts = raw.split("\\|");
        String gunId = parts[0].trim();
        if (gunId.isEmpty() || gunId.contains("=")) return Optional.empty();

        Float damage = null;
        boolean penetration = false;

        for (int i = 1; i < parts.length; i++) {
            String[] kv = parts[i].split("=", 2);
            if (kv.length != 2) return Optional.empty();
            String key = kv[0].trim().toLowerCase(Locale.ROOT);
            String value = kv[1].trim();
            switch (key) {
                case "damage" -> {
                    try {
                        float d = Float.parseFloat(value);
                        if (!Float.isFinite(d) || d < 0f) return Optional.empty();
                        damage = d;
                    } catch (NumberFormatException e) {
                        return Optional.empty();
                    }
                }
                // "pentation" is accepted as a common misspelling.
                case "penetration", "pentation" -> {
                    if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                        return Optional.empty();
                    }
                    penetration = Boolean.parseBoolean(value);
                }
                default -> {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(new GunOverride(LandProfile.globToPattern(gunId), damage, penetration));
    }

    /** Config validator. */
    public static boolean isValid(Object obj) {
        return obj instanceof String s && parse(s).isPresent();
    }

    public boolean matches(String gunId) {
        return pattern.matcher(gunId).matches();
    }

    /** The first entry matching this gun id, if any. Keys an entry leaves out do not fall through to later entries. */
    public static Optional<GunOverride> find(List<GunOverride> overrides, String gunId) {
        for (GunOverride override : overrides) {
            if (override.matches(gunId)) return Optional.of(override);
        }
        return Optional.empty();
    }
}
