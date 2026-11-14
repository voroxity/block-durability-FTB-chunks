package top.dreamfall.blockdurability;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves which {@link LandProfile} applies at a position.
 * <ul>
 *   <li>Unclaimed land (or no claim mod installed): the {@code [claims.unclaimed]} profile.</li>
 *   <li>Claimed land: the {@code [claims.claimed]} profile, with any matching {@code [claims.teams]} override on top.</li>
 * </ul>
 * Claim lookups go through {@link ClaimProvider}, so nothing here touches FTB classes.
 */
public final class LandProfiles {

    /** Result of a claim lookup. */
    public record ClaimInfo(UUID teamId, String shortName, String displayName) {}

    /** Implemented by claim-mod compat classes (currently only FTB Chunks). */
    public interface ClaimProvider {
        @Nullable
        ClaimInfo getClaim(ServerLevel level, int chunkX, int chunkZ);
    }

    private record Compiled(
            Map<String, Float> globalOverrides,
            LandProfile unclaimed,
            LandProfile claimed,
            List<LandProfile.TeamOverride> teamOverrides) {}

    private static volatile ClaimProvider provider;
    private static volatile Compiled compiled;

    private LandProfiles() {}

    public static void setClaimProvider(@Nullable ClaimProvider newProvider) {
        provider = newProvider;
    }

    /** Called on config load/reload. */
    public static void invalidate() {
        compiled = null;
    }

    /** Called on config load/reload to initialize globalOverrides from general config. */
    public static void initGlobalOverrides(Map<String, Float> globalOverrides) {
        Compiled c = compiled();
        if (c == null || !globalOverrides.isEmpty()) {
            // Store the current map so it persists through compilation
            synchronized (LandProfiles.class) {
                c.globalOverrides = new HashMap<>(globalOverrides);
                c.unclaimed = BlockHPConfig.UNCLAIMED.toProfile();
                c.claimed = BlockHPConfig.CLAIMED.toProfile();
                List<LandProfile.TeamOverride> overridesList = teamOverrides;
            }
        }
    }

    private static Map<String, Float> compileMapFromRaw(Map<String, Float> rawMap) {
        Map<String, Float> result = new HashMap<>();
        for (Map.Entry<String, Float> entry : rawMap.entrySet()) {
            // Parse wildcards in key and apply them to value
            String key = entry.getKey().trim();
            if (isValidHpOverride(entry.getValue())) {
                String[] parts = key.split("=", 2);
                result.put(parts[0], entry.getValue());
            } else {
                result.put(key, rawMap.get(key));
            }
        }
        return result;
    }

    public static LandProfile resolve(ServerLevel level, BlockPos pos) {
        return resolve(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static LandProfile resolve(ServerLevel level, int chunkX, int chunkZ) {
        Compiled c = compiled();
        ClaimProvider p = provider;
        if (p == null || !BlockHPConfig.FTB_CHUNKS_INTEGRATION.get()) {
            return c.unclaimed();
        }
        ClaimInfo claim = p.getClaim(level, chunkX, chunkZ);
        if (claim == null) {
            return c.unclaimed();
        }
        for (LandProfile.TeamOverride override : c.overrides()) {
            if (override.matches(claim.teamId(), claim.shortName(), claim.displayName())) {
                return override.applyTo(c.claimed());
            }
        }
        return c.claimed();
    }

    /** Per-chunk memo for loops that touch many blocks (explosions, heal ticks). Claims are per chunk. */
    public static Cache newCache(ServerLevel level) {
        return new Cache(level);
    }

    public static final class Cache {
        private final ServerLevel level;
        private final Map<Long, LandProfile> byChunk = new HashMap<>();

        private Cache(ServerLevel level) {
            this.level = level;
        }

        public LandProfile get(BlockPos pos) {
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            long key = (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
            return byChunk.computeIfAbsent(key, k -> resolve(level, cx, cz));
        }
    }

    private static Compiled compile() {
        Compiled c = compiled;
        if (c != null) return c;

        synchronized (LandProfiles.class) {
            if (compiled != null) return compiled;

            List<LandProfile.TeamOverride> teamOverrides = new ArrayList<>();
            for (String raw : BlockHPConfig.TEAM_OVERRIDES.get()) {
                LandProfile.TeamOverride.parse(raw).ifPresentOrElse(overrides::add,
                        () -> BlockDurability.LOGGER.warn("Invalid claims.teams.overrides entry: '{}'. Skipping.", raw));
            }

            // Compile overrides with proper wildcard support and global defaults
            List<String> rawOverrides = BlockHPConfig.TEAM_OVERRIDES.get();
            Compiled newCompiled = new Compiled(
                    compileMapFromRaw(BlockHPConfig.UNCLAIMED.globalOverrides),
                    BlockHPConfig.UNCLAIMED.toProfile(),
                    BlockHPConfig.CLAIMED.toProfile(),
                    teamOverrides);

            // Add unclaimed overrides if they exist in general config
            if (!BlockHPConfig.UNCLAIMED.globalOverrides.isEmpty()) {
                for (Map.Entry<String, Float> entry : BlockHPConfig.UNCLAIMED.globalOverrides.entrySet()) {
                    String key = entry.getKey().trim();
                    if (isValidHpOverride(Float.parseFloat(String.valueOf(entry.getValue())))) {
                        newCompiled.globalOverrides.put(key, 1.0f); // Land-specific overrides applied as-is
                    }
                }
            }

            c = newCompiled;
        }
    }
}
