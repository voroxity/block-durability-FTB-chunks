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

    private record Compiled(LandProfile unclaimed, LandProfile claimed, List<LandProfile.TeamOverride> overrides) {}

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

    private static Compiled compiled() {
        Compiled c = compiled;
        if (c != null) return c;
        synchronized (LandProfiles.class) {
            if (compiled != null) return compiled;

            List<LandProfile.TeamOverride> overrides = new ArrayList<>();
            for (String raw : BlockHPConfig.TEAM_OVERRIDES.get()) {
                LandProfile.TeamOverride.parse(raw).ifPresentOrElse(overrides::add,
                        () -> BlockDurability.LOGGER.warn("Invalid claims.teams.overrides entry: '{}'. Skipping.", raw));
            }
            compiled = new Compiled(
                    BlockHPConfig.UNCLAIMED.toProfile(),
                    BlockHPConfig.CLAIMED.toProfile(),
                    List.copyOf(overrides));
            return compiled;
        }
    }
}
